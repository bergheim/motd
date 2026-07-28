package io.github.trevarj.motd.service

import dagger.Lazy
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.RoomId
import io.github.trevarj.motd.data.db.ircTarget
import io.github.trevarj.motd.data.prefs.HistorySyncPrefs
import io.github.trevarj.motd.data.prefs.NoopHistorySyncPrefs
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.data.sync.HistoryPageLoader
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryReference
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.ChatHistoryTarget
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.HistoryReferenceType
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcCommandException
import io.github.trevarj.motd.irc.ext.ChatHistorySelectors
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.ircbackend.IrcSessions
import io.github.trevarj.motd.ui.chat.fetchAroundHistoryPage
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

sealed interface HistoryResyncState {
    data object Idle : HistoryResyncState
    data object WaitingForCapability : HistoryResyncState
    data class Running(val fetched: Int = 0, val limit: Int? = null) : HistoryResyncState
    data class Updated(val inserted: Int) : HistoryResyncState
    data object UpToDate : HistoryResyncState
    data object Unsupported : HistoryResyncState
    open class Failed(open val reason: String) : HistoryResyncState {
        open override fun equals(other: Any?): Boolean =
            other is Failed && javaClass == other.javaClass && reason == other.reason

        open override fun hashCode(): Int = reason.hashCode()

        open override fun toString(): String = "${javaClass.simpleName}(reason=$reason)"
    }
    data class Incomplete(
        val inserted: Int,
        override val reason: String,
        val awaitsTargetClassification: Boolean = false,
        val retryRecommended: Boolean = false,
    ) : Failed(reason)
    data class Capped(val inserted: Int, val limit: Int, override val reason: String) : Failed(reason)
}

/** Per-buffer progress and actionable failure state for automatic and user-requested history work. */
sealed interface HistorySyncStatus {
    data object Idle : HistorySyncStatus
    data object Checking : HistorySyncStatus
    data object Syncing : HistorySyncStatus
    data class Partial(val reason: String) : HistorySyncStatus
    data class Failed(val reason: String) : HistorySyncStatus
}

/** Prevent a cancelled or superseded sync from publishing its initial transient status late. */
internal fun initialSyncStatusIfCurrent(
    current: Map<Long, HistorySyncStatus>,
    bufferId: Long,
    generation: Long,
    currentGeneration: Long?,
    status: HistorySyncStatus,
): Map<Long, HistorySyncStatus> = if (currentGeneration == generation) {
    current + (bufferId to status)
} else {
    current
}

/**
 * Chat-facing boundary for lifecycle-driven history reconciliation. Callers supply only the
 * [BufferEntity]; the coordinator resolves and re-validates the live IRC session itself via
 * [IrcSessions] (docs/backend-neutral-xmpp-rollout.md client-escape-hatch removal), so no
 * `IrcClient`/`isCurrent` plumbing crosses this boundary.
 */
interface HistoryResyncController {
    fun syncStatus(bufferId: Long): Flow<HistorySyncStatus>

    suspend fun reconcileBuffer(
        buffer: BufferEntity,
    ): HistoryResyncState

    /**
     * Fetch the newest page without waiting behind network-wide discovery/backfill. This urgent
     * path promotes a just-sent local row before a reply or reaction needs its durable msgid.
     */
    suspend fun reconcilePendingMessage(
        buffer: BufferEntity,
    ): HistoryResyncState

    /**
     * CHATHISTORY AROUND fetch for a msgid target not yet local in [buffer] (search/reply jump).
     * Requires a live session advertising `draft/chathistory`; returns false when there is none,
     * the fetch fails, or the response cannot be used. A successful fetch persists the completed
     * page through the sole IRC→Room writer before returning true.
     */
    suspend fun fetchAround(
        buffer: BufferEntity,
        target: String,
        msgid: String,
        timeMs: Long,
        limit: Int,
    ): Boolean
}

/**
 * The sole reconnect/manual tail-revalidation entry point. The coordinator decides WHAT to fetch
 * (targets, ranges, ordering, gap recording, marker convergence) and what to report (states,
 * per-buffer sync status); every wire fetch goes through [HistoryPageLoader], whose per-network
 * lock serializes each individual CHATHISTORY request against scroll-driven Paging. Equivalent
 * whole requests (a reconnect pass, a manual refresh) still coalesce onto one [ActiveFlight], but
 * only to back user-facing status and cancellation — not as a fetch lock: two concurrent
 * same-buffer LATEST fetches are safe because [EventProcessor] deduplicates rows by msgid/identity
 * and gap recording recognizes an already-recorded interval. IRC-derived rows still flow
 * exclusively through [EventProcessor].
 */
@Singleton
class HistoryResyncCoordinator @Inject constructor(
    private val db: MotdDatabase,
    private val processor: EventProcessor,
    // Lazy because ConnectionManagerImpl holds this coordinator directly (like its avatarCoordinator
    // and webPushRegistrar dependencies) while also being the IrcSessions binding target; an eager
    // IrcSessions here would be a Dagger dependency cycle.
    private val ircSessions: Lazy<IrcSessions>,
    private val syncPrefs: HistorySyncPrefs = NoopHistorySyncPrefs,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val diagnostics: DiagnosticLogger = DiagnosticLogger.Noop,
    // The single wire-fetch primitive: every CHATHISTORY request the coordinator issues goes through
    // this shared singleton so reconnect/manual traversals serialize with scroll-driven Paging on the
    // loader's per-network lock. Defaulted so tests keep the four-argument construction.
    private val loader: HistoryPageLoader = HistoryPageLoader(processor),
) : HistoryResyncController {
    // Reuses the loader's transport seam so a source can drive both the coordinator's orchestration
    // and the loader's fetch primitives directly, and adds the discovery/classification metadata the
    // reconnect pass needs (target normalization, channel detection, and a per-connection flight id).
    internal interface HistorySource : HistoryPageLoader.HistorySource {
        override suspend fun availability(): HistoryAvailability
        override suspend fun chathistory(request: ChatHistoryRequest): ChatHistoryResponse
        fun flightIdentity(): Any = this
        fun canClassifyTargets(): Boolean = true
        fun normalizeTarget(target: String): String = IrcIdentityRules().normalize(target)
        fun isChannelTarget(target: String): Boolean = IrcIdentityRules().isChannel(target)
    }

    private data class RequestKey(val networkId: Long, val bufferId: Long?)
    private data class RequestSpec(
        val key: RequestKey,
        val sourceIdentity: Any,
    )
    private data class ActiveFlight(
        val spec: RequestSpec,
        val deferred: Deferred<HistoryResyncState>,
    )
    private data class FlightRegistration(
        val flight: ActiveFlight,
        val ownsFlight: Boolean,
    )

    private sealed interface WorkStatus {
        data object Complete : WorkStatus
        data class Incomplete(
            val reason: String,
            val awaitsTargetClassification: Boolean = false,
        ) : WorkStatus
        data class Capped(val reason: String, val limit: Int) : WorkStatus
    }

    private data class WorkResult(
        val status: WorkStatus = WorkStatus.Complete,
        val highWater: Long? = null,
        val inserted: Int = 0,
    )

    private data class TargetPass(
        val inserted: Int,
        val status: WorkStatus,
        val highWater: Long?,
        val retryRecommended: Boolean,
    )

    private data class TargetDiscovery(
        val targets: List<ChatHistoryTarget>,
        val status: WorkStatus,
        val highWater: Long?,
    )

    private data class SyncTarget(
        val knownBufferId: Long?,
        val name: String,
        val latestMessageTime: Long?,
    )

    // Cancellation is non-suspending, so registration and removal share a synchronous monitor.
    private val activeGuard = Any()
    private val activeFlights = LinkedHashMap<RequestSpec, ActiveFlight>()
    private val syncStatuses = MutableStateFlow<Map<Long, HistorySyncStatus>>(emptyMap())
    private val syncStatusGenerations = ConcurrentHashMap<Long, AtomicLong>()
    internal var requestTimeoutMs: Long = REQUEST_TIMEOUT_MS
    internal var targetsRequestLimit: Int = TARGETS_REQUEST_LIMIT

    override fun syncStatus(bufferId: Long): Flow<HistorySyncStatus> = syncStatuses
        .map { it[bufferId] ?: HistorySyncStatus.Idle }
        .distinctUntilChanged()

    /**
     * Reconcile a visible chat. The request shares the exact same per-buffer single flight as the
     * reconnect network pass.
     *
     * Resolves the live session for [buffer]'s network and re-validates identity around suspension
     * points exactly as the removed `ConnectionManager.clientFor` contract required (see
     * [IrcSessions]). No session at entry is treated the same as the mid-flight staleness this file
     * already detects via `isCurrent()`: [staleConnection]. Chat-facing callers previously never
     * invoked this boundary without first resolving a non-null client themselves, so this mirrors
     * that same terminal state rather than inventing a new one.
     */
    override suspend fun reconcileBuffer(buffer: BufferEntity): HistoryResyncState {
        val client = ircSessions.get().sessionFor(buffer.networkId) ?: return staleConnection()
        val isCurrent = { ircSessions.get().sessionFor(buffer.networkId) === client }
        return reconcileBuffer(buffer, client, isCurrent)
    }

    /**
     * Preserved for [ConnectionManagerImpl]'s `seedJoinedChannelHistory` (an IRC-internal
     * registration-race caller reached through the concrete coordinator type, not the chat-facing
     * [HistoryResyncController] boundary). Body unchanged from before the client/isCurrent params
     * were removed from the interface above.
     */
    suspend fun reconcileBuffer(
        buffer: BufferEntity,
        client: IrcClient,
        isCurrent: () -> Boolean,
    ): HistoryResyncState = reconcileBuffer(
        networkId = buffer.networkId,
        bufferId = buffer.id,
        target = buffer.ircTarget,
        source = ClientHistorySource(client),
        isCurrent = isCurrent,
    )

    override suspend fun reconcilePendingMessage(buffer: BufferEntity): HistoryResyncState {
        val client = ircSessions.get().sessionFor(buffer.networkId) ?: return staleConnection()
        val isCurrent = { ircSessions.get().sessionFor(buffer.networkId) === client }
        return reconcilePendingMessage(
            networkId = buffer.networkId,
            bufferId = buffer.id,
            target = buffer.ircTarget,
            source = ClientHistorySource(client),
            isCurrent = isCurrent,
        )
    }

    /**
     * Copied from the `ChatJumpResolver` `fetchAround` lambda that used to live in ChatViewModel
     * (docs/backend-neutral-xmpp-rollout.md client-escape-hatch removal): only the session
     * resolution changed, from `ConnectionManager.clientFor` to [IrcSessions.sessionFor].
     * Persistence still routes through [processor], the sole IRC→Room writer, via the same
     * [IrcEventSink.persistHistoryPage] override it implements.
     */
    override suspend fun fetchAround(
        buffer: BufferEntity,
        target: String,
        msgid: String,
        timeMs: Long,
        limit: Int,
    ): Boolean {
        val networkId = buffer.networkId
        val client = ircSessions.get().sessionFor(networkId) ?: return false
        val availability = client.historyAvailability as? HistoryAvailability.Ready
            ?: return false
        return try {
            fetchAroundHistoryPage(
                target = target,
                msgid = msgid,
                timeMs = timeMs,
                limit = limit,
                availability = availability,
                requestPage = client::chathistory,
                persistPage = { request, response ->
                    processor.persistHistoryPage(
                        networkId,
                        request,
                        response,
                        expectedRoomId = buffer.id,
                    )
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    /**
     * A normal reconciliation owns the coarse per-network gate while it discovers targets and
     * repairs gaps. A user action that only needs the newest msgid must not queue behind that whole
     * pass. IrcClient still correlates labeled responses and serializes unlabeled CHATHISTORY at
     * the wire boundary, while EventProcessor remains the sole Room writer.
     */
    internal suspend fun reconcilePendingMessage(
        networkId: Long,
        bufferId: Long,
        target: String,
        source: HistorySource,
        isCurrent: () -> Boolean = { true },
    ): HistoryResyncState {
        val ready = when (val availability = source.availability()) {
            HistoryAvailability.Unsupported -> return HistoryResyncState.Unsupported
            HistoryAvailability.NegotiatingOrOffline -> return historyUnavailable()
            is HistoryAvailability.Ready -> availability
        }
        if (!isCurrent()) return staleConnection()
        val referenceTypes = ready.referenceTypes
        val msgidAllowed = HistoryReferenceType.MSGID in referenceTypes
        return try {
            val request = ChatHistoryRequest(
                subcommand = ChatHistoryRequest.Subcommand.LATEST,
                target = target,
                limit = ready.pageLimit.coerceAtMost(PAGE_LIMIT).coerceAtLeast(1),
            )
            // The loader serializes this LATEST on the same per-network wire lock as every other
            // history fetch. Because that lock is held per wire request (never for a whole discovery
            // pass), an urgent pending promotion interleaves between a network resync's pages instead
            // of queuing behind the entire pass — the guarantee the old bespoke bypass provided.
            val latest = loader.fetchMessages(
                networkId,
                source,
                request,
                referenceTypes,
                msgidAllowed,
                timeoutMs = PENDING_MESSAGE_TIMEOUT_MS,
            )
            if (!isCurrent()) return staleConnection()
            val inserted = ingest(networkId, bufferId, request, latest)
            if (
                !latest.isTerminalPage() &&
                !latest.hasUsableDirectionalBoundary(
                    ChatHistoryRequest.Subcommand.LATEST,
                    referenceTypes,
                )
            ) {
                HistoryResyncState.Incomplete(
                    inserted,
                    "CHATHISTORY LATEST returned no usable primary-message boundary",
                )
            } else if (inserted > 0) {
                HistoryResyncState.Updated(inserted)
            } else {
                HistoryResyncState.UpToDate
            }
        } catch (_: TimeoutCancellationException) {
            HistoryResyncState.Failed("Pending message history refresh timed out")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: StaleConnectionException) {
            staleConnection()
        } catch (error: Exception) {
            HistoryResyncState.Failed(
                error.message?.take(160) ?: "Pending message history refresh failed",
            )
        }
    }

    suspend fun resyncNetwork(
        networkId: Long,
        openBuffers: List<Pair<Long, String>>,
        client: IrcClient,
        isCurrent: () -> Boolean,
    ): HistoryResyncState {
        if (!client.targetClassificationReady.value) {
            withTimeoutOrNull(TARGET_CLASSIFICATION_WAIT_TIMEOUT_MS) {
                client.targetClassificationReady.first { it }
            }
        }
        if (!isCurrent()) return staleConnection()
        return resyncNetwork(networkId, openBuffers, ClientHistorySource(client), isCurrent)
    }

    internal suspend fun resyncNetwork(
        networkId: Long,
        openBuffers: List<Pair<Long, String>>,
        source: HistorySource,
        isCurrent: () -> Boolean = { true },
    ): HistoryResyncState = coalesced(
        RequestSpec(
            RequestKey(networkId, null),
            sourceIdentity = source.flightIdentity(),
        ),
    ) {
        diagnostics.record("history", "network_sync_started") {
            mapOf("network_id" to networkId, "open_buffers" to openBuffers.size)
        }
        when (source.availability()) {
            HistoryAvailability.Unsupported -> return@coalesced HistoryResyncState.Unsupported
            HistoryAvailability.NegotiatingOrOffline -> return@coalesced historyUnavailable()
            is HistoryAvailability.Ready -> Unit
        }
        val syncGenerations = openBuffers.associate { (bufferId, _) ->
            bufferId to beginSyncStatus(bufferId, HistorySyncStatus.Checking)
        }
        // A room row's newest message is not a reliable reconnect cursor: a newer push-delivered
        // message in one buffer can otherwise hide an older missed message in another. The wall
        // clock bounds discovery but is never persisted; only completed server response metadata
        // can advance the dedicated whole-network cursor.
        val previousSync = syncPrefs.lastSuccessfulSync(networkId)
        val lower = (previousSync ?: Instant.EPOCH.toEpochMilli())
            .minus(TARGETS_FUZZ_MS)
            .coerceAtLeast(Instant.EPOCH.toEpochMilli())
        val upper = Instant.now().toEpochMilli() + TARGETS_FUZZ_MS
        val result = try {
            val discovery = if (source.canClassifyTargets()) {
                discoverTargets(networkId, source, upper, lower)
            } else {
                TargetDiscovery(
                    targets = emptyList(),
                    status = WorkStatus.Incomplete(
                        "CHATHISTORY TARGETS deferred until CHANTYPES negotiation settles",
                        awaitsTargetClassification = true,
                    ),
                    highWater = null,
                )
            }
            val targetPass = syncTargets(
                networkId = networkId,
                targets = mergeSyncTargets(openBuffers, discovery.targets, source),
                source = source,
                isCurrent = isCurrent,
                hasDiscoveryWatermark = previousSync != null,
                syncGenerations = syncGenerations,
            )
            val inserted = targetPass.inserted
            val status = discovery.status.merge(targetPass.status)
            val highWater = maxHighWater(
                previousSync,
                discovery.highWater,
                targetPass.highWater,
            )
            if (status == WorkStatus.Complete && isCurrent() && highWater != null) {
                syncPrefs.setLastSuccessfulSync(networkId, highWater)
            }
            status.toState(inserted, retryRecommended = targetPass.retryRecommended)
        } catch (_: TimeoutCancellationException) {
            HistoryResyncState.Failed("History refresh timed out")
        } catch (cancelled: CancellationException) {
            syncGenerations.forEach { (bufferId, generation) ->
                finishSyncStatus(bufferId, generation, HistorySyncStatus.Idle)
            }
            throw cancelled
        } catch (_: StaleConnectionException) {
            staleConnection()
        } catch (error: Exception) {
            HistoryResyncState.Failed(
                error.message?.take(160) ?: "History refresh failed",
            )
        }
        diagnostics.record("history", "network_sync_finished") {
            mapOf(
                "network_id" to networkId,
                "targets" to openBuffers.size,
                "result" to result::class.simpleName,
            )
        }
        syncGenerations.forEach { (bufferId, generation) ->
            finishUnresolvedSyncStatus(
                bufferId,
                generation,
                if (isCurrent()) result else HistoryResyncState.Idle,
            )
        }
        result
    }

    /**
     * Enumerate the complete TARGETS interval before the network cursor is advanced. TARGETS has
     * BETWEEN ordering semantics. Each next upper bound overlaps the oldest returned millisecond;
     * target identity deduplication absorbs that replay while preserving same-timestamp ties. A
     * saturated tie that cannot move beyond the overlap is explicitly incomplete.
     */
    private suspend fun discoverTargets(
        networkId: Long,
        source: HistorySource,
        upper: Long,
        lower: Long,
    ): TargetDiscovery {
        val limit = source.pageLimit().coerceAtLeast(1)
        val targets = LinkedHashMap<String, ChatHistoryTarget>()
        var pageUpper = upper
        var highWater: Long? = null
        var previousTie: Pair<Long, Set<String>>? = null
        var requestsInChunk = 0
        var status: WorkStatus = WorkStatus.Complete
        val chunkLimit = targetsRequestLimit.coerceAtLeast(1)
        while (true) {
            val response = loader.fetchTargets(
                networkId,
                source,
                ChatHistoryRequest(
                    subcommand = ChatHistoryRequest.Subcommand.TARGETS,
                    target = "*",
                    bound1 = ChatHistorySelectors.timestamp(pageUpper),
                    bound2 = ChatHistorySelectors.timestamp(lower),
                    limit = limit,
                ),
                requestTimeoutMs,
            )
            requestsInChunk++
            val page = response.targets
            page.forEach { target ->
                val key = source.normalizeTarget(target.name)
                val existing = targets[key]
                if (existing == null || target.latestMessageTime > existing.latestMessageTime) {
                    targets[key] = target
                }
                highWater = maxHighWater(highWater, target.latestMessageTime)
            }
            if (response.endOfHistory || page.isEmpty()) {
                return TargetDiscovery(targets.values.toList(), status, highWater)
            }

            val oldest = page.minOf { it.latestMessageTime }
            val tiedKeys = page.asSequence()
                .filter { it.latestMessageTime == oldest }
                .map { source.normalizeTarget(it.name) }
                .toSet()
            if (previousTie == (oldest to tiedKeys)) {
                if (page.size < limit && oldest > lower) {
                    // Soju 0.10.x omits draft/chathistory-end. Move beyond its repeated short tie
                    // page so older targets are still recovered, but never call the pass complete:
                    // IRCv3 permits a server to return fewer than the requested limit, so another
                    // same-time target could remain undisclosed.
                    status = status.merge(
                        WorkStatus.Incomplete(
                            "CHATHISTORY TARGETS could not prove a timestamp tie was exhausted",
                        ),
                    )
                    pageUpper = oldest
                    previousTie = null
                    if (requestsInChunk >= chunkLimit) {
                        requestsInChunk = 0
                        yield()
                    }
                    continue
                }
                return TargetDiscovery(
                    targets.values.toList(),
                    WorkStatus.Incomplete(
                        "CHATHISTORY TARGETS saturated a timestamp tie and could not advance",
                    ),
                    highWater,
                )
            }
            previousTie = oldest to tiedKeys

            // BETWEEN excludes both timestamp selectors. Move one millisecond past the oldest
            // timestamp so every tied target is replayed and deduplicated instead of skipped.
            val nextUpper = oldest.takeIf { it < Long.MAX_VALUE }?.plus(1)
            if (nextUpper == null || nextUpper >= pageUpper || nextUpper <= lower) {
                val reason = if (page.size >= limit && nextUpper != null && nextUpper >= pageUpper) {
                    "CHATHISTORY TARGETS saturated a timestamp tie and could not advance"
                } else {
                    "CHATHISTORY TARGETS returned an unusable boundary"
                }
                return TargetDiscovery(
                    targets.values.toList(),
                    WorkStatus.Incomplete(reason),
                    highWater,
                )
            }
            pageUpper = nextUpper
            if (requestsInChunk >= chunkLimit) {
                diagnostics.record("history", "targets_sync_continued") {
                    mapOf("targets" to targets.size, "high_water" to highWater)
                }
                requestsInChunk = 0
                yield()
            }
        }
    }

    private fun mergeSyncTargets(
        openBuffers: List<Pair<Long, String>>,
        discovered: List<ChatHistoryTarget>,
        source: HistorySource,
    ): List<SyncTarget> {
        val targets = LinkedHashMap<String, SyncTarget>()
        openBuffers.forEach { (bufferId, name) ->
            targets[source.normalizeTarget(name)] = SyncTarget(bufferId, name, null)
        }
        discovered.forEach { target ->
            val key = source.normalizeTarget(target.name)
            val existing = targets[key]
            targets[key] = if (existing == null) {
                SyncTarget(null, target.name, target.latestMessageTime)
            } else {
                existing.copy(
                    latestMessageTime = existing.latestMessageTime
                        ?.let { maxOf(it, target.latestMessageTime) }
                        ?: target.latestMessageTime,
                )
            }
        }
        return targets.values.sortedWith(
            compareByDescending<SyncTarget> { it.latestMessageTime != null }
                .thenByDescending { it.latestMessageTime ?: Long.MIN_VALUE },
        )
    }

    internal suspend fun reconcileBuffer(
        networkId: Long,
        bufferId: Long,
        target: String,
        source: HistorySource,
        isCurrent: () -> Boolean = { true },
    ): HistoryResyncState {
        when (source.availability()) {
            HistoryAvailability.Unsupported -> return HistoryResyncState.Unsupported
            HistoryAvailability.NegotiatingOrOffline -> return historyUnavailable()
            is HistoryAvailability.Ready -> Unit
        }
        if (!isCurrent()) return staleConnection()
        return coalesced(
            RequestSpec(
                RequestKey(networkId, bufferId),
                source.flightIdentity(),
            ),
        ) {
            try {
                val work = syncRecentTarget(
                    networkId = networkId,
                    bufferId = bufferId,
                    target = target,
                    source = source,
                    isCurrent = isCurrent,
                    discoveredLatestMessageTime = null,
                )
                work.status.toState(work.inserted)
            } catch (_: TimeoutCancellationException) {
                HistoryResyncState.Failed("History refresh timed out")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: StaleConnectionException) {
                staleConnection()
            } catch (error: Exception) {
                HistoryResyncState.Failed(error.message?.take(160) ?: "History refresh failed")
            }
        }
    }

    private suspend fun syncTargets(
        networkId: Long,
        targets: List<SyncTarget>,
        source: HistorySource,
        isCurrent: () -> Boolean,
        hasDiscoveryWatermark: Boolean,
        syncGenerations: Map<Long, Long> = emptyMap(),
    ): TargetPass {
        when (source.availability()) {
            HistoryAvailability.Unsupported -> error("History support disappeared during reconciliation")
            HistoryAvailability.NegotiatingOrOffline -> error("History support became unavailable")
            is HistoryAvailability.Ready -> Unit
        }
        if (!isCurrent()) throw StaleConnectionException()
        var inserted = 0
        var status: WorkStatus = WorkStatus.Complete
        var highWater: Long? = null
        var retryRecommended = false
        for (targetSpec in targets) {
            if (!isCurrent()) throw StaleConnectionException()
            val target = targetSpec.name
            val canonicalRoomId = targetSpec.knownBufferId ?: if (source.isChannelTarget(target)) {
                continue
            } else {
                processor.ensureHistoryQuery(networkId, target, source.normalizeTarget(target))
            }
            if (
                hasDiscoveryWatermark &&
                targetSpec.latestMessageTime == null &&
                db.historyCursorDao().byRoom(canonicalRoomId) != null
            ) {
                continue
            }
            val syncGeneration = syncGenerations[canonicalRoomId]
            if (syncGeneration != null) {
                publishSyncStatus(canonicalRoomId, syncGeneration, HistorySyncStatus.Syncing)
            }
            val targetResult = syncRecentTarget(
                networkId = networkId,
                bufferId = canonicalRoomId,
                target = target,
                source = source,
                isCurrent = isCurrent,
                discoveredLatestMessageTime = targetSpec.latestMessageTime,
            )
            inserted += targetResult.inserted
            // TARGETS describes the newest server event, which may be a JOIN or an event that is
            // intentionally filtered/rerouted during ingestion. Count either a durable local event
            // or an event observed in this response as reaching it; relying on the chat cursor alone
            // would retry forever for those valid cases.
            val newestStoredTime = maxHighWater(
                db.messageDao().latestBoundary(canonicalRoomId)?.serverTime,
                db.historyCursorDao().byRoom(canonicalRoomId)?.newestServerTime,
                targetResult.highWater,
            )
            val reachedAdvertisedLatest = targetSpec.latestMessageTime?.let { advertisedLatest ->
                newestStoredTime?.let { it >= advertisedLatest } == true
            }
            val effectiveStatus = if (
                reachedAdvertisedLatest == false && targetResult.status == WorkStatus.Complete
            ) {
                WorkStatus.Incomplete("CHATHISTORY did not reach the latest advertised message")
            } else {
                targetResult.status
            }
            val targetNeedsRetry = reachedAdvertisedLatest == false
            retryRecommended = retryRecommended || targetNeedsRetry
            status = status.merge(effectiveStatus)
            highWater = maxHighWater(highWater, targetResult.highWater)
            if (syncGeneration != null) {
                finishSyncStatus(
                    canonicalRoomId,
                    syncGeneration,
                    if (reachedAdvertisedLatest == true) {
                        HistorySyncStatus.Idle
                    } else {
                        effectiveStatus.toSyncStatus()
                    },
                )
            }
        }
        return TargetPass(inserted, status, highWater, retryRecommended)
    }

    /**
     * Seed one changed target newest-first. Each completed response is published immediately so a
     * visible chat can paint after one round trip; any older retained interval remains a durable
     * gap for directional Paging instead of being traversed during reconnect.
     */
    private suspend fun syncRecentTarget(
        networkId: Long,
        bufferId: Long,
        target: String,
        source: HistorySource,
        isCurrent: () -> Boolean,
        discoveredLatestMessageTime: Long?,
    ): WorkResult {
        val room = db.bufferDao().observeById(bufferId) ?: throw StaleConnectionException()
        val referenceTypes = source.referenceTypes()
        val msgidAllowed = HistoryReferenceType.MSGID in referenceTypes
        val discardedBoundary = ChatHistoryReference(
            room.historyDiscardedThroughMsgid,
            room.historyDiscardedThroughTime,
        ).takeIf { it.msgid != null || it.serverTime != null }
        if (room.dismissed && discoveredLatestMessageTime == null) return WorkResult()
        val discardedThroughTime = discardedBoundary?.serverTime
        if (
            room.dismissed &&
            discardedThroughTime != null &&
            discoveredLatestMessageTime != null &&
            discoveredLatestMessageTime <= discardedThroughTime
        ) {
            return WorkResult()
        }

        val requestLimit = minOf(source.pageLimit(), RECENT_PAGE_SIZE)
        val boundedLatest = discardedBoundary
            ?.takeIf { room.type == BufferType.QUERY }
            ?.let { floor ->
                fetchPageOrNullOnRejectedBoundary(
                    networkId = networkId,
                    target = target,
                    subcommand = ChatHistoryRequest.Subcommand.LATEST,
                    source = source,
                    boundary = floor,
                    secondBoundary = null,
                    referenceTypes = referenceTypes,
                    limit = requestLimit,
                    msgidAllowed = msgidAllowed,
                )
            }
        val request = boundedLatest?.request ?: ChatHistoryRequest(
            subcommand = ChatHistoryRequest.Subcommand.LATEST,
            target = target,
            limit = requestLimit,
        )
        val page = boundedLatest?.response ?: loader.fetchMessages(
            networkId,
            source,
            request,
            referenceTypes,
            msgidAllowed,
            requestTimeoutMs,
        )
        if (!isCurrent()) throw StaleConnectionException()
        val inserted = ingest(networkId, bufferId, request, page)
        val highWater = page.highWater()
        if (page.isTerminalPage()) return WorkResult(highWater = highWater, inserted = inserted)
        if (page.oldest?.msgid == null && page.primaryMessageCount >= request.limit) {
            return WorkResult(
                WorkStatus.Incomplete("CHATHISTORY timestamp boundary is saturated"),
                highWater,
                inserted,
            )
        }

        if (page.directionalBoundary(ChatHistoryRequest.Subcommand.LATEST) == null) {
            return WorkResult(
                WorkStatus.Incomplete("CHATHISTORY LATEST returned no usable oldest boundary"),
                highWater,
                inserted,
            )
        }
        // ingest persisted this non-terminal oldest boundary as a durable gap. Automatic reconnect
        // stops here; only user-authorized paging or manual refresh may traverse it with BEFORE.
        return WorkResult(highWater = highWater, inserted = inserted)
    }

    /** Manual eager recovery retained for explicit Missing/All Available requests. */
    private suspend fun coalesced(
        spec: RequestSpec,
        block: suspend () -> HistoryResyncState,
    ): HistoryResyncState {
        val registration = synchronized(activeGuard) {
            val joined = activeFlights[spec]
            if (joined != null) {
                FlightRegistration(joined, ownsFlight = false)
            } else {
                val deferred = scope.async(start = CoroutineStart.LAZY) {
                    // Wire serialization now lives in the loader's per-network lock, acquired per
                    // fetch inside block(); this flight only owns request-level coalescing and
                    // user-facing status.
                    block()
                }
                val created = ActiveFlight(spec, deferred)
                activeFlights[spec] = created
                deferred.invokeOnCompletion {
                    removeActiveFlight(created)
                }
                FlightRegistration(created, ownsFlight = true)
            }
        }
        val flight = registration.flight
        if (registration.ownsFlight) flight.deferred.start()
        try {
            return flight.deferred.await()
        } finally {
            if (flight.deferred.isCompleted) {
                removeActiveFlight(flight)
            }
        }
    }

    private fun removeActiveFlight(flight: ActiveFlight) {
        synchronized(activeGuard) {
            if (activeFlights[flight.spec] === flight) {
                activeFlights.remove(flight.spec)
            }
        }
    }

    /**
     * [HistoryPageLoader.fetchPage] rethrows the server's original `INVALID_MSGREFTYPE` when a
     * rejected msgid boundary has no advertised timestamp fallback, so Paging surfaces those
     * diagnostics via MediatorResult.Error. Reconnect/manual orchestration instead treats that
     * unrecoverable local boundary exactly like a pre-check rejection (null): callers degrade to a
     * LATEST seed rather than failing the whole pass on a stale stored cursor.
     */
    private suspend fun fetchPageOrNullOnRejectedBoundary(
        networkId: Long,
        target: String,
        subcommand: ChatHistoryRequest.Subcommand,
        source: HistorySource,
        boundary: ChatHistoryReference,
        secondBoundary: ChatHistoryReference?,
        referenceTypes: Set<HistoryReferenceType>,
        limit: Int,
        msgidAllowed: Boolean,
    ): HistoryPageLoader.FetchedPage? = try {
        loader.fetchPage(
            networkId = networkId,
            target = target,
            subcommand = subcommand,
            source = source,
            boundary = boundary,
            secondBoundary = secondBoundary,
            referenceTypes = referenceTypes,
            limit = limit,
            msgidAllowed = msgidAllowed,
            timeoutMs = requestTimeoutMs,
        )
    } catch (error: IrcCommandException) {
        // Only the exact no-fallback msgid rejection degrades; every other command error (including
        // a rejected timestamp selector) still propagates as a failure.
        val unrecoverableMsgidRejection = error.code == HistoryPageLoader.INVALID_MSGREFTYPE &&
            loader.selectorOf(boundary, referenceTypes, msgidAllowed = false) == null
        if (!unrecoverableMsgidRejection) throw error
        null
    }

    private suspend fun HistorySource.referenceTypes(): Set<HistoryReferenceType> =
        (availability() as? HistoryAvailability.Ready)?.referenceTypes ?: emptySet()

    private suspend fun HistorySource.pageLimit(): Int =
        ((availability() as? HistoryAvailability.Ready)?.pageLimit ?: PAGE_LIMIT)
            .coerceAtMost(PAGE_LIMIT)
            .coerceAtLeast(1)

    private suspend fun HistorySource.supportsReference(type: HistoryReferenceType): Boolean =
        (availability() as? HistoryAvailability.Ready)
            ?.referenceTypes
            ?.contains(type) == true

    private suspend fun latestBoundaryFromRoom(bufferId: Long): ChatHistoryReference? =
        db.messageDao().latestBoundary(bufferId)?.let { ChatHistoryReference(it.msgid, it.serverTime) }

    private suspend fun hasStoredChat(bufferId: Long): Boolean = db.messageDao().hasStoredChat(bufferId)

    private fun ChatHistoryResponse.Messages.isTerminalPage(): Boolean =
        endOfHistory || primaryMessageCount == 0

    private fun ChatHistoryResponse.Messages.directionalBoundary(
        subcommand: ChatHistoryRequest.Subcommand,
    ): ChatHistoryReference? = when (subcommand) {
        ChatHistoryRequest.Subcommand.AFTER -> newest
        ChatHistoryRequest.Subcommand.LATEST,
        ChatHistoryRequest.Subcommand.BEFORE,
        ChatHistoryRequest.Subcommand.BETWEEN,
        -> oldest
        ChatHistoryRequest.Subcommand.AROUND -> null
        ChatHistoryRequest.Subcommand.TARGETS -> error("TARGETS is not a message page")
    }

    private fun ChatHistoryResponse.Messages.hasUsableDirectionalBoundary(
        subcommand: ChatHistoryRequest.Subcommand,
        referenceTypes: Set<HistoryReferenceType>,
    ): Boolean = directionalBoundary(subcommand)
        ?.let { loader.selectorOf(it, referenceTypes, HistoryReferenceType.MSGID in referenceTypes) } != null

    private fun ChatHistoryResponse.Messages.highWater(): Long? =
        if (primaryMessageCount == 0) null else maxHighWater(oldest?.serverTime, newest?.serverTime)

    private suspend fun ingest(
        networkId: Long,
        expectedRoomId: RoomId,
        request: ChatHistoryRequest,
        page: ChatHistoryResponse.Messages,
        historyGapId: Long? = null,
    ): Int = ingestResult(networkId, expectedRoomId, request, page, historyGapId).inserted

    private suspend fun ingestResult(
        networkId: Long,
        expectedRoomId: RoomId,
        request: ChatHistoryRequest,
        page: ChatHistoryResponse.Messages,
        historyGapId: Long? = null,
    ): io.github.trevarj.motd.data.sync.PersistedHistoryPage {
        if (db.bufferDao().rawById(expectedRoomId) == null) throw StaleConnectionException()
        return processor.persistHistoryPageResult(
            networkId,
            request,
            page,
            expectedRoomId = expectedRoomId,
            historyGapId = historyGapId,
        )
    }

    /**
     * Fetching remains outside Room, while every page collected for one room is committed in one
     * transaction after traversal settles. This keeps Paging from observing partially reconciled
     * rows or intermediate ordering. Already-validated pages are retained on timeout/cancellation,
     * matching the previous eager-persistence behavior without exposing its incremental redraws.
     */
    private fun beginSyncStatus(bufferId: Long, status: HistorySyncStatus): Long {
        val generation = syncStatusGenerations
            .computeIfAbsent(bufferId) { AtomicLong() }
            .incrementAndGet()
        syncStatuses.update { current ->
            initialSyncStatusIfCurrent(
                current = current,
                bufferId = bufferId,
                generation = generation,
                currentGeneration = syncStatusGenerations[bufferId]?.get(),
                status = status,
            )
        }
        return generation
    }

    private fun publishSyncStatus(bufferId: Long, generation: Long, status: HistorySyncStatus) {
        syncStatuses.update { current ->
            if (syncStatusGenerations[bufferId]?.get() == generation) {
                current + (bufferId to status)
            } else {
                current
            }
        }
    }

    private fun finishSyncStatus(bufferId: Long, generation: Long, status: HistorySyncStatus) {
        syncStatuses.update { current ->
            if (syncStatusGenerations[bufferId]?.get() != generation) {
                current
            } else if (status == HistorySyncStatus.Idle) {
                current - bufferId
            } else {
                current + (bufferId to status)
            }
        }
    }

    private fun finishUnresolvedSyncStatus(
        bufferId: Long,
        generation: Long,
        result: HistoryResyncState,
    ) {
        val current = syncStatuses.value[bufferId]
        if (current != HistorySyncStatus.Checking && current != HistorySyncStatus.Syncing) return
        finishSyncStatus(bufferId, generation, result.toSyncStatus())
    }

    private fun WorkStatus.toState(
        inserted: Int,
        retryRecommended: Boolean = false,
    ): HistoryResyncState = when (this) {
        WorkStatus.Complete ->
            if (inserted > 0) HistoryResyncState.Updated(inserted) else HistoryResyncState.UpToDate
        is WorkStatus.Incomplete -> HistoryResyncState.Incomplete(
            inserted,
            reason,
            awaitsTargetClassification,
            retryRecommended,
        )
        is WorkStatus.Capped -> HistoryResyncState.Capped(inserted, limit, reason)
    }

    private fun WorkStatus.toSyncStatus(): HistorySyncStatus = when (this) {
        WorkStatus.Complete -> HistorySyncStatus.Idle
        is WorkStatus.Incomplete -> HistorySyncStatus.Partial(reason)
        is WorkStatus.Capped -> HistorySyncStatus.Partial(reason)
    }

    private fun HistoryResyncState.toSyncStatus(): HistorySyncStatus = when (this) {
        HistoryResyncState.Idle,
        is HistoryResyncState.Updated,
        HistoryResyncState.UpToDate,
        HistoryResyncState.Unsupported,
        -> HistorySyncStatus.Idle
        HistoryResyncState.WaitingForCapability -> HistorySyncStatus.Checking
        is HistoryResyncState.Running -> HistorySyncStatus.Syncing
        is HistoryResyncState.Incomplete -> HistorySyncStatus.Partial(reason)
        is HistoryResyncState.Capped -> HistorySyncStatus.Partial(reason)
        is HistoryResyncState.Failed -> HistorySyncStatus.Failed(reason)
    }

    private fun WorkStatus.merge(other: WorkStatus): WorkStatus = when {
        this is WorkStatus.Incomplete -> this
        other is WorkStatus.Incomplete -> other
        this is WorkStatus.Capped -> this
        other is WorkStatus.Capped -> other
        else -> WorkStatus.Complete
    }

    private fun maxHighWater(vararg values: Long?): Long? = values.filterNotNull().maxOrNull()

    private class ClientHistorySource(private val client: IrcClient) : HistorySource {
        override suspend fun availability(): HistoryAvailability = client.historyAvailability

        override fun flightIdentity(): Any = client

        override fun canClassifyTargets(): Boolean = client.targetClassificationReady.value

        override fun normalizeTarget(target: String): String = client.isupport.identityRules.normalize(target)

        override fun isChannelTarget(target: String): Boolean =
            client.isupport.identityRules.isChannel(target)

        override suspend fun chathistory(request: ChatHistoryRequest): ChatHistoryResponse =
            client.chathistory(request)
    }

    private class StaleConnectionException : Exception()

    private fun staleConnection(): HistoryResyncState.Failed =
        HistoryResyncState.Failed("Connection changed; try again")

    private fun historyUnavailable(): HistoryResyncState.Failed =
        HistoryResyncState.Failed("History support is still negotiating or the connection is offline")

    private companion object {
        const val PAGE_LIMIT = 100
        const val RECENT_PAGE_SIZE = 50
        const val REQUEST_TIMEOUT_MS = 35_000L
        const val PENDING_MESSAGE_TIMEOUT_MS = 65_000L
        const val TARGETS_FUZZ_MS = 10_000L
        const val TARGET_CLASSIFICATION_WAIT_TIMEOUT_MS = 10_000L
        const val TARGETS_REQUEST_LIMIT = 100
    }
}
