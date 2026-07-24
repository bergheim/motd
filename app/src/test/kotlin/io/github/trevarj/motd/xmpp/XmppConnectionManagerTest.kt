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
import io.github.trevarj.motd.data.sync.MessageNotifier
import io.github.trevarj.motd.data.sync.TypingTrackerImpl
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.SendAcceptance
import io.github.trevarj.motd.service.SendRejectionReason
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

/** Hands out a pre-queued sequence of [FakeXmppSession]s and records every creation. */
class FakeXmppSessionFactory(sessions: List<FakeXmppSession>) : XmppSessionFactory {
    private val queued = ArrayDeque(sessions)
    val created = mutableListOf<FakeXmppSession>()
    override fun create(config: XmppAccountConfig): XmppSession {
        val session = queued.removeFirstOrNull() ?: FakeXmppSession()
        created += session
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
        processor = XmppEventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop)
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
}
