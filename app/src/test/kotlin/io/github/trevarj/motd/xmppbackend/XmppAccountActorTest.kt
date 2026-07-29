package io.github.trevarj.motd.xmppbackend

import io.github.trevarj.motd.data.db.XmppAccountEntity
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [XmppAccountActor] unit tests targeting two review findings that [XmppConnectionManagerTest] does
 * not exercise directly, since both are about the actor's own [XmppAccountActor.loop] internals
 * (collector subscription timing; session-construction failure handling) rather than the manager's
 * seam behavior built on top of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class XmppAccountActorTest {
    private val selfJid = "me@glvortex.net"
    private val account = XmppAccountEntity(networkId = 1L, jid = selfJid, password = "hunter2")

    /**
     * Delegates everything to [delegate] except [connect]: mirrors [SmackXmppSession.connect]'s real
     * shape, where reaching Ready synchronously loads the roster (and can observe live stanzas) in
     * the very same suspend call, before ever yielding back to the caller's dispatcher. Emitting here
     * BEFORE calling through to [delegate]'s own (test-gated) connect -- itself never suspending
     * until it awaits that gate -- reproduces the actor's real race with no dependency on scheduler
     * internals: this emission happens synchronously, with zero suspension points, on whichever
     * coroutine calls [connect].
     */
    private class SynchronousEmitSession(private val delegate: FakeXmppSession) : XmppSession by delegate {
        override suspend fun connect() {
            delegate.emitRosterLoad(XmppRosterLoad.Loaded(listOf(XmppRosterContact("alice@example.org", "Alice"))))
            delegate.connect()
        }
    }

    /**
     * P2 review finding: the actor's seven event collectors are launched with the default
     * (dispatched) coroutine start, so nothing guarantees any of them has actually subscribed before
     * [XmppSession.connect] runs -- and `MutableSharedFlow(replay = 0)` drops an emission for good
     * when it has no subscriber yet, however large `extraBufferCapacity` is. Before the fix: this
     * test fails because the dispatched (not yet run) roster collector has not subscribed by the
     * time [SynchronousEmitSession.connect] emits, synchronously, ahead of ever yielding back to the
     * scheduler -- so the emission is lost and `received` stays empty. After starting every collector
     * with `CoroutineStart.UNDISPATCHED`, each has already subscribed (synchronously, up to its own
     * first suspension) before `loop()` ever reaches `session.connect()`, so the emission is received.
     */
    @Test
    fun collectorsSubscribeBeforeConnect_soASynchronousEmitDuringLoginIsNotLost() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val fake = FakeXmppSession()
        val received = mutableListOf<XmppRosterLoad>()
        val actor = XmppAccountActor(
            networkId = 1L,
            account = account,
            sessionFactory = XmppSessionFactory { SynchronousEmitSession(fake) },
            scope = scope,
            nextGeneration = { 1L },
            onState = { _, _, _ -> },
            onRosterLoad = { _, load -> received += load },
        )

        actor.start()
        advanceUntilIdle()
        fake.completeConnect(XmppSessionState.Ready(selfJid))
        advanceUntilIdle()

        assertEquals(1, received.size)
        assertTrue(received.single() is XmppRosterLoad.Loaded)
        scope.cancel()
    }

    /**
     * P2 review finding: a JID/resource that passes UI validation but fails Smack's stricter
     * nodeprep/resourceprep parsing makes `sessionFactory.create(account)` throw. Before the fix,
     * that exception was raised outside loop()'s guarded try/finally entirely, so it escaped as an
     * uncaught exception on the actor's own coroutine: no `Failed` state was ever published, the
     * actor's job died silently (so `isAlive` went false while the manager's `actors` map still
     * believed it live), and the user saw no error. This test proves both halves of the fix: no
     * exception escapes uncaught (captured here via a [CoroutineExceptionHandler], which a
     * `SupervisorJob` child routes an uncaught failure to), and a fatal [XmppSessionState.Failed] is
     * published instead.
     */
    @Test
    fun sessionConstructionFailure_publishesFatalFailedState_insteadOfDyingUncaught() = runTest {
        val states = mutableListOf<XmppSessionState>()
        var uncaught: Throwable? = null
        val dispatcher = StandardTestDispatcher(testScheduler)
        val handler = CoroutineExceptionHandler { _, e -> uncaught = e }
        val scope = CoroutineScope(SupervisorJob() + dispatcher + handler)
        val actor = XmppAccountActor(
            networkId = 1L,
            account = account,
            sessionFactory = XmppSessionFactory { throw IllegalArgumentException("invalid resourcepart") },
            scope = scope,
            nextGeneration = { 1L },
            onState = { _, state, _ -> states += state },
        )

        actor.start()
        advanceUntilIdle()

        assertNull("session construction failure must not escape as an uncaught exception", uncaught)
        val failed = states.singleOrNull() as? XmppSessionState.Failed
        assertNotNull("expected exactly one published Failed state", failed)
        assertTrue("a bad persisted configuration will not fix itself on retry", requireNotNull(failed).fatal)
        assertFalse("a fatal failure parks the actor", actor.isAlive)
        scope.cancel()
    }
}
