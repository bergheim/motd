package io.github.trevarj.motd.xmppbackend

import io.github.trevarj.motd.data.db.XmppAccountEntity
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
 * [connection]; [onState], [onIncoming], [onChatState], [onMucMessage], [onMucSubject],
 * [onMucOccupant], and [onRosterLoad] are invoked only from that coroutine's children (plus,
 * redundantly and harmlessly for [onState], by [XmppSession]'s own state collector forwarding the
 * same values).
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
    /** Hands each live session's incoming 1:1 chat-state notifications to [XmppProcessor] (slice X6);
     *  defaults to a no-op so tests exercising only connection lifecycle need not wire it. No
     *  [generation] parameter: like [onMucOccupant]/[onRosterLoad], this drives an in-memory seam
     *  signal, not a canonical timeline observation. */
    private val onChatState: suspend (networkId: Long, state: XmppIncomingChatState) -> Unit = { _, _ -> },
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
            // Construction is guarded separately from the connect attempt below (review fix, P2
            // finding): sessionFactory.create can throw for a JID/resource that passed UI validation
            // but fails Smack's stricter nodeprep/resourceprep parsing (SmackXmppSession's
            // constructor eagerly builds an XMPPTCPConnectionConfiguration). Left unguarded, that
            // exception used to escape loop() entirely, killing this actor's coroutine with no
            // published Failed state: the manager's `actors` entry stayed registered (looking alive)
            // while `isAlive` silently went false, and the user saw no error at all. A bad, persisted
            // configuration will not fix itself on retry, so this is fatal — exactly like a rejected
            // SASL credential below — parking the actor until an explicit connect() (e.g. after the
            // user edits the account) retries it.
            val session = try {
                sessionFactory.create(account)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                onState(networkId, XmppSessionState.Failed(e.message ?: "invalid account configuration", fatal = true), generation)
                return
            }
            connection = session
            // Started UNDISPATCHED, not the default dispatched start (review fix, P2 finding): a
            // dispatched launch only *schedules* its body, so without this nothing guarantees any of
            // these seven collectors has actually subscribed before session.connect() runs below —
            // and MutableSharedFlow(replay = 0) drops an emission for good when it has no subscriber
            // yet, no matter how large extraBufferCapacity is. A real SmackXmppSession's connect()
            // loads the roster (and can see stanzas) synchronously, in the same call, right after
            // reaching Ready, so a slow-to-start collector could lose that first emission. UNDISPATCHED
            // runs each collector's body immediately, up to its first real suspension — which is the
            // flow subscription itself, made synchronously inside `collect` before it ever suspends
            // waiting for a value — so by the time connect() is called, every subscription is already
            // in place, exactly as incomingMessages' KDoc assumes ("attaches its collector before
            // calling connect").
            val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                session.state.collect { onState(networkId, it, generation) }
            }
            // Attached before connect() below (per incomingMessages' KDoc) so nothing arriving right
            // after Ready races this subscription. Same rationale for the four MUC/roster collectors.
            val messageCollector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                session.incomingMessages.collect { onIncoming(networkId, it, generation) }
            }
            val chatStateCollector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                session.incomingChatStates.collect { onChatState(networkId, it) }
            }
            val mucMessageCollector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                session.incomingMucMessages.collect { onMucMessage(networkId, it, generation) }
            }
            val mucSubjectCollector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                session.mucSubjects.collect { onMucSubject(networkId, it, generation) }
            }
            val mucOccupantCollector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                session.mucOccupants.collect { onMucOccupant(networkId, it) }
            }
            val rosterCollector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
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
                    chatStateCollector.cancelAndJoin()
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
