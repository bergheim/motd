package io.github.trevarj.motd.data.sync

import io.github.trevarj.motd.data.db.RoomId
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.client.ChatHistoryReference
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.HistoryReferenceType
import io.github.trevarj.motd.irc.client.IrcCommandException
import io.github.trevarj.motd.irc.client.IrcDisconnectedException
import io.github.trevarj.motd.irc.ext.ChatHistorySelectors
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * Sole owner of a single CHATHISTORY page fetch: it builds the directional request from a
 * caller-supplied local boundary, applies the msgid→timestamp fallback, guards unsafe continuation,
 * persists the page through the sole IRC→Room writer ([EventProcessor]), and owns all fetch
 * concurrency (per-network wire serialization, per-direction coalescing, and the request timeout).
 *
 * Directional decisions — which boundary to page from, and how a per-focus gap constrains the
 * endOfPagination outcome — stay with the caller ([ChatHistoryRemoteMediator]); the loader only
 * turns a `(direction, boundary)` pair into one persisted page and reports whether that direction is
 * exhausted or must stop to avoid a refetch loop.
 *
 * The loader is also the single wire-access primitive for the orchestration in
 * [io.github.trevarj.motd.service.HistoryResyncCoordinator]: its multi-page reconnect/manual
 * traversals build no requests of their own but call [fetchPage]/[fetchMessages]/[fetchTargets],
 * which share this loader's per-network [Mutex] map so a scroll fetch and a reconnect catch-up
 * serialize on the wire instead of racing pages into the same timeline. That covers every
 * Paging-driven and coordinator-issued CHATHISTORY request; the one remaining path outside the
 * loader is the deep-link AROUND prefetch in ChatJumpResolver (pre-existing, unrouted here).
 */
@Singleton
class HistoryPageLoader @Inject constructor(
    private val processor: EventProcessor,
    // Opt-in fetch journal (availability gates, per-page outcomes, wire timeouts). Fields carry
    // classification, ids, counts, timestamps, and msgid PRESENCE only — never message content.
    private val diagnostics: DiagnosticLogger = DiagnosticLogger.Noop,
) {
    /**
     * Minimal seam over the live history transport (availability + a single labeled request),
     * resolved per call so a client that connects after a buffer opens is picked up on the next
     * boundary hit. Callers reuse this exact shape for their own source seams.
     */
    interface HistorySource {
        suspend fun availability(): HistoryAvailability
        suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse
    }

    /** The three directions a boundary can be paged toward. LATEST ignores [boundary]. */
    enum class Direction { OLDER, NEWER, LATEST }

    /** Outcome of a single page fetch. */
    sealed interface PageResult {
        /**
         * A page was fetched and persisted. [primaryCount] is the fetched primary-message count and
         * [insertedCount] the durable rows the persist actually added. [endOfDirection] is true when
         * this direction is exhausted (server end reached) or must stop to avoid a
         * non-advancing/saturated refetch loop; callers may still narrow that with their own
         * per-focus gap accounting.
         */
        data class Loaded(
            val primaryCount: Int,
            val insertedCount: Int,
            val endOfDirection: Boolean,
        ) : PageResult

        /** The network does not advertise CHATHISTORY. */
        data object Unsupported : PageResult

        /** History is negotiating or offline; the request is retryable. */
        data class Unavailable(val cause: Throwable) : PageResult

        /** The server returned a response that could not be used as a durable boundary. */
        data class Failed(val cause: Throwable) : PageResult
    }

    /** A fetched, boundary-trimmed page plus the selector used and whether msgids remain usable. */
    internal data class FetchedPage(
        val response: ChatHistoryResponse.Messages,
        val request: ChatHistoryRequest,
        val selector: BoundarySelector,
        val msgidAllowed: Boolean,
    )

    /** A CHATHISTORY selector value plus the reference type it was derived from. */
    internal data class BoundarySelector(
        val value: String,
        val type: HistoryReferenceType,
    )

    // Sole fetch serialization for Paging-driven pages and the coordinator's reconnect/manual
    // traversals: both acquire these per-network locks (Phase 3 gate collapse), so neither can
    // interleave the other's pages on the socket. ChatJumpResolver's AROUND prefetch is the one
    // history request that does not pass through these locks (pre-existing).
    private val networkLocks = ConcurrentHashMap<Long, Mutex>()
    private val inFlight = ConcurrentHashMap<FlightKey, CompletableDeferred<PageResult>>()
    internal var requestTimeoutMs: Long = REQUEST_TIMEOUT_MS

    // The key deliberately omits the boundary: any fresh page for (network, room, direction)
    // satisfies a concurrent request. Callers (Paging generations) re-read the local store after
    // each page and issue their next load from their own boundary, so joining whichever page is in
    // flight is safe and prevents a generation swap from double-fetching the same page.
    private data class FlightKey(val networkId: Long, val roomId: RoomId, val direction: Direction)

    /**
     * Fetch and persist exactly one page for [roomId] (always canonical) in [direction] from
     * [boundary]. Concurrent identical `(network, room, direction)` requests coalesce onto one
     * in-flight fetch; distinct fetches on the same network still serialize on the wire.
     */
    suspend fun loadPage(
        networkId: Long,
        roomId: RoomId,
        target: String,
        direction: Direction,
        source: HistorySource,
        pageSize: Int = 50,
        gapId: Long? = null,
        boundary: ChatHistoryReference? = null,
    ): PageResult {
        val availability = source.availability()
        diagnostics.record("chat_history", "loader_page_requested") {
            mapOf(
                "network_id" to networkId,
                "room_id" to roomId,
                "direction" to direction.name,
                "availability" to availability::class.simpleName,
                "boundary_has_msgid" to (boundary?.msgid != null),
                "boundary_server_time" to boundary?.serverTime,
                "gap_id" to gapId,
            )
        }
        val ready = when (availability) {
            HistoryAvailability.Unsupported -> return PageResult.Unsupported
            HistoryAvailability.NegotiatingOrOffline -> return PageResult.Unavailable(
                IrcDisconnectedException("CHATHISTORY", "history is negotiating or offline"),
            )
            is HistoryAvailability.Ready -> availability
        }
        val requestLimit = minOf(pageSize, ready.pageLimit).coerceAtLeast(1)
        val referenceTypes = ready.referenceTypes
        return coalesced(FlightKey(networkId, roomId, direction)) {
            when (direction) {
                Direction.LATEST -> loadLatest(networkId, roomId, target, source, requestLimit, referenceTypes)
                Direction.OLDER -> loadOlder(
                    networkId, roomId, target, source, requestLimit, referenceTypes, gapId, boundary,
                )
                Direction.NEWER -> loadNewer(
                    networkId, roomId, target, source, requestLimit, referenceTypes, gapId, boundary,
                )
            }
        }.also { result ->
            diagnostics.record("chat_history", "loader_page_result") {
                mapOf(
                    "room_id" to roomId,
                    "direction" to direction.name,
                    "result" to result::class.simpleName,
                    "primary_count" to (result as? PageResult.Loaded)?.primaryCount,
                    "inserted_count" to (result as? PageResult.Loaded)?.insertedCount,
                    "end_of_direction" to (result as? PageResult.Loaded)?.endOfDirection,
                )
            }
        }
    }

    /** Pull the most recent page and persist it through the sole IRC→Room writer. */
    private suspend fun loadLatest(
        networkId: Long,
        roomId: RoomId,
        target: String,
        source: HistorySource,
        requestLimit: Int,
        referenceTypes: Set<HistoryReferenceType>,
    ): PageResult {
        val allowMsgid = HistoryReferenceType.MSGID in referenceTypes
        val request = ChatHistoryRequest(
            ChatHistoryRequest.Subcommand.LATEST,
            target,
            limit = requestLimit,
        )
        val result = fetchMessages(
            networkId, source, request, referenceTypes, allowMsgid, requestTimeoutMs, retryableTimeout = true,
        )
        if (!result.isComplete && !result.hasUsableOldest(referenceTypes, true)) {
            return PageResult.Failed(
                IllegalStateException("CHATHISTORY LATEST returned no advertised primary-message boundary"),
            )
        }
        val persisted = processor.persistHistoryPageResult(
            networkId,
            request,
            result,
            expectedRoomId = roomId,
        )
        return PageResult.Loaded(
            result.primaryMessageCount,
            persisted.inserted,
            endOfDirection = result.isComplete ||
                result.cannotSafelyPageBefore(referenceTypes, true, requestLimit),
        )
    }

    /** Page older via BEFORE from [boundary], persisting into the optional focused [gapId]. */
    private suspend fun loadOlder(
        networkId: Long,
        roomId: RoomId,
        target: String,
        source: HistorySource,
        requestLimit: Int,
        referenceTypes: Set<HistoryReferenceType>,
        gapId: Long?,
        boundary: ChatHistoryReference?,
    ): PageResult {
        val oldest = boundary ?: return PageResult.Failed(
            IllegalStateException("CHATHISTORY BEFORE requires a local boundary"),
        )
        if (selectorOf(oldest, referenceTypes, msgidAllowed = true) == null) {
            return PageResult.Failed(
                IllegalStateException("CHATHISTORY BEFORE has no advertised local boundary selector"),
            )
        }
        val fetched = fetchPage(
            networkId,
            target,
            ChatHistoryRequest.Subcommand.BEFORE,
            source,
            oldest,
            secondBoundary = null,
            referenceTypes,
            requestLimit,
            msgidAllowed = true,
            requestTimeoutMs,
            retryableTimeout = true,
        ) ?: return PageResult.Failed(
            IllegalStateException("CHATHISTORY BEFORE has no advertised local boundary selector"),
        )
        val result = fetched.response
        if (!result.isComplete && !result.hasUsableOldest(referenceTypes, fetched.msgidAllowed)) {
            return PageResult.Failed(
                IllegalStateException("CHATHISTORY BEFORE returned no advertised primary-message boundary"),
            )
        }
        // Apply the page as one IRC history batch. EventProcessor wraps HistoryBatch in a single
        // Room transaction, so Paging sees one invalidation instead of up to 50 row-by-row refreshes
        // while the user is entering or flinging through a channel.
        val persisted = processor.persistHistoryPageResult(
            networkId,
            fetched.request,
            result,
            expectedRoomId = roomId,
            historyGapId = gapId,
        )
        if (result.isComplete) {
            return PageResult.Loaded(result.primaryMessageCount, persisted.inserted, endOfDirection = true)
        }
        // A non-advancing cursor would refetch forever. A saturated timestamp-only page is also
        // ambiguous because BEFORE would skip any additional messages sharing its oldest timestamp.
        // Preserve the page, leave historyComplete false, and stop this direction.
        return PageResult.Loaded(
            result.primaryMessageCount,
            persisted.inserted,
            endOfDirection = result.cannotSafelyPageBefore(
                referenceTypes,
                fetched.msgidAllowed,
                requestLimit,
                previous = fetched.selector,
            ),
        )
    }

    /** Grow toward the recent window via AFTER from [boundary], persisting into focused [gapId]. */
    private suspend fun loadNewer(
        networkId: Long,
        roomId: RoomId,
        target: String,
        source: HistorySource,
        requestLimit: Int,
        referenceTypes: Set<HistoryReferenceType>,
        gapId: Long?,
        boundary: ChatHistoryReference?,
    ): PageResult {
        val newer = boundary ?: return PageResult.Failed(
            IllegalStateException("CHATHISTORY AFTER requires a local boundary"),
        )
        if (selectorOf(newer, referenceTypes, msgidAllowed = true) == null) {
            return PageResult.Failed(
                IllegalStateException("CHATHISTORY AFTER has no advertised local boundary selector"),
            )
        }
        val fetched = fetchPage(
            networkId,
            target,
            ChatHistoryRequest.Subcommand.AFTER,
            source,
            newer,
            secondBoundary = null,
            referenceTypes,
            requestLimit,
            msgidAllowed = true,
            requestTimeoutMs,
            retryableTimeout = true,
        ) ?: return PageResult.Failed(
            IllegalStateException("CHATHISTORY AFTER has no advertised local boundary selector"),
        )
        val result = fetched.response
        if (!result.isComplete && !result.hasUsableNewest(referenceTypes, fetched.msgidAllowed)) {
            return PageResult.Failed(
                IllegalStateException("CHATHISTORY AFTER returned no advertised primary-message boundary"),
            )
        }
        val persisted = processor.persistHistoryPageResult(
            networkId,
            fetched.request,
            result,
            expectedRoomId = roomId,
            historyGapId = gapId,
        )
        return PageResult.Loaded(
            result.primaryMessageCount,
            persisted.inserted,
            endOfDirection = result.isComplete ||
                result.cannotSafelyPageAfter(
                    referenceTypes,
                    fetched.msgidAllowed,
                    requestLimit,
                    previous = fetched.selector,
                ),
        )
    }

    /**
     * Build and run one directional CHATHISTORY message request from [boundary] (and optional
     * [secondBoundary] for BETWEEN or a bounded LATEST floor), serialized on the per-network wire
     * lock. [timeoutMs] bounds the whole operation — lock wait included — so a caller's budget
     * (e.g. the urgent pending-message path) cannot silently stretch behind a busy wire. Applies
     * the msgid→timestamp fallback on `INVALID_MSGREFTYPE` and trims boundaries the server never
     * advertised. Returns null when [boundary] (or a required [secondBoundary]) has no advertised
     * selector up front; a runtime msgid rejection with no advertised timestamp fallback instead
     * rethrows the server's original [IrcCommandException] so callers keep its diagnostics. Does
     * not persist; the caller owns persistence.
     */
    internal suspend fun fetchPage(
        networkId: Long,
        target: String,
        subcommand: ChatHistoryRequest.Subcommand,
        source: HistorySource,
        boundary: ChatHistoryReference,
        secondBoundary: ChatHistoryReference?,
        referenceTypes: Set<HistoryReferenceType>,
        limit: Int,
        msgidAllowed: Boolean,
        timeoutMs: Long,
        retryableTimeout: Boolean = false,
    ): FetchedPage? {
        val selector = selectorOf(boundary, referenceTypes, msgidAllowed) ?: return null
        val secondSelector = secondBoundary?.let { selectorOf(it, referenceTypes, msgidAllowed = false)?.value }
        if (secondBoundary != null && secondSelector == null) return null
        val request = ChatHistoryRequest(
            subcommand = subcommand,
            target = target,
            bound1 = selector.value,
            bound2 = secondSelector,
            limit = limit.coerceAtLeast(1),
        )
        return onWireLock(networkId, timeoutMs, retryableTimeout) {
            try {
                FetchedPage(
                    runRequest(source, request).withAdvertisedBoundaries(referenceTypes, msgidAllowed),
                    request,
                    selector,
                    msgidAllowed,
                )
            } catch (error: IrcCommandException) {
                if (selector.type != HistoryReferenceType.MSGID || error.code != INVALID_MSGREFTYPE) {
                    throw error
                }
                // The pre-checks proved a msgid selector was advertised, yet the server rejected it
                // at runtime and no timestamp fallback exists for this boundary. Surface the
                // server's own error rather than a misleading "no selector" failure.
                val timestamp = selectorOf(boundary, referenceTypes, msgidAllowed = false)
                    ?: throw error
                val fallbackRequest = request.copy(bound1 = timestamp.value)
                FetchedPage(
                    runRequest(source, fallbackRequest).withAdvertisedBoundaries(referenceTypes, false),
                    fallbackRequest,
                    timestamp,
                    false,
                )
            }
        }
    }

    /**
     * Run an arbitrary pre-built message request (an unbounded LATEST seed, for example) on the
     * per-network wire lock; [timeoutMs] bounds lock wait plus the request. The caller owns request
     * construction of subcommand/target/limit and owns persistence.
     */
    internal suspend fun fetchMessages(
        networkId: Long,
        source: HistorySource,
        request: ChatHistoryRequest,
        referenceTypes: Set<HistoryReferenceType>,
        msgidAllowed: Boolean,
        timeoutMs: Long,
        retryableTimeout: Boolean = false,
    ): ChatHistoryResponse.Messages =
        onWireLock(networkId, timeoutMs, retryableTimeout) {
            runRequest(source, request).withAdvertisedBoundaries(referenceTypes, msgidAllowed)
        }

    /** Run one CHATHISTORY TARGETS discovery request on the per-network wire lock. */
    internal suspend fun fetchTargets(
        networkId: Long,
        source: HistorySource,
        request: ChatHistoryRequest,
        timeoutMs: Long,
    ): ChatHistoryResponse.Targets =
        onWireLock(networkId, timeoutMs, retryableTimeout = false) {
            source.chathistory(request) as? ChatHistoryResponse.Targets
                ?: error("CHATHISTORY TARGETS returned a message response")
        }

    /** The advertised selector for [reference], or null when nothing usable was advertised. */
    internal fun selectorOf(
        reference: ChatHistoryReference,
        referenceTypes: Set<HistoryReferenceType>,
        msgidAllowed: Boolean,
    ): BoundarySelector? = reference.selector(referenceTypes, msgidAllowed)

    /**
     * Coalesce concurrent identical fetches. The leader runs [block] in its caller's coroutine, so
     * cancelling the leader (e.g. a Pager generation replaced mid-APPEND by a bounds change) fails
     * its own flight — but must not poison joined followers from live generations. A follower whose
     * own context is still active treats the leader's [CancellationException] as "flight abandoned"
     * and retries: it becomes the next leader or joins a newer flight. A follower that is itself
     * cancelled rethrows its own cancellation; non-cancellation failures are shared by all awaiters.
     */
    private suspend fun coalesced(
        key: FlightKey,
        block: suspend () -> PageResult,
    ): PageResult {
        while (true) {
            val existing = inFlight[key]
            if (existing != null) {
                try {
                    return existing.await()
                } catch (cancelled: CancellationException) {
                    // Distinguish "the leader was cancelled" from "this follower was cancelled":
                    // only a still-active follower may retry.
                    currentCoroutineContext().ensureActive()
                    // Let the cancelled leader finish unwinding (it removes the failed flight in
                    // its finally) before re-inspecting the map, so the retry cannot busy-spin on
                    // the same dead deferred.
                    yield()
                    continue
                }
            }
            val deferred = CompletableDeferred<PageResult>()
            if (inFlight.putIfAbsent(key, deferred) != null) continue
            try {
                val result = block()
                deferred.complete(result)
                return result
            } catch (error: Throwable) {
                deferred.completeExceptionally(error)
                throw error
            } finally {
                inFlight.remove(key, deferred)
            }
        }
    }

    /**
     * Acquire the per-network wire lock and run [block] with [timeoutMs] bounding the WHOLE
     * operation: lock wait plus the request(s). A timeout fired while still queued behind another
     * fetch cancels the pending lock acquisition cleanly, so a caller's budget is honored even on a
     * busy wire.
     */
    private suspend fun <T> onWireLock(
        networkId: Long,
        timeoutMs: Long,
        retryableTimeout: Boolean,
        block: suspend () -> T,
    ): T {
        // withTimeout crosses a coroutine boundary, so coroutine stacktrace recovery would hand back
        // a copy of whatever the block raised. RemoteMediator must let the original
        // CancellationException instance reach Paging untouched, so capture and rethrow the exact
        // throwable the block produced.
        var raised: Throwable? = null
        return try {
            withTimeout(timeoutMs) {
                try {
                    networkLocks.getOrPut(networkId, ::Mutex).withLock { block() }
                } catch (error: Throwable) {
                    raised = error
                    throw error
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            diagnostics.record("chat_history", "loader_wire_timeout") {
                mapOf("network_id" to networkId, "timeout_ms" to timeoutMs, "retryable" to retryableTimeout)
            }
            // For Paging (retryableTimeout), never let the timeout escape as a CancellationException:
            // the mediator rethrows those to Paging, whose accessor would keep this direction's
            // LoadState stuck at Loading with a stale pending request. Surface it as a retryable
            // transport failure instead. The coordinator's traversals want the original timeout so
            // their own TimeoutCancellationException handlers can report a friendly result.
            if (retryableTimeout) throw IrcDisconnectedException("CHATHISTORY", "request timed out")
            throw timeout
        } catch (error: Throwable) {
            throw raised ?: error
        }
    }

    private suspend fun runRequest(
        source: HistorySource,
        request: ChatHistoryRequest,
    ): ChatHistoryResponse.Messages =
        (source.chathistory(request) as? ChatHistoryResponse.Messages)
            ?.boundedToRequest(request)
            ?: error("CHATHISTORY ${request.subcommand} returned a TARGETS response")

    /** Keep stored cursors constrained to selectors the server actually advertised. */
    private fun ChatHistoryResponse.Messages.withAdvertisedBoundaries(
        referenceTypes: Set<HistoryReferenceType>,
        allowMsgid: Boolean,
    ): ChatHistoryResponse.Messages {
        if (allowMsgid && HistoryReferenceType.MSGID in referenceTypes) return this
        return copy(
            oldest = oldest?.copy(msgid = null),
            newest = newest?.copy(msgid = null),
        )
    }

    private val ChatHistoryResponse.Messages.isComplete: Boolean
        get() = endOfHistory || primaryMessageCount == 0

    private fun ChatHistoryResponse.Messages.hasUsableOldest(
        referenceTypes: Set<HistoryReferenceType>,
        allowMsgid: Boolean,
    ): Boolean = oldest?.selector(referenceTypes, allowMsgid) != null

    private fun ChatHistoryResponse.Messages.hasUsableNewest(
        referenceTypes: Set<HistoryReferenceType>,
        allowMsgid: Boolean,
    ): Boolean = newest?.selector(referenceTypes, allowMsgid) != null

    private fun ChatHistoryResponse.Messages.cannotSafelyPageBefore(
        referenceTypes: Set<HistoryReferenceType>,
        allowMsgid: Boolean,
        requestLimit: Int,
        previous: BoundarySelector? = null,
    ): Boolean {
        if (isComplete) return false
        val next = oldest?.selector(referenceTypes, allowMsgid) ?: return true
        return next.value == previous?.value ||
            (next.type == HistoryReferenceType.TIMESTAMP && primaryMessageCount >= requestLimit)
    }

    private fun ChatHistoryResponse.Messages.cannotSafelyPageAfter(
        referenceTypes: Set<HistoryReferenceType>,
        allowMsgid: Boolean,
        requestLimit: Int,
        previous: BoundarySelector,
    ): Boolean {
        if (isComplete) return false
        val next = newest?.selector(referenceTypes, allowMsgid) ?: return true
        return next.value == previous.value ||
            (next.type == HistoryReferenceType.TIMESTAMP && primaryMessageCount >= requestLimit)
    }

    private fun ChatHistoryReference.selector(
        referenceTypes: Set<HistoryReferenceType>,
        allowMsgid: Boolean,
    ): BoundarySelector? {
        val exactMsgid = msgid
        val exactServerTime = serverTime
        return when {
            allowMsgid && HistoryReferenceType.MSGID in referenceTypes && !exactMsgid.isNullOrEmpty() ->
                BoundarySelector(ChatHistorySelectors.msgid(exactMsgid), HistoryReferenceType.MSGID)
            HistoryReferenceType.TIMESTAMP in referenceTypes && exactServerTime != null ->
                BoundarySelector(ChatHistorySelectors.timestamp(exactServerTime), HistoryReferenceType.TIMESTAMP)
            else -> null
        }
    }

    internal companion object {
        /** IRCv3 error code for "this msgid selector type is not accepted"; shared with callers. */
        internal const val INVALID_MSGREFTYPE = "INVALID_MSGREFTYPE"

        private const val REQUEST_TIMEOUT_MS = 35_000L
    }
}
