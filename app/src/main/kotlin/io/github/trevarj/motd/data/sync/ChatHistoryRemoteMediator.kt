package io.github.trevarj.motd.data.sync

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import io.github.trevarj.motd.bouncer.isBouncerConsole
import io.github.trevarj.motd.data.db.BufferDao
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.HistoryCursorDao
import io.github.trevarj.motd.data.db.HistoryCursorEntity
import io.github.trevarj.motd.data.db.HistoryGapDao
import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.MessageDao
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.ircTarget
import io.github.trevarj.motd.data.history.HistoryLadderStalled
import io.github.trevarj.motd.data.history.PageProgress
import io.github.trevarj.motd.data.history.Pageability
import io.github.trevarj.motd.data.history.olderPageability
import io.github.trevarj.motd.data.history.openGapFloor
import io.github.trevarj.motd.data.repo.ChatHistoryMediatorFactory
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.data.visibility.oldestPresentedMessageQuery
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.client.ChatHistoryReference
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.IrcDisconnectedException
import io.github.trevarj.motd.irc.event.historyEventMetadataOrNull
import io.github.trevarj.motd.irc.ext.ChatHistorySelectors
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.service.ConnectionManager
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CHATHISTORY-backed older paging. The list is DESC (newest first), so APPEND fetches older messages
 * via BEFORE. This is the bottom-of-timeline ladder and nothing else.
 *
 * REFRESH → if the buffer is empty and the network advertises chathistory, pull LATEST once.
 * APPEND  → older boundary; stop when historyComplete/no cap; when the buffer is empty (no oldest
 *           boundary yet) pull LATEST once to backfill on first open; otherwise BEFORE the oldest
 *           protocol page boundary. Completed empty pages and explicit end markers persist the
 *           confirmed start-of-history state through EventProcessor. Interior history gaps belong to
 *           [HistoryGapFillCoordinator], and the request is clamped strictly below every open gap so
 *           the two can never name the same interval; see [appendGapFloor].
 * PREPEND → ends immediately. The timeline is unbounded and painted newest-first, so there is never
 *           an interval above the presented rows for it to fetch: live events supply newer messages,
 *           and a gap ABOVE a row is an interior seam the coordinator owns.
 *
 * Paging treats `endOfPaginationReached` as PERMANENT for a direction — it outlives the PagingSource
 * generation that observed it — so APPEND reports it only when older paging is genuinely finished
 * (history complete), never merely because the loader had to stop at one ambiguous equal-timestamp
 * page edge, and never for a page that simply made no progress: that stop is retryable and is
 * reported as [io.github.trevarj.motd.data.history.HistoryLadderStalled] so the timeline can offer
 * the reader the fetch. APPEND also walks across a bounded run of pages that persist only rows hidden
 * by the Pager's visibility policy; raw inserts do not count as progress until the presented oldest
 * boundary moves. See [appendResult].
 *
 * Every entry uses SKIP_INITIAL_REFRESH so the cached DB paints without network I/O; Paging3 then
 * drives REFRESH (empty-store LATEST seed, otherwise no-op) and scroll-triggered APPEND for older
 * history. The loader owns availability, page-limit derivation, and all fetch concurrency.
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
    // Owns the fetch/persist/concurrency primitives. Defaulted so the existing positional test
    // construction stays valid; production always injects the shared singleton via the factory.
    private val loader: HistoryPageLoader = HistoryPageLoader(processor),
    // Opt-in decision-point journal for the paging control flow. Fields carry classification, ids,
    // counts, timestamps, and msgid PRESENCE only — never message content or msgid values. This is
    // the observability that identified the unrecoverable-gap append stall on timestamp-only wires.
    private val diagnostics: DiagnosticLogger = DiagnosticLogger.Noop,
    // Exact coordinate space of the PagingSource this mediator feeds. Production always supplies both;
    // defaults retain concise direct construction in tests whose rows are all visible.
    private val visibility: MessageVisibilitySpec = MessageVisibilitySpec(),
    private val identityRules: IrcIdentityRules = IrcIdentityRules(),
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
        // Local cache is authoritative for normal entry and deep-link initial paint; a deep jump's
        // AROUND page is pre-fetched by ChatJumpResolver. Paging drives REFRESH/APPEND explicitly
        // afterward, and the loader owns availability + concurrency for each fetch it performs.
        InitializeAction.SKIP_INITIAL_REFRESH

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, MessageEntity>,
    ): MediatorResult {
        return try {
            val buffer =
                bufferDao.observeById(bufferId)
                    ?: return endLoad(loadType, "missing_buffer")
            if (buffer.type == BufferType.SERVER && !buffer.isBouncerConsole) {
                // Console buffers have no CHATHISTORY target. With the mediator attached
                // unconditionally, mirror the UI's Hidden rule here or every console open would
                // emit junk `CHATHISTORY BEFORE <servername>` traffic. soju's BouncerServ room is the
                // exception: it is a real target the bouncer answers.
                return endLoad(loadType, "server_buffer")
            }
            val networkId = buffer.networkId
            // The loader re-derives availability, page limit, and reference types from the source per
            // fetch and owns all wire serialization/coalescing, so no upfront availability gate or
            // per-buffer lock is needed here.
            when (loadType) {
                LoadType.REFRESH -> {
                    refresh(networkId, buffer.id, buffer.ircTarget)
                }

                // Nothing above the newest row is ever fetched here; see the class doc.
                LoadType.PREPEND -> {
                    MediatorResult.Success(endOfPaginationReached = true)
                }

                LoadType.APPEND -> {
                    append(networkId, buffer.id, buffer.ircTarget)
                }
            }.also { result ->
                diagnostics.record("chat_history", "mediator_load_result") {
                    mapOf(
                        "load_type" to loadType.name,
                        "room_id" to bufferId,
                        "outcome" to
                            when (result) {
                                is MediatorResult.Success -> {
                                    if (result.endOfPaginationReached) "end" else "more"
                                }

                                is MediatorResult.Error -> {
                                    "error"
                                }
                            },
                        "error_class" to
                            (result as? MediatorResult.Error)
                                ?.throwable
                                ?.let { it::class.simpleName },
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
        return loader
            .loadPage(
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
    ): MediatorResult {
        val presentedBefore = oldestPresentedAnchor(roomId)
        var pagesLoaded = 0
        while (true) {
            val gaps = historyGapDao?.forRoom(roomId).orEmpty()
            val cursor = historyCursorDao?.byRoom(roomId)
            val pageability =
                olderPageability(
                    focusedGap = null,
                    historyComplete = bufferDao.observeById(roomId)?.historyComplete == true,
                    cursorOldest = cursor?.let { ChatHistoryReference(it.oldestMsgid, it.oldestServerTime) },
                    oldestLocalRow =
                        messageDao
                            .oldestBoundary(roomId)
                            ?.let { ChatHistoryReference(it.msgid, it.serverTime) },
                    progress = null,
                    gapFloor = appendGapFloor(gaps),
                )
            val page =
                when (pageability) {
                    is Pageability.End -> {
                        return endLoad(LoadType.APPEND, pageability.reason)
                    }

                    Pageability.SeedLatest -> {
                        recordAppendBoundary(roomId, gaps, cursor, boundary = null)
                        // Empty local store hit the end boundary on first open. With
                        // SKIP_INITIAL_REFRESH the REFRESH backfill never fires, so seed via LATEST.
                        loader.loadPage(
                            networkId,
                            roomId,
                            target,
                            HistoryPageLoader.Direction.LATEST,
                            history,
                            pageSize,
                        ) to null
                    }

                    is Pageability.Page -> {
                        recordAppendBoundary(roomId, gaps, cursor, pageability.boundary)
                        loader.loadPage(
                            networkId,
                            roomId,
                            target,
                            HistoryPageLoader.Direction.OLDER,
                            history,
                            pageSize,
                            gapId = pageability.focusedGapId,
                            boundary = pageability.boundary,
                        ) to pageability.boundary
                    }
                }
            pagesLoaded++
            val result = page.first.appendResult(roomId, page.second)
            if (result !is MediatorResult.Success || result.endOfPaginationReached) return result

            // Paging demand is expressed in the filtered list, not in raw Room rows. A full page of
            // hidden JOIN/QUIT events can advance every protocol cursor while adding no item for
            // Paging to request past. Keep walking until the exact presented oldest boundary moves.
            if (oldestPresentedAnchor(roomId).advancedOlderThan(presentedBefore)) return result
            if (pagesLoaded >= PRESENTATION_PAGE_BUDGET) return MediatorResult.Error(HistoryLadderStalled())
        }
    }

    private suspend fun oldestPresentedAnchor(roomId: Long): TimelineAnchor? =
        messageDao
            .rawMessage(oldestPresentedMessageQuery(roomId, visibility, identityRules))
            ?.let { TimelineAnchor(it.serverTime, it.id, it.timelineOrder) }

    private fun TimelineAnchor?.advancedOlderThan(previous: TimelineAnchor?): Boolean = this != null && (previous == null || this < previous)

    /**
     * The floor that keeps this APPEND out of the coordinator's territory, or null when it has none.
     *
     * APPEND is deliberately NOT gap-directed. The timeline is unbounded, so the local PagingSource
     * only runs dry at the true oldest retained row — never at an interior seam — and the APPEND
     * Paging asks for is therefore always a request for backlog BELOW the bottom of the timeline.
     * Aiming it at a gap made it answer a question nobody asked, and one consequence was a real
     * defect: an unrecoverable gap anywhere in the room reported the whole direction permanently
     * finished, so scrolling to the bottom of the list could never fetch another page. Interior
     * seams are owned by [HistoryGapFillCoordinator], which is driven by taps and by the autopilot
     * rather than by Paging running out of rows.
     *
     * The split between the two demand sources has to be STRUCTURAL, not incidental, because on an
     * unbounded timeline the two ladders otherwise coincide at open: the coordinator pages BEFORE the
     * gap's newer edge, and the mediator's own ladder can arrive at exactly that reference (a
     * reconnect LATEST page unions its oldest row into the stored cursor, and that row IS the gap's
     * newer edge). Two fetches, one interval, one of them guaranteed to insert nothing.
     *
     * The rule is a partition of the timeline rather than an ordering: **the coordinator owns every
     * interval an open gap covers, and the mediator owns everything strictly below all of them.**
     * [openGapFloor] supplies the boundary that expresses it.
     */
    private fun appendGapFloor(gaps: List<HistoryGapEntity>): ChatHistoryReference? = openGapFloor(gaps)

    /** The APPEND decision point: the gap state it was taken against and the boundary it carries. */
    private fun recordAppendBoundary(
        roomId: Long,
        gaps: List<HistoryGapEntity>,
        cursor: HistoryCursorEntity?,
        boundary: ChatHistoryReference?,
    ) {
        diagnostics.record("chat_history", "append_boundary") {
            mapOf(
                "room_id" to roomId,
                "gap_count" to gaps.size,
                "has_cursor" to (cursor != null),
                "boundary_has_msgid" to (boundary?.msgid != null),
                "boundary_server_time" to boundary?.serverTime,
            )
        }
    }

    /**
     * Decide APPEND terminality from PROGRESS rather than from the loader's per-page cursor guard.
     *
     * [HistoryPageLoader.PageResult.Loaded.endOfDirection] conflates two different facts: "this
     * direction is exhausted" and "I cannot safely page again from THIS cursor" (an ambiguous
     * equal-timestamp boundary at a saturated page edge). Paging treats `endOfPaginationReached` as
     * permanently terminal for the direction, so reporting the second fact kills older backfill after
     * a single page on a timestamp-only wire (soju advertises `MSGREFTYPES=timestamp`), where every
     * saturated page trips it. Terminate only when older paging is genuinely finished — history is
     * complete. A page that made no progress at all stops this attempt too, but as a retryable
     * failure rather than a terminal one; see [toMediatorResult].
     *
     * An unrecoverable seam elsewhere in the room deliberately cannot end this direction: this
     * ladder is never pointed at a gap (see [appendGapFloor]).
     * Otherwise the boundary moved (or rows landed), so the next APPEND issues a different request
     * and the ambiguity that stopped this page no longer applies.
     */
    private suspend fun HistoryPageLoader.PageResult.appendResult(
        roomId: Long,
        previous: ChatHistoryReference?,
    ): MediatorResult {
        val page = this as? HistoryPageLoader.PageResult.Loaded ?: return toMediatorResult()
        // Re-read AFTER the persist, and deliberately so: this page may have shrunk or closed the
        // focused gap, receded the cursor, or proven history complete, and every one of those facts
        // is an input to terminality. The decision itself is pure, so the reads stay here in the open.
        val gaps = historyGapDao?.forRoom(roomId).orEmpty()
        val gapFloor = appendGapFloor(gaps)
        val historyComplete = bufferDao.observeById(roomId)?.historyComplete == true
        val cursorOldest =
            historyCursorDao
                ?.byRoom(roomId)
                ?.let { ChatHistoryReference(it.oldestMsgid, it.oldestServerTime) }
        val oldestLocalRow =
            messageDao
                .oldestBoundary(roomId)
                ?.let { ChatHistoryReference(it.msgid, it.serverTime) }
        // Two questions off the one post-page snapshot: where the NEXT request would go, and whether
        // this page earned one. Only the second is progress-aware; the first supplies the boundary
        // the anti-livelock diagnostic reports.
        val ladder =
            olderPageability(null, historyComplete, cursorOldest, oldestLocalRow, null, gapFloor)
        val verdict =
            olderPageability(
                null,
                historyComplete,
                cursorOldest,
                oldestLocalRow,
                PageProgress(previous = previous, insertedCount = page.insertedCount),
                gapFloor,
            )
        return verdict.toMediatorResult(LoadType.APPEND, ladder, page)
    }

    /**
     * Map a post-page [Pageability] onto this direction's Paging result.
     *
     * The anti-livelock stop is the only end the boundary [ladder] did not reach on its own, and it
     * is not the same kind of statement as the others: the direction is NOT finished — this attempt
     * simply achieved nothing, and repeating it unprompted would hammer the wire. Reporting it as
     * end-of-pagination retired the direction for the whole Pager, where no amount of scrolling
     * could revive it and only leaving and re-entering the room did. It is a retryable failure
     * instead, which the timeline renders as an explicit "load older" affordance. A silent stall is
     * hard to diagnose in the field, so record the page and the boundary that failed to move.
     */
    private fun Pageability.toMediatorResult(
        loadType: LoadType,
        ladder: Pageability,
        page: HistoryPageLoader.PageResult.Loaded,
    ): MediatorResult {
        if (this !is Pageability.End) return MediatorResult.Success(endOfPaginationReached = false)
        if (ladder is Pageability.End) return endLoad(loadType, reason)
        // No boundary to ask from again — an empty store whose seed found nothing — so there is
        // nothing to offer the reader either. End quietly; the room keeps no durable claim, and the
        // next entry seeds again.
        val next =
            ladder as? Pageability.Page ?: return endLoad(
                loadType,
                reason,
                mapOf("primary_count" to page.primaryCount, "end_of_direction" to page.endOfDirection),
            )
        diagnostics.record("chat_history", "mediator_load_stalled") {
            mapOf(
                "load_type" to loadType.name,
                "room_id" to bufferId,
                "end_reason" to reason,
                "primary_count" to page.primaryCount,
                "end_of_direction" to page.endOfDirection,
                "focused_gap_id" to next.focusedGapId,
                "boundary_has_msgid" to (next.boundary.msgid != null),
                "boundary_server_time" to next.boundary.serverTime,
            )
        }
        return MediatorResult.Error(HistoryLadderStalled())
    }

    /** Map a loader outcome onto this direction's Paging result. */
    private fun HistoryPageLoader.PageResult.toMediatorResult(): MediatorResult =
        when (this) {
            is HistoryPageLoader.PageResult.Loaded -> {
                MediatorResult.Success(endOfPaginationReached = endOfDirection)
            }

            HistoryPageLoader.PageResult.Unsupported -> {
                MediatorResult.Success(endOfPaginationReached = true)
            }

            is HistoryPageLoader.PageResult.Unavailable -> {
                MediatorResult.Error(cause)
            }

            is HistoryPageLoader.PageResult.Failed -> {
                MediatorResult.Error(cause)
            }
        }

    internal companion object {
        /** Maximum hidden history quantum before control returns as an explicit reader retry. */
        internal const val PRESENTATION_PAGE_BUDGET = 8
    }
}

/** Enforce the client-requested primary bound even when a server over-delivers a batch. */
internal fun ChatHistoryResponse.Messages.boundedToRequest(
    request: ChatHistoryRequest,
    preferredAroundMsgid: String? = null,
): ChatHistoryResponse.Messages {
    if (primaryMessageCount <= request.limit) return this
    val primaryIndices =
        events.indices.filter { index ->
            events[index].historyEventMetadataOrNull()?.isContext != true
        }
    if (primaryIndices.size <= request.limit) return this
    val selectedIndices =
        when (request.subcommand) {
            ChatHistoryRequest.Subcommand.AFTER,
            ChatHistoryRequest.Subcommand.BETWEEN,
            -> {
                primaryIndices.take(request.limit)
            }

            ChatHistoryRequest.Subcommand.BEFORE,
            ChatHistoryRequest.Subcommand.LATEST,
            -> {
                primaryIndices.takeLast(request.limit)
            }

            ChatHistoryRequest.Subcommand.AROUND -> {
                val preferredPosition =
                    preferredAroundMsgid?.let { preferred ->
                        primaryIndices
                            .indexOfFirst { index ->
                                events[index].historyEventMetadataOrNull()?.msgid == preferred
                            }.takeIf { it >= 0 }
                    }
                val targetPosition =
                    preferredPosition ?: primaryIndices.indexOfFirst { index ->
                        val metadata = events[index].historyEventMetadataOrNull()
                        request.bound1 == metadata?.msgid?.let(ChatHistorySelectors::msgid) ||
                            metadata?.serverTime?.let(ChatHistorySelectors::timestamp) == request.bound1
                    }
                check(targetPosition >= 0) {
                    "CHATHISTORY AROUND over-delivered without the requested retained boundary"
                }
                val start =
                    (targetPosition - request.limit / 2)
                        .coerceIn(0, primaryIndices.size - request.limit)
                primaryIndices.subList(start, start + request.limit)
            }

            ChatHistoryRequest.Subcommand.TARGETS -> {
                return this
            }
        }
    val selected = selectedIndices.toSet()
    val retained =
        events.filterIndexed { index, event ->
            index in selected || event.historyEventMetadataOrNull()?.isContext == true
        }
    val references =
        selected.sorted().mapNotNull { index ->
            events[index].historyEventMetadataOrNull()?.let { metadata ->
                ChatHistoryReference(metadata.msgid, metadata.serverTime)
            }
        }

    fun ChatHistoryReference?.usable(): Boolean = this != null && (!msgid.isNullOrEmpty() || serverTime != null)
    val oldest = references.firstOrNull()
    val newest = references.lastOrNull()
    val hasRequiredContinuation =
        when (request.subcommand) {
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
 * Real mediator factory wired into [io.github.trevarj.motd.data.repo.MessageRepositoryImpl].
 */
@OptIn(ExperimentalPagingApi::class)
@Singleton
class ChatHistoryMediatorFactoryImpl
    @Inject
    constructor(
        private val connectionManager: ConnectionManager,
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
            visibility: MessageVisibilitySpec,
            identityRules: IrcIdentityRules,
        ): RemoteMediator<Int, MessageEntity> =
            ChatHistoryRemoteMediator(
                bufferId,
                bufferDao,
                messageDao,
                processor,
                historyFor(bufferId),
                historyCursorDao = historyCursorDao,
                historyGapDao = historyGapDao,
                loader = loader,
                diagnostics = diagnostics,
                visibility = visibility,
                identityRules = identityRules,
            )

        // Resolve the live client lazily per call: the buffer can open before its network reaches
        // Ready, and clientFor(...) is only stable once connected. Missing/negotiating clients remain
        // retryable rather than masquerading as unsupported or a completed empty history response.
        private fun historyFor(bufferId: Long): ChatHistoryRemoteMediator.HistorySource =
            object : ChatHistoryRemoteMediator.HistorySource {
                private suspend fun client() = bufferDao.observeById(bufferId)?.networkId?.let { connectionManager.clientFor(it) }

                override suspend fun availability(): HistoryAvailability = client()?.historyAvailability ?: HistoryAvailability.NegotiatingOrOffline

                override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse = client()?.chathistory(req) ?: throw IrcDisconnectedException("CHATHISTORY", null)
            }
    }
