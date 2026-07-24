package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.Protocol
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoutingConnectionManagerTest {

    /** Recording fake for the IRC side. Implements the full [ConnectionManager] interface, the
     *  same surface `ConnectionManagerImpl` implements, so it can stand in for the
     *  [IrcConnectionManager]-qualified binding without constructing the real (heavily-dependency-
     *  laden) class. */
    private class FakeIrc : ConnectionManager {
        override val connectionStates = MutableStateFlow<Map<Long, IrcClientState>>(emptyMap())
        override val certPrompts = MutableStateFlow<List<CertPrompt>>(emptyList())

        var startAllCalls = 0
        var stopAllCalls = 0
        var reconnectStaleCalls = 0
        var lastConnect: Long? = null
        var lastDisconnect: Long? = null
        var lastSendMessageArgs: Any? = null
        var sendReactCalled = false
        var requestMembersCalled = false
        var markReadCalled = false
        var lastPartChannelForClose: Pair<Long, String?>? = null

        override fun clientFor(networkId: Long): IrcClient? = null
        override suspend fun startAll() { startAllCalls++ }
        override suspend fun stopAll() { stopAllCalls++ }
        override suspend fun connect(networkId: Long) { lastConnect = networkId }
        override suspend fun disconnect(networkId: Long) { lastDisconnect = networkId }
        override suspend fun reconnectStale() { reconnectStaleCalls++ }

        override suspend fun sendMessage(
            bufferId: Long,
            text: String,
            replyToEventId: Long?,
        ): SendAcceptance {
            lastSendMessageArgs = Triple(bufferId, text, replyToEventId)
            return SendAcceptance.Accepted(listOf(1L))
        }

        override suspend fun sendTyping(bufferId: Long, state: String) = Unit

        override suspend fun sendReact(bufferId: Long, msgid: String, emoji: String) {
            sendReactCalled = true
        }

        override suspend fun joinChannel(networkId: Long, channel: String) = Unit

        override suspend fun requestMembers(bufferId: Long, force: Boolean) {
            requestMembersCalled = true
        }

        override suspend fun partChannel(bufferId: Long, reason: String?) = Unit

        override suspend fun partChannelForClose(bufferId: Long, reason: String?): Boolean {
            lastPartChannelForClose = bufferId to reason
            return true
        }

        override suspend fun ensureQueryBuffer(networkId: Long, nick: String): Long = 100L
        override suspend fun ensureServerBuffer(networkId: Long): Long = 200L

        override suspend fun markRead(bufferId: Long, anchor: TimelineAnchor) {
            markReadCalled = true
        }

        override suspend fun evaluatePushMode() = Unit
        override suspend fun trustCert(prompt: CertPrompt) = Unit
        override fun dismissCertPrompt(prompt: CertPrompt) = Unit
    }

    /** Recording fake for the XMPP side, implementing the router-only [XmppConnectionSurface]. */
    private class FakeXmpp : XmppConnectionSurface {
        override val connectionStates = MutableStateFlow<Map<Long, IrcClientState>>(emptyMap())

        var startAllCalls = 0
        var stopAllCalls = 0
        var reconnectStaleCalls = 0
        var lastConnect: Long? = null
        var lastDisconnect: Long? = null
        var lastSendMessage: Pair<Long, String>? = null
        var lastPartChannel: Pair<Long, String?>? = null
        var lastJoinChannel: Pair<Long, String>? = null

        override suspend fun startAll() { startAllCalls++ }
        override suspend fun stopAll() { stopAllCalls++ }
        override suspend fun connect(networkId: Long) { lastConnect = networkId }
        override suspend fun disconnect(networkId: Long) { lastDisconnect = networkId }
        override suspend fun reconnectStale() { reconnectStaleCalls++ }

        override suspend fun sendMessage(bufferId: Long, text: String): SendAcceptance {
            lastSendMessage = bufferId to text
            return SendAcceptance.Accepted(listOf(2L))
        }

        override suspend fun sendTyping(bufferId: Long, state: String) = Unit

        override suspend fun joinChannel(networkId: Long, roomJid: String) {
            lastJoinChannel = networkId to roomJid
        }

        override suspend fun partChannel(bufferId: Long, reason: String?) {
            lastPartChannel = bufferId to reason
        }

        override suspend fun ensureQueryBuffer(networkId: Long, bareJid: String): Long = 300L
        override suspend fun ensureServerBuffer(networkId: Long): Long = 400L
    }

    private lateinit var db: MotdDatabase
    private lateinit var irc: FakeIrc
    private lateinit var xmpp: FakeXmpp

    private var ircNetworkId = 0L
    private var xmppNetworkId = 0L
    private var ircBufferId = 0L
    private var xmppBufferId = 0L

    @Before
    fun setUp() = runTest {
        db = inMemoryDb()
        irc = FakeIrc()
        xmpp = FakeXmpp()
        ircNetworkId = db.networkDao().insert(network("irc-net"))
        xmppNetworkId = db.networkDao().insert(network("xmpp-net").copy(protocol = Protocol.XMPP))
        ircBufferId = db.bufferDao().insert(buffer(ircNetworkId, "#chan"))
        xmppBufferId = db.bufferDao().insert(buffer(xmppNetworkId, "friend@example.com"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun router(scope: CoroutineScope) =
        RoutingConnectionManager(irc, xmpp, db, scope)

    @Test
    fun sendMessage_ircBuffer_goesToIrc() = runTest {
        val router = router(backgroundScope)
        val result = router.sendMessage(ircBufferId, "hi")
        assertTrue(result is SendAcceptance.Accepted)
        assertEquals(Triple(ircBufferId, "hi", null), irc.lastSendMessageArgs)
        assertNull(xmpp.lastSendMessage)
    }

    @Test
    fun sendMessage_xmppBuffer_goesToXmpp() = runTest {
        val router = router(backgroundScope)
        val result = router.sendMessage(xmppBufferId, "hi")
        assertTrue(result is SendAcceptance.Accepted)
        assertEquals(xmppBufferId to "hi", xmpp.lastSendMessage)
        assertNull(irc.lastSendMessageArgs)
    }

    @Test
    fun sendMessage_deletedBuffer_rejected() = runTest {
        val router = router(backgroundScope)
        val result = router.sendMessage(999_999L, "hi")
        assertEquals(SendAcceptance.Rejected(SendRejectionReason.BUFFER_NOT_FOUND), result)
        assertNull(irc.lastSendMessageArgs)
        assertNull(xmpp.lastSendMessage)
    }

    @Test
    fun startAll_fansOutToBoth() = runTest {
        val router = router(backgroundScope)
        router.startAll()
        assertEquals(1, irc.startAllCalls)
        assertEquals(1, xmpp.startAllCalls)
    }

    @Test
    fun states_merge() = runTest {
        val router = router(backgroundScope)
        runCurrent()
        irc.connectionStates.value = mapOf(ircNetworkId to IrcClientState.Disconnected)
        xmpp.connectionStates.value = mapOf(xmppNetworkId to IrcClientState.Disconnected)
        val merged = router.connectionStates
        // Force the combine collector (launched Eagerly in backgroundScope) to process both
        // emissions before asserting on the merged snapshot.
        runCurrent()
        assertEquals(
            mapOf(
                ircNetworkId to IrcClientState.Disconnected,
                xmppNetworkId to IrcClientState.Disconnected,
            ),
            merged.value,
        )
    }

    @Test
    fun sendReact_xmppBuffer_isNoop() = runTest {
        val router = router(backgroundScope)
        router.sendReact(xmppBufferId, "msgid123", "👍")
        assertFalse(irc.sendReactCalled)
    }

    @Test
    fun partChannelForClose_xmpp_returnsTrue() = runTest {
        val router = router(backgroundScope)
        val result = router.partChannelForClose(xmppBufferId, "bye")
        assertTrue(result)
        assertEquals(xmppBufferId to "bye", xmpp.lastPartChannel)
        assertNull(irc.lastPartChannelForClose)
    }
}
