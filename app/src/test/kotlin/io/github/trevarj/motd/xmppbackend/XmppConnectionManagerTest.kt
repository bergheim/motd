package io.github.trevarj.motd.xmppbackend

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.backend.ConnectionState
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.XmppAccountEntity
import io.github.trevarj.motd.service.RosterLoadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * XmppConnectionManager unit tests (docs/backend-neutral-xmpp-rollout.md "PR 2"). Mirrors the
 * fork/xmpp-support prototype's `XmppConnectionManagerTest` style (Robolectric + in-memory
 * [MotdDatabase] sharing a [StandardTestDispatcher]'s scheduler with the manager's coroutines) but
 * reshaped for the new seam: assertions land on the neutral [ConnectionState] map, not `IrcClientState`.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class XmppConnectionManagerTest {
    private lateinit var db: MotdDatabase
    private lateinit var appScope: CoroutineScope
    private lateinit var factory: FakeXmppSessionFactory
    private lateinit var manager: XmppConnectionManager
    private var nid: Long = 0

    private val selfJid = "me@glvortex.net"

    /**
     * Build the DB and manager sharing [TestScope]'s scheduler so Room work and the actor's
     * coroutines both advance deterministically under virtual time. Must run inside runTest.
     */
    private suspend fun TestScope.bootstrap(sessions: List<FakeXmppSession>) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
            .setQueryExecutor(dispatcher.asExecutor())
            .setTransactionExecutor(dispatcher.asExecutor())
            .allowMainThreadQueries()
            .build()
        appScope = CoroutineScope(SupervisorJob() + dispatcher)
        factory = FakeXmppSessionFactory(sessions)
        manager = XmppConnectionManager(db, factory, appScope)
        nid = db.networkDao().insert(
            NetworkEntity(
                name = "glvortex", role = NetworkRole.DIRECT,
                // Placeholder IRC-shaped columns: NOT NULL on the shared row, but grandfathered to
                // the IRC adapter and never read by the XMPP manager (docs/backend-neutral-xmpp-rollout.md).
                host = "unused.invalid", port = 5222,
                nick = "unused", username = "unused", realname = "unused",
                protocol = XmppChatBackend.XMPP_PROTOCOL.value,
            ),
        )
        db.xmppAccountDao().upsert(XmppAccountEntity(networkId = nid, jid = selfJid, password = "hunter2"))
    }

    @After
    fun tearDown() {
        if (::appScope.isInitialized) appScope.cancel()
        if (::db.isInitialized) db.close()
    }

    @Test
    fun connect_happyPath_reachesReady_withGenerationOne() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))

        manager.connect(nid)
        advanceUntilIdle()
        // Connecting is published before the (test-controlled) handshake resolves.
        assertEquals(ConnectionState.Connecting, manager.connectionStates.value[nid])

        s1.completeConnect(XmppSessionState.Ready(selfJid))
        advanceUntilIdle()

        assertEquals(
            ConnectionState.Ready(selfHandle = selfJid, generation = 1L, negotiationRevision = 0),
            manager.connectionStates.value[nid],
        )
    }

    @Test
    fun reconnect_afterTransientFailure_backsOffAndBumpsGeneration() = runTest {
        val s1 = FakeXmppSession()
        val s2 = FakeXmppSession()
        bootstrap(listOf(s1, s2))

        manager.connect(nid)
        advanceUntilIdle()
        s1.completeConnect(XmppSessionState.Ready(selfJid))
        advanceUntilIdle()
        assertEquals(1L, (manager.connectionStates.value[nid] as ConnectionState.Ready).generation)

        // An async drop after Ready (e.g. the transport listener firing), non-fatal.
        s1.publish(XmppSessionState.Failed("connection reset", fatal = false))
        runCurrent() // process the drop and enter the backoff wait; no retry session yet
        assertEquals(1, factory.created.size)
        assertTrue(manager.connectionStates.value[nid] is ConnectionState.Failed)

        advanceUntilIdle() // elapse backoff (virtual time) and spawn the retry session
        assertEquals(2, factory.created.size)

        s2.completeConnect(XmppSessionState.Ready(selfJid))
        advanceUntilIdle()

        val ready = manager.connectionStates.value[nid] as ConnectionState.Ready
        assertEquals(2L, ready.generation)
        assertEquals(selfJid, ready.selfHandle)
    }

    @Test
    fun fatalAuthFailure_parksWithoutRetry() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))

        // autoConnect defaults true, so startAll's reconcile spawns the actor on its own.
        manager.startAll()
        advanceUntilIdle()
        assertEquals(1, factory.created.size)

        s1.completeConnect(XmppSessionState.Failed(XMPP_AUTH_FAILURE_REASON, fatal = true))
        advanceUntilIdle()

        val state = manager.connectionStates.value[nid]
        assertTrue(state is ConnectionState.Failed && state.fatal)
        assertEquals(XMPP_AUTH_FAILURE_REASON, (state as ConnectionState.Failed).reason)

        // No auto-retry: neither time passing nor a reconcile-driven reconnectStale sweep
        // resurrects a fatal (auth) park — only an explicit connect() may retry it.
        advanceTimeBy(120_000L)
        manager.reconnectStale()
        advanceUntilIdle()
        assertEquals(1, factory.created.size)
        assertTrue((manager.connectionStates.value[nid] as ConnectionState.Failed).fatal)
    }

    @Test
    fun stopAll_tearsSessionsDown() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))

        manager.connect(nid)
        advanceUntilIdle()
        s1.completeConnect(XmppSessionState.Ready(selfJid))
        advanceUntilIdle()
        assertTrue(manager.connectionStates.value[nid] is ConnectionState.Ready)

        manager.stopAll()
        advanceUntilIdle()

        assertEquals(1, s1.disconnectCalls)
        assertTrue(manager.connectionStates.value.isEmpty())
    }

    @Test
    fun ircRows_areNeverTouched() = runTest {
        val s1 = FakeXmppSession()
        // nid (protocol=xmpp, autoConnect=true by default) legitimately spawns via startAll below;
        // s1 is queued for it so that expected activity is accounted for, not mistaken for the irc
        // row leaking through.
        bootstrap(listOf(s1))
        val ircId = db.networkDao().insert(
            NetworkEntity(
                name = "libera", role = NetworkRole.DIRECT,
                host = "irc.libera.chat", port = 6697,
                nick = "me", username = "me", realname = "Me",
                protocol = "irc",
            ),
        )

        manager.startAll()
        advanceUntilIdle()
        // Only the xmpp row's actor was created; observeAll() re-emitting on the irc insert must not
        // pull it into reconcile.
        assertEquals(1, factory.created.size)
        assertTrue(manager.connectionStates.value.containsKey(nid))
        assertFalse(manager.connectionStates.value.containsKey(ircId))

        // An explicit manual connect on a non-xmpp row must also be a no-op.
        manager.connect(ircId)
        advanceUntilIdle()
        assertEquals(1, factory.created.size)
        assertFalse(manager.connectionStates.value.containsKey(ircId))
    }

    @Test
    fun backoffDelay_isExponential_cappedAt60s_withinJitterBounds() {
        val account = XmppAccountEntity(networkId = 1L, jid = selfJid, password = "hunter2")
        val standaloneFactory = FakeXmppSessionFactory()
        fun actorWithJitter(jitter: Double) = XmppAccountActor(
            networkId = 1L,
            account = account,
            sessionFactory = standaloneFactory,
            scope = TestScope(),
            nextGeneration = { 0L },
            onState = { _, _, _ -> },
            random = { jitter },
        )
        val minJitter = actorWithJitter(0.0) // factor 0.7
        val maxJitter = actorWithJitter(1.0) // factor 1.3

        assertEquals(700L, minJitter.backoffDelayMs(0)) // 1000 * 0.7
        assertEquals(1_300L, maxJitter.backoffDelayMs(0)) // 1000 * 1.3
        assertEquals(1_400L, minJitter.backoffDelayMs(1)) // 2000 * 0.7
        assertEquals(2_600L, maxJitter.backoffDelayMs(1)) // 2000 * 1.3
        assertEquals(42_000L, minJitter.backoffDelayMs(6)) // capped: 60000 * 0.7
        assertEquals(78_000L, maxJitter.backoffDelayMs(6)) // capped: 60000 * 1.3
        assertEquals(78_000L, maxJitter.backoffDelayMs(20)) // still capped past attempt 6
    }

    // -- roster (buddy-list) load state (slice X5); see XmppConnectionManager's "Known seam gap" --

    @Test
    fun rosterStates_transitionsNotLoaded_loading_loaded() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))

        manager.connect(nid)
        advanceUntilIdle()
        assertEquals(RosterLoadState.NOT_LOADED, manager.rosterStates.value[nid])

        s1.completeConnect(XmppSessionState.Ready(selfJid))
        advanceUntilIdle()
        assertEquals(RosterLoadState.LOADING, manager.rosterStates.value[nid])

        s1.emitRosterLoad(XmppRosterLoad.Loaded(listOf(XmppRosterContact("alice@example.org", "Alice"))))
        advanceUntilIdle()
        assertEquals(RosterLoadState.LOADED, manager.rosterStates.value[nid])
    }

    @Test
    fun rosterStates_failedOnRosterError() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))

        manager.connect(nid)
        advanceUntilIdle()
        s1.completeConnect(XmppSessionState.Ready(selfJid))
        advanceUntilIdle()
        assertEquals(RosterLoadState.LOADING, manager.rosterStates.value[nid])

        s1.emitRosterLoad(XmppRosterLoad.Failed("roster IQ timed out"))
        advanceUntilIdle()
        assertEquals(RosterLoadState.FAILED, manager.rosterStates.value[nid])
    }

    @Test
    fun rosterStates_clearedOnDisconnect() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))

        manager.connect(nid)
        advanceUntilIdle()
        s1.completeConnect(XmppSessionState.Ready(selfJid))
        advanceUntilIdle()
        s1.emitRosterLoad(XmppRosterLoad.Loaded(emptyList()))
        advanceUntilIdle()
        assertEquals(RosterLoadState.LOADED, manager.rosterStates.value[nid])

        manager.disconnect(nid)
        advanceUntilIdle()
        assertFalse(manager.rosterStates.value.containsKey(nid))
    }

    // -- MUC join nick decision (slice X5): configured resource, else the bare-JID localpart. --

    @Test
    fun joinChannel_fallsBackToBareJidLocalpart_whenNoResourceConfigured() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1)) // bootstrap's account configures no resource.
        manager.connect(nid)
        advanceUntilIdle()
        s1.completeConnect(XmppSessionState.Ready(selfJid))
        advanceUntilIdle()

        manager.joinChannel(nid, "room@conference.example.org")
        advanceUntilIdle()

        assertEquals(listOf("room@conference.example.org" to "me"), s1.joinRoomCalls)
    }

    @Test
    fun joinChannel_prefersConfiguredResourceOverBareJidLocalpart() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))
        db.xmppAccountDao().upsert(
            XmppAccountEntity(networkId = nid, jid = selfJid, password = "hunter2", resource = "phone"),
        )
        manager.connect(nid)
        advanceUntilIdle()
        s1.completeConnect(XmppSessionState.Ready(selfJid))
        advanceUntilIdle()

        manager.joinChannel(nid, "room@conference.example.org")
        advanceUntilIdle()

        assertEquals(listOf("room@conference.example.org" to "phone"), s1.joinRoomCalls)
    }
}
