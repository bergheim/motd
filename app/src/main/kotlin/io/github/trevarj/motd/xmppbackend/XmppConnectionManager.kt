package io.github.trevarj.motd.xmppbackend

import io.github.trevarj.motd.backend.ConnectionState
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.TimelineEventId
import io.github.trevarj.motd.data.db.XmppAccountEntity
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.ImmediateWireAcceptance
import io.github.trevarj.motd.service.RosterLoadState
import io.github.trevarj.motd.service.SendAcceptance
import io.github.trevarj.motd.service.SendRejectionReason
import io.github.trevarj.motd.service.resolveAndAdvanceCurrentReadTarget
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** [XmppSessionState] -> the neutral [ConnectionState] the seam publishes to shared code. */
private fun XmppSessionState.toConnectionState(generation: Long): ConnectionState = when (this) {
    XmppSessionState.Disconnected -> ConnectionState.Disconnected
    XmppSessionState.Connecting -> ConnectionState.Connecting
    XmppSessionState.Authenticating -> ConnectionState.Authenticating
    is XmppSessionState.Ready -> ConnectionState.Ready(
        selfHandle = bareJid,
        generation = generation,
        negotiationRevision = 0, // no negotiated-capability concept yet; arrives with a later slice.
    )
    is XmppSessionState.Failed -> ConnectionState.Failed(reason = reason, fatal = fatal)
}

/**
 * [ConnectionManager] for XMPP network rows only (docs/backend-neutral-xmpp-rollout.md "PR 2").
 * Structured like `service.ConnectionManagerImpl`/`service.ConnectionActor` — one
 * [XmppAccountActor] per row, reconciled against `networkDao.observeAll()` — without importing
 * either or reproducing the old prototype's IRC-shaped router. IRC (and any other non-"xmpp") rows
 * are never observed, spawned, or otherwise touched.
 *
 * Slice X5 adds MUC join/leave/occupant-refresh ([joinChannel]/[partChannel]/[requestMembers]) and
 * MUC member-load state ([memberLoadStates]). Slice X6 adds durable pending sends and send
 * acknowledgements ([sendMessage]/[retryMessage]/[writeAndTrack]) and 1:1 typing ([sendTyping]).
 * A later review pass added [ensureQueryBuffer]/[ensureServerBuffer] (shared UI's `/msg`, `/query`,
 * "Message user", and "Server messages" reach every backend through these) and [markRead] (the
 * backend-neutral local read-anchor advance). React, history, and cert prompts remain out of scope
 * and return the same inert rejection/no-op
 * [InertConnectionManager][io.github.trevarj.motd.backend.InertConnectionManager] uses, pending
 * later slices.
 *
 * Every live session's [XmppSession.incomingMessages]/`incomingMucMessages`/`mucSubjects`/
 * `mucOccupants`/`rosterLoad` is wired to [XmppProcessor] through the actor it runs on
 * ([ensureActorLocked]); this manager itself only ever writes the in-memory [_connectionStates] and
 * [_memberLoadStates] maps, never Room timeline/member/user state
 * (docs/backend-neutral-xmpp-rollout.md "Persistence and writer ownership").
 *
 * **`memberLoadStates` is buffer-id-keyed, not network-id-keyed (Branch-1-fixed seam contract):**
 * this slice's first pass published XMPP's account-level roster-loaded signal into what was then
 * called `rosterStates`, keyed by network id — but IRC already keyed that exact map by *buffer* id
 * (a channel's NAMES-load state), and `CompositeConnectionManager` unions every backend's map into
 * one flat `Long` keyspace, so a mixed IRC+XMPP install risked a silent cross-backend key collision.
 * Branch 1 renamed the field to [ConnectionManager.memberLoadStates] and pinned its key to buffer ids
 * in the contract doc (see `service/ServiceSeam.kt`). This class now publishes genuine **MUC
 * member-list load state**, keyed by the room's buffer id, exactly analogous to IRC's channel NAMES
 * state — never the XMPP account roster, which stays entirely internal (see [XmppProcessor.onRosterLoad]'s
 * KDoc). [joinChannel]/[onMucOccupant]/[requestMembers]/[partChannel]/[publishState] are this map's
 * only writers; see each for its transition.
 */
@Singleton
class XmppConnectionManager @Inject constructor(
    private val db: MotdDatabase,
    private val sessionFactory: XmppSessionFactory,
    @ApplicationScope private val scope: CoroutineScope,
    private val processor: XmppProcessor = XmppProcessor(db),
) : ConnectionManager {

    private val actors = ConcurrentHashMap<Long, XmppAccountActor>()

    /**
     * The [XmppAccountEntity] fingerprint ([xmppAccountFingerprint]) each live [actors] entry was
     * built from (review fix — mirrors `:irc` `ConnectionRegistry`'s per-actor `fingerprint` +
     * `isConfigurationFailure`-gated rebuild). [XmppAccountActor] captures its `account` parameter
     * at construction and never re-reads Room, so without this an edited JID/password/resource
     * would leave every live and even fatally-parked actor running forever on stale credentials —
     * [ensureActorLocked] is the sole writer/reader pairing that keeps this in sync with [actors].
     */
    private val actorFingerprints = ConcurrentHashMap<Long, String>()

    /** Sticky manual override of autoConnect: true = force-connect, false = force-disconnect,
     *  absent = follow the row's autoConnect flag. Survives reconcile emissions, cleared by stopAll. */
    private val userIntents = ConcurrentHashMap<Long, Boolean>()

    /** Manager-global monotonic session identity (mirrors `:irc` ConnectionRegistry's sessionSeq):
     *  every new connection attempt anywhere gets the next value, so [ConnectionState.Ready] always
     *  carries a fresh generation even across a transparent actor-owned reconnect. */
    private val sessionSeq = AtomicLong(0)

    /**
     * MUC send-acknowledgement watchdogs (slice X6; docs/backend-neutral-xmpp-rollout.md baseline
     * "send acknowledgements"), keyed like `:irc` `ConnectionRegistry.pendingEchoJobs` by
     * `"$bufferId:$label"`. Mirrors [io.github.trevarj.motd.service.ConnectionManagerImpl]'s
     * `armEchoTimeout` shape with a plain coroutine + map instead of that class's actor/command-channel
     * machinery: a fresh arm for the same key cancels any prior job (labels are unique per attempt, so
     * this only ever matters defensively), and the job removes its own entry on completion. Launched
     * on [scope] (not any per-actor job), so a send that survives a reconnect is still resolved:
     * [io.github.trevarj.motd.data.db.MessageDao.failIfStillPending]'s `pendingLabel = :label AND
     * msgid IS NULL` guard is idempotent, so firing after the row is already confirmed/failed by
     * some other path is always a safe no-op — see [armSendTimeout].
     */
    private val pendingSendTimeouts = ConcurrentHashMap<String, Job>()

    private val mutex = Mutex()
    private var reconcileJob: Job? = null

    private val _connectionStates = MutableStateFlow<Map<Long, ConnectionState>>(emptyMap())
    override val connectionStates: StateFlow<Map<Long, ConnectionState>> = _connectionStates.asStateFlow()

    /**
     * MUC member-list load state per CHANNEL buffer id (slice X5; corrected after Branch-1 feedback
     * — see this class's KDoc). NOT the account-level XMPP roster, which never reaches this map.
     */
    private val _memberLoadStates = MutableStateFlow<Map<Long, RosterLoadState>>(emptyMap())
    override val memberLoadStates: StateFlow<Map<Long, RosterLoadState>> = _memberLoadStates.asStateFlow()

    /** This manager's view of the network table: rows carrying the XMPP discriminator only. */
    private fun observeXmppNetworks(): Flow<List<NetworkEntity>> =
        db.networkDao().observeAll().map { rows -> rows.filter { it.protocol == XmppChatBackend.XMPP_PROTOCOL.value } }

    private fun wantConnected(row: NetworkEntity): Boolean = userIntents[row.id] ?: row.autoConnect

    override suspend fun startAll() {
        mutex.withLock {
            if (reconcileJob?.isActive == true) return@withLock
            reconcileJob = scope.launch {
                observeXmppNetworks().collect { rows -> reconcile(rows) }
            }
        }
    }

    override suspend fun stopAll() {
        mutex.withLock {
            reconcileJob?.cancel()
            reconcileJob = null
            for (actor in actors.values) actor.stopAndJoin()
            actors.clear()
            actorFingerprints.clear()
            userIntents.clear()
            _connectionStates.value = emptyMap()
            _memberLoadStates.value = emptyMap()
        }
    }

    override suspend fun connect(networkId: Long) {
        val row = db.networkDao().byId(networkId) ?: return
        if (row.protocol != XmppChatBackend.XMPP_PROTOCOL.value) return
        userIntents[networkId] = true
        mutex.withLock {
            val existing = actors[networkId]
            // Manual connect overrides even a fatal (auth) park or stale credentials: the user may
            // have fixed creds, or just edited them (review fix — see [actorFingerprints]'s KDoc). A
            // live, healthy actor whose fingerprint is unchanged is left alone; [ensureActorLocked]
            // decides that from the fresh account row, so a live-but-edited actor still rebuilds here
            // instead of only reconcile ever noticing.
            ensureActorLocked(row, forceRebuild = existing == null || !existing.isAlive)
        }
    }

    override suspend fun disconnect(networkId: Long) {
        val row = db.networkDao().byId(networkId)
        if (row != null && row.protocol != XmppChatBackend.XMPP_PROTOCOL.value) return
        userIntents[networkId] = false
        mutex.withLock {
            actors.remove(networkId)?.stopAndJoin()
            actorFingerprints.remove(networkId)
            _connectionStates.update { it - networkId }
            clearMemberLoadStatesForNetwork(networkId)
        }
    }

    /**
     * Re-drive the wanted set against the current DB snapshot. A non-fatal failure never kills an
     * actor's job (it backs off and retries internally, see [XmppAccountActor]), so there is no
     * "dead but wanted" actor to revive here yet; a fatal auth park is intentionally left alone
     * (only an explicit [connect] retries it). Per-actor liveness probing of a Ready session, like
     * `:irc`'s foreground probe, is not yet built for XMPP and is not claimed here.
     */
    override suspend fun reconnectStale() {
        if (reconcileJob?.isActive != true) return
        reconcile(observeXmppNetworks().first())
    }

    /**
     * Re-drive the wanted set. Every wanted row — including one already tracked in [actors] —
     * always goes through [ensureActorLocked], which is what notices a credential edit even for a
     * row this manager already believes is connected (review fix: the previous
     * `actors.containsKey(row.id)` skip meant a JID/password/resource edit, or even a corrected
     * password after a fatal auth park, was never picked up until the user found some other way to
     * fully disconnect and reconnect). A fatal park with *unchanged* credentials is still never
     * revived here — [ensureActorLocked] only rebuilds on a fingerprint change, preserving the
     * existing "only an explicit [connect] retries an unchanged fatal park" contract.
     */
    private suspend fun reconcile(rows: List<NetworkEntity>) {
        mutex.withLock {
            val wantedIds = rows.filter(::wantConnected).mapTo(mutableSetOf()) { it.id }
            for (id in actors.keys.toList()) {
                if (id !in wantedIds) {
                    actors.remove(id)?.stopAndJoin()
                    actorFingerprints.remove(id)
                    _connectionStates.update { it - id }
                    clearMemberLoadStatesForNetwork(id)
                }
            }
            for (row in rows) {
                if (row.id !in wantedIds) continue
                ensureActorLocked(row)
            }
        }
    }

    /**
     * Create an actor for [row], or — when [forceRebuild] or the persisted [XmppAccountEntity] no
     * longer matches the fingerprint the existing actor was built from ([xmppAccountFingerprint]) —
     * tear down the stale one first. Caller must hold [mutex]. A missing detail row (corrupt/deleted
     * `xmpp_accounts` row) is skipped rather than crashing the whole reconcile.
     *
     * Mirrors `:irc` `ConnectionRegistry.ensureActor`'s fingerprint-triggered rebuild (see
     * [actorFingerprints]'s KDoc) rather than inventing a new mechanism: [reconcile] calls this with
     * [forceRebuild] = false for every wanted row (fingerprint-diff-only, so an unchanged fatal park
     * is still left alone), while [connect] passes true whenever the existing actor is absent or
     * dead (an explicit user action always overrides a fatal park, unchanged credentials or not).
     */
    private suspend fun ensureActorLocked(row: NetworkEntity, forceRebuild: Boolean = false) {
        val account = db.xmppAccountDao().byNetwork(row.id) ?: return
        val fingerprint = xmppAccountFingerprint(account)
        val existing = actors[row.id]
        if (existing != null) {
            if (!forceRebuild && actorFingerprints[row.id] == fingerprint) return
            existing.stopAndJoin()
            actors.remove(row.id)
            actorFingerprints.remove(row.id)
            clearMemberLoadStatesForNetwork(row.id)
        }
        val actor = XmppAccountActor(
            networkId = row.id,
            account = account,
            sessionFactory = sessionFactory,
            scope = scope,
            nextGeneration = sessionSeq::incrementAndGet,
            onState = ::publishState,
            onIncoming = processor::onIncomingDirectMessage,
            // Slice X6: incoming 1:1 typing is a bare pass-through, exactly like onRosterLoad below —
            // XmppProcessor routes it straight to the shared TypingTracker seam, an in-memory signal
            // this manager never touches (docs/backend-neutral-xmpp-rollout.md "one-to-one typing").
            onChatState = processor::onChatState,
            onMucMessage = processor::onMucMessage,
            onMucSubject = processor::onMucSubject,
            onMucOccupant = ::onMucOccupant,
            // Purely internal past this point (slice X5 correction): the account-level roster load
            // only drives XmppProcessor's UserEntity upserts and never touches the seam — see
            // XmppProcessor.onRosterLoad's KDoc — so this is a bare pass-through, not a manager wrapper.
            onRosterLoad = processor::onRosterLoad,
        )
        actors[row.id] = actor
        actorFingerprints[row.id] = fingerprint
        actor.start()
    }

    /**
     * [_connectionStates] always gets a fresh value per network id here. [_memberLoadStates] only
     * ever loses entries here — a non-Ready transition ("the session drops", in [memberLoadStates]'
     * KDoc terms) means every one of this network's CHANNEL buffers stops receiving MUC presence, so
     * their member-list state can no longer be trusted. [joinChannel]/[onMucOccupant]/[requestMembers]
     * (and, for a *reconnect*, [rejoinPersistedChannels] below) are what (re-)populate an entry once
     * a room is actually joined again.
     */
    private suspend fun publishState(networkId: Long, state: XmppSessionState, generation: Long) {
        _connectionStates.update { it + (networkId to state.toConnectionState(generation)) }
        if (state is XmppSessionState.Ready) {
            rejoinPersistedChannels(networkId)
        } else {
            clearMemberLoadStatesForNetwork(networkId)
        }
    }

    /**
     * Rejoin every CHANNEL buffer whose durable self-join state (`BufferEntity.joined`) is still
     * true once a fresh session reaches Ready (review fix; chosen over marking those buffers
     * unjoined/disabling their composer, mirroring `:irc` `ConnectionManagerImpl.onReady`'s
     * identical "a fresh direct socket has no channel membership" JOIN restore for its own DIRECT
     * rows). [XmppAccountActor] creates a brand-new [XmppSession] on every (re)connect attempt, so
     * without this, a session that reconnects after a drop has no MUC room membership at all even
     * though Room still marks these buffers joined: [SmackXmppSession.sendMessage] decides "is this
     * a room" purely from *its own* in-session `roomListeners` map (empty on every fresh session,
     * see that class's KDoc), so it would silently fall through and address the room as a
     * one-to-one chat stanza instead of a groupchat one.
     *
     * Each rejoin runs on [scope] rather than inline here, so a slow/unresponsive room (a MUC join
     * can wait up to a minute for a cold gateway, see [SmackXmppSession.joinRoom]'s KDoc) can never
     * block this state collector from noticing the *next* state transition — the same
     * fire-and-move-on shape `:irc`'s own JOIN restore already has (a one-way wire write there; a
     * backgrounded [joinChannel] call here, since a MUC join has no such fire-and-forget primitive
     * of its own). Reuses [joinChannel] itself — the exact same buffer-resolve/LOADING-publish/
     * `session.joinRoom` sequence a user-initiated join already runs — rather than a second,
     * duplicated rejoin path.
     */
    private suspend fun rejoinPersistedChannels(networkId: Long) {
        for (roomJid in db.bufferDao().joinedChannelNames(networkId)) {
            scope.launch { joinChannel(networkId, roomJid) }
        }
    }

    /**
     * The manager-owned half of the split [XmppProcessor.onMucOccupantEvent] documents: the
     * processor is the sole Room writer and reports back the buffer id it resolved/created; only a
     * [XmppMucOccupantEvent.Snapshot] — a complete, just-(re)loaded occupant list — advances that
     * buffer's [memberLoadStates] entry to `LOADED`. [XmppMucOccupantEvent.Joined]/`Left` are
     * incremental deltas against an already-loaded list and do not themselves change the load state.
     */
    private suspend fun onMucOccupant(networkId: Long, event: XmppMucOccupantEvent) {
        val bufferId = processor.onMucOccupantEvent(networkId, event)
        if (event is XmppMucOccupantEvent.Snapshot) {
            _memberLoadStates.update { it + (bufferId to RosterLoadState.LOADED) }
        }
    }

    /** Every CHANNEL buffer of [networkId] stops being trusted as "loaded": used both for an
     *  observed session drop ([publishState]) and an explicit teardown ([connect]/[disconnect]/
     *  [reconcile], none of which route through [publishState] — cancelling an actor's job unwinds
     *  past its final [XmppAccountActor.loop] state publication, exactly like [_connectionStates]
     *  already has to handle at each of those call sites). */
    private suspend fun clearMemberLoadStatesForNetwork(networkId: Long) {
        val channelIds = db.bufferDao().channelIds(networkId)
        if (channelIds.isNotEmpty()) _memberLoadStates.update { it - channelIds.toSet() }
    }

    // -- durable pending sends and send acknowledgements (slice X6; docs/backend-neutral-xmpp-rollout.md
    // baseline). Mirrors `:irc` ConnectionManagerImpl.sendMessage/retryMessage/writeDurablePlan's
    // decision structure: persist a durable pending row FIRST, then attempt the wire write, then
    // decide confirm-now / arm-a-timeout / fail-now from the outcome — never the other order. --

    /**
     * Accept a send for [bufferId] the way `:irc` `ConnectionManagerImpl.sendMessage` does:
     * durability precedes the wire. [XmppProcessor.persistOutgoingSend] persists the pending row
     * unconditionally, before this method has even looked at whether a live session exists, so a
     * send made while disconnected is still durably represented (visible, retryable) rather than
     * silently dropped. [writeAndTrack] then resolves the live session and decides confirm/arm/fail —
     * see its KDoc for the exact acknowledgement shape per buffer type.
     *
     * Only QUERY and CHANNEL buffers are sendable (a SERVER buffer, and any other future buffer type,
     * is rejected outright — mirroring IRC's identical SERVER guard); an empty body is
     * [io.github.trevarj.motd.service.SendRejectionReason.INVALID_CONTENT], matching what `:irc`'s
     * chunk-splitter would reject a same-shaped composer submission as. [replyToEventId] is honored
     * only when it names an event already in this same buffer (mirroring IRC's identical
     * cross-buffer guard in `sendMessage`); its `msgid` (if any) is carried onto the persisted row
     * for the shared reply-preview UI, but — unlike IRC — is never sent as a wire-level reply tag,
     * since XMPP has no such capability in this baseline (see [XmppProcessor.persistOutgoingSend]'s
     * KDoc).
     */
    override suspend fun sendMessage(
        bufferId: Long,
        text: String,
        replyToEventId: TimelineEventId?,
    ): SendAcceptance {
        val buffer = db.bufferDao().observeById(bufferId)
            ?: return SendAcceptance.Rejected(SendRejectionReason.BUFFER_NOT_FOUND)
        if (buffer.type != BufferType.QUERY && buffer.type != BufferType.CHANNEL) {
            return SendAcceptance.Rejected(SendRejectionReason.UNSUPPORTED_BUFFER)
        }
        if (text.isEmpty()) {
            return SendAcceptance.Rejected(SendRejectionReason.INVALID_CONTENT)
        }
        val account = db.xmppAccountDao().byNetwork(buffer.networkId)
            ?: return SendAcceptance.Rejected(SendRejectionReason.BUFFER_NOT_FOUND)
        val sender = if (buffer.type == BufferType.CHANNEL) mucNick(account) else account.jid
        val parent = replyToEventId
            ?.let { db.messageDao().byCanonicalId(it) }
            ?.takeIf { it.bufferId == buffer.id }

        val pending = withContext(NonCancellable) {
            processor.persistOutgoingSend(
                networkId = buffer.networkId,
                bufferId = buffer.id,
                sender = sender,
                text = text,
                replyToEventId = parent?.id,
                replyToMsgid = parent?.msgid,
                connectionGeneration = currentGeneration(buffer.networkId),
            )
        }
        return writeAndTrack(buffer, text, pending.label, pending.eventId)
    }

    /**
     * Retry a failed, still-unconfirmed self-send with a fresh attempt label (docs/backend-neutral-xmpp-rollout.md
     * baseline "send acknowledgements"; mirrors `:irc` `ConnectionManagerImpl.retryMessage` +
     * [io.github.trevarj.motd.data.sync.EventProcessor.beginRetry] through
     * [XmppProcessor.beginRetry]/[CanonicalTimelineStore.beginRetry]). [isRetryEligible] mirrors
     * IRC's [io.github.trevarj.motd.service.isGenericRetryEligible] minus its IRC-only checks
     * (BouncerServ, redacted text): only a failed, unconfirmed, still-self row in a real
     * conversation buffer may be retried. The resend then follows the exact same [writeAndTrack]
     * path a fresh [sendMessage] does.
     */
    override suspend fun retryMessage(eventId: TimelineEventId): SendAcceptance {
        val original = db.messageDao().byCanonicalId(eventId)
            ?: return SendAcceptance.Rejected(SendRejectionReason.EVENT_NOT_RETRYABLE)
        val buffer = db.bufferDao().observeById(original.bufferId)
            ?: return SendAcceptance.Rejected(SendRejectionReason.BUFFER_NOT_FOUND)
        if (!isRetryEligible(buffer, original)) {
            return SendAcceptance.Rejected(SendRejectionReason.EVENT_NOT_RETRYABLE)
        }
        val retried = withContext(NonCancellable) {
            processor.beginRetry(buffer.networkId, original.id, currentGeneration(buffer.networkId))
        } ?: return SendAcceptance.Rejected(SendRejectionReason.EVENT_NOT_RETRYABLE)
        return writeAndTrack(buffer, original.text, retried.label, retried.eventId)
    }

    /**
     * Resolve the live session and wire-write [text] labeled [label] for [eventId], deciding the
     * acknowledgement path from the outcome — the shared tail of [sendMessage]/[retryMessage],
     * mirroring how `:irc` `ConnectionManagerImpl` shares `writeDurablePlan` between both. The whole
     * decision (not just the persistence step before it) runs under [NonCancellable], mirroring
     * `:irc` `completeDurableAcceptance`'s KDoc: "Return durable acceptance even if the caller is
     * cancelled after the transaction commits" — once a pending row exists, a cancelled caller must
     * not leave it stranded with nothing left to resolve it.
     *
     * Decision table (docs/backend-neutral-xmpp-rollout.md baseline "durable pending sends and send
     * acknowledgements"):
     *  - no live/Ready session for [BufferEntity.networkId]: fail the row immediately — mirrors
     *    IRC's `writeDurablePlan` `client == null || ready == null -> failPendingEvents`, i.e. never
     *    the fork/xmpp-support prototype's blanket 30s watchdog for this case (a send that was never
     *    going to have a session to answer it should not make the user wait 30s to find out).
     *  - the wire write itself throws: fail the row immediately — mirrors IRC's
     *    `transmitDurableOutgoingPlan`'s `write`-step catch.
     *  - wire write succeeds, QUERY (1:1): confirm now — mirrors IRC's `!hasCap("echo-message")`
     *    path (`confirmIfStillPending`). A DM's own send is only ever echoed back via XEP-0280
     *    carbons, deferred past this baseline, so there is no reflection to wait for.
     *  - wire write succeeds, CHANNEL (MUC): do NOT confirm — arm a [armSendTimeout] watchdog and
     *    let the room's reflection reconcile the row through the normal incoming path instead (see
     *    [XmppProcessor.onMucMessage]'s KDoc) — mirrors IRC's `hasCap("echo-message")` path
     *    (`armEchoTimeout`).
     */
    private suspend fun writeAndTrack(
        buffer: BufferEntity,
        text: String,
        label: String,
        eventId: TimelineEventId,
    ): SendAcceptance = withContext(NonCancellable) {
        val eventIds = listOf(eventId)
        val session = actors[buffer.networkId]?.connection
        val ready = _connectionStates.value[buffer.networkId] as? ConnectionState.Ready
        if (session == null || ready == null) {
            db.messageDao().failPending(eventIds)
            return@withContext SendAcceptance.Accepted(eventIds, ImmediateWireAcceptance.DISCONNECTED)
        }
        val wireAcceptance = try {
            session.sendMessage(buffer.displayName, text, label)
            ImmediateWireAcceptance.ACCEPTED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ImmediateWireAcceptance.FAILED
        }
        if (wireAcceptance != ImmediateWireAcceptance.ACCEPTED) {
            db.messageDao().failPending(eventIds)
            return@withContext SendAcceptance.Accepted(eventIds, wireAcceptance)
        }
        when (buffer.type) {
            BufferType.QUERY -> db.messageDao().confirmIfStillPending(buffer.id, label)
            BufferType.CHANNEL -> armSendTimeout(buffer.id, label)
            BufferType.SERVER -> Unit
        }
        SendAcceptance.Accepted(eventIds)
    }

    /** Mirrors [io.github.trevarj.motd.service.isGenericRetryEligible] minus IRC-only checks
     *  (BouncerServ, redacted-text placeholders): a failed, still-unconfirmed self-send in a real
     *  conversation buffer. */
    private fun isRetryEligible(buffer: BufferEntity, message: MessageEntity): Boolean =
        message.isSelf && message.failed && message.msgid == null && buffer.type != BufferType.SERVER

    /**
     * MUC send-acknowledgement watchdog: mirrors `:irc` `ConnectionManagerImpl.armEchoTimeout`'s
     * shape (delay, then fail-if-still-pending) as a plain coroutine keyed in [pendingSendTimeouts],
     * rather than that class's `ConnectionRegistry` actor/command-channel machinery — see
     * [pendingSendTimeouts]'s KDoc for why a plain map is a safe, proportionate substitute here.
     */
    private fun armSendTimeout(bufferId: Long, label: String) {
        val key = "$bufferId:$label"
        pendingSendTimeouts.remove(key)?.cancel()
        // Labels are fresh per attempt, so this key is never re-armed while this job is still
        // in flight; an unconditional remove(key) is therefore always removing this same job.
        pendingSendTimeouts[key] = scope.launch {
            delay(SEND_TIMEOUT_MS)
            db.messageDao().failIfStillPending(bufferId, label)
            pendingSendTimeouts.remove(key)
        }
    }

    /** Best-effort current connection generation for [networkId], or null with no live Ready
     *  session — purely a diagnostic passenger on the persisted observation, exactly like `:irc`'s
     *  `connectionGenerations[networkId]` (never consulted by [CanonicalTimelineStore]'s own
     *  reconciliation, which identifies solely through aliases). */
    private fun currentGeneration(networkId: Long): Long? =
        (_connectionStates.value[networkId] as? ConnectionState.Ready)?.generation

    /**
     * Map the seam's IRC-shaped typing vocabulary (`:irc` `IrcClient.sendTyping`'s `+typing` tag
     * values, as sent by `ChatViewModel.sendTyping`/its recomposition-triggered "done": "active" on
     * composing, "done" on an emptied composer or right after a send; "paused" is accepted for
     * protocol completeness though nothing upstream sends it today) onto XEP-0085 chat states
     * (docs/backend-neutral-xmpp-rollout.md baseline "one-to-one typing where supported"):
     *  - "active" -> [XmppChatState.COMPOSING] (currently typing);
     *  - "paused" -> [XmppChatState.PAUSED] (was typing, paused);
     *  - "done" -> [XmppChatState.ACTIVE] (stopped typing, still in the conversation — XEP-0085's
     *    `active`, not `inactive`, which means the user left the conversation entirely, a presence/idle
     *    concept this baseline does not track).
     *
     * QUERY buffers only, matching XEP-0085's 1:1 chat-state scope — MUC typing is explicitly out of
     * this baseline (docs/backend-neutral-xmpp-rollout.md "one-to-one typing where supported"), and a
     * buffer with no live session is a silent no-op, mirroring every other buffer-scoped method here.
     */
    override suspend fun sendTyping(bufferId: Long, state: String) {
        val buffer = db.bufferDao().observeById(bufferId) ?: return
        if (buffer.type != BufferType.QUERY) return
        val session = actors[buffer.networkId]?.connection ?: return
        val chatState = when (state) {
            "active" -> XmppChatState.COMPOSING
            "paused" -> XmppChatState.PAUSED
            "done" -> XmppChatState.ACTIVE
            else -> return
        }
        session.sendChatState(buffer.displayName, chatState)
    }

    // arrives with slice X4/X6
    override suspend fun sendReact(bufferId: Long, msgid: String, emoji: String) = Unit

    /**
     * Join the MUC at [channel] (a bare room JID such as `room@conference.example.org` — XMPP has no
     * IRC-style channel-name convention, so shared/protocol-owned callers supply the room's address
     * directly). Presents the account's configured [XmppAccountEntity.resource] as the in-room
     * nickname when set, else the JID's localpart: this baseline slice has no dedicated
     * per-room-nickname UI yet, so it reuses whichever single identity the account already
     * configures. A future slice can add a real per-room nickname without changing this signature.
     *
     * Resolves (find-or-creates, via [XmppProcessor.ensureMucBuffer]) the room's buffer *before*
     * asking the session to join, so [memberLoadStates] can publish that buffer's entry as `LOADING`
     * immediately — mirroring IRC's self-JOIN transition to `LOADING` in `ConnectionManagerImpl`. A
     * rejected/timed-out join (see [XmppSession.joinRoom]'s KDoc) leaves the entry stuck at `LOADING`
     * rather than moving it to `FAILED`: this baseline has no join-failure signal to drive that
     * transition with (a known, narrower gap than the one Branch 1 already fixed here).
     */
    override suspend fun joinChannel(networkId: Long, channel: String) {
        val session = actors[networkId]?.connection ?: return
        val account = db.xmppAccountDao().byNetwork(networkId) ?: return
        val bufferId = processor.ensureMucBuffer(networkId, channel).id
        _memberLoadStates.update { it + (bufferId to RosterLoadState.LOADING) }
        session.joinRoom(channel, mucNick(account))
    }

    /** In-room nickname this account presents for MUC operations: the configured resource, else the
     *  bare JID's localpart (see [joinChannel]'s KDoc). Factored out (slice X6) so a MUC send's
     *  persisted `sender` — see [sendMessage] — matches the nick the room will later reflect it back
     *  under, exactly like the nick [joinChannel] already presents when entering the room. */
    private fun mucNick(account: XmppAccountEntity): String =
        account.resource?.takeIf(String::isNotBlank) ?: account.jid.substringBefore('@')

    /** Resolve [bufferId]'s room (a MUC buffer's `name` column is its bare room JID; see
     *  [XmppProcessor]) and leave it, dropping its [memberLoadStates] entry entirely (not just
     *  resetting it — this session receives no further presence for a room it explicitly left, so
     *  nothing will repopulate the entry until an explicit rejoin). A non-CHANNEL buffer, or a buffer
     *  with no live session, is a silent no-op — mirroring every other buffer-scoped method here. */
    override suspend fun partChannel(bufferId: Long, reason: String?) {
        val buffer = db.bufferDao().rawById(bufferId) ?: return
        if (buffer.type != BufferType.CHANNEL) return
        val session = actors[buffer.networkId]?.connection ?: return
        session.leaveRoom(buffer.name)
        processor.onLeftRoom(bufferId)
        _memberLoadStates.update { it - bufferId }
    }

    /**
     * Ask the live session to re-publish [bufferId]'s current MUC occupant list, publishing `LOADING`
     * immediately (mirroring IRC's explicit-refresh transition in `ConnectionManagerImpl`) until the
     * refreshed [XmppMucOccupantEvent.Snapshot] lands and [onMucOccupant] advances it to `LOADED`.
     * [force] is accepted for signature compatibility with [ConnectionManager.requestMembers] but
     * unused: unlike IRC's NAMES (a real wire round-trip worth deduping while one is already in
     * flight), a MUC occupant refresh only re-emits [XmppSession]'s already-live, Smack-cached
     * occupant list — cheap enough that there is no in-flight request to dedupe against.
     */
    override suspend fun requestMembers(bufferId: Long, force: Boolean) {
        val buffer = db.bufferDao().rawById(bufferId) ?: return
        if (buffer.type != BufferType.CHANNEL) return
        val session = actors[buffer.networkId]?.connection ?: return
        _memberLoadStates.update { it + (bufferId to RosterLoadState.LOADING) }
        session.refreshOccupants(buffer.name)
    }

    /**
     * Find-or-create the QUERY buffer for [nick] (a bare JID here, not an IRC nick — the seam's
     * shared vocabulary; see [ConnectionManager.ensureQueryBuffer]'s KDoc). Review fix: shared UI
     * (`/msg`, `/query`, "Message user") reaches this for XMPP networks the exact same way it does
     * for IRC ones, so this used to `error()` from viewModelScope instead of opening a buffer.
     * Delegates to [XmppProcessor.ensureQueryBuffer] — the identical find-or-create idiom an
     * incoming DM/chat-state already uses — rather than a second, duplicated implementation.
     */
    override suspend fun ensureQueryBuffer(networkId: Long, nick: String): Long =
        processor.ensureQueryBuffer(networkId, nick).id

    /**
     * Find-or-create the per-network SERVER buffer (review fix; see [ensureQueryBuffer]'s KDoc —
     * the drawer's "Server messages" entry reaches this for XMPP networks too). Mirrors `:irc`
     * `ConnectionManagerImpl.ensureServerBuffer`'s displayName fallback (the network row's own
     * name, else "Server") through [XmppProcessor.ensureServerBuffer].
     */
    override suspend fun ensureServerBuffer(networkId: Long): Long =
        processor.ensureServerBuffer(networkId, db.networkDao().byId(networkId)?.name ?: "Server").id

    /**
     * Advance the local read anchor through the exact shared DAO path `:irc`
     * [io.github.trevarj.motd.service.ConnectionManagerImpl.markRead] uses
     * ([io.github.trevarj.motd.service.resolveAndAdvanceCurrentReadTarget]) — review fix: this used
     * to be a no-op, so XMPP conversations never cleared unread and "mark all read" did nothing for
     * them. Read-anchor advancement is backend-neutral (docs/backend-neutral-xmpp-rollout.md); only
     * IRC's own wire MARKREAD send (and its read-marker-capability/authoritative-boundary gating)
     * is IRC-specific, and is deliberately not mirrored here — XMPP has no wire read-marker in this
     * baseline (XEP-0333 is in the deferred cross-device list), so there is nothing to send.
     */
    override suspend fun markRead(bufferId: Long, anchor: TimelineAnchor) {
        resolveAndAdvanceCurrentReadTarget(db, bufferId, anchor)
    }

    // arrives with slice X4/X6
    override suspend fun evaluatePushMode() = Unit

    // arrives with slice X4/X6
    override val certPrompts: StateFlow<List<CertPrompt>> = MutableStateFlow(emptyList())

    // arrives with slice X4/X6
    override suspend fun trustCert(prompt: CertPrompt) = Unit

    // arrives with slice X4/X6
    override fun dismissCertPrompt(prompt: CertPrompt) = Unit

    private companion object {
        /** MUC send-acknowledgement watchdog (slice X6); matches both `:irc`
         *  `ConnectionManagerImpl.ECHO_TIMEOUT_MS` and the fork/xmpp-support prototype's
         *  `XmppAccountActor.SEND_TIMEOUT_MS`. */
        const val SEND_TIMEOUT_MS = 30_000L
    }
}

/**
 * Connection-affecting XMPP account fields, mirroring `:irc`'s
 * [io.github.trevarj.motd.service.networkFingerprint] (review fix: "Make a credential change
 * rebuild the actor... the IRC side solves this with a configuration fingerprint"). Only
 * [XmppAccountEntity.jid]/[XmppAccountEntity.password]/[XmppAccountEntity.resource] are ever read
 * by a live session ([SmackXmppSession]'s KDoc), so those three — and nothing else on the row —
 * are exactly what a credential edit must be detected from. [XmppConnectionManager.ensureActorLocked]
 * compares this against the fingerprint its live actor was built from and rebuilds on a mismatch,
 * regardless of whether that actor is currently alive.
 */
internal fun xmppAccountFingerprint(account: XmppAccountEntity): String =
    "${account.jid}:${account.password}:${account.resource}"
