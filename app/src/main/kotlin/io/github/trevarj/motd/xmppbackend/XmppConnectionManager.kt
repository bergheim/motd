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
 * XMPP roster-load state ([rosterStates]). Everything else — sendMessage, typing, react, query/server
 * buffers, markRead, history, cert prompts — is still out of scope and returns the same inert
 * rejection/no-op [InertConnectionManager][io.github.trevarj.motd.backend.InertConnectionManager]
 * uses, pending later slices.
 *
 * Every live session's [XmppSession.incomingMessages]/`incomingMucMessages`/`mucSubjects`/
 * `mucOccupants`/`rosterLoad` is wired to [XmppProcessor] through the actor it runs on
 * ([ensureActorLocked]); this manager itself only ever writes the in-memory [_connectionStates] and
 * [_rosterStates] maps, never Room timeline/member/user state
 * (docs/backend-neutral-xmpp-rollout.md "Persistence and writer ownership").
 *
 * **Known seam gap (flagged for Branch 1, not worked around here):** [ConnectionManager.rosterStates]
 * is `Map<Long, RosterLoadState>` with exactly one established scope today — IRC's
 * `service.ConnectionManagerImpl` publishes it **per buffer id** (a channel's NAMES-load state, read
 * by `ChatViewModel`/`ChannelInfoViewModel` as `rosterStates[bufferId]`). XMPP's roster is an
 * account-level buddy list with no per-buffer meaning, so this class publishes it **per network id**
 * instead — there is no seam concept for "which scope this map uses" to pick from. Both scopes share
 * one `Long` key space through `CompositeConnectionManager`'s `union(maps) = maps.fold(emptyMap()) {
 * acc, map -> acc + map }`, and `networks`/`buffers` are independent autoincrement id sequences, so a
 * real installation with both an IRC and an XMPP network risks a numeric key collision where one
 * backend's entry silently overwrites the other's (backends are folded in `protocol.value` sorted
 * order, so "xmpp" always wins a collision over "irc"). This needs a Branch 1 fix — e.g. splitting
 * `rosterStates` into a genuinely buffer-scoped field and a separate network-scoped one, or a typed
 * key — not a per-backend workaround.
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
     * XMPP roster (buddy-list) load state per network id (slice X5) — NOT per buffer id. This
     * deliberately reuses [ConnectionManager.rosterStates]' `Map<Long, RosterLoadState>` shape for a
     * differently-scoped fact than IRC publishes there (see this class's KDoc "Known seam gap").
     */
    private val _rosterStates = MutableStateFlow<Map<Long, RosterLoadState>>(emptyMap())
    override val rosterStates: StateFlow<Map<Long, RosterLoadState>> = _rosterStates.asStateFlow()

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
            _rosterStates.value = emptyMap()
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
            _rosterStates.update { it - networkId }
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
                    _rosterStates.update { it - id }
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
            onMucOccupant = processor::onMucOccupantEvent,
            onRosterLoad = ::publishRosterLoad,
        )
        actors[row.id] = actor
        actor.start()
    }

    private fun publishState(networkId: Long, state: XmppSessionState, generation: Long) {
        _connectionStates.update { it + (networkId to state.toConnectionState(generation)) }
        // Roster loading starts only once the session is Ready (see SmackXmppSession.connect); any
        // other phase — including a mid-session drop back to Disconnected/Failed — means nothing is
        // currently loaded for this network.
        val rosterState = if (state is XmppSessionState.Ready) RosterLoadState.LOADING else RosterLoadState.NOT_LOADED
        _rosterStates.update { it + (networkId to rosterState) }
    }

    /** Composes the two independent consumers of one roster-load outcome: this manager's own
     *  [rosterStates] signal, and [XmppProcessor]'s [UserEntity][io.github.trevarj.motd.data.db.UserEntity]
     *  persistence — mirrors how [ensureActorLocked] wires [onState] (manager-only) alongside
     *  [onIncoming] (processor-only) for the DM path, just combined into one callback here because
     *  both consumers need the same single roster-load event. */
    private suspend fun publishRosterLoad(networkId: Long, load: XmppRosterLoad) {
        val rosterState = if (load is XmppRosterLoad.Loaded) RosterLoadState.LOADED else RosterLoadState.FAILED
        _rosterStates.update { it + (networkId to rosterState) }
        processor.onRosterLoad(networkId, load)
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
     */
    override suspend fun joinChannel(networkId: Long, channel: String) {
        val session = actors[networkId]?.connection ?: return
        val account = db.xmppAccountDao().byNetwork(networkId) ?: return
        val nick = account.resource?.takeIf(String::isNotBlank) ?: account.jid.substringBefore('@')
        session.joinRoom(channel, nick)
    }

    /** Resolve [bufferId]'s room (a MUC buffer's `name` column is its bare room JID; see
     *  [XmppProcessor]) and leave it. A non-CHANNEL buffer, or a buffer with no live session, is a
     *  silent no-op — mirroring every other buffer-scoped method in this class. */
    override suspend fun partChannel(bufferId: Long, reason: String?) {
        val buffer = db.bufferDao().rawById(bufferId) ?: return
        if (buffer.type != BufferType.CHANNEL) return
        val session = actors[buffer.networkId]?.connection ?: return
        session.leaveRoom(buffer.name)
        processor.onLeftRoom(bufferId)
    }

    /**
     * Ask the live session to re-publish [bufferId]'s current MUC occupant list. [force] is accepted
     * for signature compatibility with [ConnectionManager.requestMembers] but unused: unlike IRC's
     * NAMES (a real wire round-trip worth deduping while one is already in flight), a MUC occupant
     * refresh only re-emits [XmppSession]'s already-live, Smack-cached occupant list — cheap enough
     * that there is no in-flight request to dedupe against.
     */
    override suspend fun requestMembers(bufferId: Long, force: Boolean) {
        val buffer = db.bufferDao().rawById(bufferId) ?: return
        if (buffer.type != BufferType.CHANNEL) return
        val session = actors[buffer.networkId]?.connection ?: return
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
