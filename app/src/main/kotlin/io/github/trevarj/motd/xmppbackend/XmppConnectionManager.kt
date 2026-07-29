package io.github.trevarj.motd.xmppbackend

import io.github.trevarj.motd.backend.ConnectionState
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.TimelineEventId
import io.github.trevarj.motd.data.db.XmppAccountEntity
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.RosterLoadState
import io.github.trevarj.motd.service.SendAcceptance
import io.github.trevarj.motd.service.SendRejectionReason
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * MUC member-load state ([memberLoadStates]). Everything else — sendMessage, typing, react,
 * query/server buffers, markRead, history, cert prompts — is still out of scope and returns the same
 * inert rejection/no-op [InertConnectionManager][io.github.trevarj.motd.backend.InertConnectionManager]
 * uses, pending later slices.
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

    /** Sticky manual override of autoConnect: true = force-connect, false = force-disconnect,
     *  absent = follow the row's autoConnect flag. Survives reconcile emissions, cleared by stopAll. */
    private val userIntents = ConcurrentHashMap<Long, Boolean>()

    /** Manager-global monotonic session identity (mirrors `:irc` ConnectionRegistry's sessionSeq):
     *  every new connection attempt anywhere gets the next value, so [ConnectionState.Ready] always
     *  carries a fresh generation even across a transparent actor-owned reconnect. */
    private val sessionSeq = AtomicLong(0)

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
            if (existing != null && existing.isAlive) return@withLock
            // Manual connect overrides even a fatal (auth) park: the user may have fixed creds.
            existing?.stopAndJoin()
            actors.remove(networkId)
            if (existing != null) clearMemberLoadStatesForNetwork(networkId)
            ensureActorLocked(row)
        }
    }

    override suspend fun disconnect(networkId: Long) {
        val row = db.networkDao().byId(networkId)
        if (row != null && row.protocol != XmppChatBackend.XMPP_PROTOCOL.value) return
        userIntents[networkId] = false
        mutex.withLock {
            actors.remove(networkId)?.stopAndJoin()
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

    private suspend fun reconcile(rows: List<NetworkEntity>) {
        mutex.withLock {
            val wantedIds = rows.filter(::wantConnected).mapTo(mutableSetOf()) { it.id }
            for (id in actors.keys.toList()) {
                if (id !in wantedIds) {
                    actors.remove(id)?.stopAndJoin()
                    _connectionStates.update { it - id }
                    clearMemberLoadStatesForNetwork(id)
                }
            }
            for (row in rows) {
                if (row.id !in wantedIds || actors.containsKey(row.id)) continue
                ensureActorLocked(row)
            }
        }
    }

    /** Create, register, and start an actor for [row]. Caller must hold [mutex]. A missing detail
     *  row (corrupt/deleted `xmpp_accounts` row) is skipped rather than crashing the whole reconcile. */
    private suspend fun ensureActorLocked(row: NetworkEntity) {
        if (actors.containsKey(row.id)) return
        val account = db.xmppAccountDao().byNetwork(row.id) ?: return
        val actor = XmppAccountActor(
            networkId = row.id,
            account = account,
            sessionFactory = sessionFactory,
            scope = scope,
            nextGeneration = sessionSeq::incrementAndGet,
            onState = ::publishState,
            onIncoming = processor::onIncomingDirectMessage,
            onMucMessage = processor::onMucMessage,
            onMucSubject = processor::onMucSubject,
            onMucOccupant = ::onMucOccupant,
            // Purely internal past this point (slice X5 correction): the account-level roster load
            // only drives XmppProcessor's UserEntity upserts and never touches the seam — see
            // XmppProcessor.onRosterLoad's KDoc — so this is a bare pass-through, not a manager wrapper.
            onRosterLoad = processor::onRosterLoad,
        )
        actors[row.id] = actor
        actor.start()
    }

    /**
     * [_connectionStates] always gets a fresh value per network id here. [_memberLoadStates] only
     * ever loses entries here — a non-Ready transition ("the session drops", in [memberLoadStates]'
     * KDoc terms) means every one of this network's CHANNEL buffers stops receiving MUC presence, so
     * their member-list state can no longer be trusted. [joinChannel]/[onMucOccupant]/[requestMembers]
     * are what (re-)populate an entry once a room is actually joined again.
     */
    private suspend fun publishState(networkId: Long, state: XmppSessionState, generation: Long) {
        _connectionStates.update { it + (networkId to state.toConnectionState(generation)) }
        if (state !is XmppSessionState.Ready) clearMemberLoadStatesForNetwork(networkId)
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

    // -- Out of scope for this slice: same inert contract as InertConnectionManager. --

    // arrives with slice X4/X6
    override suspend fun sendMessage(
        bufferId: Long,
        text: String,
        replyToEventId: TimelineEventId?,
    ): SendAcceptance = SendAcceptance.Rejected(SendRejectionReason.BUFFER_NOT_FOUND)

    // arrives with slice X4/X6
    override suspend fun sendTyping(bufferId: Long, state: String) = Unit

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
        val nick = account.resource?.takeIf(String::isNotBlank) ?: account.jid.substringBefore('@')
        val bufferId = processor.ensureMucBuffer(networkId, channel).id
        _memberLoadStates.update { it + (bufferId to RosterLoadState.LOADING) }
        session.joinRoom(channel, nick)
    }

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

    // arrives with slice X4/X6
    override suspend fun ensureQueryBuffer(networkId: Long, nick: String): Long =
        error("XMPP buffers are not implemented yet (docs/backend-neutral-xmpp-rollout.md)")

    // arrives with slice X4/X6
    override suspend fun ensureServerBuffer(networkId: Long): Long =
        error("XMPP buffers are not implemented yet (docs/backend-neutral-xmpp-rollout.md)")

    // arrives with slice X4/X6
    override suspend fun markRead(bufferId: Long, anchor: TimelineAnchor) = Unit

    // arrives with slice X4/X6
    override suspend fun evaluatePushMode() = Unit

    // arrives with slice X4/X6
    override val certPrompts: StateFlow<List<CertPrompt>> = MutableStateFlow(emptyList())

    // arrives with slice X4/X6
    override suspend fun trustCert(prompt: CertPrompt) = Unit

    // arrives with slice X4/X6
    override fun dismissCertPrompt(prompt: CertPrompt) = Unit
}
