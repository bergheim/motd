package io.github.trevarj.motd.ui.channellist

import androidx.lifecycle.SavedStateHandle
import io.github.trevarj.motd.backend.ConnectionState
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.ircbackend.IrcSessions
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.SendAcceptance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [ChannelListViewModel] tests for the P2 review finding: XMPP rows appear in the shared
 * new-conversation network picker with Browse enabled, but [ChannelListViewModel] used to wait on
 * [IrcSessions.sessionFor] unconditionally and necessarily time out, since XMPP never registers a
 * live session with that IRC-owned accessor at all.
 *
 * Robolectric (mirroring [io.github.trevarj.motd.ui.chat.ChatViewModelTest]): [ChannelListViewModel]
 * parses its [networkId][ChannelListViewModel] out of [SavedStateHandle] via `toRoute`, which needs
 * real (Robolectric-shadowed) `Bundle` support rather than the unmocked Android stubs plain JUnit gets.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelListViewModelTest {

    private class FakeNetworkRepository(private val network: NetworkEntity) : NetworkRepository {
        override fun observeNetworks(): Flow<List<NetworkEntity>> = flowOf(listOf(network))
        override suspend fun addNetwork(n: NetworkEntity): Long = 0
        override suspend fun updateNetwork(n: NetworkEntity) = Unit
        override suspend fun deleteNetwork(id: Long) = Unit
        override suspend fun networkById(id: Long): NetworkEntity? = network.takeIf { it.id == id }
        override suspend fun childrenOf(rootId: Long): List<NetworkEntity> = emptyList()
    }

    private class FakeBufferRepository : BufferRepository {
        override fun observeChatList(): Flow<List<ChatListRow>> = flowOf(emptyList())
        override fun observeBuffer(id: Long): Flow<io.github.trevarj.motd.data.db.BufferEntity?> = flowOf(null)
        override fun observeMembers(bufferId: Long): Flow<List<MemberEntity>> = flowOf(emptyList())
        override suspend fun setPinned(id: Long, pinned: Boolean) = Unit
        override suspend fun setMuted(id: Long, muted: Boolean) = Unit
        override suspend fun setLayoutDensityOverride(id: Long, layout: LayoutDensity?) = true
        override suspend fun deleteBuffer(id: Long) = Unit
    }

    /** Records every call so a test can prove the unsupported path never reaches it at all. */
    private class FakeIrcSessions : IrcSessions {
        var calls = 0
        override fun sessionFor(networkId: Long): IrcClient? {
            calls++
            return null
        }
    }

    private class FakeConnectionManager(var discoverySupported: Boolean) : ConnectionManager {
        override val connectionStates = MutableStateFlow<Map<Long, ConnectionState>>(emptyMap())
        override suspend fun startAll() = Unit
        override suspend fun stopAll() = Unit
        override suspend fun connect(networkId: Long) = Unit
        override suspend fun disconnect(networkId: Long) = Unit
        override suspend fun reconnectStale() = Unit
        override suspend fun supportsRoomDiscovery(networkId: Long): Boolean = discoverySupported
        override suspend fun sendMessage(bufferId: Long, text: String, replyToEventId: Long?) =
            SendAcceptance.Accepted(emptyList())
        override suspend fun sendTyping(bufferId: Long, state: String) = Unit
        override suspend fun sendReact(bufferId: Long, msgid: String, emoji: String) = Unit
        override suspend fun joinChannel(networkId: Long, channel: String) = Unit
        override suspend fun partChannel(bufferId: Long, reason: String?) = Unit
        override suspend fun ensureQueryBuffer(networkId: Long, nick: String): Long = 0
        override suspend fun ensureServerBuffer(networkId: Long): Long = 0
        override suspend fun markRead(bufferId: Long, anchor: TimelineAnchor) = Unit
        override suspend fun evaluatePushMode() = Unit
        override val certPrompts = MutableStateFlow<List<CertPrompt>>(emptyList())
        override suspend fun trustCert(prompt: CertPrompt) = Unit
        override fun dismissCertPrompt(prompt: CertPrompt) = Unit
    }

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val xmppNetwork = NetworkEntity(
        id = 5, name = "glvortex", role = NetworkRole.DIRECT,
        host = "unused.invalid", port = 5222, nick = "unused", username = "unused", realname = "unused",
        protocol = "xmpp",
    )

    private fun vm(network: NetworkEntity, connectionManager: FakeConnectionManager, ircSessions: FakeIrcSessions) =
        ChannelListViewModel(
            savedStateHandle = SavedStateHandle(mapOf("networkId" to network.id)),
            networkRepository = FakeNetworkRepository(network),
            bufferRepository = FakeBufferRepository(),
            connectionManager = connectionManager,
            ircSessions = ircSessions,
        )

    /**
     * Before the fix: this test fails because `start()` unconditionally subscribed to
     * `connectionStates` and reached for `ircSessions.sessionFor`, driving `availability` to READY
     * (never UNSUPPORTED) and eventually triggering `fetch()`'s poll-and-timeout.
     */
    @Test
    fun `a backend with no room-discovery capability never polls IrcSessions and settles as unsupported`() =
        runTest {
            val ircSessions = FakeIrcSessions()
            val connectionManager = FakeConnectionManager(discoverySupported = false)
            val viewModel = vm(xmppNetwork, connectionManager, ircSessions)

            viewModel.start()
            connectionManager.connectionStates.value =
                mapOf(xmppNetwork.id to ConnectionState.Ready("me@glvortex.net"))
            runCurrent()

            assertEquals(ChannelBrowserAvailability.UNSUPPORTED, viewModel.state.value.availability)
            assertEquals(0, ircSessions.calls)
            assertFalse(viewModel.state.value.loading)
        }

    @Test
    fun `a backend that supports room discovery still drives the normal ready flow`() = runTest {
        val ircNetwork = xmppNetwork.copy(protocol = "irc")
        val connectionManager = FakeConnectionManager(discoverySupported = true)
        val viewModel = vm(ircNetwork, connectionManager, FakeIrcSessions())

        viewModel.start()
        connectionManager.connectionStates.value = mapOf(ircNetwork.id to ConnectionState.Ready("me"))
        runCurrent()

        assertEquals(ChannelBrowserAvailability.READY, viewModel.state.value.availability)
    }
}
