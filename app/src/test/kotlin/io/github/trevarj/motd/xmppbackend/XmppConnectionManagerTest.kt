package io.github.trevarj.motd.xmppbackend

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.backend.ConnectionState
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.EventAliasNamespace
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.XmppAccountEntity
import io.github.trevarj.motd.di.AppClock
import io.github.trevarj.motd.service.ImmediateWireAcceptance
import io.github.trevarj.motd.service.PendingChannelCloseCoordinator
import io.github.trevarj.motd.service.RosterLoadState
import io.github.trevarj.motd.service.SendAcceptance
import io.github.trevarj.motd.service.SendRejectionReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Mirrors [XmppProcessor]'s private `scopedMsgid`/`MSGID_AUTHORITY_SEPARATOR` (review fix — see
 * [XmppProcessorTest]'s copy of this same helper for the full rationale) without depending on that
 * implementation detail directly. The separator is built from [Int.toChar] rather than typed as a
 * literal escape so this file stays plain, unambiguous source text.
 */
private fun scopedMsgid(stanzaId: String, vararg authority: String): String =
    (authority.toList() + stanzaId).joinToString(0.toChar().toString())

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

    // -- MUC member-load state (slice X5, corrected after Branch-1 pinned memberLoadStates to buffer
    // ids: see XmppConnectionManager's KDoc). Buffer-keyed, per joined room — never the account-level
    // XMPP roster, which stays entirely internal to xmppbackend (see the two tests at the bottom). --

    private val roomJid = "room@conference.example.org"

    /** Connect [nid] on [session] and drive it to Ready. Mirrors XmppProcessorTest's helper. */
    private suspend fun TestScope.connectReady(session: FakeXmppSession) {
        manager.connect(nid)
        advanceUntilIdle()
        session.completeConnect(XmppSessionState.Ready(selfJid))
        advanceUntilIdle()
    }

    @Test
    fun memberLoadStates_joinGoesLoading_thenLoadedOnOccupantSnapshot() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))
        connectReady(s1)

        manager.joinChannel(nid, roomJid)
        advanceUntilIdle()
        val buffer = requireNotNull(db.bufferDao().byName(nid, roomJid)) {
            "joinChannel must resolve/create the room's buffer before the session actually joins"
        }
        assertEquals(RosterLoadState.LOADING, manager.memberLoadStates.value[buffer.id])

        s1.emitOccupantSnapshot(roomJid, listOf("me", "alice"))
        advanceUntilIdle()
        assertEquals(RosterLoadState.LOADED, manager.memberLoadStates.value[buffer.id])
    }

    @Test
    fun memberLoadStates_requestMembersGoesLoading_thenLoadedOnRefreshedSnapshot() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))
        connectReady(s1)
        manager.joinChannel(nid, roomJid)
        advanceUntilIdle()
        s1.emitOccupantSnapshot(roomJid, listOf("me", "alice"))
        advanceUntilIdle()
        val buffer = requireNotNull(db.bufferDao().byName(nid, roomJid))
        assertEquals(RosterLoadState.LOADED, manager.memberLoadStates.value[buffer.id])

        manager.requestMembers(buffer.id)
        advanceUntilIdle()
        assertEquals(RosterLoadState.LOADING, manager.memberLoadStates.value[buffer.id])
        assertEquals(listOf(roomJid), s1.refreshOccupantsCalls)

        s1.emitOccupantSnapshot(roomJid, listOf("me", "alice", "carol"))
        advanceUntilIdle()
        assertEquals(RosterLoadState.LOADED, manager.memberLoadStates.value[buffer.id])
    }

    @Test
    fun memberLoadStates_entryRemovedOnLeave_andOnDisconnect() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))
        connectReady(s1)
        manager.joinChannel(nid, roomJid)
        advanceUntilIdle()
        s1.emitOccupantSnapshot(roomJid, listOf("me", "alice"))
        advanceUntilIdle()
        val buffer = requireNotNull(db.bufferDao().byName(nid, roomJid))
        assertEquals(RosterLoadState.LOADED, manager.memberLoadStates.value[buffer.id])

        // Leaving drops the entry outright (not a reset to NOT_LOADED): this session receives no
        // further presence for the room, so nothing will repopulate it short of an explicit rejoin.
        manager.partChannel(buffer.id)
        advanceUntilIdle()
        assertFalse(manager.memberLoadStates.value.containsKey(buffer.id))

        // Rejoin and reach LOADED again, then drop the whole session: same removal, network-wide.
        manager.joinChannel(nid, roomJid)
        advanceUntilIdle()
        s1.emitOccupantSnapshot(roomJid, listOf("me", "alice"))
        advanceUntilIdle()
        assertEquals(RosterLoadState.LOADED, manager.memberLoadStates.value[buffer.id])

        manager.disconnect(nid)
        advanceUntilIdle()
        assertFalse(manager.memberLoadStates.value.containsKey(buffer.id))
    }

    @Test
    fun memberLoadStates_entryRemovedWhenSessionDropsMidReconnect() = runTest {
        val s1 = FakeXmppSession()
        val s2 = FakeXmppSession()
        bootstrap(listOf(s1, s2))
        connectReady(s1)
        manager.joinChannel(nid, roomJid)
        advanceUntilIdle()
        s1.emitOccupantSnapshot(roomJid, listOf("me", "alice"))
        advanceUntilIdle()
        val buffer = requireNotNull(db.bufferDao().byName(nid, roomJid))
        assertEquals(RosterLoadState.LOADED, manager.memberLoadStates.value[buffer.id])

        // An async drop after Ready (e.g. the transport listener firing) — the actor keeps running
        // and will retry, but this session's occupant knowledge is stale the moment it drops.
        s1.publish(XmppSessionState.Failed("connection reset", fatal = false))
        advanceUntilIdle()
        assertFalse(manager.memberLoadStates.value.containsKey(buffer.id))
    }

    /**
     * The account-level XMPP roster (buddy list, [XmppRosterLoad]) is a completely different concept
     * from MUC member-list load state and must never appear in the buffer-keyed [memberLoadStates]
     * map — conflating the two (keying the old `rosterStates` by network id) was exactly the bug
     * Branch 1 fixed. [XmppRosterLoad.Failed] equally must not leak into the seam, and must not fail
     * the connection itself (a roster hiccup is not a connection failure).
     */
    @Test
    fun accountRosterLoad_neverReachesMemberLoadStates_loadedOrFailed() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))
        connectReady(s1)

        s1.emitRosterLoad(XmppRosterLoad.Loaded(listOf(XmppRosterContact("alice@example.org", "Alice"))))
        advanceUntilIdle()
        assertTrue(manager.memberLoadStates.value.isEmpty())

        s1.emitRosterLoad(XmppRosterLoad.Failed("roster IQ timed out"))
        advanceUntilIdle()
        assertTrue(manager.memberLoadStates.value.isEmpty())
        assertTrue(manager.connectionStates.value[nid] is ConnectionState.Ready)
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

    // -- durable pending sends and send acknowledgements, and 1:1 typing (slice X6;
    // docs/backend-neutral-xmpp-rollout.md baseline). Exercised through the real pipeline:
    // manager.sendMessage/retryMessage/sendTyping, a live FakeXmppSession, and the shared canonical
    // tables — never a private XMPP write path. Mirrors `:irc` ConnectionManagerImpl's
    // sendMessage/retryMessage/writeDurablePlan decision structure; see XmppConnectionManager's
    // own KDoc on each method for the exact IRC idiom each mirrors. --

    private val peerJid = "alice@example.org"

    /** ensureQueryBuffer is not implemented yet (a separate, unstarted slice — see its stub's
     *  comment), so tests insert the QUERY buffer directly, exactly like BackendContractTest's
     *  createBuffer does for its fake backend. */
    private suspend fun insertQueryBuffer(networkId: Long, jid: String = peerJid): Long =
        db.bufferDao().insert(
            BufferEntity(networkId = networkId, name = jid, displayName = jid, type = BufferType.QUERY),
        )

    @Test
    fun sendMessage_dm_persistsPendingThenConfirmsAfterWireWrite() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))
        connectReady(s1)
        val bufferId = insertQueryBuffer(nid)

        val acceptance = manager.sendMessage(bufferId, "hello there")
        advanceUntilIdle()

        val accepted = acceptance as SendAcceptance.Accepted
        assertEquals(ImmediateWireAcceptance.ACCEPTED, accepted.immediateWireAcceptance)

        val sent = s1.sentMessages.single()
        assertEquals(peerJid, sent.to)
        assertEquals("hello there", sent.body)

        val row = db.canonicalTimelineDao().eventsForRoom(bufferId).single()
        assertEquals(accepted.eventIds.single(), row.id)
        assertTrue(row.isSelf)
        assertEquals("hello there", row.text)
        assertNull(row.pendingLabel) // DMs confirm on wire-write success; no echo cap to wait for.
        assertFalse(row.failed)
        assertNull(row.msgid) // never echoed back in this baseline (carbons deferred).
    }

    @Test
    fun sendMessage_dm_noLiveSession_failsImmediately() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1)) // never connected: actors[nid] stays empty, mirroring IRC's client==null.
        val bufferId = insertQueryBuffer(nid)

        val acceptance = manager.sendMessage(bufferId, "hello there")
        advanceUntilIdle()

        val accepted = acceptance as SendAcceptance.Accepted
        assertEquals(ImmediateWireAcceptance.DISCONNECTED, accepted.immediateWireAcceptance)
        assertTrue(s1.sentMessages.isEmpty())

        // Durably represented and immediately failed — mirrors IRC's writeDurablePlan
        // (client == null || ready == null -> failPendingEvents), never a 30s wait for a session
        // that was never going to answer.
        val row = db.canonicalTimelineDao().eventsForRoom(bufferId).single()
        assertTrue(row.failed)
        assertNotNull(row.pendingLabel) // failPending sets failed=1 without clearing the label.
        assertNull(row.msgid)
    }

    @Test
    fun sendMessage_muc_staysPendingUntilReflectedEcho_thenEnrichesSameRow() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))
        connectReady(s1)
        manager.joinChannel(nid, roomJid)
        advanceUntilIdle()
        val buffer = requireNotNull(db.bufferDao().byName(nid, roomJid))

        val acceptance = manager.sendMessage(buffer.id, "hi room")
        // runCurrent, NOT advanceUntilIdle: the send just armed a live 30s watchdog job (see
        // armSendTimeout), and advanceUntilIdle would fast-forward straight through it to prove
        // this test's own point moot — runCurrent settles only what's already due, exactly like
        // ConnectionRegistryTest's armEchoTimeout tests use around :irc's identical watchdog.
        runCurrent()

        val accepted = acceptance as SendAcceptance.Accepted
        assertEquals(ImmediateWireAcceptance.ACCEPTED, accepted.immediateWireAcceptance)
        val sent = s1.sentMessages.single()
        assertEquals(roomJid, sent.to)

        val pendingRow = db.canonicalTimelineDao().eventsForRoom(buffer.id).single()
        assertNotNull(pendingRow.pendingLabel) // MUC does NOT confirm on write; waits for the echo.
        assertNull(pendingRow.msgid)
        assertFalse(pendingRow.failed)

        // The room reflects the accepted message back to every occupant, including the sender
        // ("me" — the bootstrapped account's bare-JID-localpart nick), with the same stanza id this
        // session set on the outgoing send.
        s1.emitMucMessage(roomJid, "me", "hi room", stanzaId = sent.messageId)
        advanceUntilIdle()

        val rows = db.canonicalTimelineDao().eventsForRoom(buffer.id)
        val row = rows.single() // still exactly one row: the reflection enriches, never duplicates.
        // msgid is scoped to its assigning authority (room + own occupant nick), not the raw stanza
        // id verbatim; the LABEL-based reconciliation above is unaffected (it uses sent.messageId
        // directly — see scopedMsgid's KDoc on why LABEL and MSGID differ here).
        assertEquals(scopedMsgid(sent.messageId, roomJid, "me"), row.msgid)
        assertNull(row.pendingLabel)
        assertFalse(row.failed)
        assertTrue(row.isSelf)
        assertEquals(
            setOf(EventAliasNamespace.LABEL, EventAliasNamespace.MSGID),
            db.canonicalTimelineDao().aliasesFor(row.id).map { it.namespace }.toSet(),
        )
    }

    @Test
    fun sendMessage_muc_failsAfterAcknowledgementTimeout_whenNoReflectionArrives() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))
        connectReady(s1)
        manager.joinChannel(nid, roomJid)
        advanceUntilIdle()
        val buffer = requireNotNull(db.bufferDao().byName(nid, roomJid))

        manager.sendMessage(buffer.id, "hi room")
        runCurrent() // settle the send itself without racing past its own still-armed watchdog.
        assertFalse(db.canonicalTimelineDao().eventsForRoom(buffer.id).single().failed)

        // The MUC send-acknowledgement watchdog (mirrors :irc ECHO_TIMEOUT_MS): fast-forward past
        // its 30s delay and let it fire, since nothing else in this test keeps the queue non-idle.
        advanceUntilIdle()

        val row = db.canonicalTimelineDao().eventsForRoom(buffer.id).single()
        assertTrue(row.failed)
        assertNotNull(row.pendingLabel)
        assertNull(row.msgid)
    }

    /**
     * P1 review finding: [SmackXmppSession.sendMessage] used to decide "is this a room" purely from
     * its own in-session `roomListeners` map, which is empty on every fresh session and briefly stale
     * mid reconnect — so a CHANNEL send in that window silently went out as a one-to-one `chat`
     * stanza addressed at the room JID instead of failing loudly. The manager now decides the wire
     * shape itself from the buffer's own type and passes it explicitly, so a CHANNEL buffer can never
     * produce a CHAT-kind send, independent of whether THIS live session has actually (re)joined the
     * room yet. Before the fix this test could not even express the assertion: [FakeXmppSession]'s
     * `sendMessage` had no `kind` parameter at all — the wire-shape decision lived entirely inside the
     * (untested-at-this-level) real [SmackXmppSession].
     */
    @Test
    fun sendMessage_toAChannelBuffer_alwaysSendsGroupchat_evenIfThisSessionHasNotActuallyJoinedYet() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))
        connectReady(s1)
        // A CHANNEL buffer whose durable joined=true predates this live session (e.g. a rejoin still
        // in flight, or one that failed) -- this session's own join bookkeeping (s1.joinRoomCalls) is
        // empty, the exact "rejoin has not finished or failed" window the P1 finding describes.
        val bufferId = db.bufferDao().insert(
            BufferEntity(networkId = nid, name = roomJid, displayName = roomJid, type = BufferType.CHANNEL, joined = true),
        )
        assertTrue(s1.joinRoomCalls.isEmpty())

        manager.sendMessage(bufferId, "hi room")
        runCurrent()

        val sent = s1.sentMessages.single()
        assertEquals(XmppSendKind.GROUPCHAT, sent.kind) // never CHAT for a CHANNEL buffer.
    }

    @Test
    fun sendMessage_toAQueryBuffer_alwaysSendsChat() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))
        connectReady(s1)
        val bufferId = insertQueryBuffer(nid)

        manager.sendMessage(bufferId, "hello there")
        advanceUntilIdle()

        assertEquals(XmppSendKind.CHAT, s1.sentMessages.single().kind)
    }

    @Test
    fun retryMessage_issuesNewLabel_andClearsOnRetriedEcho() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))
        connectReady(s1)
        manager.joinChannel(nid, roomJid)
        advanceUntilIdle()
        val buffer = requireNotNull(db.bufferDao().byName(nid, roomJid))

        manager.sendMessage(buffer.id, "hi room")
        advanceUntilIdle() // let the send's own 30s watchdog fire, producing a failed row to retry.
        val failedRow = db.canonicalTimelineDao().eventsForRoom(buffer.id).single()
        assertTrue(failedRow.failed)
        val firstLabel = s1.sentMessages.single().messageId

        val retryAcceptance = manager.retryMessage(failedRow.id)
        // runCurrent, NOT advanceUntilIdle: the retry arms its OWN fresh 30s watchdog on the new
        // label, which advanceUntilIdle would fire immediately — see the "stays pending" test above.
        runCurrent()

        val retryAccepted = retryAcceptance as SendAcceptance.Accepted
        assertEquals(ImmediateWireAcceptance.ACCEPTED, retryAccepted.immediateWireAcceptance)
        assertEquals(listOf(failedRow.id), retryAccepted.eventIds) // same canonical row, not a new one.

        assertEquals(2, s1.sentMessages.size)
        val secondLabel = s1.sentMessages[1].messageId
        assertNotEquals(firstLabel, secondLabel)

        val retriedRow = db.canonicalTimelineDao().eventsForRoom(buffer.id).single()
        assertEquals(failedRow.id, retriedRow.id)
        assertFalse(retriedRow.failed)
        assertEquals(secondLabel, retriedRow.pendingLabel)

        s1.emitMucMessage(roomJid, "me", "hi room", stanzaId = secondLabel)
        advanceUntilIdle()

        val finalRow = db.canonicalTimelineDao().eventsForRoom(buffer.id).single()
        assertEquals(failedRow.id, finalRow.id)
        assertNull(finalRow.pendingLabel)
        assertFalse(finalRow.failed)
        assertEquals(scopedMsgid(secondLabel, roomJid, "me"), finalRow.msgid)
    }

    /**
     * REFUTES a P2 review finding claiming [sendMessage]'s two separate `withContext(NonCancellable)`
     * blocks (`processor.persistOutgoingSend`, then [writeAndTrack]'s own body) can be torn apart by
     * a caller-scope cancellation landing between them, stranding a persisted row with no wire write
     * and no timeout watchdog. Empirically false for this exact shape: [sendMessage] calls
     * [writeAndTrack] directly, with no cancellable suspension point of its own in between the two
     * `withContext(NonCancellable)` blocks, so there is nothing for an ambient cancellation to land
     * on until *after* [writeAndTrack] has already fully run. This test reproduces the identical
     * pattern in isolation — enter one `withContext(NonCancellable)`, cancel the enclosing job while
     * still inside it, then immediately enter a second, separate one — and confirms empirically (on
     * this project's actual kotlinx-coroutines-test) that the second block still runs to completion
     * and that no [CancellationException] is ever observed inside this coroutine: cancellation is
     * only ever noticed at an actual cancellable suspension point, and this shape has none between
     * the two blocks. (`:irc`'s `ConnectionManagerImpl.sendMessage` has the identical two-block shape
     * around `completeDurableAcceptance` — its KDoc says as much: "Return durable acceptance even if
     * the caller is cancelled after the transaction commits" — for exactly this reason.)
     */
    @Test
    fun sendMessage_twoSeparateNonCancellableBlocks_bothStillRunToCompletion_ifTheCallerCancelsInBetween() =
        runTest {
            var firstBlockRan = false
            var secondBlockRan = false
            var observedCancellation = false
            val enteredFirst = kotlinx.coroutines.CompletableDeferred<Unit>()
            val proceed = kotlinx.coroutines.CompletableDeferred<Unit>()

            val job = launch {
                try {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        enteredFirst.complete(Unit)
                        proceed.await() // pause here so the test can cancel while inside this block
                        firstBlockRan = true
                    }
                    // Mirrors the gap between persistOutgoingSend's withContext(NonCancellable) and
                    // writeAndTrack's own, separate withContext(NonCancellable) in sendMessage/
                    // retryMessage: nothing cancellable runs between the two.
                    withContext(kotlinx.coroutines.NonCancellable) {
                        secondBlockRan = true
                    }
                } catch (cancelled: CancellationException) {
                    observedCancellation = true
                    throw cancelled
                }
            }

            enteredFirst.await()
            job.cancel() // e.g. a ViewModel scope torn down mid-send
            proceed.complete(Unit) // let the first block's own suspension resume and finish its work
            advanceUntilIdle()

            assertTrue("the first NonCancellable block must still complete", firstBlockRan)
            assertTrue(
                "the second, separate NonCancellable block must ALSO still run -- there is no " +
                    "cancellable checkpoint between the two for the cancellation to land on",
                secondBlockRan,
            )
            assertFalse("no CancellationException is ever observed inside this coroutine", observedCancellation)
        }

    @Test
    fun sendMessage_rejectsUnknownBuffer() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))
        connectReady(s1)

        val acceptance = manager.sendMessage(999_999L, "hello")
        advanceUntilIdle()

        assertEquals(SendAcceptance.Rejected(SendRejectionReason.BUFFER_NOT_FOUND), acceptance)
        assertTrue(s1.sentMessages.isEmpty())
    }

    @Test
    fun sendMessage_rejectsEmptyText() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))
        connectReady(s1)
        val bufferId = insertQueryBuffer(nid)

        val acceptance = manager.sendMessage(bufferId, "")
        advanceUntilIdle()

        assertEquals(SendAcceptance.Rejected(SendRejectionReason.INVALID_CONTENT), acceptance)
        assertTrue(db.canonicalTimelineDao().eventsForRoom(bufferId).isEmpty())
    }

    @Test
    fun sendTyping_query_mapsActivePausedDoneToChatStates() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))
        connectReady(s1)
        val bufferId = insertQueryBuffer(nid)

        manager.sendTyping(bufferId, "active")
        manager.sendTyping(bufferId, "paused")
        manager.sendTyping(bufferId, "done")
        advanceUntilIdle()

        assertEquals(
            listOf(
                peerJid to XmppChatState.COMPOSING,
                peerJid to XmppChatState.PAUSED,
                peerJid to XmppChatState.ACTIVE,
            ),
            s1.sentChatStates,
        )
    }

    @Test
    fun sendTyping_muc_isNoOp() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))
        connectReady(s1)
        manager.joinChannel(nid, roomJid)
        advanceUntilIdle()
        val buffer = requireNotNull(db.bufferDao().byName(nid, roomJid))

        manager.sendTyping(buffer.id, "active")
        advanceUntilIdle()

        assertTrue(s1.sentChatStates.isEmpty())
    }

    /**
     * A reply's `replyToMsgid` (carried for the shared reply-preview UI; see
     * [XmppProcessor.persistOutgoingSend]'s KDoc) must show the SAME authority-scoped value already
     * stored on the parent row, not the bare wire stanza id some other peer could also be using.
     */
    @Test
    fun sendMessage_reply_carriesTheAuthorityScopedParentMsgid() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))
        connectReady(s1)

        s1.emit(XmppIncomingMessage(fromBareJid = peerJid, body = "original", stanzaId = "1", delayStampMillis = null))
        advanceUntilIdle()
        val buffer = requireNotNull(db.bufferDao().byName(nid, peerJid))
        val original = db.canonicalTimelineDao().eventsForRoom(buffer.id).single()
        assertEquals(scopedMsgid("1", peerJid), original.msgid)

        val acceptance = manager.sendMessage(buffer.id, "a reply", replyToEventId = original.id)
        advanceUntilIdle()

        val accepted = acceptance as SendAcceptance.Accepted
        val replyRow = db.canonicalTimelineDao().eventsForRoom(buffer.id).single { it.id == accepted.eventIds.single() }
        assertEquals(original.id, replyRow.replyToEventId)
        assertEquals(original.msgid, replyRow.replyToMsgid)
    }

    // -- ensureQueryBuffer / ensureServerBuffer (review fix: these used to error() from
    // viewModelScope for every XMPP network instead of opening a buffer — /msg, /query, "Message
    // user", and the drawer's "Server messages" entry all reach these through ConnectionManager). --

    @Test
    fun ensureQueryBuffer_findsOrCreatesTheQueryBufferForABareJid() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))

        val bufferId = manager.ensureQueryBuffer(nid, peerJid)
        advanceUntilIdle()

        val buffer = requireNotNull(db.bufferDao().rawById(bufferId))
        assertEquals(BufferType.QUERY, buffer.type)
        assertEquals(peerJid, buffer.displayName)

        // Idempotent: a second call resolves the identical buffer rather than creating another one.
        assertEquals(bufferId, manager.ensureQueryBuffer(nid, peerJid))
    }

    @Test
    fun ensureQueryBuffer_resolvesTheSameBufferAnIncomingDmAlreadyCreated() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))
        connectReady(s1)

        s1.emit(XmppIncomingMessage(fromBareJid = peerJid, body = "hi", stanzaId = "1", delayStampMillis = null))
        advanceUntilIdle()
        val existing = requireNotNull(db.bufferDao().byName(nid, peerJid))

        assertEquals(existing.id, manager.ensureQueryBuffer(nid, peerJid))
    }

    @Test
    fun ensureServerBuffer_findsOrCreatesTheServerBuffer_namedAfterTheNetwork() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))

        val bufferId = manager.ensureServerBuffer(nid)
        advanceUntilIdle()

        val buffer = requireNotNull(db.bufferDao().rawById(bufferId))
        assertEquals(BufferType.SERVER, buffer.type)
        assertEquals("glvortex", buffer.displayName) // bootstrap()'s network row is named "glvortex".

        // Idempotent, exactly like ensureQueryBuffer above.
        assertEquals(bufferId, manager.ensureServerBuffer(nid))
    }

    // -- MUC rejoin after reconnect (review fix: a session drop used to leave Room's `joined = true`
    // buffers with no live room membership at all on the replacement session, since a fresh
    // XmppSession/SmackXmppSession always starts with no rooms joined). --

    @Test
    fun reconnect_rejoinsPersistedJoinedChannels_onTheReplacementSession() = runTest {
        val s1 = FakeXmppSession()
        val s2 = FakeXmppSession()
        bootstrap(listOf(s1, s2))
        connectReady(s1)
        manager.joinChannel(nid, roomJid)
        advanceUntilIdle()
        s1.emitOccupantSnapshot(roomJid, listOf("me", "alice"))
        advanceUntilIdle()
        val buffer = requireNotNull(db.bufferDao().byName(nid, roomJid))
        assertTrue(requireNotNull(db.bufferDao().rawById(buffer.id)).joined)
        assertEquals(listOf(roomJid to "me"), s1.joinRoomCalls)

        // An async drop after Ready (e.g. the transport listener firing) — the actor reconnects with
        // a brand-new session, which (like a real SmackXmppSession) starts with no room membership.
        s1.publish(XmppSessionState.Failed("connection reset", fatal = false))
        advanceUntilIdle()
        s2.completeConnect(XmppSessionState.Ready(selfJid))
        advanceUntilIdle()

        // The new session must have actually rejoined the room — not just inherited Room's stale
        // joined=true — so a subsequent send is addressed as a groupchat, not silently misrouted to
        // a one-to-one chat stanza (see SmackXmppSession.sendMessage's own-session roomListeners check).
        assertEquals(listOf(roomJid to "me"), s2.joinRoomCalls)
    }

    @Test
    fun reconnect_doesNotRejoinAChannelThatWasExplicitlyLeftBeforeTheDrop() = runTest {
        val s1 = FakeXmppSession()
        val s2 = FakeXmppSession()
        bootstrap(listOf(s1, s2))
        connectReady(s1)
        manager.joinChannel(nid, roomJid)
        advanceUntilIdle()
        s1.emitOccupantSnapshot(roomJid, listOf("me", "alice"))
        advanceUntilIdle()
        val buffer = requireNotNull(db.bufferDao().byName(nid, roomJid))

        manager.partChannel(buffer.id)
        advanceUntilIdle()
        assertFalse(requireNotNull(db.bufferDao().rawById(buffer.id)).joined)

        s1.publish(XmppSessionState.Failed("connection reset", fatal = false))
        advanceUntilIdle()
        s2.completeConnect(XmppSessionState.Ready(selfJid))
        advanceUntilIdle()

        assertTrue(s2.joinRoomCalls.isEmpty())
    }

    // -- wire target field consistency (review fix, P2 finding: partChannel/requestMembers used to
    // address the room by BufferEntity.name -- BufferStore.getOrCreate's own internal row key, which
    // it disambiguates with a NUL-separated type suffix on a name collision with another buffer, so
    // it is not always a valid JID -- while sendMessage/sendTyping already (correctly) used
    // displayName, which BufferStore never touches and which always retains the exact address a room
    // was found-or-created with. All four wire paths now agree on displayName, matching the existing
    // "wire targets always retain server spelling" contract RoomEntity.ircTarget and
    // BufferDao.joinedChannelNames/observeJoinedChannelNames already document for IRC.) --

    @Test
    fun partChannel_addressesTheRoomByDisplayName_notTheDisambiguatedInternalKey() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))
        connectReady(s1)
        // Simulates BufferStore.getOrCreate's own collision-disambiguation shape: `name` carries a
        // NUL-separated type suffix and is no longer a valid bare JID, while `displayName` is still
        // the exact room address.
        val bufferId = db.bufferDao().insert(
            BufferEntity(
                networkId = nid,
                name = "$roomJid\u0000channel",
                displayName = roomJid,
                type = BufferType.CHANNEL,
                joined = true,
            ),
        )

        manager.partChannel(bufferId)
        advanceUntilIdle()

        assertEquals(listOf(roomJid), s1.leaveRoomCalls)
    }

    @Test
    fun requestMembers_addressesTheRoomByDisplayName_notTheDisambiguatedInternalKey() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))
        connectReady(s1)
        val bufferId = db.bufferDao().insert(
            BufferEntity(
                networkId = nid,
                name = "$roomJid\u0000channel",
                displayName = roomJid,
                type = BufferType.CHANNEL,
                joined = true,
            ),
        )

        manager.requestMembers(bufferId)
        advanceUntilIdle()

        assertEquals(listOf(roomJid), s1.refreshOccupantsCalls)
    }

    // -- account fingerprint must compare fields, not an ambiguously-joined string (review fix, P2
    // finding: "a:b"/"c" and "a"/"b:c" used to join to the identical "$jid:a:b:c" string, so an
    // edited account with exactly this shape of change could read as unchanged and keep stale
    // credentials). --

    @Test
    fun xmppAccountFingerprint_distinguishesFieldsThatWouldCollideUnderAnUnescapedSeparator() {
        // password "a:b" + resource "c"  vs  password "a" + resource "b:c": both would join to the
        // exact same "$jid:a:b:c" string under a plain ":"-separated concatenation.
        val edited = XmppAccountEntity(networkId = nid, jid = selfJid, password = "a:b", resource = "c")
        val original = XmppAccountEntity(networkId = nid, jid = selfJid, password = "a", resource = "b:c")

        assertNotEquals(xmppAccountFingerprint(edited), xmppAccountFingerprint(original))
    }

    @Test
    fun reconcile_rebuildsTheActor_whenACollidingCredentialEditChangesOnlyTheSeparatorPosition() = runTest {
        val s1 = FakeXmppSession()
        val s2 = FakeXmppSession()
        bootstrap(listOf(s1, s2))
        db.xmppAccountDao().upsert(
            XmppAccountEntity(networkId = nid, jid = selfJid, password = "a", resource = "b:c"),
        )
        manager.startAll()
        advanceUntilIdle()
        s1.completeConnect(XmppSessionState.Ready(selfJid))
        advanceUntilIdle()
        assertEquals(1, factory.created.size)

        // A password/resource edit that a ":"-joined fingerprint string would read as unchanged.
        // reconcile is driven off networkDao's own invalidation, not the xmpp_accounts satellite
        // table, so a network-row touch is what actually re-drives it -- mirrors
        // reconcile_revivesAFatallyParkedActor_onceThePersistedPasswordIsCorrected below.
        db.xmppAccountDao().upsert(
            XmppAccountEntity(networkId = nid, jid = selfJid, password = "a:b", resource = "c"),
        )
        db.networkDao().update(requireNotNull(db.networkDao().byId(nid)))
        advanceUntilIdle()

        assertEquals(2, factory.created.size)
        assertEquals("a:b", factory.accountsUsed[1].password)
        assertEquals("c", factory.accountsUsed[1].resource)
    }

    // -- credential fingerprint (review fix: XmppAccountActor captures its XmppAccountEntity at
    // construction and never re-reads Room, and reconcile used to skip every id already tracked in
    // `actors`, so an edited JID/password/resource — even one that fixed a fatal auth failure — was
    // never picked up until something fully disconnected and reconnected the actor from scratch). --

    @Test
    fun reconcile_revivesAFatallyParkedActor_onceThePersistedPasswordIsCorrected() = runTest {
        val s1 = FakeXmppSession()
        val s2 = FakeXmppSession()
        bootstrap(listOf(s1, s2))
        manager.startAll()
        advanceUntilIdle()
        assertEquals(1, factory.created.size)

        s1.completeConnect(XmppSessionState.Failed(XMPP_AUTH_FAILURE_REASON, fatal = true))
        advanceUntilIdle()
        assertTrue((manager.connectionStates.value[nid] as ConnectionState.Failed).fatal)

        // Reconcile alone must never revive an UNCHANGED fatal park (pre-existing, tested behavior —
        // see fatalAuthFailure_parksWithoutRetry above).
        manager.reconnectStale()
        advanceUntilIdle()
        assertEquals(1, factory.created.size)

        // The user fixes the password via the edit screen. XmppAccountRepository.updateAccount writes
        // both the network row and the xmpp_accounts row in one transaction, so this mirrors that.
        db.xmppAccountDao().upsert(XmppAccountEntity(networkId = nid, jid = selfJid, password = "correct-password"))
        db.networkDao().update(requireNotNull(db.networkDao().byId(nid)))
        advanceUntilIdle()

        // The credential change alone, through the always-running reconcile loop, must revive the
        // parked actor with the corrected password — the user has no separate "reconnect" control to
        // find (XmppAccountViewModel.save's edit path never calls ConnectionManager.connect).
        assertEquals(2, factory.created.size)
        assertEquals("correct-password", factory.accountsUsed[1].password)

        s2.completeConnect(XmppSessionState.Ready(selfJid))
        advanceUntilIdle()
        assertTrue(manager.connectionStates.value[nid] is ConnectionState.Ready)
    }

    @Test
    fun connect_rebuildsAnAlreadyAliveActor_whenCalledAfterAPasswordEdit() = runTest {
        val s1 = FakeXmppSession()
        val s2 = FakeXmppSession()
        bootstrap(listOf(s1, s2))
        manager.connect(nid)
        advanceUntilIdle()
        s1.completeConnect(XmppSessionState.Ready(selfJid))
        advanceUntilIdle()
        assertEquals(1, factory.created.size)

        // Calling connect() again with nothing changed must be a no-op: still alive, same fingerprint.
        manager.connect(nid)
        advanceUntilIdle()
        assertEquals(1, factory.created.size)
        assertEquals(0, s1.disconnectCalls)

        db.xmppAccountDao().upsert(XmppAccountEntity(networkId = nid, jid = selfJid, password = "new-password"))
        advanceUntilIdle()

        // Still alive on the OLD credentials here: only connect() (not startAll()'s reconcile loop)
        // is in play in this test, and the actor was never told anything changed. An explicit
        // reconnect must still notice the edit rather than leaving it connected on stale creds.
        manager.connect(nid)
        advanceUntilIdle()

        assertEquals(2, factory.created.size)
        assertEquals("new-password", factory.accountsUsed[1].password)
        assertEquals(1, s1.disconnectCalls) // the stale actor was actually torn down, not just replaced.
    }

    // -- markRead (review fix: this used to be a no-op, so XMPP conversations never cleared unread
    // and "mark all read" did nothing for them). --

    @Test
    fun markRead_advancesTheLocalReadAnchor_forAnIncomingMessage() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))
        connectReady(s1)

        s1.emit(XmppIncomingMessage(fromBareJid = peerJid, body = "hi", stanzaId = "1", delayStampMillis = null))
        advanceUntilIdle()
        val buffer = requireNotNull(db.bufferDao().byName(nid, peerJid))
        val row = db.canonicalTimelineDao().eventsForRoom(buffer.id).single()
        assertNull(db.bufferDao().observeById(buffer.id)?.localReadAnchorTime)

        manager.markRead(buffer.id, TimelineAnchor(row.serverTime, row.id))
        advanceUntilIdle()

        val updated = requireNotNull(db.bufferDao().observeById(buffer.id))
        assertEquals(row.serverTime, updated.localReadAnchorTime)
        assertEquals(row.id, updated.localReadAnchorEventId)
    }

    // -- connectionActivity (review fix, P1 finding: this manager published only connectionStates,
    // which shared chat UI never reads -- ChatViewModel resolves a buffer's connection state
    // exclusively from connectionManager.connectionActivity, so every XMPP buffer rendered as
    // disconnected even while this manager's own map said Ready). --

    @Test
    fun connectionActivity_mirrorsConnectionStates_soChatUiSeesTheLiveSession() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))
        connectReady(s1)

        // ChatViewModel's own lookup: activity.states[networkId] ?: Disconnected.
        assertEquals(
            ConnectionState.Ready(selfHandle = selfJid, generation = 1L, negotiationRevision = 0),
            manager.connectionActivity.value.states[nid],
        )
        assertEquals(manager.connectionStates.value, manager.connectionActivity.value.states)
        // XMPP has no reconnect history catch-up in this baseline, so nothing is ever pending.
        assertTrue(manager.connectionActivity.value.historyCatchUpPending.isEmpty())
    }

    @Test
    fun connectionActivity_initializationCompletesOnlyAfterTheFirstReconcile() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))

        assertFalse(manager.connectionActivity.value.initializationComplete)

        manager.startAll()
        advanceUntilIdle()

        assertTrue(manager.connectionActivity.value.initializationComplete)
        assertEquals(ConnectionState.Connecting, manager.connectionActivity.value.states[nid])
    }

    @Test
    fun connectionActivity_progressingTracksActorLiveness_andClearsOnAFatalPark() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))

        manager.startAll()
        advanceUntilIdle()
        assertEquals(true, manager.connectionActivity.value.progressing[nid])

        // A fatal park ends the actor's loop for good. ChatViewModel.entryHistoryReady waits on
        // `progressing[networkId] != true` for a Failed network, so a stale `true` here would hang
        // chat entry on this network forever.
        s1.completeConnect(XmppSessionState.Failed(XMPP_AUTH_FAILURE_REASON, fatal = true))
        advanceUntilIdle()

        assertTrue(manager.connectionActivity.value.states[nid] is ConnectionState.Failed)
        assertEquals(false, manager.connectionActivity.value.progressing[nid])
    }

    // -- partChannelForClose (review fix, P2 finding: not overridden at all, so a durable channel
    // close inherited the seam default -- "call partChannel, report true unless it threw" -- and no
    // XMPP event ever completed it, leaving PendingChannelCloseCoordinator in AWAITING_CONFIRMATION
    // forever while the hidden row was never deleted). --

    @Test
    fun partChannelForClose_completesTheDurableClose_sinceXmppHasNoSelfPartEcho() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))
        connectReady(s1)
        manager.joinChannel(nid, roomJid)
        advanceUntilIdle()
        s1.emitOccupantSnapshot(roomJid, listOf("me", "alice"))
        advanceUntilIdle()
        val buffer = requireNotNull(db.bufferDao().byName(nid, roomJid))
        db.bufferDao().markPendingClose(buffer.id, 1_234L)

        val accepted = manager.partChannelForClose(buffer.id)
        advanceUntilIdle()

        assertTrue(accepted)
        assertEquals(listOf(roomJid), s1.leaveRoomCalls)
        // Same end state IRC reaches from EventProcessor.onParted's self-PART branch: the hidden row
        // and its history are gone, so the coordinator's next sweep finds nothing left to retry.
        assertNull(db.bufferDao().rawById(buffer.id))
        assertFalse(manager.memberLoadStates.value.containsKey(buffer.id))
    }

    @Test
    fun channelClose_throughTheCoordinator_deletesTheHiddenRowInsteadOfRetryingForever() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))
        connectReady(s1)
        manager.joinChannel(nid, roomJid)
        advanceUntilIdle()
        s1.emitOccupantSnapshot(roomJid, listOf("me"))
        advanceUntilIdle()
        val buffer = requireNotNull(db.bufferDao().byName(nid, roomJid))
        val coordinator = PendingChannelCloseCoordinator.forTest(
            db = db,
            connections = manager,
            clock = AppClock { 1_234L },
            scope = appScope,
            // Any scheduled retry parks forever, so this asserts the close completed on the first
            // attempt rather than being papered over by a later sweep.
            retryWait = { awaitCancellation() },
        )

        coordinator.requestClose(buffer.id)
        advanceUntilIdle()

        assertEquals(listOf(roomJid), s1.leaveRoomCalls)
        assertNull(db.bufferDao().rawById(buffer.id))
    }

    @Test
    fun partChannelForClose_plainLeaveKeepsTheBufferAndItsHistory() = runTest {
        // ChannelInfoViewModel.part() uses the same seam for a non-durable "leave channel", where
        // pendingCloseAt is null: that must stay a leave, exactly like IRC's non-close self-PART.
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))
        connectReady(s1)
        manager.joinChannel(nid, roomJid)
        advanceUntilIdle()
        s1.emitOccupantSnapshot(roomJid, listOf("me"))
        advanceUntilIdle()
        val buffer = requireNotNull(db.bufferDao().byName(nid, roomJid))
        assertTrue(requireNotNull(db.bufferDao().rawById(buffer.id)).joined)

        val accepted = manager.partChannelForClose(buffer.id)
        advanceUntilIdle()

        assertTrue(accepted)
        assertEquals(listOf(roomJid), s1.leaveRoomCalls)
        val stillThere = requireNotNull(db.bufferDao().rawById(buffer.id))
        assertFalse(stillThere.joined)
    }

    @Test
    fun partChannelForClose_reportsFalse_whenThereIsNoLiveReadySession() = runTest {
        val s1 = FakeXmppSession()
        bootstrap(listOf(s1))
        val bufferId = db.bufferDao().insert(
            BufferEntity(
                networkId = nid,
                name = roomJid,
                displayName = roomJid,
                type = BufferType.CHANNEL,
                joined = true,
            ),
        )
        db.bufferDao().markPendingClose(bufferId, 1_234L)

        // Never connected: the coordinator must retry, not treat the close as done and delete it.
        assertFalse(manager.partChannelForClose(bufferId))
        assertNotNull(db.bufferDao().rawById(bufferId))
        assertTrue(s1.leaveRoomCalls.isEmpty())
    }
}
