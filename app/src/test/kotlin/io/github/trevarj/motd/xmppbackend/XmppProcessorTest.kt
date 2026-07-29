package io.github.trevarj.motd.xmppbackend

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.EventAliasNamespace
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.XmppAccountEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * XmppProcessor unit tests (docs/backend-neutral-xmpp-rollout.md "PR 2", slice X4). Every scenario is
 * driven the way production actually delivers a message — through a live [FakeXmppSession] plumbed by
 * [XmppConnectionManager]/[XmppAccountActor], the same bootstrap style as [XmppConnectionManagerTest]
 * — and asserted on the shared canonical tables directly, proving the only write path is
 * [io.github.trevarj.motd.data.sync.BufferStore] / [io.github.trevarj.motd.data.sync.CanonicalTimelineStore],
 * never a private XMPP table (docs/backend-neutral-xmpp-rollout.md "Persistence and writer ownership").
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class XmppProcessorTest {
    private lateinit var db: MotdDatabase
    private lateinit var appScope: CoroutineScope
    private lateinit var factory: FakeXmppSessionFactory
    private lateinit var manager: XmppConnectionManager

    private val selfJid = "me@glvortex.net"

    /** Mirrors [XmppConnectionManagerTest.bootstrap]: Room and the manager's coroutines share the
     *  [TestScope]'s scheduler so both advance deterministically under virtual time. */
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
    }

    /** Insert an XMPP network row (+ its account satellite row) and return its id. */
    private suspend fun xmppNetwork(name: String): Long {
        val id = db.networkDao().insert(
            NetworkEntity(
                name = name, role = NetworkRole.DIRECT,
                // Placeholder IRC-shaped columns: NOT NULL on the shared row, but grandfathered to
                // the IRC adapter and never read by the XMPP manager (docs/backend-neutral-xmpp-rollout.md).
                host = "unused.invalid", port = 5222,
                nick = "unused", username = "unused", realname = "unused",
                protocol = XmppChatBackend.XMPP_PROTOCOL.value,
            ),
        )
        db.xmppAccountDao().upsert(XmppAccountEntity(networkId = id, jid = selfJid, password = "hunter2"))
        return id
    }

    /** Connect [networkId] on [session] and drive it to Ready. */
    private suspend fun TestScope.connectReady(networkId: Long, session: FakeXmppSession) {
        manager.connect(networkId)
        advanceUntilIdle()
        session.completeConnect(XmppSessionState.Ready(selfJid))
        advanceUntilIdle()
    }

    @After
    fun tearDown() {
        if (::appScope.isInitialized) appScope.cancel()
        if (::db.isInitialized) db.close()
    }

    @Test
    fun `a DM creates the query buffer and lands exactly once`() = runTest {
        val session = FakeXmppSession()
        bootstrap(listOf(session))
        val networkId = xmppNetwork("glvortex")
        connectReady(networkId, session)

        session.emit(
            XmppIncomingMessage(
                fromBareJid = "alice@example.org",
                body = "hello there",
                stanzaId = "stanza-1",
                delayStampMillis = null,
            ),
        )
        advanceUntilIdle()

        val buffer = requireNotNull(db.bufferDao().byName(networkId, "alice@example.org")) {
            "expected a QUERY buffer for the DM sender"
        }
        assertEquals(BufferType.QUERY, buffer.type)
        assertEquals("alice@example.org", buffer.displayName)

        val rows = db.canonicalTimelineDao().eventsForRoom(buffer.id)
        val row = rows.single()
        assertEquals("hello there", row.text)
        assertEquals("alice@example.org", row.sender)
        assertEquals("stanza-1", row.msgid)
        assertFalse(row.isSelf)
    }

    @Test
    fun `redelivery of the same stanza id dedups to one row`() = runTest {
        val session = FakeXmppSession()
        bootstrap(listOf(session))
        val networkId = xmppNetwork("glvortex")
        connectReady(networkId, session)

        val message = XmppIncomingMessage(
            fromBareJid = "alice@example.org",
            body = "hello there",
            stanzaId = "stanza-1",
            delayStampMillis = null,
        )
        session.emit(message)
        advanceUntilIdle()
        session.emit(message)
        advanceUntilIdle()

        val buffer = requireNotNull(db.bufferDao().byName(networkId, "alice@example.org"))
        val rows = db.canonicalTimelineDao().eventsForRoom(buffer.id)
        val row = rows.single()
        assertEquals("stanza-1", row.msgid)
        assertEquals(
            listOf(EventAliasNamespace.MSGID),
            db.canonicalTimelineDao().aliasesFor(row.id).map { it.namespace },
        )
    }

    @Test
    fun `two networks with the identical JID stay separate buffers`() = runTest {
        val sessionA = FakeXmppSession()
        val sessionB = FakeXmppSession()
        bootstrap(listOf(sessionA, sessionB))
        val networkA = xmppNetwork("glvortex-a")
        val networkB = xmppNetwork("glvortex-b")
        connectReady(networkA, sessionA)
        connectReady(networkB, sessionB)

        sessionA.emit(
            XmppIncomingMessage(
                fromBareJid = "alice@example.org",
                body = "hi from network A",
                stanzaId = "a-1",
                delayStampMillis = null,
            ),
        )
        sessionB.emit(
            XmppIncomingMessage(
                fromBareJid = "alice@example.org",
                body = "hi from network B",
                stanzaId = "b-1",
                delayStampMillis = null,
            ),
        )
        advanceUntilIdle()

        val bufferA = requireNotNull(db.bufferDao().byName(networkA, "alice@example.org"))
        val bufferB = requireNotNull(db.bufferDao().byName(networkB, "alice@example.org"))
        assertNotEquals(bufferA.id, bufferB.id)

        val rowsA = db.canonicalTimelineDao().eventsForRoom(bufferA.id)
        val rowsB = db.canonicalTimelineDao().eventsForRoom(bufferB.id)
        assertEquals("hi from network A", rowsA.single().text)
        assertEquals("hi from network B", rowsB.single().text)
    }

    @Test
    fun `a message with no stanza id still lands with no alias`() = runTest {
        val session = FakeXmppSession()
        bootstrap(listOf(session))
        val networkId = xmppNetwork("glvortex")
        connectReady(networkId, session)

        session.emit(
            XmppIncomingMessage(
                fromBareJid = "alice@example.org",
                body = "no id on this one",
                stanzaId = null,
                delayStampMillis = null,
            ),
        )
        advanceUntilIdle()

        val buffer = requireNotNull(db.bufferDao().byName(networkId, "alice@example.org"))
        val row = db.canonicalTimelineDao().eventsForRoom(buffer.id).single()
        assertEquals("no id on this one", row.text)
        assertNull(row.msgid)
        assertTrue(db.canonicalTimelineDao().aliasesFor(row.id).isEmpty())
    }

    @Test
    fun `processing only starts for xmpp rows' sessions`() = runTest {
        val session = FakeXmppSession()
        bootstrap(listOf(session))
        val xmppId = xmppNetwork("glvortex")
        val ircId = db.networkDao().insert(
            NetworkEntity(
                name = "libera", role = NetworkRole.DIRECT,
                host = "irc.libera.chat", port = 6697,
                nick = "me", username = "me", realname = "Me",
                protocol = "irc",
            ),
        )

        // autoConnect defaults true, so startAll's reconcile spawns an actor for the xmpp row only;
        // the irc row never gets an XmppAccountActor (or a session/collector) at all.
        manager.startAll()
        advanceUntilIdle()
        assertEquals(1, factory.created.size)

        session.completeConnect(XmppSessionState.Ready(selfJid))
        advanceUntilIdle()

        session.emit(
            XmppIncomingMessage(
                fromBareJid = "alice@example.org",
                body = "hi",
                stanzaId = "stanza-1",
                delayStampMillis = null,
            ),
        )
        advanceUntilIdle()

        val buffer = requireNotNull(db.bufferDao().byName(xmppId, "alice@example.org"))
        assertEquals(1, db.canonicalTimelineDao().eventsForRoom(buffer.id).size)

        // A manual connect on the irc row is a no-op (XmppConnectionManager rejects non-xmpp rows),
        // so no second session/actor/buffer is ever created for it.
        manager.connect(ircId)
        advanceUntilIdle()
        assertEquals(1, factory.created.size)
        assertNull(db.bufferDao().byName(ircId, "alice@example.org"))
    }
}
