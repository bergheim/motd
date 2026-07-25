package io.github.trevarj.motd.xmpp

import androidx.room.withTransaction
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.EventAliasEntity
import io.github.trevarj.motd.data.db.EventAliasNamespace
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.TimelineEventEntity
import io.github.trevarj.motd.data.db.TimelineEventId
import io.github.trevarj.motd.data.db.UserEntity
import io.github.trevarj.motd.data.sync.MessageNotifier
import io.github.trevarj.motd.data.sync.TypingTrackerImpl
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sole writer of XMPP-derived Room state (mirrors the IRC `EventProcessor` invariant). Consumes
 * exactly the [XmppEvent] variants produced by the Smack-backed session/actor layer (Task 6/7);
 * this class itself must stay Smack-free so it remains unit-testable without a real connection.
 *
 * Writes are serialized per network with an in-memory [Mutex] map — same effect as the IRC
 * `NetworkEventSequencer` without depending on it (the XMPP account actor already delivers events
 * for one network sequentially; this is defense in depth plus safety for the public
 * `createPending`/`confirmSend`/`failAllPending` entry points called from outside `process`).
 */
@Singleton
class XmppEventProcessor @Inject constructor(
    private val db: MotdDatabase,
    private val typing: TypingTrackerImpl,
    private val notifier: MessageNotifier,
) {
    private val locks = ConcurrentHashMap<Long, Mutex>()

    private suspend fun <T> withNetworkLock(networkId: Long, block: suspend () -> T): T =
        locks.getOrPut(networkId) { Mutex() }.withLock { block() }

    suspend fun process(networkId: Long, event: XmppEvent) {
        // Ready/Disconnected carry no DB write (connection state lives elsewhere) — return before
        // touching the per-network mutex at all rather than acquiring it just to no-op.
        if (event is XmppEvent.Ready || event is XmppEvent.Disconnected) return
        withNetworkLock(networkId) {
            when (event) {
                is XmppEvent.RosterUpdated -> handleRosterUpdated(networkId, event)
                is XmppEvent.ChatMessage -> handleChatMessage(networkId, event)
                is XmppEvent.ChatState -> handleChatState(networkId, event)
                is XmppEvent.MucMessage -> handleMucMessage(networkId, event)
                is XmppEvent.MucSubject -> handleMucSubject(networkId, event)
                is XmppEvent.MucOccupantJoined -> handleMucOccupantJoined(networkId, event)
                is XmppEvent.MucOccupantLeft -> handleMucOccupantLeft(networkId, event)
                is XmppEvent.MucSelfJoined -> handleMucSelfJoined(networkId, event)
                is XmppEvent.MucJoinFailed -> handleMucJoinFailed(networkId, event)
                is XmppEvent.MucKicked -> handleMucKicked(networkId, event)
                is XmppEvent.SendConfirmed -> confirmSendLocked(networkId, event.originId)
                is XmppEvent.Ready -> Unit // unreachable — handled by the early return above
                is XmppEvent.Disconnected -> Unit // unreachable — handled by the early return above
            }
        }
    }

    /** Durable pending row for an outbound message; returns event id. Null buffer -> null. */
    suspend fun createPending(
        networkId: Long,
        bufferId: Long,
        text: String,
        originId: String,
    ): TimelineEventId? = withNetworkLock(networkId) {
        if (db.bufferDao().rawById(bufferId) == null) return@withNetworkLock null
        val nick = db.networkDao().byId(networkId)?.nick.orEmpty()
        // Echo a "/me …" send as a styled action locally; the wire still carries the literal
        // "/me …" body (the caller sends `text` unchanged) so the gateway/peer converts it.
        val (kind, stored) = actionAware(text)
        db.canonicalTimelineDao().insertEvent(
            TimelineEventEntity(
                bufferId = bufferId,
                msgid = originId,
                serverTime = System.currentTimeMillis(),
                sender = nick,
                normalizedActor = nick,
                kind = kind,
                text = stored,
                isSelf = true,
                pendingLabel = originId,
                dedupKey = "xmpp:pending:$bufferId:$originId",
                serverTimeAuthoritative = false,
            ),
        )
    }

    /**
     * Test-facing convenience: acquires the per-network lock and delegates to [confirmSendLocked].
     * Production confirmation runs inline through `process(SendConfirmed)`, which already holds the
     * lock; this public wrapper lets tests confirm a send directly without routing a full
     * [XmppEvent] through [process].
     */
    suspend fun confirmSend(networkId: Long, originId: String): Unit =
        withNetworkLock(networkId) { confirmSendLocked(networkId, originId) }

    /** Flip all still-pending XMPP rows of this network to failed (reconnect / timeout). */
    suspend fun failAllPending(networkId: Long): Unit = withNetworkLock(networkId) {
        val messageDao = db.messageDao()
        for (target in db.bufferDao().openTargets(networkId)) {
            for (row in messageDao.pendingInBuffer(target.id)) {
                messageDao.update(row.copy(failed = true, pendingLabel = null))
            }
        }
    }

    /**
     * Fail exactly one still-pending XMPP row identified by its [originId] (the per-send 30s
     * timeout). No-op when the row was already confirmed — a [SendConfirmed] / MUC reflection clears
     * its `pendingLabel`, so [MessageDao.byPendingLabel] returns null and nothing is touched.
     */
    suspend fun failPending(networkId: Long, originId: String): Unit = withNetworkLock(networkId) {
        val messageDao = db.messageDao()
        for (target in db.bufferDao().openTargets(networkId)) {
            val pending = messageDao.byPendingLabel(target.id, originId) ?: continue
            messageDao.update(pending.copy(failed = true, pendingLabel = null))
            return@withNetworkLock
        }
    }

    suspend fun ensureQueryBuffer(networkId: Long, bareJid: String): Long =
        withNetworkLock(networkId) { ensureQueryBufferLocked(networkId, normalizeJid(bareJid)) }

    suspend fun ensureServerBuffer(networkId: Long): Long = withNetworkLock(networkId) {
        val bufferDao = db.bufferDao()
        bufferDao.byName(networkId, SERVER_BUFFER_NAME)?.let { return@withNetworkLock it.id }
        val network = db.networkDao().byId(networkId)
        val displayName = network?.jid ?: network?.name ?: "Server"
        val candidate = BufferEntity(
            networkId = networkId,
            name = SERVER_BUFFER_NAME,
            displayName = displayName,
            type = BufferType.SERVER,
        )
        val insertedId = bufferDao.insertIgnore(candidate)
        if (insertedId > 0L) insertedId else checkNotNull(bufferDao.byName(networkId, SERVER_BUFFER_NAME)).id
    }

    // ---- event handlers (unlocked — always called while holding the per-network mutex) ----

    private suspend fun handleRosterUpdated(networkId: Long, event: XmppEvent.RosterUpdated) {
        val bufferDao = db.bufferDao()
        val userDao = db.userDao()
        for (contact in event.contacts) {
            val bareJid = normalizeJid(contact.bareJid)
            userDao.upsert(UserEntity(networkId = networkId, nick = bareJid, realname = contact.name))
            val displayName = contact.name ?: bareJid
            bufferDao.byName(networkId, bareJid)
                ?.takeIf { it.type == BufferType.QUERY && it.displayName != displayName }
                ?.let { bufferDao.update(it.copy(displayName = displayName)) }
        }
    }

    private suspend fun handleChatMessage(networkId: Long, event: XmppEvent.ChatMessage) {
        val bareJid = normalizeJid(event.fromBareJid)
        val bufferId = ensureQueryBufferLocked(networkId, bareJid)
        val (kind, text) = actionAware(event.text)
        insertDedupedMessage(
            networkId = networkId,
            bufferId = bufferId,
            senderIdentity = bareJid,
            stanzaId = event.stanzaId ?: UUID.randomUUID().toString(),
            sender = bareJid,
            text = text,
            kind = kind,
            isSelf = false,
            delayedAtMs = event.delayedAtMs,
            hasMention = false,
        )
    }

    private suspend fun handleChatState(networkId: Long, event: XmppEvent.ChatState) {
        val bareJid = normalizeJid(event.fromBareJid)
        val bufferId = ensureQueryBufferLocked(networkId, bareJid)
        typing.onTyping(bufferId, bareJid, if (event.composing) "active" else "done")
    }

    private suspend fun handleMucMessage(networkId: Long, event: XmppEvent.MucMessage) {
        val roomJid = normalizeJid(event.roomJid)
        val bufferId = ensureMucBufferLocked(networkId, roomJid)
        val ourNick = db.networkDao().byId(networkId)?.nick
        val isOwnNick = ourNick != null && event.occupantNick == ourNick
        val stanzaId = event.stanzaId
        if (isOwnNick && stanzaId != null) {
            // Correlate by msgid (= originId), not by pendingLabel: the stream-level SendConfirmed
            // ack can arrive for a MUC send too, and typically races ahead of the room's own
            // reflection — it already clears pendingLabel via confirmSendLocked, so by the time the
            // reflection lands here pendingLabel may already be null. byMsgid still finds the row
            // (msgid is set at createPending time and never cleared), so this reflection always
            // resolves to "confirm/no-op", never a duplicate insert.
            val existing = db.messageDao().byMsgid(bufferId, stanzaId)
            if (existing != null) {
                confirmMucReflection(networkId, bufferId, event.occupantNick, stanzaId, existing)
                return
            }
        }
        val (kind, text) = actionAware(event.text)
        val hasMention = !isOwnNick && ourNick != null && containsMention(text, ourNick)
        insertDedupedMessage(
            networkId = networkId,
            bufferId = bufferId,
            // MUC identity uses occupantNick rather than the spec's sender bare JID: real JIDs are
            // not visible in semi-anonymous rooms. Nick+stanzaId reuse after a nick change (a new
            // occupant later reusing the same nick and coincidentally the same stanza id) is an
            // accepted v1 limitation.
            senderIdentity = event.occupantNick,
            stanzaId = stanzaId ?: UUID.randomUUID().toString(),
            sender = event.occupantNick,
            text = text,
            kind = kind,
            isSelf = isOwnNick,
            delayedAtMs = event.delayedAtMs,
            hasMention = hasMention,
        )
    }

    /** Reflection of our own MUC send: confirm the existing row instead of inserting a duplicate. */
    private suspend fun confirmMucReflection(
        networkId: Long,
        bufferId: Long,
        occupantNick: String,
        stanzaId: String,
        existing: MessageEntity,
    ): Unit = db.withTransaction {
        if (existing.pendingLabel != null) {
            db.messageDao().update(existing.copy(pendingLabel = null))
        }
        // Record the XMPP_MSGID alias now (a pending row is created without one) so a redundant
        // later reflection of the same stanza id is deduped like any other message.
        db.canonicalTimelineDao().insertAliasIgnore(
            EventAliasEntity(
                networkId = networkId,
                namespace = EventAliasNamespace.XMPP_MSGID,
                value = aliasBytes(bufferId, occupantNick, stanzaId),
                timelineEventId = existing.id,
            ),
        )
    }

    private suspend fun handleMucSubject(networkId: Long, event: XmppEvent.MucSubject) {
        val roomJid = normalizeJid(event.roomJid)
        val bufferId = ensureMucBufferLocked(networkId, roomJid)
        db.bufferDao().setTopic(bufferId, event.subject, event.byNick)
        insertPlainEvent(bufferId, event.byNick ?: roomJid, event.subject, MessageKind.TOPIC)
    }

    private suspend fun handleMucOccupantJoined(networkId: Long, event: XmppEvent.MucOccupantJoined) {
        val roomJid = normalizeJid(event.roomJid)
        val bufferId = ensureMucBufferLocked(networkId, roomJid)
        db.memberDao().upsert(MemberEntity(bufferId = bufferId, nick = event.nick))
        insertPlainEvent(bufferId, event.nick, "${event.nick} joined", MessageKind.JOIN)
    }

    private suspend fun handleMucOccupantLeft(networkId: Long, event: XmppEvent.MucOccupantLeft) {
        val roomJid = normalizeJid(event.roomJid)
        val bufferId = ensureMucBufferLocked(networkId, roomJid)
        db.memberDao().remove(bufferId, event.nick)
        insertPlainEvent(bufferId, event.nick, "${event.nick} left", MessageKind.PART)
    }

    private suspend fun handleMucSelfJoined(networkId: Long, event: XmppEvent.MucSelfJoined) {
        val roomJid = normalizeJid(event.roomJid)
        val bufferId = ensureMucBufferLocked(networkId, roomJid)
        db.bufferDao().setJoined(bufferId, true)
        // Refresh the gateway pretty name the same way the roster path refreshes a QUERY displayName:
        // a buffer created before this feature (or by the actor's raw-JID join path) gets its
        // "#channel · server" name applied on join. The canonical `name` is never touched.
        val prettyName = biboumiRoomDisplayName(roomJid)
        if (prettyName != null) {
            db.bufferDao().byName(networkId, roomJid)
                ?.takeIf { it.displayName != prettyName }
                ?.let { db.bufferDao().update(it.copy(displayName = prettyName)) }
        }
        db.memberDao().replaceAll(bufferId, event.occupants.map { MemberEntity(bufferId = bufferId, nick = it) })
    }

    private suspend fun handleMucJoinFailed(networkId: Long, event: XmppEvent.MucJoinFailed) {
        val roomJid = normalizeJid(event.roomJid)
        val bufferId = ensureMucBufferLocked(networkId, roomJid)
        db.bufferDao().setJoined(bufferId, false)
        insertPlainEvent(bufferId, roomJid, event.reason, MessageKind.ERROR)
    }

    private suspend fun handleMucKicked(networkId: Long, event: XmppEvent.MucKicked) {
        val roomJid = normalizeJid(event.roomJid)
        val bufferId = ensureMucBufferLocked(networkId, roomJid)
        db.bufferDao().setJoined(bufferId, false)
        db.bufferDao().advanceMembershipCycle(bufferId)
        insertPlainEvent(bufferId, roomJid, event.reason ?: "kicked", MessageKind.KICK)
    }

    private suspend fun confirmSendLocked(networkId: Long, originId: String) {
        val messageDao = db.messageDao()
        for (target in db.bufferDao().openTargets(networkId)) {
            val pending = messageDao.byPendingLabel(target.id, originId) ?: continue
            messageDao.update(pending.copy(pendingLabel = null))
            return
        }
    }

    // ---- buffer helpers (unlocked) ----

    private suspend fun ensureQueryBufferLocked(networkId: Long, bareJid: String): Long {
        val bufferDao = db.bufferDao()
        bufferDao.byName(networkId, bareJid)?.let { return it.id }
        // A Biboumi private-message JID (`<nick>!<server>@<gateway>`) shows just the IRC nick; every
        // other 1:1 falls back to the roster realname, then the bare JID.
        val displayName = biboumiNickDisplayName(bareJid)
            ?: db.userDao().byNick(networkId, bareJid)?.realname
            ?: bareJid
        val candidate = BufferEntity(
            networkId = networkId,
            name = bareJid,
            displayName = displayName,
            type = BufferType.QUERY,
        )
        val insertedId = bufferDao.insertIgnore(candidate)
        return if (insertedId > 0L) insertedId else checkNotNull(bufferDao.byName(networkId, bareJid)).id
    }

    private suspend fun ensureMucBufferLocked(networkId: Long, roomJid: String): Long {
        val bufferDao = db.bufferDao()
        bufferDao.byName(networkId, roomJid)?.let { return it.id }
        val candidate = BufferEntity(
            networkId = networkId,
            name = roomJid,
            // A Biboumi IRC-channel room (`<channel>%<server>@<gateway>`) shows "#channel · server";
            // the canonical `name` stays the full JID. Plain MUCs fall back to the raw JID.
            displayName = biboumiRoomDisplayName(roomJid) ?: roomJid,
            type = BufferType.CHANNEL,
        )
        val insertedId = bufferDao.insertIgnore(candidate)
        return if (insertedId > 0L) insertedId else checkNotNull(bufferDao.byName(networkId, roomJid)).id
    }

    // ---- timeline row helpers (unlocked) ----

    /** Insert a chat row deduped by the `XMPP_MSGID` alias; returns null (no insert) on a replay. */
    private suspend fun insertDedupedMessage(
        networkId: Long,
        bufferId: Long,
        senderIdentity: String,
        stanzaId: String,
        sender: String,
        text: String,
        kind: MessageKind,
        isSelf: Boolean,
        delayedAtMs: Long?,
        hasMention: Boolean,
    ): TimelineEventId? = db.withTransaction {
        val canonicalDao = db.canonicalTimelineDao()
        val aliasValue = aliasBytes(bufferId, senderIdentity, stanzaId)
        if (canonicalDao.aliasByValue(networkId, EventAliasNamespace.XMPP_MSGID, aliasValue) != null) {
            return@withTransaction null
        }
        val serverTime = delayedAtMs ?: System.currentTimeMillis()
        val eventId = canonicalDao.insertEvent(
            TimelineEventEntity(
                bufferId = bufferId,
                msgid = stanzaId,
                serverTime = serverTime,
                sender = sender,
                normalizedActor = senderIdentity,
                kind = kind,
                text = text,
                isSelf = isSelf,
                hasMention = hasMention,
                dedupKey = "xmpp:$bufferId\u0000$senderIdentity\u0000$stanzaId",
                serverTimeAuthoritative = delayedAtMs != null,
            ),
        )
        val aliasId = canonicalDao.insertAliasIgnore(
            EventAliasEntity(
                networkId = networkId,
                namespace = EventAliasNamespace.XMPP_MSGID,
                value = aliasValue,
                timelineEventId = eventId,
            ),
        )
        if (aliasId == -1L) {
            // Lost a race with a concurrent duplicate despite the pre-check above; undo the insert
            // rather than leave an orphaned, un-deduped row.
            canonicalDao.deleteEvent(eventId)
            null
        } else {
            eventId
        }
    }

    /** Insert an administrative (JOIN/PART/TOPIC/ERROR/KICK) row with no dedup identity. */
    private suspend fun insertPlainEvent(
        bufferId: Long,
        sender: String,
        text: String,
        kind: MessageKind,
    ): TimelineEventId {
        val now = System.currentTimeMillis()
        return db.canonicalTimelineDao().insertEvent(
            TimelineEventEntity(
                bufferId = bufferId,
                serverTime = now,
                sender = sender,
                normalizedActor = sender,
                kind = kind,
                text = text,
                dedupKey = "xmpp:$bufferId:$kind:$now:${UUID.randomUUID()}",
                serverTimeAuthoritative = false,
            ),
        )
    }

    // NUL ('\u0000') separator, not a space: a MUC occupant nick can legally contain spaces, so a
    // space-joined key would let one nick forge another tuple's ($bufferId, $sender, $stanzaId)
    // identity and suppress-dedup a targeted message. NUL cannot appear in a JID resourcepart/nick
    // or a stanza id, so the joined components can never collide. Purely in-memory — no migration.
    private fun aliasBytes(bufferId: Long, senderIdentity: String, stanzaId: String): ByteArray =
        "$bufferId\u0000$senderIdentity\u0000$stanzaId".toByteArray(StandardCharsets.UTF_8)

    /**
     * XEP-0245: a message body beginning with "/me " is a third-person action ("* nick waves"),
     * not literal text. Biboumi uses the same convention to bridge IRC CTCP ACTIONs in both
     * directions, so this makes `/me` render as a styled action instead of raw "/me …" text on
     * incoming IRC/XMPP actions and on the local echo of one the user sends. Returns the ACTION
     * kind plus the text with the prefix stripped; otherwise PRIVMSG with the text unchanged.
     */
    private fun actionAware(body: String): Pair<MessageKind, String> =
        if (body.startsWith(ME_PREFIX)) MessageKind.ACTION to body.substring(ME_PREFIX.length)
        else MessageKind.PRIVMSG to body

    private companion object {
        const val SERVER_BUFFER_NAME = "*"
        const val ME_PREFIX = "/me "
    }
}

/** Lowercase bare JID: drop the resource (after '/'), if any, and case-fold. Smack-free by design. */
internal fun normalizeJid(jid: String): String = jid.substringBefore('/').lowercase()

/** Word-boundary, case-insensitive substring match — mirrors the IRC mention heuristic. */
internal fun containsMention(text: String, nick: String): Boolean {
    if (nick.isEmpty() || text.length < nick.length) return false
    val lowerNick = nick.lowercase()
    val lowerText = text.lowercase()
    val lastStart = lowerText.length - lowerNick.length
    for (start in 0..lastStart) {
        val end = start + lowerNick.length
        if (start > 0 && lowerText[start - 1].isMentionWordChar()) continue
        if (end < lowerText.length && lowerText[end].isMentionWordChar()) continue
        if (lowerText.substring(start, end) == lowerNick) return true
    }
    return false
}

private fun Char.isMentionWordChar(): Boolean = this == '_' || isLetterOrDigit()
