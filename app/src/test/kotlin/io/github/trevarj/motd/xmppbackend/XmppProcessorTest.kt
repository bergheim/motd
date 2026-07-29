package io.github.trevarj.motd.xmppbackend

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.EventAliasNamespace
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.XmppAccountEntity
import io.github.trevarj.motd.data.sync.TypingTrackerImpl
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
 * Mirrors [XmppProcessor]'s private `scopedMsgid`/`MSGID_AUTHORITY_SEPARATOR` (review fix: a raw
 * sender-supplied stanza id is scoped to its assigning authority before becoming a canonical
 * event's `msgid`, so two different senders reusing a common id never collide) without depending
 * on that implementation detail directly. The separator is built from [Int.toChar] rather than
 * typed as a literal escape so this file stays plain, unambiguous source text.
 */
private fun scopedMsgid(stanzaId: String, vararg authority: String): String =
    (authority.toList() + stanzaId).joinToString(0.toChar().toString())

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

    /** Exposed (slice X6) so incoming-typing tests can assert on [TypingTrackerImpl.typingNicks]
     *  directly — the same seam instance [XmppProcessor] was constructed with, not a separate one. */
    private lateinit var typingTracker: TypingTrackerImpl

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
        typingTracker = TypingTrackerImpl()
        manager = XmppConnectionManager(db, factory, appScope, XmppProcessor(db, typingTracker = typingTracker))
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
        // msgid is scoped to its assigning authority (the sender's bare JID), not the raw stanza id
        // verbatim — see scopedMsgid's KDoc on the production side.
        assertEquals(scopedMsgid("stanza-1", "alice@example.org"), row.msgid)
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
        assertEquals(scopedMsgid("stanza-1", "alice@example.org"), row.msgid)
        assertEquals(
            listOf(EventAliasNamespace.MSGID),
            db.canonicalTimelineDao().aliasesFor(row.id).map { it.namespace },
        )
    }

    /**
     * The regression this review fix closes: a raw XMPP stanza id is sender-supplied and only
     * unique within its own sender's stream (RFC 6120), unlike an IRC msgid. Before scoping the
     * canonical `msgid` to its assigning authority, two different DM peers reusing a common id
     * collided on the exact same `(networkId, MSGID, value)` alias —
     * [io.github.trevarj.motd.data.sync.CanonicalTimelineStore.ingestInTransaction] resolves an
     * MSGID match unconditionally, ahead of every weaker signal and with no bufferId check — so
     * bob's message would silently coalesce into alice's already-inserted event and never appear
     * in its own (bob's) buffer at all.
     */
    @Test
    fun `two different DM senders reusing the same stanza id land as two distinct events`() = runTest {
        val session = FakeXmppSession()
        bootstrap(listOf(session))
        val networkId = xmppNetwork("glvortex")
        connectReady(networkId, session)

        session.emit(
            XmppIncomingMessage(
                fromBareJid = "alice@example.org",
                body = "hello from alice",
                stanzaId = "1",
                delayStampMillis = null,
            ),
        )
        session.emit(
            XmppIncomingMessage(
                fromBareJid = "bob@example.org",
                body = "hello from bob",
                stanzaId = "1", // same raw stanza id alice's message above already used
                delayStampMillis = null,
            ),
        )
        advanceUntilIdle()

        val aliceBuffer = requireNotNull(db.bufferDao().byName(networkId, "alice@example.org"))
        val bobBuffer = requireNotNull(db.bufferDao().byName(networkId, "bob@example.org"))
        assertNotEquals(aliceBuffer.id, bobBuffer.id)

        val aliceRow = db.canonicalTimelineDao().eventsForRoom(aliceBuffer.id).single()
        val bobRow = db.canonicalTimelineDao().eventsForRoom(bobBuffer.id).single()
        assertEquals("hello from alice", aliceRow.text)
        assertEquals("hello from bob", bobRow.text)
        assertEquals(scopedMsgid("1", "alice@example.org"), aliceRow.msgid)
        assertEquals(scopedMsgid("1", "bob@example.org"), bobRow.msgid)
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

    // -- MUC baseline (slice X5): join/occupants/messages/subjects/leave, plus roster loading. --

    private val roomJid = "room@conference.example.org"

    @Test
    fun `joining a MUC creates the CHANNEL buffer and lands the occupant snapshot`() = runTest {
        val session = FakeXmppSession()
        bootstrap(listOf(session))
        val networkId = xmppNetwork("glvortex")
        connectReady(networkId, session)

        manager.joinChannel(networkId, roomJid)
        advanceUntilIdle()
        // nick = bare-JID localpart: the bootstrapped account configures no resource.
        assertEquals(listOf(roomJid to "me"), session.joinRoomCalls)

        session.emitOccupantSnapshot(roomJid, listOf("me", "alice", "bob"))
        advanceUntilIdle()

        val buffer = requireNotNull(db.bufferDao().byName(networkId, roomJid)) {
            "expected a CHANNEL buffer for the joined room"
        }
        assertEquals(BufferType.CHANNEL, buffer.type)
        assertEquals(roomJid, buffer.displayName)
        assertTrue(buffer.joined)

        val nicks = db.memberDao().allNow(buffer.id).map { it.nick }.toSet()
        assertEquals(setOf("me", "alice", "bob"), nicks)
    }

    @Test
    fun `a MUC message from another occupant lands once, deduped on stanza id`() = runTest {
        val session = FakeXmppSession()
        bootstrap(listOf(session))
        val networkId = xmppNetwork("glvortex")
        connectReady(networkId, session)
        manager.joinChannel(networkId, roomJid)
        advanceUntilIdle()
        session.emitOccupantSnapshot(roomJid, listOf("me", "alice"))
        advanceUntilIdle()

        session.emitMucMessage(roomJid, "alice", "hello room", "muc-stanza-1")
        advanceUntilIdle()
        session.emitMucMessage(roomJid, "alice", "hello room", "muc-stanza-1") // redelivery
        advanceUntilIdle()

        val buffer = requireNotNull(db.bufferDao().byName(networkId, roomJid))
        val rows = db.canonicalTimelineDao().eventsForRoom(buffer.id)
        val row = rows.single { it.kind == MessageKind.PRIVMSG }
        assertEquals("hello room", row.text)
        assertEquals("alice", row.sender)
        // msgid is scoped to its assigning authority (room + occupant), not the raw stanza id
        // verbatim — see scopedMsgid's KDoc on the production side.
        assertEquals(scopedMsgid("muc-stanza-1", roomJid, "alice"), row.msgid)
        assertFalse(row.isSelf)
        assertEquals(
            listOf(EventAliasNamespace.MSGID),
            db.canonicalTimelineDao().aliasesFor(row.id).map { it.namespace },
        )
    }

    /**
     * The MUC counterpart of "two different DM senders reusing the same stanza id" above: two
     * occupants in the *same* room reusing a common raw stanza id must not collide either, even
     * though (unlike two DM peers) they already share one canonical buffer. Before this fix, the
     * second occupant's message silently merged into the first occupant's already-inserted event —
     * observably, only one row would exist instead of two, and the second occupant's text would
     * never appear at all ([io.github.trevarj.motd.data.sync.CanonicalTimelineStore.enrich] keeps
     * the existing row's content unless the incoming observation is server-tag authoritative).
     */
    @Test
    fun `two MUC occupants reusing the same stanza id land as two distinct events`() = runTest {
        val session = FakeXmppSession()
        bootstrap(listOf(session))
        val networkId = xmppNetwork("glvortex")
        connectReady(networkId, session)
        manager.joinChannel(networkId, roomJid)
        advanceUntilIdle()
        session.emitOccupantSnapshot(roomJid, listOf("me", "alice", "bob"))
        advanceUntilIdle()

        session.emitMucMessage(roomJid, "alice", "hi from alice", "1")
        session.emitMucMessage(roomJid, "bob", "hi from bob", "1") // same raw stanza id as alice's
        advanceUntilIdle()

        val buffer = requireNotNull(db.bufferDao().byName(networkId, roomJid))
        val rows = db.canonicalTimelineDao().eventsForRoom(buffer.id).filter { it.kind == MessageKind.PRIVMSG }
        assertEquals(2, rows.size)
        assertEquals(setOf("hi from alice", "hi from bob"), rows.map { it.text }.toSet())
        assertEquals(
            setOf(scopedMsgid("1", roomJid, "alice"), scopedMsgid("1", roomJid, "bob")),
            rows.mapNotNull { it.msgid }.toSet(),
        )
    }

    @Test
    fun `own-occupant echo lands isSelf=true exactly once`() = runTest {
        val session = FakeXmppSession()
        bootstrap(listOf(session))
        val networkId = xmppNetwork("glvortex")
        connectReady(networkId, session)
        manager.joinChannel(networkId, roomJid) // nick = "me" (bare-JID localpart)
        advanceUntilIdle()
        session.emitOccupantSnapshot(roomJid, listOf("me", "alice"))
        advanceUntilIdle()

        session.emitMucMessage(roomJid, "me", "hi, it's me", "muc-self-1")
        advanceUntilIdle()

        val buffer = requireNotNull(db.bufferDao().byName(networkId, roomJid))
        val rows = db.canonicalTimelineDao().eventsForRoom(buffer.id)
        val row = rows.single { it.kind == MessageKind.PRIVMSG }
        assertTrue(row.isSelf)
        assertEquals("hi, it's me", row.text)
        assertEquals(1, rows.count { it.kind == MessageKind.PRIVMSG })
    }

    @Test
    fun `a MUC subject change persists as the topic-kind event and updates the buffer topic`() = runTest {
        val session = FakeXmppSession()
        bootstrap(listOf(session))
        val networkId = xmppNetwork("glvortex")
        connectReady(networkId, session)
        manager.joinChannel(networkId, roomJid)
        advanceUntilIdle()
        session.emitOccupantSnapshot(roomJid, listOf("me"))
        advanceUntilIdle()

        session.emitMucSubject(roomJid, "Welcome to the room", "alice")
        advanceUntilIdle()

        val buffer = requireNotNull(db.bufferDao().byName(networkId, roomJid))
        assertEquals("Welcome to the room", buffer.topic)
        assertEquals("alice", buffer.topicSetBy)

        val topicRow = db.canonicalTimelineDao().eventsForRoom(buffer.id).single { it.kind == MessageKind.TOPIC }
        assertEquals("topic: Welcome to the room", topicRow.text)
        assertEquals("alice", topicRow.sender)
    }

    /**
     * P2 review finding: a MUC server replays the room's current subject on every join/reconnect
     * (XEP-0045), and Smack's `SubjectUpdatedListener` fires identically for that replay and for a
     * genuine live change (see [XmppSession.mucSubjects]' KDoc: "both can fire mid-join") -- so
     * treating every emission as a fresh change accumulated one duplicate TOPIC row per reconnect.
     * [onMucSubject] now compares against the buffer's previously persisted topic first.
     */
    @Test
    fun `a replayed subject identical to the persisted topic does not duplicate the topic row`() = runTest {
        val session = FakeXmppSession()
        bootstrap(listOf(session))
        val networkId = xmppNetwork("glvortex")
        connectReady(networkId, session)
        manager.joinChannel(networkId, roomJid)
        advanceUntilIdle()
        session.emitOccupantSnapshot(roomJid, listOf("me"))
        advanceUntilIdle()

        session.emitMucSubject(roomJid, "Welcome to the room", "alice")
        advanceUntilIdle()
        val buffer = requireNotNull(db.bufferDao().byName(networkId, roomJid))
        assertEquals(
            1,
            db.canonicalTimelineDao().eventsForRoom(buffer.id).count { it.kind == MessageKind.TOPIC },
        )

        // A reconnect rejoins the room and the server replays its current (unchanged) subject --
        // servers commonly supply no attributable occupant for this informational replay, unlike a
        // real live change.
        session.emitMucSubject(roomJid, "Welcome to the room", null)
        advanceUntilIdle()

        val topicRows = db.canonicalTimelineDao().eventsForRoom(buffer.id).filter { it.kind == MessageKind.TOPIC }
        assertEquals(1, topicRows.size) // still exactly one row, not two.
        assertEquals("Welcome to the room", requireNotNull(db.bufferDao().observeById(buffer.id)).topic)

        // A genuine change to a new value still lands as a second, distinct TOPIC row.
        session.emitMucSubject(roomJid, "New topic", "bob")
        advanceUntilIdle()

        val updatedTopicRows =
            db.canonicalTimelineDao().eventsForRoom(buffer.id).filter { it.kind == MessageKind.TOPIC }
        assertEquals(2, updatedTopicRows.size)
        assertEquals("New topic", requireNotNull(db.bufferDao().observeById(buffer.id)).topic)
    }

    @Test
    fun `leaving a room stops processing further events for it`() = runTest {
        val session = FakeXmppSession()
        bootstrap(listOf(session))
        val networkId = xmppNetwork("glvortex")
        connectReady(networkId, session)
        manager.joinChannel(networkId, roomJid)
        advanceUntilIdle()
        session.emitOccupantSnapshot(roomJid, listOf("me", "alice"))
        advanceUntilIdle()
        session.emitMucMessage(roomJid, "alice", "before leaving", "before-1")
        advanceUntilIdle()

        val buffer = requireNotNull(db.bufferDao().byName(networkId, roomJid))
        assertEquals(1, db.canonicalTimelineDao().eventsForRoom(buffer.id).size)
        assertTrue(requireNotNull(db.bufferDao().observeById(buffer.id)).joined)

        manager.partChannel(buffer.id)
        advanceUntilIdle()
        assertEquals(listOf(roomJid), session.leaveRoomCalls)

        // FakeXmppSession mirrors a real session's post-leave contract: no listener remains
        // registered for a left room, so these silently do nothing rather than landing new state.
        session.emitMucMessage(roomJid, "alice", "after leaving", "after-1")
        session.emitOccupantJoined(roomJid, "carol")
        advanceUntilIdle()

        assertEquals(1, db.canonicalTimelineDao().eventsForRoom(buffer.id).size)
        assertFalse(requireNotNull(db.bufferDao().observeById(buffer.id)).joined)
        assertTrue(db.memberDao().allNow(buffer.id).isEmpty())
    }

    @Test
    fun `occupants refresh on requestMembers`() = runTest {
        val session = FakeXmppSession()
        bootstrap(listOf(session))
        val networkId = xmppNetwork("glvortex")
        connectReady(networkId, session)
        manager.joinChannel(networkId, roomJid)
        advanceUntilIdle()
        session.emitOccupantSnapshot(roomJid, listOf("me", "alice"))
        advanceUntilIdle()

        val buffer = requireNotNull(db.bufferDao().byName(networkId, roomJid))
        assertEquals(setOf("me", "alice"), db.memberDao().allNow(buffer.id).map { it.nick }.toSet())

        manager.requestMembers(buffer.id)
        advanceUntilIdle()
        assertEquals(listOf(roomJid), session.refreshOccupantsCalls)

        // Simulate the session's response: a fresh snapshot reflecting a roster change that
        // happened between join and the refresh request.
        session.emitOccupantSnapshot(roomJid, listOf("me", "alice", "carol"))
        advanceUntilIdle()

        assertEquals(setOf("me", "alice", "carol"), db.memberDao().allNow(buffer.id).map { it.nick }.toSet())
    }

    @Test
    fun `a loaded roster upserts a UserEntity row per contact`() = runTest {
        val session = FakeXmppSession()
        bootstrap(listOf(session))
        val networkId = xmppNetwork("glvortex")
        connectReady(networkId, session)

        session.emitRosterLoad(
            XmppRosterLoad.Loaded(
                listOf(
                    XmppRosterContact("alice@example.org", "Alice"),
                    XmppRosterContact("bob@example.org", null),
                ),
            ),
        )
        advanceUntilIdle()

        val alice = requireNotNull(db.userDao().byNick(networkId, "alice@example.org"))
        assertEquals("Alice", alice.realname)
        val bob = requireNotNull(db.userDao().byNick(networkId, "bob@example.org"))
        assertNull(bob.realname)
    }

    // -- incoming 1:1 typing (slice X6; docs/backend-neutral-xmpp-rollout.md baseline "one-to-one
    // typing where supported" — the incoming half; XmppConnectionManagerTest's sendTyping_query_*
    // tests cover the outgoing half). Routed to the shared TypingTracker seam, never a Room write —
    // see XmppProcessor.onChatState's KDoc. --

    @Test
    fun `incoming composing shows the sender in the typing tracker, creating its QUERY buffer like the DM path`() =
        runTest {
            val session = FakeXmppSession()
            bootstrap(listOf(session))
            val networkId = xmppNetwork("glvortex")
            connectReady(networkId, session)

            // No prior buffer for alice — the same "unseen sender" case :irc's EventProcessor.onTag
            // already creates a buffer for via its own ensureBuffer.
            session.emitChatState("alice@example.org", XmppChatState.COMPOSING)
            advanceUntilIdle()

            val buffer = requireNotNull(db.bufferDao().byName(networkId, "alice@example.org")) {
                "expected a QUERY buffer created for the chat-state sender, consistent with the DM path"
            }
            assertEquals(BufferType.QUERY, buffer.type)
            assertEquals(listOf("alice@example.org"), typingTracker.typingNicks(buffer.id).value)
        }

    @Test
    fun `paused keeps the sender typing, active clears it`() = runTest {
        val session = FakeXmppSession()
        bootstrap(listOf(session))
        val networkId = xmppNetwork("glvortex")
        connectReady(networkId, session)
        session.emitChatState("alice@example.org", XmppChatState.COMPOSING)
        advanceUntilIdle()
        val buffer = requireNotNull(db.bufferDao().byName(networkId, "alice@example.org"))
        assertEquals(listOf("alice@example.org"), typingTracker.typingNicks(buffer.id).value)

        session.emitChatState("alice@example.org", XmppChatState.PAUSED)
        advanceUntilIdle()
        assertEquals(listOf("alice@example.org"), typingTracker.typingNicks(buffer.id).value)

        session.emitChatState("alice@example.org", XmppChatState.ACTIVE)
        advanceUntilIdle()
        assertTrue(typingTracker.typingNicks(buffer.id).value.isEmpty())
    }

    @Test
    fun `inactive and gone both clear typing, same as active`() = runTest {
        val session = FakeXmppSession()
        bootstrap(listOf(session))
        val networkId = xmppNetwork("glvortex")
        connectReady(networkId, session)
        session.emitChatState("alice@example.org", XmppChatState.COMPOSING)
        advanceUntilIdle()
        val buffer = requireNotNull(db.bufferDao().byName(networkId, "alice@example.org"))
        assertEquals(listOf("alice@example.org"), typingTracker.typingNicks(buffer.id).value)

        session.emitChatState("alice@example.org", XmppChatState.INACTIVE)
        advanceUntilIdle()
        assertTrue(typingTracker.typingNicks(buffer.id).value.isEmpty())

        session.emitChatState("alice@example.org", XmppChatState.COMPOSING)
        advanceUntilIdle()
        session.emitChatState("alice@example.org", XmppChatState.GONE)
        advanceUntilIdle()
        assertTrue(typingTracker.typingNicks(buffer.id).value.isEmpty())
    }

    @Test
    fun `a chat state reusing an existing DM buffer keys typing to that same buffer`() = runTest {
        val session = FakeXmppSession()
        bootstrap(listOf(session))
        val networkId = xmppNetwork("glvortex")
        connectReady(networkId, session)

        // A real DM first, exactly like onIncomingDirectMessage's own tests, then a chat state from
        // the same sender must land on the identical buffer rather than a second one.
        session.emit(
            XmppIncomingMessage(
                fromBareJid = "alice@example.org",
                body = "hello there",
                stanzaId = "stanza-1",
                delayStampMillis = null,
            ),
        )
        advanceUntilIdle()
        val buffer = requireNotNull(db.bufferDao().byName(networkId, "alice@example.org"))

        session.emitChatState("alice@example.org", XmppChatState.COMPOSING)
        advanceUntilIdle()

        // Same buffer id, not a second one created for the chat state.
        assertEquals(buffer.id, requireNotNull(db.bufferDao().byName(networkId, "alice@example.org")).id)
        assertEquals(listOf("alice@example.org"), typingTracker.typingNicks(buffer.id).value)
    }
}
