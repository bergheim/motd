package io.github.trevarj.motd.data.sync

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import io.github.trevarj.motd.data.db.BufferDao
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageDao
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.HistoryCursorDao
import io.github.trevarj.motd.data.db.HistoryCursorEntity
import io.github.trevarj.motd.data.db.HistoryGapDao
import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.ircTarget
import io.github.trevarj.motd.data.repo.ChatHistoryMediatorFactory
import io.github.trevarj.motd.data.repo.HistoryWindowFocus
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.client.ChatHistoryReference
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.event.historyEventMetadataOrNull
import io.github.trevarj.motd.irc.client.IrcDisconnectedException
import io.github.trevarj.motd.irc.ext.ChatHistorySelectors
import io.github.trevarj.motd.ircbackend.IrcSessions
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * CHATHISTORY-backed directional paging. The list is DESC (newest first): APPEND fetches older
 * messages via BEFORE, while a focused unread/deep-link island uses PREPEND + AFTER toward recent.
 *
 * REFRESH → if the buffer is empty and the network advertises chathistory, pull LATEST once.
 * APPEND  → older boundary; stop when historyComplete/no cap; when the buffer is empty (no oldest
 *           boundary yet) pull LATEST once to backfill on first open; otherwise BEFORE the oldest
 *           protocol page boundary. Completed empty pages and explicit end markers persist the
 *           confirmed start-of-history state through EventProcessor.
 *
 * Paging treats `endOfPaginationReached` as PERMANENT for a direction, so both directional loads
 * report it only when paging is genuinely finished (gap closed/unrecoverable, history complete, or a
 * page that made no progress) — never merely because the loader had to stop at one ambiguous
 * equal-timestamp page edge. See [appendResult].
 *
 * Every entry uses SKIP_INITIAL_REFRESH so the cached DB paints without network I/O; Paging3 then
 * drives REFRESH (empty-store LATEST seed, otherwise no-op) and scroll-triggered APPEND for older
 * history. Under Recent focus, PREPEND ends immediately because live events supply newer messages.
 * The loader owns availability, page-limit derivation, and all fetch concurrency.
 */
@OptIn(ExperimentalPagingApi::class)
class ChatHistoryRemoteMediator(
    private val bufferId: Long,
    private val bufferDao: BufferDao,
    private val messageDao: MessageDao,
    private val processor: EventProcessor,
    private val history: HistorySource,
    private val pageSize: Int = 50,
    private val historyCursorDao: HistoryCursorDao? = null,
    private val historyGapDao: HistoryGapDao? = null,
    private val focus: HistoryWindowFocus = HistoryWindowFocus.Recent,
    // Owns the fetch/persist/concurrency primitives. Defaulted so the existing positional test
    // construction stays valid; production always injects the shared singleton via the factory.
    private val loader: HistoryPageLoader = HistoryPageLoader(processor),
    // Opt-in decision-point journal for the paging control flow. Fields carry classification, ids,
    // counts, timestamps, and msgid PRESENCE only — never message content or msgid values. This is
    // the observability that identified the unrecoverable-gap append stall on timestamp-only wires.
    private val diagnostics: DiagnosticLogger = DiagnosticLogger.Noop,
) : RemoteMediator<Int, MessageEntity>() {

    /**
     * Minimal seam over the live [io.github.trevarj.motd.irc.client.IrcClient] (mirrors
     * reconnect coordinator's history-source seam) so the load logic is unit-testable
     * against scripted responses without a socket. Resolved per-load so a client that connects after
     * the buffer opens is picked up on the next boundary hit. Shares [HistoryPageLoader]'s seam so a
     * scripted source can drive both directly.
     */
    interface HistorySource : HistoryPageLoader.HistorySource {
        override suspend fun availability(): HistoryAvailability
        override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse
    }

    override suspend fun initialize(): InitializeAction =
        // Local cache is authoritative for normal entry and deep-link initial paint; the Around page
        // is pre-fetched by ChatJumpResolver. Paging drives REFRESH/APPEND explicitly afterward, and
        // the loader owns availability + concurrency for each fetch it performs.
        InitializeAction.SKIP_INITIAL_REFRESH

    override suspend fun load(loadType: LoadType, state: PagingState<Int, MessageEntity>): MediatorResult {
        return try {
            val buffer = bufferDao.observeById(bufferId)
                ?: return endLoad(loadType, "missing_buffer")
            if (buffer.type == BufferType.SERVER) {
                // Console buffers have no CHATHISTORY target. With the mediator attached
                // unconditionally, mirror the UI's Hidden rule here or every console open would
                // emit junk `CHATHISTORY BEFORE <servername>` traffic.
                return endLoad(loadType, "server_buffer")
            }
            val networkId = buffer.networkId
            // The loader re-derives availability, page limit, and reference types from the source per
            // fetch and owns all wire serialization/coalescing, so no upfront availability gate or
            // per-buffer lock is needed here.
            when (loadType) {
                LoadType.REFRESH -> refresh(networkId, buffer.id, buffer.ircTarget)
                LoadType.PREPEND -> prepend(networkId, buffer.id, buffer.ircTarget)
                LoadType.APPEND -> append(
                    networkId,
                    buffer.id,
                    buffer.ircTarget,
                    buffer.historyComplete,
                )
            }.also { result ->
                diagnostics.record("chat_history", "mediator_load_result") {
                    mapOf(
                        "load_type" to loadType.name,
                        "room_id" to bufferId,
                        "outcome" to when (result) {
                            is MediatorResult.Success ->
                                if (result.endOfPaginationReached) "end" else "more"
                            is MediatorResult.Error -> "error"
                            else -> "unknown"
                        },
                        "error_class" to (result as? MediatorResult.Error)
                            ?.throwable?.let { it::class.simpleName },
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            diagnostics.record("chat_history", "mediator_load_failed") {
                mapOf(
                    "load_type" to loadType.name,
                    "room_id" to bufferId,
                    "error_class" to e::class.simpleName,
                )
            }
            MediatorResult.Error(e)
        }
    }

    /**
     * End pagination locally with the decision recorded; [reason] is a fixed classification. The
     * field is named `end_reason` because DiagnosticLogger redacts any field literally named
     * `reason` (IRC quit/kick reasons are user content; this classification is not).
     */
    private fun endLoad(
        loadType: LoadType,
        reason: String,
        extra: Map<String, Any?> = emptyMap(),
    ): MediatorResult {
        diagnostics.record("chat_history", "mediator_load_ended") {
            mapOf("load_type" to loadType.name, "room_id" to bufferId, "end_reason" to reason) + extra
        }
        return MediatorResult.Success(endOfPaginationReached = true)
    }

    private suspend fun refresh(
        networkId: Long,
        roomId: Long,
        target: String,
    ): MediatorResult {
        val newest = messageDao.newestTime(roomId)
        if (newest != null) {
            // Already have local history; the local PagingSource paints it. APPEND drives older.
            return MediatorResult.Success(endOfPaginationReached = false)
        }
        return loader.loadPage(
            networkId,
            roomId,
            target,
            HistoryPageLoader.Direction.LATEST,
            history,
            pageSize,
        ).toMediatorResult()
    }

    private suspend fun append(
        networkId: Long,
        roomId: Long,
        target: String,
        historyComplete: Boolean,
    ): MediatorResult {
        val gaps = historyGapDao?.forRoom(roomId).orEmpty()
        val focusedGap = focusedOlderGap(gaps)
        if (focusedGap?.recoverable == false) {
            return endLoad(LoadType.APPEND, "unrecoverable_focused_gap")
        }
        if (historyComplete && focusedGap == null) {
            return endLoad(LoadType.APPEND, "history_complete")
        }
        val cursor = historyCursorDao?.byRoom(roomId)
        val oldest = olderBoundary(roomId, focusedGap, cursor)
        diagnostics.record("chat_history", "append_boundary") {
            mapOf(
                "room_id" to roomId,
                "gap_count" to gaps.size,
                "focused_gap_id" to focusedGap?.id,
                "focused_gap_recoverable" to focusedGap?.recoverable,
                "has_cursor" to (cursor != null),
                "boundary_has_msgid" to (oldest?.msgid != null),
                "boundary_server_time" to oldest?.serverTime,
            )
        }
        if (oldest == null) {
            // Empty local store hit the end boundary on first open. With SKIP_INITIAL_REFRESH the
            // REFRESH backfill never fires, so seed the newest page here via LATEST. If the server
            // has history the inserted rows re-run the PagingSource; a later APPEND then pages older.
            return loader.loadPage(
                networkId,
                roomId,
                target,
                HistoryPageLoader.Direction.LATEST,
                history,
                pageSize,
            ).appendResult(roomId, previous = null)
        }
        return loader.loadPage(
            networkId,
            roomId,
            target,
            HistoryPageLoader.Direction.OLDER,
            history,
            pageSize,
            gapId = focusedGap?.id,
            boundary = oldest,
        ).appendResult(roomId, previous = oldest)
    }

    /**
     * Decide APPEND terminality from PROGRESS rather than from the loader's per-page cursor guard.
     *
     * [HistoryPageLoader.PageResult.Loaded.endOfDirection] conflates two different facts: "this
     * direction is exhausted" and "I cannot safely page again from THIS cursor" (an ambiguous
     * equal-timestamp boundary at a saturated page edge). Paging treats `endOfPaginationReached` as
     * permanently terminal for the direction, so reporting the second fact kills older backfill after
     * a single page on a timestamp-only wire (soju advertises `MSGREFTYPES=timestamp`), where every
     * saturated page trips it. Terminate only when older paging is genuinely finished:
     *  - the focused older gap became server-proven unrecoverable, or
     *  - history is complete and no focused gap remains, or
     *  - the page made no progress at all.
     * Otherwise the boundary moved (or rows landed), so the next APPEND issues a different request
     * and the ambiguity that stopped this page no longer applies.
     */
    private suspend fun HistoryPageLoader.PageResult.appendResult(
        roomId: Long,
        previous: ChatHistoryReference?,
    ): MediatorResult {
        val page = this as? HistoryPageLoader.PageResult.Loaded ?: return toMediatorResult()
        val remaining = focusedOlderGap(historyGapDao?.forRoom(roomId).orEmpty())
        if (remaining?.recoverable == false) {
            return endLoad(LoadType.APPEND, "exhausted_focused_gap")
        }
        if (bufferDao.observeById(roomId)?.historyComplete == true && remaining == null) {
            return endLoad(LoadType.APPEND, "history_complete")
        }
        val next = olderBoundary(roomId, remaining, historyCursorDao?.byRoom(roomId))
        if (page.insertedCount == 0 && !next.advancedFrom(previous)) {
            // Anti-livelock guard: the next APPEND would repeat this exact request, so returning
            // "more" here would have Paging hammer the wire. A silent permanent stop is hard to
            // diagnose in the field, so record the boundary that failed to move.
            return endLoad(
                LoadType.APPEND,
                "no_append_progress",
                mapOf(
                    "primary_count" to page.primaryCount,
                    "end_of_direction" to page.endOfDirection,
                    "focused_gap_id" to remaining?.id,
                    "boundary_has_msgid" to (next?.msgid != null),
                    "boundary_server_time" to next?.serverTime,
                ),
            )
        }
        return MediatorResult.Success(endOfPaginationReached = false)
    }

    /**
     * The APPEND boundary: the focused older gap's newer edge, else the stored protocol cursor, else
     * the oldest retained row. Recomputed after a page so a cursor that actually receded can be told
     * apart from one that did not.
     */
    private suspend fun olderBoundary(
        roomId: Long,
        focusedGap: HistoryGapEntity?,
        cursor: HistoryCursorEntity?,
    ): ChatHistoryReference? =
        focusedGap?.let { ChatHistoryReference(it.newerMsgid, it.newerServerTime) }
            ?: cursor?.let { ChatHistoryReference(it.oldestMsgid, it.oldestServerTime) }
            ?.takeIf { it.msgid != null || it.serverTime != null }
            ?: messageDao.oldestBoundary(roomId)?.let {
                ChatHistoryReference(it.msgid, it.serverTime)
            }

    /**
     * Would paging from this boundary issue a different request than [previous]? Losing a msgid at an
     * unchanged timestamp is NOT an advance: timestamp-only wires strip advertised msgid references,
     * so the next request would carry the identical timestamp selector over the identical interval.
     */
    private fun ChatHistoryReference?.advancedFrom(previous: ChatHistoryReference?): Boolean {
        if (this == null || previous == null) return this != previous
        return serverTime != previous.serverTime || (msgid != null && msgid != previous.msgid)
    }

    /** Grow an unread/deep-link segment toward the recent window. */
    private suspend fun prepend(
        networkId: Long,
        roomId: Long,
        target: String,
    ): MediatorResult {
        val gap = focusedNewerGap(historyGapDao?.forRoom(roomId).orEmpty())
            ?: return MediatorResult.Success(endOfPaginationReached = true)
        if (!gap.recoverable) return MediatorResult.Success(endOfPaginationReached = true)
        val boundary = ChatHistoryReference(gap.olderMsgid, gap.olderServerTime)
        val result = loader.loadPage(
            networkId,
            roomId,
            target,
            HistoryPageLoader.Direction.NEWER,
            history,
            pageSize,
            gapId = gap.id,
            boundary = boundary,
        )
        val page = result as? HistoryPageLoader.PageResult.Loaded ?: return result.toMediatorResult()
        // The focused newer gap shrank as this page was persisted; re-read it and apply the same
        // progress rule APPEND uses. A saturated timestamp-only catch-up page trips the loader's
        // cannotSafelyPageAfter guard, which says "not from this cursor", not "no newer history";
        // reporting it to Paging would permanently terminate PREPEND, leaving the reconnect gap open
        // and everything newer than it outside the Around window forever.
        val remaining = focusedNewerGap(historyGapDao?.forRoom(roomId).orEmpty())
            ?: return endLoad(LoadType.PREPEND, "newer_gap_closed")
        if (!remaining.recoverable) return endLoad(LoadType.PREPEND, "exhausted_focused_gap")
        val next = ChatHistoryReference(remaining.olderMsgid, remaining.olderServerTime)
        if (page.insertedCount == 0 && !next.advancedFrom(boundary)) {
            // Anti-livelock guard, mirroring APPEND: an unmoved boundary with nothing persisted
            // means the next PREPEND would repeat this request verbatim.
            return endLoad(
                LoadType.PREPEND,
                "no_prepend_progress",
                mapOf(
                    "primary_count" to page.primaryCount,
                    "end_of_direction" to page.endOfDirection,
                    "focused_gap_id" to remaining.id,
                    "boundary_has_msgid" to (next.msgid != null),
                    "boundary_server_time" to next.serverTime,
                ),
            )
        }
        return MediatorResult.Success(endOfPaginationReached = false)
    }

    /** Map a loader outcome onto this direction's Paging result. */
    private fun HistoryPageLoader.PageResult.toMediatorResult(): MediatorResult = when (this) {
        is HistoryPageLoader.PageResult.Loaded ->
            MediatorResult.Success(endOfPaginationReached = endOfDirection)
        HistoryPageLoader.PageResult.Unsupported ->
            MediatorResult.Success(endOfPaginationReached = true)
        is HistoryPageLoader.PageResult.Unavailable -> MediatorResult.Error(cause)
        is HistoryPageLoader.PageResult.Failed -> MediatorResult.Error(cause)
    }

    private suspend fun focusedOlderGap(gaps: List<HistoryGapEntity>): HistoryGapEntity? {
        val resolved = gaps.map { it to gapNewerAnchor(it) }
        return when (val current = focus) {
            HistoryWindowFocus.Recent -> resolved.maxByOrNull { it.second }?.first
            is HistoryWindowFocus.Around -> resolved
                .filter { it.second <= current.anchor }
                .maxByOrNull { it.second }
                ?.first
        }
    }

    private suspend fun focusedNewerGap(gaps: List<HistoryGapEntity>): HistoryGapEntity? {
        if (focus !is HistoryWindowFocus.Around) return null
        val anchor = focus.anchor
        return gaps.map { it to gapOlderAnchor(it) }
            .filter { it.second >= anchor }
            .minByOrNull { it.second }
            ?.first
    }

    private suspend fun gapOlderAnchor(gap: HistoryGapEntity) = gap.olderMsgid
        ?.let { messageDao.byMsgid(bufferId, it) }
        ?.let { io.github.trevarj.motd.data.db.TimelineAnchor(it.serverTime, it.id, it.timelineOrder) }
        ?: gap.olderEventId?.let { id ->
            messageDao.byCanonicalId(id)?.takeIf { it.bufferId == bufferId }
                ?.let { io.github.trevarj.motd.data.db.TimelineAnchor(it.serverTime, it.id, it.timelineOrder) }
        }
        ?: gap.olderEventId?.let {
            io.github.trevarj.motd.data.db.TimelineAnchor(
                gap.olderServerTime,
                it,
                gap.olderTimelineOrder ?: it,
            )
        }
        ?: io.github.trevarj.motd.data.db.TimelineAnchor(
            gap.olderServerTime,
            Long.MIN_VALUE,
            Long.MIN_VALUE,
        )

    private suspend fun gapNewerAnchor(gap: HistoryGapEntity) = gap.newerMsgid
        ?.let { messageDao.byMsgid(bufferId, it) }
        ?.let { io.github.trevarj.motd.data.db.TimelineAnchor(it.serverTime, it.id, it.timelineOrder) }
        ?: gap.newerEventId?.let { id ->
            messageDao.byCanonicalId(id)?.takeIf { it.bufferId == bufferId }
                ?.let { io.github.trevarj.motd.data.db.TimelineAnchor(it.serverTime, it.id, it.timelineOrder) }
        }
        ?: gap.newerEventId?.let {
            io.github.trevarj.motd.data.db.TimelineAnchor(
                gap.newerServerTime,
                it,
                gap.newerTimelineOrder ?: it,
            )
        }
        ?: io.github.trevarj.motd.data.db.TimelineAnchor(
            gap.newerServerTime,
            Long.MAX_VALUE,
            Long.MAX_VALUE,
        )
}

/** Enforce the client-requested primary bound even when a server over-delivers a batch. */
internal fun ChatHistoryResponse.Messages.boundedToRequest(
    request: ChatHistoryRequest,
    preferredAroundMsgid: String? = null,
): ChatHistoryResponse.Messages {
    if (primaryMessageCount <= request.limit) return this
    val primaryIndices = events.indices.filter { index ->
        events[index].historyEventMetadataOrNull()?.isContext != true
    }
    if (primaryIndices.size <= request.limit) return this
    val selectedIndices = when (request.subcommand) {
        ChatHistoryRequest.Subcommand.AFTER,
        ChatHistoryRequest.Subcommand.BETWEEN,
        -> primaryIndices.take(request.limit)
        ChatHistoryRequest.Subcommand.BEFORE,
        ChatHistoryRequest.Subcommand.LATEST,
        -> primaryIndices.takeLast(request.limit)
        ChatHistoryRequest.Subcommand.AROUND -> {
            val preferredPosition = preferredAroundMsgid?.let { preferred ->
                primaryIndices.indexOfFirst { index ->
                    events[index].historyEventMetadataOrNull()?.msgid == preferred
                }.takeIf { it >= 0 }
            }
            val targetPosition = preferredPosition ?: primaryIndices.indexOfFirst { index ->
                val metadata = events[index].historyEventMetadataOrNull()
                request.bound1 == metadata?.msgid?.let(ChatHistorySelectors::msgid) ||
                    metadata?.serverTime?.let(ChatHistorySelectors::timestamp) == request.bound1
            }
            check(targetPosition >= 0) {
                "CHATHISTORY AROUND over-delivered without the requested retained boundary"
            }
            val start = (targetPosition - request.limit / 2)
                .coerceIn(0, primaryIndices.size - request.limit)
            primaryIndices.subList(start, start + request.limit)
        }
        ChatHistoryRequest.Subcommand.TARGETS -> return this
    }
    val selected = selectedIndices.toSet()
    val retained = events.filterIndexed { index, event ->
        index in selected || event.historyEventMetadataOrNull()?.isContext == true
    }
    val references = selected.sorted().mapNotNull { index ->
        events[index].historyEventMetadataOrNull()?.let { metadata ->
            ChatHistoryReference(metadata.msgid, metadata.serverTime)
        }
    }
    fun ChatHistoryReference?.usable(): Boolean =
        this != null && (!msgid.isNullOrEmpty() || serverTime != null)
    val oldest = references.firstOrNull()
    val newest = references.lastOrNull()
    val hasRequiredContinuation = when (request.subcommand) {
        ChatHistoryRequest.Subcommand.AFTER,
        ChatHistoryRequest.Subcommand.BETWEEN,
        -> newest.usable()
        ChatHistoryRequest.Subcommand.BEFORE,
        ChatHistoryRequest.Subcommand.LATEST,
        -> oldest.usable()
        ChatHistoryRequest.Subcommand.AROUND -> oldest.usable() && newest.usable()
        ChatHistoryRequest.Subcommand.TARGETS -> true
    }
    check(hasRequiredContinuation) {
        "CHATHISTORY ${request.subcommand} over-delivered without a usable retained boundary"
    }
    return copy(
        events = retained,
        oldest = oldest,
        newest = newest,
        // The server may have reached its true boundary, but this client deliberately discarded
        // primary rows outside the requested window. Persist the retained page as non-terminal so
        // its oldest/newest cursor remains a durable route back to the omitted interval.
        endOfHistory = false,
        primaryMessageCount = selected.size,
    )
}

/**
 * Real mediator factory wired into [io.github.trevarj.motd.data.repo.MessageRepositoryImpl] via
 * the frozen [ChatHistoryMediatorFactory] contract; WP10 rebinds this over the WP1 no-op stub.
 */
@OptIn(ExperimentalPagingApi::class)
@Singleton
class ChatHistoryMediatorFactoryImpl @Inject constructor(
    private val ircSessions: IrcSessions,
    private val bufferDao: BufferDao,
    private val messageDao: MessageDao,
    private val processor: EventProcessor,
    private val loader: HistoryPageLoader,
    private val historyCursorDao: HistoryCursorDao,
    private val historyGapDao: HistoryGapDao,
    private val diagnostics: DiagnosticLogger,
) : ChatHistoryMediatorFactory {
    override fun create(
        bufferId: Long,
        focus: HistoryWindowFocus,
    ): RemoteMediator<Int, MessageEntity> =
        ChatHistoryRemoteMediator(
            bufferId,
            bufferDao,
            messageDao,
            processor,
            historyFor(bufferId),
            historyCursorDao = historyCursorDao,
            historyGapDao = historyGapDao,
            focus = focus,
            loader = loader,
            diagnostics = diagnostics,
        )

    // Resolve the live session lazily per call: the buffer can open before its network reaches
    // Ready, and sessionFor(...) is only stable once connected. Missing/negotiating sessions remain
    // retryable rather than masquerading as unsupported or a completed empty history response.
    private fun historyFor(bufferId: Long): ChatHistoryRemoteMediator.HistorySource =
        object : ChatHistoryRemoteMediator.HistorySource {
            private suspend fun client() =
                bufferDao.observeById(bufferId)?.networkId?.let { ircSessions.sessionFor(it) }

            override suspend fun availability(): HistoryAvailability =
                client()?.historyAvailability ?: HistoryAvailability.NegotiatingOrOffline

            override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse =
                client()?.chathistory(req) ?: throw IrcDisconnectedException("CHATHISTORY", null)
        }
}
