package io.github.trevarj.motd.xmpp

import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.SendAcceptance
import io.github.trevarj.motd.service.SendRejectionReason
import java.util.UUID
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One actor per auto-connectable XMPP network row. Owns the whole connect lifecycle for that
 * network: a fresh [XmppSession] per attempt, a single event-consuming coroutine that funnels
 * every [XmppEvent] into [XmppEventProcessor.process] sequentially, exponential reconnect backoff,
 * and the outbound send/typing/MUC control surface.
 *
 * Concurrency contract:
 * - Exactly one coroutine ([job], running [runConnectLoop]) creates sessions, consumes
 *   `session.events`, and mutates connection state. Because that loop is the only reader of the
 *   channel and the only caller of [XmppEventProcessor.process] for this network, event ordering is
 *   strictly sequential — the same invariant the IRC `ConnectionActor` relies on.
 * - Outbound calls ([sendMessage]/[sendTyping]/[joinChannel]/[leaveMuc]) run on the manager's
 *   coroutine and only read the volatile [session] reference; the durable pending row is written by
 *   the processor first, so a lost/late wire write degrades to a normal send-timeout, never a crash.
 */
internal class XmppAccountActor(
    private val networkId: Long,
    private val config: XmppAccountConfig,
    private val db: MotdDatabase,
    private val processor: XmppEventProcessor,
    private val sessionFactory: XmppSessionFactory,
    private val scope: CoroutineScope,
    private val onState: (Long, IrcClientState) -> Unit,
) {
    private val _state = MutableStateFlow<IrcClientState>(IrcClientState.Disconnected)
    val state: StateFlow<IrcClientState> get() = _state

    @Volatile private var session: XmppSession? = null
    @Volatile private var job: Job? = null

    /** Consecutive non-fatal reconnect attempts; reset to 0 on every successful [XmppEvent.Ready]. */
    private var attempt = 0

    private fun setState(next: IrcClientState) {
        _state.value = next
        onState(networkId, next)
    }

    fun start() {
        if (job?.isActive == true) return
        attempt = 0
        job = scope.launch { runConnectLoop() }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        withContext(NonCancellable) { session?.close() }
        session = null
        setState(IrcClientState.Disconnected)
    }

    /** Manual/foreground redial: tear down any current attempt and start a fresh loop from zero. */
    suspend fun restart() {
        job?.cancelAndJoin()
        withContext(NonCancellable) { session?.close() }
        session = null
        attempt = 0
        job = scope.launch { runConnectLoop() }
    }

    private suspend fun runConnectLoop() {
        while (currentCoroutineContext().isActive) {
            // Clean-reconnect rule: any row still pending from the previous session can never be
            // confirmed by the new one, so fail them all BEFORE the fresh login.
            processor.failAllPending(networkId)
            val current = sessionFactory.create(config)
            session = current
            setState(IrcClientState.Connecting)
            val outcome = try {
                current.connectAndLogin()
                consumeEvents(current)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: XmppAuthException) {
                // Rejected credentials never fix themselves; park instead of retrying.
                Outcome.Fatal(e.message ?: "Wrong address or password")
            } catch (e: Exception) {
                Outcome.Retry(e.message)
            }
            withContext(NonCancellable) { runCatching { current.close() } }
            session = null
            when (outcome) {
                is Outcome.Fatal -> {
                    // Auth-class failure: park until an explicit connect()/reconnectStale().
                    setState(IrcClientState.Failed(outcome.reason, fatal = true))
                    return
                }
                is Outcome.Retry -> {
                    setState(IrcClientState.Failed(outcome.reason ?: "disconnected", fatal = false))
                    delay(backoffDelayMs(attempt))
                    attempt++
                }
            }
        }
    }

    /** Drain [session] events into the processor until a disconnect ends this attempt. */
    private suspend fun consumeEvents(current: XmppSession): Outcome {
        for (event in current.events) {
            processor.process(networkId, event)
            when (event) {
                is XmppEvent.Ready -> {
                    attempt = 0
                    setState(
                        IrcClientState.Ready(
                            nick = event.selfBareJid,
                            caps = emptySet(),
                            isupport = emptyMap(),
                        ),
                    )
                    rejoinChannels(current)
                }
                is XmppEvent.Disconnected ->
                    return if (event.fatal) {
                        Outcome.Fatal(event.reason ?: "authentication failed")
                    } else {
                        Outcome.Retry(event.reason)
                    }
                else -> Unit
            }
        }
        // Channel closed without an explicit Disconnected event: treat as a retryable drop.
        return Outcome.Retry(null)
    }

    private suspend fun rejoinChannels(current: XmppSession) {
        for (buffer in db.bufferDao().joinedChannels(networkId)) {
            // Guard each rejoin like the user-initiated join path: a bare joinMuc can throw
            // (NoResponseException, XMPPErrorException, …) and, because this runs after Ready has
            // already reset the attempt counter, an unguarded throw would propagate out of
            // consumeEvents and trigger a full reconnect — one bad room would loop the whole account.
            // swallowTransport degrades that to "this room did not rejoin" while the rest proceed.
            swallowTransport { current.joinMuc(buffer.name, config.mucNick) }
        }
    }

    /** base 1s, doubling per attempt, capped at 60s (1s,2s,4s,…,60s). */
    fun backoffDelayMs(attemptCount: Int): Long {
        val exp = attemptCount.coerceIn(0, MAX_BACKOFF_SHIFT)
        return min(MAX_BACKOFF_MS, BASE_BACKOFF_MS shl exp)
    }

    suspend fun sendMessage(buffer: BufferEntity, text: String): SendAcceptance {
        val originId = UUID.randomUUID().toString()
        val eventId = processor.createPending(networkId, buffer.id, text, originId)
            ?: return SendAcceptance.Rejected(SendRejectionReason.BUFFER_NOT_FOUND)
        val current = session
        if (current != null) {
            try {
                when (buffer.type) {
                    BufferType.CHANNEL -> current.sendMuc(buffer.name, text, originId)
                    else -> current.sendChat(buffer.name, text, originId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Durable row already exists; the send-timeout below flips it to failed.
            }
        }
        // Independent 30s watchdog: fail the row only if it is still pending (no SendConfirmed /
        // MUC reflection has cleared it). Idempotent, so an already-confirmed row is untouched.
        // Intentionally launched on the shared scope (not the actor job) so it outlives an actor
        // stop/restart: failPending is idempotent at the DB level (byPendingLabel finds nothing once
        // the row is confirmed/failed), so a watchdog surviving a reconnect can never corrupt state.
        scope.launch {
            delay(SEND_TIMEOUT_MS)
            processor.failPending(networkId, originId)
        }
        return SendAcceptance.Accepted(listOf(eventId))
    }

    suspend fun sendTyping(buffer: BufferEntity, composing: Boolean) {
        val current = session ?: return
        swallowTransport { current.sendChatState(buffer.name, composing) }
    }

    suspend fun joinChannel(roomJid: String) {
        val normalized = normalizeJid(roomJid)
        ensureMucBuffer(normalized)
        swallowTransport { session?.joinMuc(normalized, config.mucNick) }
    }

    suspend fun leaveMuc(roomJid: String) {
        swallowTransport { session?.leaveMuc(roomJid) }
    }

    /**
     * Channel-browser MUC discovery; no live session (not yet connected/dropped) means no rooms.
     * Reads the volatile [session] like every other outbound call (see the concurrency contract in
     * the class KDoc): a session closed mid-discovery degrades to an empty/failed listing inside
     * [XmppSession.listRooms]'s own catch-all — never a crash — which is acceptable for a browse.
     */
    suspend fun listRooms(): List<MucRoomListing> = session?.listRooms() ?: emptyList()

    /** Run a best-effort wire write: a dead/closed transport must not crash the caller, but
     *  cancellation must still propagate (unlike [runCatching], which would swallow it). */
    private inline fun swallowTransport(block: () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Best effort: the durable Room state is the source of truth, not the wire write.
        }
    }

    /** Find-or-create the CHANNEL buffer; [joined] stays false until a [XmppEvent.MucSelfJoined]. */
    private suspend fun ensureMucBuffer(roomJid: String): Long {
        val bufferDao = db.bufferDao()
        bufferDao.byName(networkId, roomJid)?.let { return it.id }
        val insertedId = bufferDao.insertIgnore(
            BufferEntity(
                networkId = networkId,
                name = roomJid,
                displayName = roomJid,
                type = BufferType.CHANNEL,
            ),
        )
        return if (insertedId > 0L) {
            insertedId
        } else {
            checkNotNull(bufferDao.byName(networkId, roomJid)).id
        }
    }

    private sealed interface Outcome {
        data class Fatal(val reason: String) : Outcome
        data class Retry(val reason: String?) : Outcome
    }

    private companion object {
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 60_000L

        /** 1000 << 6 = 64000 already exceeds the 60s cap, so higher attempts add nothing. */
        const val MAX_BACKOFF_SHIFT = 6
        const val SEND_TIMEOUT_MS = 30_000L
    }
}
