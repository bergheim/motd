package io.github.trevarj.motd.xmppbackend

import io.github.trevarj.motd.backend.ConnectionState
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.TimelineEventId
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ConnectionManager
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
 * Everything outside connect/reconnect/backoff and state reporting (sendMessage, typing, react,
 * buffers, markRead, history, cert prompts) is out of scope for this slice and returns the same
 * inert rejection/no-op [InertConnectionManager][io.github.trevarj.motd.backend.InertConnectionManager]
 * uses, pending later slices.
 */
@Singleton
class XmppConnectionManager @Inject constructor(
    private val db: MotdDatabase,
    private val sessionFactory: XmppSessionFactory,
    @ApplicationScope private val scope: CoroutineScope,
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
        )
        actors[row.id] = actor
        actor.start()
    }

    private fun publishState(networkId: Long, state: XmppSessionState, generation: Long) {
        _connectionStates.update { it + (networkId to state.toConnectionState(generation)) }
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

    // arrives with slice X4/X6
    override suspend fun joinChannel(networkId: Long, channel: String) = Unit

    // arrives with slice X4/X6
    override suspend fun partChannel(bufferId: Long, reason: String?) = Unit

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
