package io.github.trevarj.motd.xmppbackend

import androidx.room.withTransaction
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.ObservationOrigin
import io.github.trevarj.motd.data.db.TimeProvenance
import io.github.trevarj.motd.data.db.TimelineEventEntity
import io.github.trevarj.motd.data.db.TimelineEventId
import io.github.trevarj.motd.data.db.UserEntity
import io.github.trevarj.motd.data.sync.BufferStore
import io.github.trevarj.motd.data.sync.CanonicalTimelineStore
import io.github.trevarj.motd.data.sync.SemanticIdentity
import io.github.trevarj.motd.data.sync.TimelineObservation
import io.github.trevarj.motd.data.sync.TypingTrackerImpl
import io.github.trevarj.motd.service.TypingTracker
import java.util.UUID
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
 *
 * [typingTracker] (slice X6) is the one exception to "canonical repositories only": incoming 1:1
 * typing is in-memory seam state, not a Room write, routed through the neutral
 * [TypingTracker] **interface** — never the concrete [TypingTrackerImpl] — exactly the shared write
 * contract Branch 1 added after this backend flagged its absence (docs/backend-neutral-xmpp-rollout.md
 * "Feedback into PR 1"). `:irc`'s own [io.github.trevarj.motd.data.sync.EventProcessor] still depends
 * on the concrete class directly (a grandfathered detail of the original single-backend design, not
 * this backend's concern); depending on the interface here is what proves the boundary.
 */
@Singleton
class XmppProcessor @Inject constructor(
    private val db: MotdDatabase,
    private val bufferStore: BufferStore = BufferStore(db),
    private val canonicalTimeline: CanonicalTimelineStore = CanonicalTimelineStore(db),
    private val typingTracker: TypingTracker = TypingTrackerImpl(),
) {
    // -- durable pending sends (slice X6; docs/backend-neutral-xmpp-rollout.md baseline: "durable
    // pending sends and send acknowledgements"). Mirrors EventProcessor's persistOutgoingPlan/beginRetry
    // pairing, minus the multi-chunk planning and per-network sequencer IRC needs (a single XMPP
    // send is always exactly one event, and this processor has no second writer to serialize
    // against — see the class KDoc). --

    /** [label] is the fresh outgoing-stanza id the caller hands to the live session; [eventId] is the
     *  durable canonical row it now labels. */
    data class OutgoingSend(val label: String, val eventId: TimelineEventId)

    /**
     * Persist the durable pending row for one outgoing send, before any wire write is attempted
     * (docs/backend-neutral-xmpp-rollout.md "durable pending sends" — durability precedes the wire,
     * exactly like `:irc`'s `ConnectionManagerImpl.sendMessage` +
     * [io.github.trevarj.motd.data.sync.EventProcessor.persistOutgoingPlan]). Builds a fresh label,
     * an `isSelf=true` PRIVMSG row pending on it (`dedupKey` follows
     * [SemanticIdentity.pendingKey] — the store's pending-row idiom, matching what
     * `persistOutgoingPlan` itself writes), and ingests it as [ObservationOrigin.LOCAL_SEND].
     * [TimelineObservation.label] is left at its default (`event.pendingLabel`), so
     * [CanonicalTimelineStore] derives the LABEL alias automatically — the same shape
     * `BackendContractTest`'s "sender-supplied and archive-assigned identifiers reconcile on one row"
     * scenario exercises for IRC. [replyToEventId]/[replyToMsgid], when supplied, are attached
     * verbatim for the shared reply-preview UI; XMPP has no wire-level reply capability to negotiate
     * in this baseline, so — unlike IRC — nothing here ever rewrites [text] with a fallback quote
     * prefix.
     *
     * [sender] is caller-resolved (the account's bare JID for a DM, the account's in-room nickname
     * for a MUC) rather than looked up here, since only [XmppConnectionManager] knows which one
     * applies to [bufferId] without an extra buffer-type branch in this processor.
     */
    suspend fun persistOutgoingSend(
        networkId: Long,
        bufferId: Long,
        sender: String,
        text: String,
        replyToEventId: TimelineEventId? = null,
        replyToMsgid: String? = null,
        connectionGeneration: Long? = null,
    ): OutgoingSend {
        val label = newOutgoingLabel()
        val event = TimelineEventEntity(
            bufferId = bufferId,
            msgid = null,
            serverTime = System.currentTimeMillis(),
            sender = sender,
            kind = MessageKind.PRIVMSG,
            text = text,
            isSelf = true,
            replyToMsgid = replyToMsgid,
            replyToEventId = replyToEventId,
            pendingLabel = label,
            dedupKey = SemanticIdentity.pendingKey(label),
            serverTimeAuthoritative = false,
        )
        val result = canonicalTimeline.ingest(
            TimelineObservation(
                networkId = networkId,
                event = event,
                origin = ObservationOrigin.LOCAL_SEND,
                connectionGeneration = connectionGeneration,
                batchId = null,
                timeProvenance = TimeProvenance.LOCAL_CLOCK,
            ),
        )
        return OutgoingSend(label, result.event.id)
    }

    /**
     * Attach a fresh attempt label to a failed, still-unconfirmed send (docs/backend-neutral-xmpp-rollout.md
     * baseline "send acknowledgements"; mirrors
     * [io.github.trevarj.motd.data.sync.EventProcessor.beginRetry]). [CanonicalTimelineStore.beginRetry]
     * already re-arms `pendingLabel`/clears `failed` and inserts the new LABEL alias + LOCAL_SEND
     * observation transactionally — this is a thin pass-through that only supplies the fresh label,
     * so [XmppConnectionManager.retryMessage] never has to reach past this processor into
     * [CanonicalTimelineStore] directly. Null when [eventId] is not a retryable row (see
     * [CanonicalTimelineStore.beginRetry]'s guard: must still be self, pending on [eventId]'s prior
     * label, and have no msgid).
     */
    suspend fun beginRetry(
        networkId: Long,
        eventId: TimelineEventId,
        connectionGeneration: Long? = null,
    ): OutgoingSend? {
        val label = newOutgoingLabel()
        val retried = canonicalTimeline.beginRetry(networkId, eventId, label, connectionGeneration)
            ?: return null
        return OutgoingSend(label, retried.id)
    }

    private fun newOutgoingLabel(): String = "xmpp-${UUID.randomUUID()}"

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
     * Route one incoming 1:1 XEP-0085 chat-state notification to the shared [TypingTracker] seam
     * (slice X6; docs/backend-neutral-xmpp-rollout.md baseline "one-to-one typing where supported" —
     * the incoming half; [XmppConnectionManager.sendTyping] is the outgoing half). Finds-or-creates
     * the sender's QUERY buffer through [BufferStore.getOrCreate] — the identical idiom
     * [onIncomingDirectMessage] uses — so a chat state from a JID with no prior conversation still
     * surfaces a buffer to show it against; `:irc`'s
     * [io.github.trevarj.motd.data.sync.EventProcessor.onTag] does the same find-or-create
     * (`ensureBuffer`) for a TAGMSG(+typing) from an unseen sender, so this is not a new precedent —
     * IRC already treats "create" as the right behavior for exactly this case.
     *
     * Maps [XmppChatState] onto the seam's IRC-shaped vocabulary — the inverse of
     * [XmppConnectionManager.sendTyping]'s mapping: COMPOSING -> "active", PAUSED -> "paused", and
     * ACTIVE/INACTIVE/GONE all collapse to "done" (each means "not composing" from the seam's
     * three-state perspective; XEP-0085's finer distinction between "still present" (active), "gone
     * idle" (inactive), and "left the conversation" (gone) has no counterpart there).
     *
     * [XmppIncomingChatState.isCarbonOrSelf] is never applied, mirroring [onIncomingDirectMessage]'s
     * identical guard: nothing sets it true yet (no carbons in this baseline), but the field — and
     * this check — exist now so a later carbons slice reshapes the guard's input, not this dispatch.
     */
    suspend fun onChatState(networkId: Long, state: XmppIncomingChatState) {
        if (state.isCarbonOrSelf) return // carbons/self-echo land with a later slice
        val buffer = bufferStore.getOrCreate(
            networkId = networkId,
            normalizedName = state.fromBareJid,
            displayName = state.fromBareJid,
            type = BufferType.QUERY,
        )
        val seamState = when (state.state) {
            XmppChatState.COMPOSING -> "active"
            XmppChatState.PAUSED -> "paused"
            XmppChatState.ACTIVE, XmppChatState.INACTIVE, XmppChatState.GONE -> "done"
        }
        typingTracker.onTyping(buffer.id, state.fromBareJid, seamState)
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
     *
     * **Pending-send reconciliation (slice X6):** [XmppConnectionManager.sendMessage] sets the
     * outgoing stanza's `id` to the durable row's pending label (see [persistOutgoingSend]) before
     * handing it to a live session, so a room's reflection of that same send comes back here with
     * [XmppIncomingMucMessage.stanzaId] equal to that label. When [XmppIncomingMucMessage.isSelf] is
     * true, this observation's `label` is threaded as that stanza id — mirroring how
     * [io.github.trevarj.motd.data.sync.EventProcessor] threads `ctx.label` through every incoming
     * IRC observation — so [CanonicalTimelineStore]'s LABEL-alias lookup finds the still-pending row
     * and coalesces this reflection into it (the exact shape `BackendContractTest`'s "sender-supplied
     * and archive-assigned identifiers reconcile on one row" scenario proves for IRC), clearing
     * `pendingLabel` and un-failing it even if a send-timeout already fired first — both are
     * [CanonicalTimelineStore.enrich]'s generic behavior once the label matches, not special-cased
     * here. A non-self message's stanza id is never threaded as a label: only an occupant reflecting
     * back as *us* can plausibly be the echo of a send this account made.
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
                label = message.stanzaId.takeIf { message.isSelf },
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
     *
     * Returns the resolved buffer id so [XmppConnectionManager] can publish
     * [io.github.trevarj.motd.service.ConnectionManager.memberLoadStates] (a buffer-id-keyed,
     * manager-owned in-memory signal) without this processor ever touching that seam state itself —
     * this class stays a pure Room writer, exactly the persistence/writer-ownership split
     * [onLeftRoom] documents for the symmetric leave case.
     */
    suspend fun onMucOccupantEvent(networkId: Long, event: XmppMucOccupantEvent): Long {
        val buffer = ensureMucBuffer(networkId, event.roomBareJid)
        when (event) {
            is XmppMucOccupantEvent.Snapshot -> {
                db.memberDao().replaceAll(buffer.id, event.nicks.map { MemberEntity(buffer.id, it) })
                db.bufferDao().setJoined(buffer.id, true)
            }
            is XmppMucOccupantEvent.Joined -> db.memberDao().upsert(MemberEntity(buffer.id, event.nick))
            is XmppMucOccupantEvent.Left -> db.memberDao().remove(buffer.id, event.nick)
        }
        return buffer.id
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
     * (`upsertUser`). [XmppRosterLoad.Failed] carries no contacts and persists nothing.
     *
     * This account-level load outcome is deliberately xmppbackend-internal: it drives only this
     * [UserEntity] upsert and never reaches [io.github.trevarj.motd.service.ConnectionManager]. It
     * must not be confused with [XmppMucOccupantEvent] member-list state, which is a genuinely
     * different, per-buffer concept that *does* reach the seam (see [onMucOccupantEvent] and
     * [XmppConnectionManager]'s `memberLoadStates` wiring) — conflating the two into one seam map was
     * the bug Branch 1 fixed after this slice's first pass.
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

    /**
     * Find-or-create the CHANNEL buffer for a MUC room, named/displayed as its bare JID — same
     * "no friendly name wired yet" tradeoff [onIncomingDirectMessage] documents for a QUERY.
     *
     * Not `private`: [XmppConnectionManager.joinChannel] also calls this directly to resolve the
     * buffer id *before* the room is actually joined, so it can publish a `LOADING` member-load
     * state immediately — the room-scoped Room write (find-or-create) still happens only here, in
     * the processor, preserving the single-writer invariant even though the manager triggers it.
     */
    suspend fun ensureMucBuffer(networkId: Long, roomBareJid: String) = bufferStore.getOrCreate(
        networkId = networkId,
        normalizedName = roomBareJid,
        displayName = roomBareJid,
        type = BufferType.CHANNEL,
    )
}
