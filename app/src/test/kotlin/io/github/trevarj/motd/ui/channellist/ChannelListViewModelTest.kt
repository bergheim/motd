package io.github.trevarj.motd.ui.channellist

import androidx.lifecycle.SavedStateHandle
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.Protocol
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.SendAcceptance
import io.github.trevarj.motd.service.XmppConnectionSurface
import io.github.trevarj.motd.xmpp.MucRoomListing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Channel-browser XMPP branch (room-browse): [ChannelListViewModel.fetch] must go through MUC
 *  service discovery instead of waiting on [ConnectionManager.clientFor], which is always null for
 *  an XMPP network id. */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelListViewModelTest {

    private class FakeNetworkRepository(initial: NetworkEntity) : NetworkRepository {
        val networks = mutableMapOf(initial.id to initial)
        override fun observeNetworks() = flowOf(networks.values.toList())
        override suspend fun addNetwork(n: NetworkEntity): Long = error("unused")
        override suspend fun updateNetwork(n: NetworkEntity) { networks[n.id] = n }
        override suspend fun deleteNetwork(id: Long) { networks.remove(id) }
        override suspend fun networkById(id: Long): NetworkEntity? = networks[id]
        override suspend fun childrenOf(rootId: Long) = emptyList<NetworkEntity>()
    }

    private class FakeBufferRepository : BufferRepository {
        override fun observeChatList() = flowOf(emptyList<io.github.trevarj.motd.data.db.ChatListRow>())
        override fun observeBuffer(id: Long) = flowOf<io.github.trevarj.motd.data.db.BufferEntity?>(null)
        override fun observeMembers(bufferId: Long) = flowOf(emptyList<io.github.trevarj.motd.data.db.MemberEntity>())
        override suspend fun setPinned(id: Long, pinned: Boolean) = Unit
        override suspend fun setMuted(id: Long, muted: Boolean) = Unit
        override suspend fun setLayoutDensityOverride(
            id: Long,
            layout: io.github.trevarj.motd.data.prefs.LayoutDensity?,
        ): Boolean = true
        override suspend fun deleteBuffer(id: Long) = Unit
    }

    private class FakeConnectionManager(
        initial: Map<Long, IrcClientState> = emptyMap(),
    ) : ConnectionManager {
        override val connectionStates = MutableStateFlow(initial)
        override fun clientFor(networkId: Long): IrcClient? = null
        override suspend fun startAll() = Unit
        override suspend fun stopAll() = Unit
        override suspend fun connect(networkId: Long) = Unit
        override suspend fun disconnect(networkId: Long) = Unit
        override suspend fun reconnectStale() = Unit
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

    private class FakeXmppConnectionSurface(
        initial: Map<Long, IrcClientState> = emptyMap(),
    ) : XmppConnectionSurface {
        var listings: List<MucRoomListing> = emptyList()
        val listRoomsCalls = mutableListOf<Long>()
        override val connectionStates = MutableStateFlow(initial)
        override suspend fun startAll() = Unit
        override suspend fun stopAll() = Unit
        override suspend fun connect(networkId: Long) = Unit
        override suspend fun disconnect(networkId: Long) = Unit
        override suspend fun reconnectStale() = Unit
        override suspend fun sendMessage(bufferId: Long, text: String) = SendAcceptance.Accepted(emptyList())
        override suspend fun sendTyping(bufferId: Long, state: String) = Unit
        override suspend fun joinChannel(networkId: Long, roomJid: String) = Unit
        override suspend fun listRooms(networkId: Long): List<MucRoomListing> {
            listRoomsCalls += networkId
            return listings
        }
        override suspend fun partChannel(bufferId: Long, reason: String?) = Unit
        override suspend fun ensureQueryBuffer(networkId: Long, bareJid: String): Long = 0
        override suspend fun ensureServerBuffer(networkId: Long): Long = 0
    }

    private val networkId = 1L
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun xmppNetwork() = NetworkEntity(
        id = networkId, name = "glvortex", protocol = Protocol.XMPP, role = NetworkRole.DIRECT,
        host = "xmpp.glvortex.net", port = 5222,
        nick = "me", username = "me", realname = "Me", jid = "me@glvortex.net",
    )

    private fun viewModel(
        network: NetworkEntity,
        connectionManager: FakeConnectionManager,
        xmppConnectionSurface: FakeXmppConnectionSurface,
    ): ChannelListViewModel = ChannelListViewModel(
        savedStateHandle = SavedStateHandle(mapOf("networkId" to network.id)),
        networkRepository = FakeNetworkRepository(network),
        bufferRepository = FakeBufferRepository(),
        connectionManager = connectionManager,
        xmppConnectionSurface = xmppConnectionSurface,
    )

    @Test
    fun `fetch on ready XMPP network lists MUC rooms via the surface, not clientFor`() = runTest(dispatcher) {
        val listings = listOf(
            MucRoomListing(roomJid = "lobby@conf.glvortex.net", name = "Lobby"),
            MucRoomListing(roomJid = "random@conf.glvortex.net", name = null),
        )
        // The injected ConnectionManager IS the app-wide routing surface (RoutingConnectionManager
        // in production, which already merges IRC + XMPP connectionStates) — the ViewModel reads
        // Ready/Disconnected from it, never from xmppConnectionSurface.connectionStates directly.
        val connectionManager = FakeConnectionManager(
            initial = mapOf(networkId to IrcClientState.Ready("me@glvortex.net", emptySet(), emptyMap())),
        )
        val xmppSurface = FakeXmppConnectionSurface().apply { this.listings = listings }

        val vm = viewModel(xmppNetwork(), connectionManager, xmppSurface)
        vm.start()
        runCurrent()

        // Auto-fetch fired once Ready was observed via the routing ConnectionManager's states map.
        assertTrue(vm.state.value.loaded)
        assertEquals(listOf(networkId), xmppSurface.listRoomsCalls)
        assertEquals(
            setOf("lobby@conf.glvortex.net", "random@conf.glvortex.net"),
            vm.state.value.listings.map { it.name }.toSet(),
        )
        assertEquals("Lobby", vm.state.value.listings.first { it.name == "lobby@conf.glvortex.net" }.topic)
    }

    @Test
    fun `query filters MUC listings client-side by room JID or name substring`() = runTest(dispatcher) {
        val listings = listOf(
            MucRoomListing(roomJid = "lobby@conf.glvortex.net", name = "Lobby"),
            MucRoomListing(roomJid = "random@conf.glvortex.net", name = "Off Topic"),
        )
        val connectionManager = FakeConnectionManager(
            initial = mapOf(networkId to IrcClientState.Ready("me@glvortex.net", emptySet(), emptyMap())),
        )
        val xmppSurface = FakeXmppConnectionSurface().apply { this.listings = listings }

        val vm = viewModel(xmppNetwork(), connectionManager, xmppSurface)
        vm.start()
        runCurrent()

        vm.onQueryChange("lobby")
        vm.fetch()
        runCurrent()

        assertEquals(listOf("lobby@conf.glvortex.net"), vm.state.value.listings.map { it.name })
    }
}
