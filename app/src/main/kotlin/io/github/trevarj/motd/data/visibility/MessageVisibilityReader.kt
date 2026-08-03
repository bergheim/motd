package io.github.trevarj.motd.data.visibility

import androidx.sqlite.db.SimpleSQLiteQuery
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.RoomEntity
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.identityRules
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class VisibleMessageAnchor(
    val id: Long,
    val msgid: String?,
    val serverTime: Long,
    val timelineOrder: Long = id,
)

/** Policy-backed targeted reads sharing the Room paging predicate. */
@Singleton
class MessageVisibilityReader @Inject constructor(
    private val db: MotdDatabase,
) {
    suspend fun effectiveLocalReadAnchor(buffer: RoomEntity): TimelineAnchor? {
        val local = buffer.localReadAnchorTime?.let { serverTime ->
            val eventId = buffer.localReadAnchorEventId ?: 0L
            val canonicalId = resolveCanonicalEventId(eventId)
            val order = db.canonicalTimelineDao().eventById(canonicalId)?.timelineOrder ?: eventId
            TimelineAnchor(serverTime, eventId, order)
        }
        val mute = buffer.localUnreadFloorTime?.let { serverTime ->
            TimelineAnchor(serverTime, Long.MAX_VALUE, Long.MAX_VALUE)
        }
        return listOfNotNull(local, mute).maxOrNull()
    }

    fun observeLatestRawAnchor(bufferId: Long): Flow<TimelineAnchor?> =
        db.invalidationTracker.createFlow(
            "messages",
            "buffers",
            emitInitialState = true,
        ).map { latestRawAnchor(bufferId) }.distinctUntilChanged()

    /** Emits only when event-id coalescence may require a live viewport re-anchor. */
    fun observeEventRedirects(): Flow<Unit> =
        db.invalidationTracker.createFlow(
            "event_redirects",
            emitInitialState = false,
        ).map { _: Set<String> -> }

    suspend fun latestRawAnchor(bufferId: Long): TimelineAnchor? =
        db.messageDao().newestMessage(canonicalRoomId(bufferId))?.let {
            TimelineAnchor(it.serverTime, it.id, it.timelineOrder)
        }

    suspend fun countTimelineNewer(
        bufferId: Long,
        serverTime: Long,
        id: Long,
        spec: MessageVisibilitySpec,
    ): Int {
        val canonicalId = resolveCanonicalEventId(id)
        val timelineOrder = db.canonicalTimelineDao().eventById(canonicalId)?.timelineOrder ?: id
        return countTimelineNewer(
            bufferId,
            TimelineAnchor(serverTime, id, timelineOrder),
            spec,
        )
    }

    suspend fun countTimelineNewer(
        bufferId: Long,
        anchor: TimelineAnchor,
        spec: MessageVisibilitySpec,
    ): Int {
        val context = visibilityContext(bufferId)
        return db.messageDao().rawCount(
            countTimelineNewerQuery(
                context.roomId,
                anchor.serverTime,
                anchor.eventId,
                anchor.timelineOrder,
                spec,
                context.identityRules,
            ),
        )
    }

    suspend fun countVisibleUnreadInTimelinePrefix(
        bufferId: Long,
        beforeIndex: Int,
        after: TimelineAnchor,
        maxCount: Int,
        spec: MessageVisibilitySpec,
        bounds: MessageWindowBounds = MessageWindowBounds(),
    ): Int {
        if (beforeIndex <= 0 || maxCount <= 0) return 0
        val context = visibilityContext(bufferId)
        return db.messageDao().rawCount(
            countVisibleUnreadInTimelinePrefixQuery(
                context.roomId,
                beforeIndex,
                after,
                maxCount,
                spec,
                context.identityRules,
                bounds,
            ),
        )
    }

    suspend fun firstVisibleUnreadAnchor(
        bufferId: Long,
        after: TimelineAnchor,
        spec: MessageVisibilitySpec,
        bounds: MessageWindowBounds = MessageWindowBounds(),
    ): TimelineAnchor? {
        val context = visibilityContext(bufferId)
        return db.messageDao().rawMessage(
            firstVisibleUnreadQuery(context.roomId, after, spec, context.identityRules, bounds),
        )
            ?.let { TimelineAnchor(it.serverTime, it.id, it.timelineOrder) }
    }

    /**
     * Lazy-list index of the nearest unread nick mention strictly below the viewport (the oldest
     * unread mention within the newest [beforeIndex] visible-timeline rows), or null if none.
     * Mirrors [countVisibleUnreadInTimelinePrefix] so the FAB can resolve a jump target with one
     * cheap DB read instead of scanning paged items during a fling.
     */
    suspend fun nearestUnreadMentionBelowIndex(
        bufferId: Long,
        beforeIndex: Int,
        after: TimelineAnchor,
        spec: MessageVisibilitySpec,
    ): Int? {
        if (beforeIndex <= 0) return null
        val target = nearestUnreadMentionBelow(
            bufferId,
            beforeIndex,
            after,
            spec,
        ) ?: return null
        return countTimelineNewer(bufferId, target.serverTime, target.id, spec)
    }

    suspend fun nearestUnreadMentionBelow(
        bufferId: Long,
        beforeIndex: Int,
        after: TimelineAnchor,
        spec: MessageVisibilitySpec,
        bounds: MessageWindowBounds = MessageWindowBounds(),
    ): VisibleMessageAnchor? {
        if (beforeIndex <= 0) return null
        val context = visibilityContext(bufferId)
        return db.messageDao().rawMessage(
            nearestUnreadMentionInPrefixQuery(
                context.roomId,
                beforeIndex,
                after,
                spec,
                context.identityRules,
                bounds,
            ),
        )?.let { VisibleMessageAnchor(it.id, it.msgid, it.serverTime, it.timelineOrder) }
    }

    suspend fun resolveSavedAnchor(
        bufferId: Long,
        msgid: String?,
        serverTime: Long,
        id: Long,
        spec: MessageVisibilitySpec,
    ): VisibleMessageAnchor? {
        val context = visibilityContext(bufferId)
        val visibility = MessageVisibilitySql(spec, context.identityRules)
        val canonicalEventId = resolveCanonicalEventId(id)
        val savedOrder = db.canonicalTimelineDao().eventById(canonicalEventId)?.timelineOrder ?: id
        val exact = queryMessage(
            where = when {
                msgid != null -> "m.msgid = ?"
                canonicalEventId != id -> "m.id = ?"
                else -> "m.serverTime = ? AND m.id = ?"
            },
            args = when {
                msgid != null -> listOf(msgid)
                canonicalEventId != id -> listOf(canonicalEventId)
                else -> listOf(serverTime, id)
            },
            bufferId = context.roomId,
            visibility = visibility.anchor(),
            order = "m.serverTime DESC, m.timelineOrder DESC, m.id DESC",
        )
        if (exact != null) return exact.toAnchor()

        // Prefer the first meaningful row at or behind the old viewport, then the nearest newer
        // row. This avoids surprising forward jumps while history is being read.
        val older = queryMessage(
            where = "m.serverTime < ? OR (m.serverTime = ? AND " +
                "(m.timelineOrder < ? OR (m.timelineOrder = ? AND m.id < ?)))",
            args = listOf(serverTime, serverTime, savedOrder, savedOrder, id),
            bufferId = context.roomId,
            visibility = visibility.anchor(),
            order = "m.serverTime DESC, m.timelineOrder DESC, m.id DESC",
        )
        if (older != null) return older.toAnchor()
        return queryMessage(
            where = "m.serverTime > ? OR (m.serverTime = ? AND " +
                "(m.timelineOrder > ? OR (m.timelineOrder = ? AND m.id > ?)))",
            args = listOf(serverTime, serverTime, savedOrder, savedOrder, id),
            bufferId = context.roomId,
            visibility = visibility.anchor(),
            order = "m.serverTime ASC, m.timelineOrder ASC, m.id ASC",
        )?.toAnchor()
    }

    /** Newest row that can define effective bottom; ignored raw tails remain separately observed. */
    suspend fun latestEffectiveAnchor(
        bufferId: Long,
        spec: MessageVisibilitySpec,
    ): VisibleMessageAnchor? {
        val context = visibilityContext(bufferId)
        return queryMessage(
            where = "1",
            args = emptyList(),
            bufferId = context.roomId,
            visibility = MessageVisibilitySql(spec, context.identityRules).anchor(),
            order = "m.serverTime DESC, m.timelineOrder DESC, m.id DESC",
        )?.toAnchor()
    }

    private suspend fun canonicalRoomId(bufferId: Long): Long =
        db.bufferDao().canonicalId(bufferId) ?: bufferId

    private suspend fun visibilityContext(bufferId: Long): VisibilityContext {
        val room = db.bufferDao().observeById(bufferId)
            ?: return VisibilityContext(bufferId, IrcIdentityRules())
        val identityRules = db.networkIdentityDao().byNetwork(room.networkId)?.identityRules
            ?: IrcIdentityRules()
        return VisibilityContext(room.id, identityRules)
    }

    suspend fun resolveCanonicalEventId(eventId: Long): Long =
        db.canonicalTimelineDao().canonicalEventId(eventId)

    /** Replace fool-authored chat-list state, then re-sort by the resulting meaningful activity. */
    suspend fun resolveChatList(
        rows: List<ChatListRow>,
        spec: MessageVisibilitySpec,
    ): List<ChatListRow> {
        if (spec.fools.isEmpty()) return rows
        val resolved = rows.map { row -> resolveChatListRow(row, spec) }
        return resolved.sortedWith(
            compareByDescending<ChatListRow> { it.pinned }
                .thenBy { it.lastMessageTime == null }
                .thenByDescending { it.lastMessageTime ?: Long.MIN_VALUE }
                .thenByDescending { it.bufferId },
        )
    }

    private suspend fun resolveChatListRow(
        row: ChatListRow,
        spec: MessageVisibilitySpec,
    ): ChatListRow {
        val visibility = MessageVisibilitySql(
            spec,
            IrcIdentityRules.from(row.caseMapping, row.chanTypes),
        )
        val preview = queryMessage(
            where = "1",
            args = emptyList(),
            bufferId = row.bufferId,
            visibility = visibility.preview(),
            order = "m.serverTime DESC, m.timelineOrder DESC, m.id DESC",
        )
        val unreadCount = chatListCount(row.bufferId, visibility.visibleUnread(), mentionsOnly = false)
        val mentionCount = chatListCount(row.bufferId, visibility.visibleUnread(), mentionsOnly = true)
        return row.copy(
            lastMessageText = preview?.text,
            lastMessageSender = preview?.sender,
            lastMessageTime = preview?.serverTime,
            unreadCount = unreadCount,
            mentionCount = mentionCount,
        )
    }

    private suspend fun queryMessage(
        where: String,
        args: List<Any>,
        bufferId: Long,
        visibility: String,
        order: String,
    ): MessageEntity? = db.messageDao().rawMessage(
        SimpleSQLiteQuery(
            "SELECT m.* FROM messages m WHERE m.bufferId = ? AND ($where) " +
                "AND $visibility ORDER BY $order LIMIT 1",
            (listOf(bufferId) + args).toTypedArray(),
        ),
    )

    private suspend fun chatListCount(
        bufferId: Long,
        visibility: String,
        mentionsOnly: Boolean,
    ): Int = db.messageDao().rawCount(
        SimpleSQLiteQuery(
            "SELECT COUNT(*) FROM buffers b JOIN messages m ON m.bufferId = b.id " +
                "WHERE b.id = ? AND (" +
                "m.serverTime > MAX(COALESCE(b.localReadAnchorTime, 0), " +
                "COALESCE(b.localUnreadFloorTime, 0)) OR (" +
                "m.serverTime = b.localReadAnchorTime AND " +
                "COALESCE(b.localUnreadFloorTime, -9223372036854775808) < b.localReadAnchorTime " +
                "AND (m.timelineOrder > COALESCE((SELECT timelineOrder FROM messages " +
                "WHERE id = b.localReadAnchorEventId), COALESCE(b.localReadAnchorEventId, 0)) " +
                "OR (m.timelineOrder = COALESCE((SELECT timelineOrder FROM messages " +
                "WHERE id = b.localReadAnchorEventId), COALESCE(b.localReadAnchorEventId, 0)) " +
                "AND m.id > COALESCE(b.localReadAnchorEventId, 0))))) " +
                "AND $visibility" + if (mentionsOnly) " AND m.hasMention = 1" else "",
            arrayOf(bufferId),
        ),
    )

    private fun MessageEntity.toAnchor() = VisibleMessageAnchor(id, msgid, serverTime, timelineOrder)

    private data class VisibilityContext(
        val roomId: Long,
        val identityRules: IrcIdentityRules,
    )
}
