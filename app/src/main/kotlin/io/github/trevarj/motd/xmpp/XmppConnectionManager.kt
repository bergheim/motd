package io.github.trevarj.motd.xmpp

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.Protocol
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.SendAcceptance
import io.github.trevarj.motd.service.SendRejectionReason
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jivesoftware.smack.android.AndroidSmackInitializer

/**
 * XMPP counterpart of the IRC `ConnectionManagerImpl`: it owns one [XmppAccountActor] per
 * auto-connectable XMPP network row and reconciles that live set against `networkDao.observeAll()`.
 * IRC rows are ignored entirely — this manager only ever touches `protocol == Protocol.XMPP` rows.
 *
 * State-map consistency: every actor publishes its [IrcClientState] through the [publishState]
 * callback, which folds it into [_connectionStates] with an atomic `update {}`. Structural changes
 * to the actor set (create/stop/restart) are serialized by [mutex], so a reconcile emission never
 * races a manual connect/disconnect.
 */
@Singleton
class XmppConnectionManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val db: MotdDatabase,
    private val processor: XmppEventProcessor,
    private val sessionFactory: XmppSessionFactory,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val actors = ConcurrentHashMap<Long, XmppAccountActor>()

    // Sticky manual override of autoConnect: true = force-connect, false = force-disconnect,
    // absent = follow the row's autoConnect flag. Survives reconcile emissions.
    private val userIntents = ConcurrentHashMap<Long, Boolean>()

    private val _connectionStates = MutableStateFlow<Map<Long, IrcClientState>>(emptyMap())
    val connectionStates: StateFlow<Map<Long, IrcClientState>> = _connectionStates.asStateFlow()

    private val mutex = Mutex()
    private var reconcileJob: Job? = null

    init {
        // Registers Smack's Android providers/DNS; no-op-safe under Robolectric (faked factory).
        runCatching { AndroidSmackInitializer.initialize(appContext) }
    }

    suspend fun startAll() {
        mutex.withLock {
            if (reconcileJob?.isActive == true) return@withLock
            reconcileJob = scope.launch {
                db.networkDao().observeAll().collect { all -> reconcile(all) }
            }
        }
    }

    suspend fun stopAll() {
        mutex.withLock {
            reconcileJob?.cancel()
            reconcileJob = null
            for ((_, actor) in actors) actor.stop()
            actors.clear()
            _connectionStates.value = emptyMap()
        }
    }

    suspend fun connect(networkId: Long) {
        val entity = db.networkDao().byId(networkId) ?: return
        if (entity.protocol != Protocol.XMPP) return
        userIntents[networkId] = true
        mutex.withLock {
            val existing = actors[networkId]
            if (existing == null) {
                spawnActor(entity)
            } else if (existing.state.value.isTerminal()) {
                // Manual connect overrides even a fatal (auth) park — the user may have fixed creds.
                existing.restart()
            }
        }
    }

    suspend fun disconnect(networkId: Long) {
        // Same guard as connect(): a non-XMPP (or vanished) row is not ours to touch.
        if (db.networkDao().byId(networkId)?.protocol != Protocol.XMPP) return
        userIntents[networkId] = false
        mutex.withLock {
            actors.remove(networkId)?.stop()
            _connectionStates.update { it - networkId }
        }
    }

    /** Wake any actor parked in a non-fatal Failed/Disconnected state; leave healthy ones alone. */
    suspend fun reconnectStale() {
        mutex.withLock {
            for ((_, actor) in actors) {
                if (actor.state.value.isReconnectable()) actor.restart()
            }
        }
    }

    suspend fun sendMessage(bufferId: Long, text: String): SendAcceptance {
        val buffer = db.bufferDao().rawById(bufferId)
            ?: return SendAcceptance.Rejected(SendRejectionReason.BUFFER_NOT_FOUND)
        if (buffer.type != BufferType.CHANNEL && buffer.type != BufferType.QUERY) {
            return SendAcceptance.Rejected(SendRejectionReason.UNSUPPORTED_BUFFER)
        }
        val actor = actors[buffer.networkId]
            ?: return SendAcceptance.Rejected(SendRejectionReason.BUFFER_NOT_FOUND)
        return actor.sendMessage(buffer, text)
    }

    suspend fun sendTyping(bufferId: Long, state: String) {
        val buffer = db.bufferDao().rawById(bufferId) ?: return
        // Chat-state notifications only make sense for 1:1 QUERY conversations.
        if (buffer.type != BufferType.QUERY) return
        val actor = actors[buffer.networkId] ?: return
        actor.sendTyping(buffer, composing = state == "active")
    }

    suspend fun joinChannel(networkId: Long, roomJid: String) {
        actors[networkId]?.joinChannel(roomJid)
    }

    suspend fun partChannel(bufferId: Long, reason: String?) {
        val buffer = db.bufferDao().rawById(bufferId) ?: return
        if (buffer.type != BufferType.CHANNEL) return
        actors[buffer.networkId]?.leaveMuc(buffer.name)
        // Explicit-leave semantics: drop membership and advance the cycle directly, so a later
        // reconnect JOIN replay lands in a fresh membership cycle rather than resurrecting this one.
        db.bufferDao().setJoined(bufferId, false)
        db.bufferDao().advanceMembershipCycle(bufferId)
    }

    suspend fun ensureQueryBuffer(networkId: Long, bareJid: String): Long {
        requireXmpp(networkId)
        return processor.ensureQueryBuffer(networkId, bareJid)
    }

    suspend fun ensureServerBuffer(networkId: Long): Long {
        requireXmpp(networkId)
        return processor.ensureServerBuffer(networkId)
    }

    /**
     * These entry points return a buffer id, so a silent wrong answer for a non-XMPP row is worse
     * than failing fast: guard by protocol and reject with [IllegalArgumentException].
     */
    private suspend fun requireXmpp(networkId: Long) {
        val protocol = db.networkDao().byId(networkId)?.protocol
        require(protocol == Protocol.XMPP) {
            "ensure*Buffer is XMPP-only; network $networkId is $protocol"
        }
    }

    // ---- internal reconciliation (always called while holding [mutex]) ----

    private suspend fun reconcile(all: List<NetworkEntity>) {
        mutex.withLock {
            val wanted = all
                .filter { it.protocol == Protocol.XMPP && wantConnected(it) }
                .associateBy { it.id }
            for (id in actors.keys.toList()) {
                if (id !in wanted) {
                    actors.remove(id)?.stop()
                    _connectionStates.update { it - id }
                }
            }
            for ((id, entity) in wanted) {
                if (actors.containsKey(id)) continue
                spawnActor(entity)
            }
        }
    }

    private fun wantConnected(n: NetworkEntity): Boolean = userIntents[n.id] ?: n.autoConnect

    /** Create, register, and start an actor for [n]. Caller must hold [mutex]. */
    private fun spawnActor(n: NetworkEntity) {
        val actor = XmppAccountActor(
            networkId = n.id,
            config = XmppAccountConfig(
                bareJid = n.jid!!,
                password = n.saslPassword.orEmpty(),
                host = n.host,
                port = n.port,
                directTls = n.tls,
                mucNick = n.nick,
            ),
            db = db,
            processor = processor,
            sessionFactory = sessionFactory,
            scope = scope,
            onState = ::publishState,
        )
        actors[n.id] = actor
        actor.start()
    }

    private fun publishState(networkId: Long, state: IrcClientState) {
        _connectionStates.update { it + (networkId to state) }
    }

    /** reconnectStale: non-fatal parked actors only (a fatal auth park needs an explicit connect). */
    private fun IrcClientState.isReconnectable(): Boolean =
        this is IrcClientState.Disconnected ||
            (this is IrcClientState.Failed && !fatal)

    /** connect: any non-live state (fatal park included) is eligible for a manual redial. */
    private fun IrcClientState.isTerminal(): Boolean =
        this is IrcClientState.Disconnected || this is IrcClientState.Failed
}
