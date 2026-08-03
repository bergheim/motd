package io.github.trevarj.motd.data.repo

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import io.github.trevarj.motd.data.db.BufferDao
import io.github.trevarj.motd.data.db.MessageDao
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.HistoryGapDao
import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.NetworkIdentityDao
import io.github.trevarj.motd.data.db.ReactionDao
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.identityRules
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.data.visibility.MessageWindowBounds
import io.github.trevarj.motd.data.visibility.countTimelineNewerQuery
import io.github.trevarj.motd.data.visibility.messagePagingQuery
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

// Paging 3 stream backed by the local pagingSource, with a RemoteMediator supplied per buffer
// by the injected factory (WP1 no-op / WP5 CHATHISTORY-backed).
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessageRepositoryImpl @Inject constructor(
    private val bufferDao: BufferDao,
    private val networkIdentityDao: NetworkIdentityDao,
    private val messageDao: MessageDao,
    private val reactionDao: ReactionDao,
    private val mediatorFactory: ChatHistoryMediatorFactory,
    private val historyGapDao: HistoryGapDao,
) : MessageRepository {
    @OptIn(ExperimentalPagingApi::class)
    override fun messages(
        bufferId: Long,
        visibility: MessageVisibilitySpec,
    ): Flow<PagingData<MessageEntity>> = messages(bufferId, visibility, HistoryWindowFocus.Recent)

    @OptIn(ExperimentalPagingApi::class)
    override fun messages(
        bufferId: Long,
        visibility: MessageVisibilitySpec,
        focus: HistoryWindowFocus,
    ): Flow<PagingData<MessageEntity>> = messages(bufferId, visibility, focus, initialKey = null)

    @OptIn(ExperimentalPagingApi::class)
    override fun messages(
        bufferId: Long,
        visibility: MessageVisibilitySpec,
        focus: HistoryWindowFocus,
        initialKey: Int?,
    ): Flow<PagingData<MessageEntity>> =
        pagingContextFlow(bufferId, focus).flatMapLatest { context ->
                Pager(
                    config = MESSAGE_PAGING_CONFIG,
                    // Seed the first source load from the caller-computed key so a deep
                    // open-at-first-unread entry materializes together with the viewport below it in
                    // the initial refresh (see entryAnchorPagingKey — callers gate depth and shift
                    // the key; Room clamps a key at or past the window end to the trailing load, so
                    // a transiently smaller window cannot key past its own bounds).
                    initialKey = initialKey?.coerceAtLeast(0),
                    // Scroll-driven paging: the mediator is always attached so Paging3 APPEND drives
                    // older history under Recent focus and AFTER/BEFORE gap fill under Around. The
                    // canonical id comes from pagingContextFlow, so a durable redirect still paints
                    // and pages the winner room.
                    remoteMediator = mediatorFactory.create(context.roomId, focus),
                    pagingSourceFactory = {
                        messageDao.pagingSource(
                            messagePagingQuery(
                                context.roomId,
                                visibility,
                                context.identityRules,
                                context.bounds.lowerBoundary,
                                context.bounds.upperBoundary,
                            ),
                        )
                    },
                ).flow
            }

    // Kept for the frozen contract; scopes to a small, fixed msgid set (safe under 999 vars).
    override fun reactions(bufferId: Long, msgids: List<String>): Flow<List<ReactionEntity>> =
        canonicalRoomIdFlow(bufferId).flatMapLatest { reactionDao.observeFor(it, msgids) }

    override suspend fun byId(id: Long): MessageEntity? = messageDao.byCanonicalId(id)

    override suspend fun canonicalRoomId(bufferId: Long): Long = resolveRoomId(bufferId)

    override suspend fun byMsgid(bufferId: Long, msgid: String): MessageEntity? =
        messageDao.byMsgid(resolveRoomId(bufferId), msgid)

    override fun observeByMsgid(bufferId: Long, msgid: String): Flow<MessageEntity?> =
        canonicalRoomIdFlow(bufferId).flatMapLatest { messageDao.observeByMsgid(it, msgid) }

    // Wait for the echo to promote a pending own row's msgid in place. observeMsgid emits the
    // current value immediately (null while pending) and again when the row updates, so first
    // non-null wins; withTimeoutOrNull bounds the wait so a lost echo can't hang the react forever.
    override suspend fun awaitMsgid(id: Long, timeoutMs: Long): String? =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            messageDao.observeCanonicalMsgid(id).firstOrNull { it != null }
        }

    override suspend fun countNewerThan(
        bufferId: Long,
        serverTime: Long,
        id: Long,
        visibility: MessageVisibilitySpec,
    ): Int = countNewerThan(
        bufferId,
        serverTime,
        id,
        visibility,
        HistoryWindowFocus.Recent,
    )

    override suspend fun countNewerThan(
        bufferId: Long,
        serverTime: Long,
        id: Long,
        visibility: MessageVisibilitySpec,
        focus: HistoryWindowFocus,
    ): Int {
        val context = resolvePagingContext(bufferId, focus)
        val timelineOrder = messageDao.byCanonicalId(id)?.timelineOrder ?: id
        return messageDao.rawCount(
            countTimelineNewerQuery(
                context.roomId,
                serverTime,
                id,
                timelineOrder,
                visibility,
                context.identityRules,
                context.bounds.lowerBoundary,
                context.bounds.upperBoundary,
            ),
        )
    }

    override suspend fun deleteMessage(id: Long) = messageDao.deleteWithAnchorFallback(id)

    override suspend fun historyWindowBounds(
        bufferId: Long,
        focus: HistoryWindowFocus,
    ): MessageWindowBounds = resolvePagingContext(bufferId, focus).bounds

    override fun observeHistoryWindowBounds(
        bufferId: Long,
        focus: HistoryWindowFocus,
    ): Flow<MessageWindowBounds> = pagingContextFlow(bufferId, focus).map { it.bounds }

    private fun canonicalRoomIdFlow(bufferId: Long): Flow<Long> = bufferDao.observe(bufferId)
        .map { it?.id ?: bufferId }
        .distinctUntilChanged()

    private fun pagingContextFlow(bufferId: Long, focus: HistoryWindowFocus): Flow<PagingContext> =
        bufferDao.observe(bufferId).flatMapLatest { room ->
            if (room == null) {
                flowOf(PagingContext(bufferId, IrcIdentityRules(), MessageWindowBounds()))
            } else {
                networkIdentityDao.observe(room.networkId)
                    .combine(historyGapDao.observeForRoom(room.id)) { identity, gaps -> identity to gaps }
                    .map { (identity, gaps) ->
                        PagingContext(
                            room.id,
                            identity?.identityRules ?: IrcIdentityRules(),
                            historyWindowBounds(focus, resolveHistoryGaps(room.id, gaps)),
                        )
                    }
            }
        }.distinctUntilChanged()

    private suspend fun resolvePagingContext(
        bufferId: Long,
        focus: HistoryWindowFocus,
    ): PagingContext {
        val room = bufferDao.observeById(bufferId)
            ?: return PagingContext(bufferId, IrcIdentityRules(), MessageWindowBounds())
        val identityRules = networkIdentityDao.byNetwork(room.networkId)?.identityRules
            ?: IrcIdentityRules()
        return PagingContext(
            room.id,
            identityRules,
            historyWindowBounds(
                focus,
                resolveHistoryGaps(room.id, historyGapDao.forRoom(room.id)),
            ),
        )
    }

    private suspend fun resolveHistoryGaps(
        roomId: Long,
        gaps: List<HistoryGapEntity>,
    ): List<ResolvedHistoryGap> = gaps.map { gap ->
        ResolvedHistoryGap(
            gap = gap,
            // The fallback is chosen by how the anchor is USED as a window edge, not by which side
            // of the gap it names. `older` only ever becomes an upperBoundary and `newer` only ever
            // becomes a lowerBoundary (see historyWindowBounds), and both bounds are inclusive at
            // the anchor. An unidentifiable boundary must therefore be maximally PERMISSIVE within
            // its serverTime — see resolveGapBoundary.
            older = resolveGapBoundary(
                roomId,
                gap.olderMsgid,
                gap.olderServerTime,
                gap.olderEventId,
                gap.olderTimelineOrder,
                fallback = Long.MAX_VALUE,
            ),
            newer = resolveGapBoundary(
                roomId,
                gap.newerMsgid,
                gap.newerServerTime,
                gap.newerEventId,
                gap.newerTimelineOrder,
                fallback = Long.MIN_VALUE,
            ),
        )
    }

    /**
     * Resolve one stored gap edge to a comparable timeline position, preferring the exact local row
     * (msgid, then retained eventId), then the stored tuple, and finally a synthetic anchor.
     *
     * [fallback] is reached only when the client cannot identify the boundary event AT ALL: no
     * resolvable msgid, no eventId. [TimelineAnchor] compares serverTime, then timelineOrder, then
     * eventId, so a `Long.MAX_VALUE` fallback would not merely be imprecise — it would dominate
     * every real row sharing the boundary's serverTime and, used as the Recent lowerBoundary,
     * exclude all of them. With a gap edge at or above the newest local row that empties the
     * presented window entirely: the timeline composes nothing and no paging key resolves, even
     * though every row is durable in Room. Callers therefore pass the fallback that is maximally
     * permissive for the bound this edge feeds. An unknown boundary cannot say where the gap is, so
     * bounding the window at a guessed position is not more truthful than not bounding it — only
     * more destructive.
     */
    private suspend fun resolveGapBoundary(
        roomId: Long,
        msgid: String?,
        serverTime: Long,
        eventId: Long?,
        timelineOrder: Long?,
        fallback: Long,
    ): TimelineAnchor = msgid?.let { messageDao.byMsgid(roomId, it) }
        ?.let { TimelineAnchor(it.serverTime, it.id, it.timelineOrder) }
        ?: eventId?.let { id ->
            messageDao.byCanonicalId(id)?.takeIf { it.bufferId == roomId }
                ?.let { TimelineAnchor(it.serverTime, it.id, it.timelineOrder) }
        }
        ?: eventId?.let { TimelineAnchor(serverTime, it, timelineOrder ?: it) }
        ?: TimelineAnchor(serverTime, fallback, fallback)

    private suspend fun resolveRoomId(bufferId: Long): Long =
        bufferDao.canonicalId(bufferId) ?: bufferId

    private data class PagingContext(
        val roomId: Long,
        val identityRules: IrcIdentityRules,
        val bounds: MessageWindowBounds,
    )
}

internal data class ResolvedHistoryGap(
    val gap: HistoryGapEntity,
    val older: TimelineAnchor,
    val newer: TimelineAnchor,
)

internal typealias HistoryWindowBounds = MessageWindowBounds

internal fun historyWindowBounds(
    focus: HistoryWindowFocus,
    gaps: List<ResolvedHistoryGap>,
): MessageWindowBounds = when (focus) {
    HistoryWindowFocus.Recent -> MessageWindowBounds(
        lowerBoundary = gaps.maxByOrNull { it.newer }?.newer,
    )
    is HistoryWindowFocus.Around -> MessageWindowBounds(
        lowerBoundary = gaps
            .filter { it.newer <= focus.anchor }
            .maxByOrNull { it.newer }
            ?.newer,
        upperBoundary = gaps
            .filter { it.older >= focus.anchor }
            .minByOrNull { it.older }
            ?.older,
    )
}

internal val MESSAGE_PAGING_CONFIG = PagingConfig(
    pageSize = 50,
    prefetchDistance = 25,
    enablePlaceholders = true,
    maxSize = 500,
    jumpThreshold = 250,
)

/**
 * Pager initial key for an open-at-first-unread entry anchored at timeline offset [index].
 *
 * Room's paging source treats a refresh key as the load's START offset (end-clamping it only when
 * the key sits within `initialLoadSize` of the window end), so keying the Pager at the anchor
 * itself would materialize the anchor plus OLDER rows only — every newer row below it in the
 * reversed viewport would stay a placeholder until later prepend hints, which a regenerating
 * bounded window can starve. Shift the key back by `initialLoadSize - pageSize` so the first load
 * covers the anchor, a full viewport of newer rows below it, and one page of older rows above.
 * Anchors inside the default newest load return null: the plain newest-first refresh already
 * materializes them, keeping first-open backfill behavior untouched.
 */
internal fun entryAnchorPagingKey(index: Int): Int? {
    val config = MESSAGE_PAGING_CONFIG
    if (index < config.initialLoadSize) return null
    return (index - (config.initialLoadSize - config.pageSize)).coerceAtLeast(0)
}
