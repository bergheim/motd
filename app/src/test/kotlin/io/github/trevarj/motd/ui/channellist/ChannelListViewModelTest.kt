package io.github.trevarj.motd.ui.channellist

import androidx.lifecycle.SavedStateHandle
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.Protocol
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.irc.client.ChannelListing
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcClientConfig
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.transport.IrcTransport
import io.github.trevarj.motd.irc.transport.TransportFactory
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.SendAcceptance
import io.github.trevarj.motd.service.XmppConnectionSurface
import io.github.trevarj.motd.xmpp.MucRoomListing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

    /**
     * Scriptable in-memory [IrcTransport] for the IRC-branch regression test: the test pushes
     * inbound lines with [feed] (as if the server sent them) and inspects [sent] to assert what a
     * real [IrcClient] wrote on the wire. Mirrors `:irc`'s own test-only `FakeTransport`, which
     * `app` cannot depend on directly (it lives in `:irc`'s test sourceSet, not a shared fixture).
     */
    private class FakeIrcTransport : IrcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()
        override suspend fun connect() = Unit
        override val incoming: Flow<String> = inbound.consumeAsFlow()
        override suspend fun send(line: String) { sent.add(line) }
        override suspend fun close() { inbound.close() }
        suspend fun feed(line: String) = inbound.send(line)
    }

    private class FakeConnectionManager(
        initial: Map<Long, IrcClientState> = emptyMap(),
        private val client: IrcClient? = null,
    ) : ConnectionManager {
        override val connectionStates = MutableStateFlow(initial)
        override fun clientFor(networkId: Long): IrcClient? = client
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
        override suspend fun listIrcGateways(networkId: Long): List<String> = emptyList()
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

    private fun ircNetwork() = NetworkEntity(
        id = networkId, name = "libera", protocol = Protocol.IRC, role = NetworkRole.DIRECT,
        host = "irc.libera.chat", port = 6697,
        nick = "me", username = "me", realname = "Me",
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

    /**
     * IRC regression: `fetch()` for an IRC-protocol network must still reach the real
     * `IrcClient.listChannels(mask, minUsers, cap)` with [listArgsFor]'s args and apply the
     * result, unaffected by the new XMPP branch. `IrcClient` is a concrete (non-open) class, not
     * an interface, so it cannot be swapped for a recording fake the way `XmppConnectionSurface`
     * can — a real client wired to a scriptable [FakeIrcTransport] is the seam the old behavior was
     * (and still is) actually testable at, mirroring `:irc`'s own `IrcClientTest` registration
     * helpers (`registeredNoCaps`), which `app` cannot import directly (test-only, different
     * module, no shared test-fixtures dependency).
     */
    @Test
    fun `fetch on ready IRC network reaches client listChannels with listArgsFor args`() = runTest(dispatcher) {
        val ft = FakeIrcTransport()
        val ircClientScope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val ircClient = IrcClient(
            config = IrcClientConfig(
                host = "irc.libera.chat", port = 6697, tls = true,
                nick = "me", username = "me", realname = "Me",
            ),
            factory = TransportFactory { _, _, _, _, _ -> ft },
            scope = ircClientScope,
        )
        ircClient.start()
        runCurrent()
        // Register with no labeled-response cap so listChannels takes the raw 322/323 fallback,
        // which puts the actual wire-level LIST line (and its mask/minUsers args) directly under
        // test, same as the `:irc` module's own `registeredNoCaps` helper.
        ft.feed(":srv CAP * LS :message-tags server-time batch")
        runCurrent()
        ft.feed(":srv CAP me ACK :message-tags server-time batch")
        runCurrent()
        ft.feed(":srv 001 me :Welcome")
        ft.feed(":srv 005 me CHATHISTORY=100 :are supported")
        runCurrent()
        assertTrue(ircClient.state.value is IrcClientState.Ready)

        val connectionManager = FakeConnectionManager(
            initial = mapOf(networkId to IrcClientState.Ready("me", emptySet(), emptyMap())),
            client = ircClient,
        )
        val vm = viewModel(ircNetwork(), connectionManager, FakeXmppConnectionSurface())
        vm.start()
        runCurrent()

        // start()'s auto-fetch (blank query) already sent one LIST; drain it before the explicit
        // query fetch below so its `loading` flag doesn't block the second call.
        ft.feed(":srv 323 me :End of /LIST")
        runCurrent()
        assertTrue(vm.state.value.loaded)

        vm.onQueryChange("kotlin")
        vm.fetch()
        runCurrent()

        // fetch() reached the real IrcClient.listChannels with listArgsFor("kotlin")'s mask —
        // never the "Channel listing is not available yet" clientFor-timeout path.
        assertTrue(ft.sent.last { it.startsWith("LIST") }.contains("*kotlin*"))

        ft.feed(":srv 322 me #kotlin 12 :Kotlin chat")
        ft.feed(":srv 323 me :End of /LIST")
        runCurrent()

        assertTrue(vm.state.value.loaded)
        assertEquals(listOf(ChannelListing("#kotlin", 12, "Kotlin chat")), vm.state.value.listings)
    }
}
