package io.github.trevarj.motd.xmppbackend

import io.github.trevarj.motd.data.db.XmppAccountEntity
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One actor per auto-connectable XMPP network row (docs/backend-neutral-xmpp-rollout.md "PR 2").
 * Owns the whole connect/reconnect lifecycle for that network: a fresh [XmppSession] per attempt,
 * exponential backoff between retries, and fatal-vs-retryable classification off [XmppSessionState]
 * alone. Mirrors the reliability semantics of the fork/xmpp-support prototype's `XmppAccountActor`
 * and of `:irc`'s `service.ConnectionActor` — a single coroutine drives each attempt in strict
 * sequence, a fatal failure parks the actor instead of retrying — without importing either.
 *
 * Concurrency contract: exactly one coroutine ([job], running [loop]) creates sessions and mutates
 * [connection]; [onState], [onIncoming], [onMucMessage], [onMucSubject], [onMucOccupant], and
 * [onRosterLoad] are invoked only from that coroutine's children (plus, redundantly and harmlessly
 * for [onState], by [XmppSession]'s own state collector forwarding the same values).
 */
internal class XmppAccountActor(
    private val networkId: Long,
    private val account: XmppAccountEntity,
    private val sessionFactory: XmppSessionFactory,
    private val scope: CoroutineScope,
    /** Assigns this attempt's session a fresh, manager-global monotonic generation number. */
    private val nextGeneration: () -> Long,
    /** `suspend` since [XmppConnectionManager]'s state publisher reads that network's CHANNEL buffer
     *  ids (a Room query) on a non-Ready transition, to clear their member-load state. Both call
     *  sites below are already inside a suspend context, so this is a type-only change. */
    private val onState: suspend (networkId: Long, state: XmppSessionState, generation: Long) -> Unit,
    /** Hands each live session's incoming DMs to [XmppProcessor] (docs/backend-neutral-xmpp-rollout.md
     *  "PR 2" X4); defaults to a no-op so tests exercising only connection lifecycle need not wire it. */
    private val onIncoming: suspend (networkId: Long, message: XmppIncomingMessage, generation: Long) -> Unit =
        { _, _, _ -> },
    /** Hands each live session's incoming MUC messages/subjects/occupant deltas, and its one-shot
     *  roster load outcome, to [XmppProcessor] and [XmppConnectionManager] (slice X5); each defaults
     *  to a no-op so tests exercising only connection lifecycle need not wire them. */
    private val onMucMessage: suspend (networkId: Long, message: XmppIncomingMucMessage, generation: Long) -> Unit =
        { _, _, _ -> },
    private val onMucSubject: suspend (networkId: Long, subject: XmppMucSubject, generation: Long) -> Unit =
        { _, _, _ -> },
    private val onMucOccupant: suspend (networkId: Long, event: XmppMucOccupantEvent) -> Unit = { _, _ -> },
    private val onRosterLoad: suspend (networkId: Long, load: XmppRosterLoad) -> Unit = { _, _ -> },
    private val random: () -> Double = { Random.nextDouble() },
) {
    @Volatile var connection: XmppSession? = null
        private set

    private var job: Job? = null

    /** True while the reconnect loop is still running; false once a fatal failure parked it (or the
     *  actor was never started/already stopped). */
    val isAlive: Boolean get() = job?.isActive == true

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    /** Cancel the loop and tear down any live session. Idempotent; safe even if never started. */
    suspend fun stopAndJoin() {
        val running = job
        job = null
        running?.cancelAndJoin()
    }

    private suspend fun loop() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val generation = nextGeneration()
            val session = sessionFactory.create(account)
            connection = session
            val collector = scope.launch { session.state.collect { onState(networkId, it, generation) } }
            // Attached before connect() below (per incomingMessages' KDoc) so nothing arriving right
            // after Ready races this subscription. Same rationale for the four MUC/roster collectors.
            val messageCollector = scope.launch {
                session.incomingMessages.collect { onIncoming(networkId, it, generation) }
            }
            val mucMessageCollector = scope.launch {
                session.incomingMucMessages.collect { onMucMessage(networkId, it, generation) }
            }
            val mucSubjectCollector = scope.launch {
                session.mucSubjects.collect { onMucSubject(networkId, it, generation) }
            }
            val mucOccupantCollector = scope.launch {
                session.mucOccupants.collect { onMucOccupant(networkId, it) }
            }
            val rosterCollector = scope.launch {
                session.rosterLoad.collect { onRosterLoad(networkId, it) }
            }
            var reachedReady = false

            val terminal = try {
                session.connect()
                val afterConnect = session.state.value
                if (afterConnect is XmppSessionState.Ready) {
                    reachedReady = true
                    // Stay attached to this Ready session until it moves away from Ready — a server
                    // close, socket error, or (later slices) an explicit disconnect.
                    session.state.first { it !is XmppSessionState.Ready }
                } else {
                    afterConnect
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                // XmppSession.connect() is documented to never throw except CancellationException;
                // this is a defensive fallback so a bug there degrades to a normal retry instead of
                // silently killing the actor with no published Failed state.
                XmppSessionState.Failed(e.message ?: "connection error", fatal = false)
            } finally {
                // NonCancellable covers the whole cleanup, not just disconnect(): when this finally
                // runs because stopAndJoin() cancelled our own job, every suspend call below —
                // including collector.cancelAndJoin() — would otherwise throw immediately instead of
                // actually waiting/tearing down, per Kotlin's prompt-cancellation guarantee.
                withContext(NonCancellable) {
                    collector.cancelAndJoin()
                    messageCollector.cancelAndJoin()
                    mucMessageCollector.cancelAndJoin()
                    mucSubjectCollector.cancelAndJoin()
                    mucOccupantCollector.cancelAndJoin()
                    rosterCollector.cancelAndJoin()
                    runCatching { session.disconnect() }
                }
                connection = null
            }

            // Guarantee the manager observes the final outcome even if the collector above missed
            // the last emission (e.g. the defensive-fallback path, which the session never saw).
            onState(networkId, terminal, generation)

            if (terminal is XmppSessionState.Failed && terminal.fatal) return

            attempt = if (reachedReady) 0 else attempt
            delay(backoffDelayMs(attempt))
            attempt++
        }
    }

    /**
     * delay = min(cap, base * 2^attempt) * jitter(0.7..1.3). Base/cap (1s doubling, 60s cap) mirror
     * the fork/xmpp-support prototype's `XmppAccountActor`; the jitter factor mirrors `:irc`'s
     * `ConnectionActor` so many accounts recovering from the same outage do not reconnect in lockstep.
     */
    fun backoffDelayMs(attempt: Int): Long {
        val exp = BASE_MS * (1L shl attempt.coerceAtMost(MAX_SHIFT))
        val capped = minOf(CAP_MS, exp)
        val jitter = JITTER_LOW + random() * (JITTER_HIGH - JITTER_LOW)
        return (capped * jitter).toLong()
    }

    companion object {
        const val BASE_MS = 1_000L
        const val CAP_MS = 60_000L
        const val JITTER_LOW = 0.7
        const val JITTER_HIGH = 1.3

        /** 1000 << 6 = 64000 already exceeds the 60s cap, so higher attempts add nothing. */
        private const val MAX_SHIFT = 6
    }
}
