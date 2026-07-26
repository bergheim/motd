package io.github.trevarj.motd.xmpp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.Protocol
import io.github.trevarj.motd.data.sync.CanonicalTimelineStore
import io.github.trevarj.motd.data.sync.MessageNotifier
import io.github.trevarj.motd.data.sync.TypingTrackerImpl
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.SendAcceptance
import io.github.trevarj.motd.service.SendRejectionReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Hands out a pre-queued sequence of [FakeXmppSession]s and records every creation. */
class FakeXmppSessionFactory(sessions: List<FakeXmppSession>) : XmppSessionFactory {
    private val queued = ArrayDeque(sessions)
    val created = mutableListOf<FakeXmppSession>()
    val configs = mutableListOf<XmppAccountConfig>()
    override fun create(config: XmppAccountConfig): XmppSession {
        val session = queued.removeFirstOrNull() ?: FakeXmppSession()
        created += session
        configs += config
        return session
    }
}

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class XmppConnectionManagerTest {
    private lateinit var db: MotdDatabase
    private lateinit var processor: XmppEventProcessor
    private lateinit var appScope: CoroutineScope
    private lateinit var factory: FakeXmppSessionFactory
    private lateinit var manager: XmppConnectionManager
    private var nid: Long = 0

    private val selfJid = "me@glvortex.net"
    private val roomJid = "room@conf.glvortex.net"

    /**
     * Build the DB, processor, and manager sharing [TestScope]'s scheduler so Room work and the
     * actor's coroutines both advance deterministically under virtual time. Must run inside runTest.
     */
    private suspend fun TestScope.bootstrap(sessions: List<FakeXmppSession>) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
            .setQueryExecutor(dispatcher.asExecutor())
            .setTransactionExecutor(dispatcher.asExecutor())
            .allowMainThreadQueries()
            .build()
        processor = XmppEventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop, CanonicalTimelineStore(db))
        appScope = CoroutineScope(SupervisorJob() + dispatcher)
        factory = FakeXmppSessionFactory(sessions)
        manager = XmppConnectionManager(context, db, processor, factory, appScope)
        nid = db.networkDao().insert(
            NetworkEntity(
                name = "glvortex", protocol = Protocol.XMPP, role = NetworkRole.DIRECT,
                host = "xmpp.glvortex.net", port = 5222,
                nick = "me", username = "me", realname = "Me",
                jid = selfJid,
            ),
        )
    }

    @After fun tearDown() {
        if (::appScope.isInitialized) appScope.cancel()
        if (::db.isInitialized) db.close()
    }

    @Test
    fun ready_onlyAfterRosterLoaded() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))
        manager.connect(nid)
        advanceUntilIdle()
        // The session is connecting but not yet Ready until the roster-loaded event arrives.
        assertEquals(IrcClientState.Connecting, manager.connectionStates.value[nid])

        s1.emit(XmppEvent.Ready(selfJid))
        advanceUntilIdle()
        assertEquals(
            IrcClientState.Ready(selfJid, emptySet(), emptyMap()),
            manager.connectionStates.value[nid],
        )
    }

    @Test
    fun reconnect_createsFreshSession_failsPending_rejoinsMucs() = runTest {
        val s1 = FakeXmppSession()
        val s2 = FakeXmppSession()
        bootstrap(listOf(s1, s2))
        // Seed a joined MUC (via plain DAO writes) so reconnect must rejoin it.
        val roomBufferId = db.bufferDao().insertIgnore(
            BufferEntity(
                networkId = nid,
                name = roomJid,
                displayName = roomJid,
                type = BufferType.CHANNEL,
            ),
        )
        db.bufferDao().setJoined(roomBufferId, true)

        manager.connect(nid)
        advanceUntilIdle()
        s1.emit(XmppEvent.Ready(selfJid))
        advanceUntilIdle()

        // A durable pending row that the dropped session can never confirm.
        val pendingId = processor.createPending(nid, roomBufferId, "hi", "orig")!!

        s1.emit(XmppEvent.Disconnected(reason = null, fatal = false))
        runCurrent() // process the drop, enter the 1s backoff wait
        assertEquals(1, factory.created.size)

        advanceTimeBy(1_100L) // elapse the exponential backoff (attempt 0 -> 1s)
        advanceUntilIdle()

        // A fresh session was created, the stale pending row failed, and the MUC was rejoined.
        assertEquals(2, factory.created.size)
        assertTrue(db.messageDao().byId(pendingId)!!.failed)

        s2.emit(XmppEvent.Ready(selfJid))
        advanceUntilIdle()
        assertTrue(s2.joinedRooms.contains(roomJid))
    }

    @Test
    fun rejoin_oneRoomFails_staysReady_rejoinsHealthyRoom() = runTest {
        val badRoom = "bad@conf.glvortex.net"
        val goodRoom = "good@conf.glvortex.net"
        val s1 = FakeXmppSession().apply { failJoinFor = setOf(badRoom) }
        bootstrap(listOf(s1))
        // Seed two joined MUCs; one will throw on rejoin.
        for (room in listOf(badRoom, goodRoom)) {
            val id = db.bufferDao().insertIgnore(
                BufferEntity(networkId = nid, name = room, displayName = room, type = BufferType.CHANNEL),
            )
            db.bufferDao().setJoined(id, true)
        }

        manager.connect(nid)
        advanceUntilIdle()
        s1.emit(XmppEvent.Ready(selfJid))
        advanceUntilIdle()

        // The failing room's join threw, but it degraded to that room only: the actor stayed Ready,
        // still rejoined the healthy room, and never tore down / reconnected (one session created).
        assertTrue(manager.connectionStates.value[nid] is IrcClientState.Ready)
        assertTrue(s1.joinedRooms.contains(goodRoom))
        assertFalse(s1.joinedRooms.contains(badRoom))
        assertEquals(1, factory.created.size)
    }

    @Test
    fun fatalAuthFailure_doesNotRetry() = runTest {
        val s1 = FakeXmppSession()
        val s2 = FakeXmppSession()
        bootstrap(listOf(s1, s2))
        manager.connect(nid)
        advanceUntilIdle()

        s1.emit(XmppEvent.Disconnected(reason = "invalid credentials", fatal = true))
        advanceUntilIdle()

        // No second session — a fatal failure parks the actor instead of retrying.
        assertEquals(1, factory.created.size)
        val state = manager.connectionStates.value[nid]
        assertTrue(state is IrcClientState.Failed && state.fatal)
    }

    @Test
    fun thrownAuthFailure_parksWithClearReason() = runTest {
        val s1 = FakeXmppSession().apply { failLoginWith = XmppAuthException() }
        val s2 = FakeXmppSession()
        bootstrap(listOf(s1, s2))
        manager.connect(nid)
        advanceUntilIdle()

        // A rejected password thrown from login() must park (no retry), with a message the
        // onboarding/add-network error UI can show verbatim.
        assertEquals(1, factory.created.size)
        val state = manager.connectionStates.value[nid]
        assertTrue(state is IrcClientState.Failed && state.fatal)
        assertEquals("Wrong address or password", (state as IrcClientState.Failed).reason)
    }

    @Test
    fun sendMessage_unknownBuffer_rejected_noPendingRow() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))
        manager.connect(nid)
        advanceUntilIdle()
        s1.emit(XmppEvent.Ready(selfJid))
        advanceUntilIdle()

        val result = manager.sendMessage(bufferId = 999_999L, text = "hi")
        assertEquals(SendAcceptance.Rejected(SendRejectionReason.BUFFER_NOT_FOUND), result)
        assertTrue(s1.sentChats.isEmpty())
    }

    @Test
    fun sendMessage_writesPending_thenSendsWithSameOriginId() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))
        manager.connect(nid)
        advanceUntilIdle()
        s1.emit(XmppEvent.Ready(selfJid))
        advanceUntilIdle()

        val queryBufferId = manager.ensureQueryBuffer(nid, "bob@glvortex.net")
        val result = manager.sendMessage(queryBufferId, "yo")
        assertTrue(result is SendAcceptance.Accepted)
        val eventId = (result as SendAcceptance.Accepted).eventIds.single()

        val (to, text, originId) = s1.sentChats.single()
        assertEquals("bob@glvortex.net", to)
        assertEquals("yo", text)
        // The durable row's msgid is the same origin id sent on the wire.
        assertEquals(originId, db.messageDao().byId(eventId)!!.msgid)
    }

    @Test
    fun listRooms_returnsSessionListings_whenReady() = runTest {
        val s1 = FakeXmppSession().apply {
            roomListings = listOf(
                MucRoomListing(roomJid = "lobby@conf.glvortex.net", name = "Lobby"),
                MucRoomListing(roomJid = "random@conf.glvortex.net", name = null),
            )
        }
        bootstrap(listOf(s1))
        manager.connect(nid)
        advanceUntilIdle()
        s1.emit(XmppEvent.Ready(selfJid))
        advanceUntilIdle()

        assertEquals(s1.roomListings, manager.listRooms(nid))
    }

    @Test
    fun listRooms_emptyList_whenNoActor() = runTest {
        bootstrap(emptyList())
        // No connect() was ever called, so no actor exists for this network id.
        assertEquals(emptyList<MucRoomListing>(), manager.listRooms(nid))
    }

    @Test
    fun listIrcGateways_returnsSessionGateways_whenReady() = runTest {
        val s1 = FakeXmppSession().apply { ircGateways = listOf("irc.xmpp.glvortex.net") }
        bootstrap(listOf(s1))
        manager.connect(nid)
        advanceUntilIdle()
        s1.emit(XmppEvent.Ready(selfJid))
        advanceUntilIdle()

        assertEquals(listOf("irc.xmpp.glvortex.net"), manager.listIrcGateways(nid))
    }

    @Test
    fun listIrcGateways_isCachedForSessionLifetime() = runTest {
        val s1 = FakeXmppSession().apply { ircGateways = listOf("irc.xmpp.glvortex.net") }
        bootstrap(listOf(s1))
        manager.connect(nid)
        advanceUntilIdle()
        s1.emit(XmppEvent.Ready(selfJid))
        advanceUntilIdle()

        assertEquals(listOf("irc.xmpp.glvortex.net"), manager.listIrcGateways(nid))
        // Change the session's answer: a cached non-empty result must be reused, not re-discovered.
        s1.ircGateways = listOf("changed.example.net")
        assertEquals(listOf("irc.xmpp.glvortex.net"), manager.listIrcGateways(nid))

        // Disconnect clears the cache; a subsequent query with no actor returns empty.
        manager.disconnect(nid)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), manager.listIrcGateways(nid))
    }

    @Test
    fun listIrcGateways_cacheInvalidated_whenSessionDropsOutOfReady() = runTest {
        val s1 = FakeXmppSession().apply { ircGateways = listOf("gw-old.example.net") }
        val s2 = FakeXmppSession().apply { ircGateways = listOf("gw-new.example.net") }
        bootstrap(listOf(s1, s2))
        manager.connect(nid)
        advanceUntilIdle()
        s1.emit(XmppEvent.Ready(selfJid))
        advanceUntilIdle()
        assertEquals(listOf("gw-old.example.net"), manager.listIrcGateways(nid)) // cached on s1

        // A non-fatal drop makes the actor publish a non-Ready state (Failed) and recycle its
        // session internally — the manager must invalidate the cache off that state transition,
        // not just on connect/disconnect/reconcile.
        s1.emit(XmppEvent.Disconnected(reason = null, fatal = false))
        runCurrent()
        advanceTimeBy(1_100L) // elapse backoff so the fresh session (s2) is created
        advanceUntilIdle()
        s2.emit(XmppEvent.Ready(selfJid))
        advanceUntilIdle()

        // The stale s1 result was dropped; discovery re-runs against the fresh session.
        assertEquals(listOf("gw-new.example.net"), manager.listIrcGateways(nid))
    }

    @Test
    fun listIrcGateways_singleFlight_collapsesConcurrentCallers() = runTest {
        val gate = CompletableDeferred<Unit>()
        val s1 = FakeXmppSession().apply {
            ircGateways = listOf("irc.xmpp.glvortex.net")
            gateIrcGateways = gate
        }
        bootstrap(listOf(s1))
        manager.connect(nid)
        advanceUntilIdle()
        s1.emit(XmppEvent.Ready(selfJid))
        advanceUntilIdle()

        // Two overlapping callers while discovery is still in flight (gate not yet released).
        val first = async { manager.listIrcGateways(nid) }
        val second = async { manager.listIrcGateways(nid) }
        runCurrent()
        // Both joined the same in-flight discovery: the session saw exactly one disco call.
        assertEquals(1, s1.listIrcGatewaysCalls)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("irc.xmpp.glvortex.net"), first.await())
        assertEquals(listOf("irc.xmpp.glvortex.net"), second.await())
        assertEquals(1, s1.listIrcGatewaysCalls)
    }

    @Test
    fun listIrcGateways_emptyList_whenNoActor() = runTest {
        bootstrap(emptyList())
        assertEquals(emptyList<String>(), manager.listIrcGateways(nid))
    }

    @Test
    fun ircRows_areIgnored() = runTest {
        bootstrap(emptyList())
        val ircNid = db.networkDao().insert(
            NetworkEntity(
                name = "libera", protocol = Protocol.IRC, role = NetworkRole.DIRECT,
                host = "irc.libera.chat", port = 6697,
                nick = "me", username = "me", realname = "Me",
            ),
        )
        manager.connect(ircNid)
        advanceUntilIdle()

        // No actor, no session, and no published state for an IRC row.
        assertTrue(factory.created.isEmpty())
        assertFalse(manager.connectionStates.value.containsKey(ircNid))
    }

    @Test
    fun sendMessage_watchdog_failsRow_after30sWithoutConfirm() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))
        manager.connect(nid)
        advanceUntilIdle()
        s1.emit(XmppEvent.Ready(selfJid))
        advanceUntilIdle()

        val queryBufferId = manager.ensureQueryBuffer(nid, "bob@glvortex.net")
        val result = manager.sendMessage(queryBufferId, "hi")
        assertTrue(result is SendAcceptance.Accepted)
        val eventId = (result as SendAcceptance.Accepted).eventIds.single()
        assertEquals(1, s1.sentChats.size) // wire write attempted, still pending

        // No SendConfirmed ever arrives: the 30s actor-level watchdog must fail the row.
        advanceTimeBy(30_001L)
        runCurrent()

        val row = db.messageDao().byId(eventId)!!
        assertTrue(row.failed)
        assertNull(row.pendingLabel)
    }

    @Test
    fun sendMessage_watchdog_noOp_whenConfirmedBefore30s() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))
        manager.connect(nid)
        advanceUntilIdle()
        s1.emit(XmppEvent.Ready(selfJid))
        advanceUntilIdle()

        val queryBufferId = manager.ensureQueryBuffer(nid, "bob@glvortex.net")
        val result = manager.sendMessage(queryBufferId, "hi")
        val eventId = (result as SendAcceptance.Accepted).eventIds.single()
        val originId = s1.sentChats.single().third

        // SendConfirmed arrives (through the actor's own event loop) at +10s.
        advanceTimeBy(10_000L)
        runCurrent()
        s1.emit(XmppEvent.SendConfirmed(originId))
        runCurrent()

        // Advance well past the 30s deadline: the watchdog must be a no-op on the confirmed row.
        advanceTimeBy(30_001L)
        runCurrent()

        val row = db.messageDao().byId(eventId)!!
        assertFalse(row.failed)
        assertNull(row.pendingLabel)
        assertEquals(originId, row.msgid)
    }

    @Test
    fun disconnect_midBackoff_cancelsPendingRetry() = runTest {
        val s1 = FakeXmppSession()
        val s2 = FakeXmppSession()
        bootstrap(listOf(s1, s2))
        manager.connect(nid)
        advanceUntilIdle()

        s1.emit(XmppEvent.Disconnected(reason = null, fatal = false))
        runCurrent() // enter the 1s backoff wait
        assertEquals(1, factory.created.size)

        advanceTimeBy(500L) // partway into the backoff, before it elapses
        runCurrent()
        assertEquals(1, factory.created.size)

        // Manual disconnect while mid-backoff must cancel the pending retry delay outright.
        manager.disconnect(nid)
        advanceUntilIdle()
        assertEquals(1, factory.created.size) // no fresh session was ever created
        assertFalse(manager.connectionStates.value.containsKey(nid))
    }

    @Test
    fun manualConnect_survivesReconcile_forNonAutoConnectRow() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))
        // Make the XMPP row opt-out of autoConnect so only a manual intent can spawn it.
        val row = db.networkDao().byId(nid)!!
        db.networkDao().update(row.copy(autoConnect = false))

        manager.startAll()
        advanceUntilIdle()
        // Reconcile with autoConnect=false and no manual intent spawns nothing.
        assertTrue(factory.created.isEmpty())

        manager.connect(nid)
        advanceUntilIdle()
        s1.emit(XmppEvent.Ready(selfJid))
        advanceUntilIdle()
        assertEquals(1, factory.created.size)
        assertTrue(manager.connectionStates.value[nid] is IrcClientState.Ready)

        // A DB write re-emits observeAll → reconcile runs again. Manual intent must survive it.
        db.networkDao().insert(
            NetworkEntity(
                name = "libera", protocol = Protocol.IRC, role = NetworkRole.DIRECT,
                host = "irc.libera.chat", port = 6697,
                nick = "me", username = "me", realname = "Me",
            ),
        )
        advanceUntilIdle()

        assertEquals(1, factory.created.size) // no duplicate actor
        assertTrue(manager.connectionStates.value[nid] is IrcClientState.Ready)

        manager.stopAll()
        advanceUntilIdle()
    }

    @Test
    fun backoffDelay_isExponential_cappedAt60s() = runTest {
        bootstrap(emptyList())
        val actor = XmppAccountActor(
            networkId = 1L,
            config = XmppAccountConfig(selfJid, "", "host", 5222, directTls = true, mucNick = "me"),
            db = db,
            processor = processor,
            sessionFactory = factory,
            scope = appScope,
            onState = { _, _ -> },
        )
        assertEquals(1_000L, actor.backoffDelayMs(0))
        assertEquals(2_000L, actor.backoffDelayMs(1))
        assertEquals(4_000L, actor.backoffDelayMs(2))
        assertEquals(60_000L, actor.backoffDelayMs(6))
        assertEquals(60_000L, actor.backoffDelayMs(20))
    }

    @Test
    fun editingAccountConfig_respawnsActorWithNewConfig() = runTest {
        bootstrap(listOf(FakeXmppSession(), FakeXmppSession()))
        manager.startAll()
        advanceUntilIdle()
        assertEquals(1, factory.created.size)

        // Fix a wrong password on the live account row; reconcile must respawn the actor with the
        // corrected config rather than keep using the stale one until an app restart.
        val row = db.networkDao().byId(nid)!!
        db.networkDao().update(row.copy(saslPassword = "corrected"))
        advanceUntilIdle()

        assertEquals(2, factory.created.size)
        assertEquals("corrected", factory.configs.last().password)
    }
}
