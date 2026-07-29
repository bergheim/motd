package io.github.trevarj.motd.xmppbackend

import androidx.room.withTransaction
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.ObservationOrigin
import io.github.trevarj.motd.data.db.TimeProvenance
import io.github.trevarj.motd.data.db.TimelineEventEntity
import io.github.trevarj.motd.data.db.UserEntity
import io.github.trevarj.motd.data.sync.BufferStore
import io.github.trevarj.motd.data.sync.CanonicalTimelineStore
import io.github.trevarj.motd.data.sync.TimelineObservation
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The XMPP backend's single processor (docs/backend-neutral-xmpp-rollout.md "Persistence and writer
 * ownership"): the only path that turns a live [XmppSession]'s wire events into canonical Room facts.
 * "Each backend has exactly one processor that turns its wire events into canonical facts, and every
 * processor persists those facts only through the shared canonical repositories; no backend adds a
 * private Room write path" — this class writes exclusively through [BufferStore],
 * [CanonicalTimelineStore], and (slice X5) the shared [io.github.trevarj.motd.data.db.MemberDao]/
 * [io.github.trevarj.motd.data.db.UserDao] via [db] — the same shared surface
 * [io.github.trevarj.motd.data.sync.EventProcessor] uses for IRC, and owns no table of its own.
 * [XmppConnectionManager]/[XmppAccountActor] hand this processor each live session's DM/MUC/roster
 * events; neither touches Room directly (docs/backend-neutral-xmpp-rollout.md "Required boundary").
 */
@Singleton
class XmppProcessor @Inject constructor(
    private val db: MotdDatabase,
    private val bufferStore: BufferStore = BufferStore(db),
    private val canonicalTimeline: CanonicalTimelineStore = CanonicalTimelineStore(db),
) {
    /**
     * Persist one incoming direct message from [message.fromBareJid][XmppIncomingMessage.fromBareJid]
     * on [networkId]'s live session.
     *
     * Finds-or-creates the QUERY buffer for (networkId, bare JID) through [BufferStore.getOrCreate] —
     * the same shared, network-scoped find-or-create idiom
     * [io.github.trevarj.motd.data.sync.EventProcessor] uses for IRC queries — named and displayed
     * as the bare JID (XMPP has no separate roster-name concept wired in this slice; a friendly
     * display name arrives with roster support). Two networks sharing an identical bare JID stay
     * separate conversations because every buffer/alias lookup here is scoped by [networkId].
     *
     * Dedup/alias identity: [XmppIncomingMessage.stanzaId] becomes the canonical event's `msgid`, so
     * [CanonicalTimelineStore] derives an [io.github.trevarj.motd.data.db.EventAliasNamespace.MSGID]
     * alias from it exactly like an IRC `msgid` — the rollout doc's "generic backend-scoped identity"
     * direction rather than a protocol-named `XMPP_MSGID` alias. A stanza id is sender-supplied, not
     * archive-assigned (no XMPP MAM/XEP-0359 stanza-id in this slice), which matches the MSGID
     * namespace's existing IRC semantics (also sender/server-assigned per-message, not a paging
     * cursor) rather than requiring a new namespace. Redelivery with the same stanza id therefore
     * dedups to the same row; a message with no stanza id lands with `msgid = null` and no alias,
     * exactly like an IRC message with no msgid.
     */
    suspend fun onIncomingDirectMessage(
        networkId: Long,
        message: XmppIncomingMessage,
        connectionGeneration: Long?,
    ) {
        if (message.isCarbonOrSelf) return // carbons/self-echo land with a later slice
        val buffer = bufferStore.getOrCreate(
            networkId = networkId,
            normalizedName = message.fromBareJid,
            displayName = message.fromBareJid,
            type = BufferType.QUERY,
        )
        val delayStamp = message.delayStampMillis
        val timeProvenance = if (delayStamp != null) TimeProvenance.SERVER_TAG else TimeProvenance.LOCAL_CLOCK
        val serverTime = delayStamp ?: System.currentTimeMillis()
        val event = TimelineEventEntity(
            bufferId = buffer.id,
            msgid = message.stanzaId,
            serverTime = serverTime,
            sender = message.fromBareJid,
            kind = MessageKind.PRIVMSG,
            text = message.body,
            isSelf = false,
            dedupKey = message.stanzaId
                ?: "xmpp-dm:$networkId:${buffer.id}:$serverTime:${message.fromBareJid}:${message.body}",
        )
        canonicalTimeline.ingest(
            TimelineObservation(
                networkId = networkId,
                event = event,
                origin = ObservationOrigin.LIVE,
                connectionGeneration = connectionGeneration,
                batchId = null,
                timeProvenance = timeProvenance,
            ),
        )
        // notification policy arrives with a later slice
    }

    /**
     * Persist one incoming MUC (groupchat) message (slice X5). Finds-or-creates the CHANNEL buffer
     * for (networkId, room bare JID) through [BufferStore.getOrCreate] — the same find-or-create
     * idiom [onIncomingDirectMessage] uses for a QUERY, keyed by [XmppIncomingMucMessage.roomBareJid]
     * instead of a peer's bare JID.
     *
     * Dedup/alias identity mirrors [onIncomingDirectMessage] exactly:
     * [XmppIncomingMucMessage.stanzaId] becomes the canonical event's `msgid` (an MSGID alias, not a
     * new namespace), so redelivery of the same stanza id dedups to one row and a stanza with no id
     * lands with `msgid = null` and no alias.
     *
     * [XmppIncomingMucMessage.isSelf] — computed by the session from its own in-room nickname, never
     * re-derived here — becomes the canonical event's `isSelf` directly: an MUC reflects every
     * accepted message back to its sender, so this is the only "did I send this" signal available in
     * a semi-anonymous room (no JID comparison is possible).
     */
    suspend fun onMucMessage(
        networkId: Long,
        message: XmppIncomingMucMessage,
        connectionGeneration: Long?,
    ) {
        val buffer = ensureMucBuffer(networkId, message.roomBareJid)
        val delayStamp = message.delayStampMillis
        val timeProvenance = if (delayStamp != null) TimeProvenance.SERVER_TAG else TimeProvenance.LOCAL_CLOCK
        val serverTime = delayStamp ?: System.currentTimeMillis()
        val event = TimelineEventEntity(
            bufferId = buffer.id,
            msgid = message.stanzaId,
            serverTime = serverTime,
            sender = message.occupantNick,
            kind = MessageKind.PRIVMSG,
            text = message.body,
            isSelf = message.isSelf,
            dedupKey = message.stanzaId
                ?: "xmpp-muc:$networkId:${buffer.id}:$serverTime:${message.occupantNick}:${message.body}",
        )
        canonicalTimeline.ingest(
            TimelineObservation(
                networkId = networkId,
                event = event,
                origin = ObservationOrigin.LIVE,
                connectionGeneration = connectionGeneration,
                batchId = null,
                timeProvenance = timeProvenance,
            ),
        )
        // notification policy arrives with a later slice
    }

    /**
     * Persist a MUC subject (topic) change the way
     * [io.github.trevarj.motd.data.sync.EventProcessor.onTopicChanged] persists an IRC TOPIC: both
     * the buffer's own `topic`/`topicSetBy` columns ([io.github.trevarj.motd.data.db.BufferDao.setTopic])
     * *and* a TOPIC-[MessageKind] canonical timeline row through the identical
     * [CanonicalTimelineStore.ingest] mechanism, reusing IRC's exact `"topic: <value>"` text
     * convention and `setBy ?: ""` sender fallback — so shared UI (`MessageList`'s system-row
     * rendering, which treats [MessageKind.TOPIC] as a system kind and renders `text` verbatim) shows
     * an XMPP subject change exactly like an IRC topic change, with no protocol-aware branch.
     */
    suspend fun onMucSubject(
        networkId: Long,
        subject: XmppMucSubject,
        connectionGeneration: Long?,
    ) {
        val buffer = ensureMucBuffer(networkId, subject.roomBareJid)
        db.bufferDao().setTopic(buffer.id, subject.subject, subject.byNick)
        val now = System.currentTimeMillis()
        val event = TimelineEventEntity(
            bufferId = buffer.id,
            serverTime = now,
            sender = subject.byNick ?: "",
            kind = MessageKind.TOPIC,
            text = "topic: ${subject.subject}",
            dedupKey = "xmpp-muc-topic:$networkId:${buffer.id}:$now:${subject.subject}",
        )
        canonicalTimeline.ingest(
            TimelineObservation(
                networkId = networkId,
                event = event,
                origin = ObservationOrigin.LIVE,
                connectionGeneration = connectionGeneration,
                batchId = null,
                timeProvenance = TimeProvenance.LOCAL_CLOCK,
            ),
        )
    }

    /**
     * Occupant snapshot/join/leave for one MUC room (slice X5). Members are feature-local roster
     * state, not timeline events (docs/backend-neutral-xmpp-rollout.md): writes go straight through
     * the shared [io.github.trevarj.motd.data.db.MemberDao], mirroring the same NAMES-reconciliation
     * idiom [io.github.trevarj.motd.data.sync.EventProcessor.onNames] uses for IRC —
     * [XmppMucOccupantEvent.Snapshot] is an atomic clear-then-insert replay (`memberDao.replaceAll`,
     * same as an IRC NAMES reply landing via `MemberDao.replaceAll`), while
     * [XmppMucOccupantEvent.Joined]/[XmppMucOccupantEvent.Left] are incremental upsert/remove (same
     * as IRC's live JOIN/PART via `MemberDao.upsert`/`remove`). A [XmppMucOccupantEvent.Snapshot] also
     * marks the buffer joined ([io.github.trevarj.motd.data.db.BufferDao.setJoined]), mirroring IRC's
     * self-JOIN `markJoined(bufferId, true)` — it only ever arrives after a successful
     * [XmppSession.joinRoom]. MUC occupant affiliation/role (IRC's member-prefix equivalent) is not
     * modeled by this baseline slice, so every [MemberEntity] here carries the default empty
     * `prefixes`.
     */
    suspend fun onMucOccupantEvent(networkId: Long, event: XmppMucOccupantEvent) {
        val buffer = ensureMucBuffer(networkId, event.roomBareJid)
        when (event) {
            is XmppMucOccupantEvent.Snapshot -> {
                db.memberDao().replaceAll(buffer.id, event.nicks.map { MemberEntity(buffer.id, it) })
                db.bufferDao().setJoined(buffer.id, true)
            }
            is XmppMucOccupantEvent.Joined -> db.memberDao().upsert(MemberEntity(buffer.id, event.nick))
            is XmppMucOccupantEvent.Left -> db.memberDao().remove(buffer.id, event.nick)
        }
    }

    /**
     * Mark a MUC buffer unjoined after an explicit local leave. [XmppConnectionManager.partChannel]
     * calls this directly (not through the actor's event-callback wiring like every other handler in
     * this class) because a voluntary [XmppSession.leaveRoom] has no corresponding Smack callback to
     * route through the normal live-event flow — unlike IRC PART, which is always a real wire event
     * [io.github.trevarj.motd.data.sync.EventProcessor.onParted] observes. Clears cached occupants
     * the same way EventProcessor's self-PART does (`memberDao.clear`): a stale member list must not
     * linger for a room this session no longer receives presence for.
     */
    suspend fun onLeftRoom(bufferId: Long) {
        db.withTransaction {
            db.memberDao().clear(bufferId)
            db.bufferDao().setJoined(bufferId, false)
        }
    }

    /**
     * Roster (buddy-list) contacts loaded once per session (slice X5). Mirrors
     * [io.github.trevarj.motd.data.sync.EventProcessor]'s WHO/account idiom of upserting a shared
     * [UserEntity] row per identity through [io.github.trevarj.motd.data.db.UserDao] — the same
     * shared table IRC populates from WHOX rows and JOIN/MONITOR account tags — keyed here by
     * (networkId, bareJid) in [UserEntity.nick] since XMPP has no separate nick concept for a roster
     * contact, with the roster-supplied display name (when present) carried in [UserEntity.realname],
     * matching what [onMucMessage]/[onIncomingDirectMessage] key their buffers by. An existing row's
     * `realname` is preserved when a later load supplies no name, mirroring
     * [io.github.trevarj.motd.data.sync.EventProcessor]'s fetch-existing-then-merge upsert idiom
     * (`upsertUser`). [XmppRosterLoad.Failed] carries no contacts and persists nothing — the load
     * outcome itself is published on [XmppConnectionManager.rosterStates], not through this
     * processor (see that class's roster-state wiring).
     */
    suspend fun onRosterLoad(networkId: Long, load: XmppRosterLoad) {
        if (load !is XmppRosterLoad.Loaded) return
        val userDao = db.userDao()
        for (contact in load.contacts) {
            val existing = userDao.byNick(networkId, contact.bareJid)
            userDao.upsert(
                (existing ?: UserEntity(networkId = networkId, nick = contact.bareJid)).copy(
                    realname = contact.name ?: existing?.realname,
                ),
            )
        }
    }

    /** Find-or-create the CHANNEL buffer for a MUC room, named/displayed as its bare JID — same
     *  "no friendly name wired yet" tradeoff [onIncomingDirectMessage] documents for a QUERY. */
    private suspend fun ensureMucBuffer(networkId: Long, roomBareJid: String) = bufferStore.getOrCreate(
        networkId = networkId,
        normalizedName = roomBareJid,
        displayName = roomBareJid,
        type = BufferType.CHANNEL,
    )
}
