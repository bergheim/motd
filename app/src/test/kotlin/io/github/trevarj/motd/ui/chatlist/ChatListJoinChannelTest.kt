package io.github.trevarj.motd.ui.chatlist

import androidx.lifecycle.SavedStateHandle
import io.github.trevarj.motd.backend.ConnectionState
import io.github.trevarj.motd.backend.RoomTargetSyntax
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.OnboardingPrefs
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.ThemeMode
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.service.BufferReadMarker
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ChannelCloseCoordinator
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.DeliveryMode
import io.github.trevarj.motd.service.ReadMarkerSnapshotter
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
import org.junit.Before
import org.junit.Test

/**
 * [ChatListViewModel.joinChannel] tests for the P2 review finding: the shared new-conversation
 * flow used to apply an IRC-shaped `"#$input"` transform to EVERY network's join target inside
 * `NewConversationSheet` itself, so entering a bare XMPP room JID like `room@conference.example.org`
 * tried to join `"#room@conference.example.org"` instead. The transform now lives behind
 * [ConnectionManager.roomTargetSyntax], consulted here rather than in shared UI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatListJoinChannelTest {

    private class FakeNetworkRepository : NetworkRepository {
        override fun observeNetworks(): Flow<List<NetworkEntity>> = flowOf(emptyList())
        override suspend fun addNetwork(n: NetworkEntity): Long = 0
        override suspend fun updateNetwork(n: NetworkEntity) = Unit
        override suspend fun deleteNetwork(id: Long) = Unit
        override suspend fun networkById(id: Long): NetworkEntity? = null
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

    /** Records every [joinChannel] target actually reached, and hands out a settable
     *  [roomTargetSyntax] so a test can simulate either an IRC-shaped or a null (passthrough)
     *  backend capability. */
    private class FakeConnectionManager(var syntax: RoomTargetSyntax?) : ConnectionManager {
        val joinedTargets = mutableListOf<String>()
        override val connectionStates = MutableStateFlow<Map<Long, ConnectionState>>(emptyMap())
        override suspend fun startAll() = Unit
        override suspend fun stopAll() = Unit
        override suspend fun connect(networkId: Long) = Unit
        override suspend fun disconnect(networkId: Long) = Unit
        override suspend fun reconnectStale() = Unit
        override suspend fun roomTargetSyntax(networkId: Long): RoomTargetSyntax? = syntax
        override suspend fun sendMessage(bufferId: Long, text: String, replyToEventId: Long?) =
            SendAcceptance.Accepted(emptyList())
        override suspend fun sendTyping(bufferId: Long, state: String) = Unit
        override suspend fun sendReact(bufferId: Long, msgid: String, emoji: String) = Unit
        override suspend fun joinChannel(networkId: Long, channel: String) {
            joinedTargets += channel
        }
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

    private fun vm(connectionManager: ConnectionManager) = ChatListViewModel(
        bufferRepository = FakeBufferRepository(),
        networkRepository = FakeNetworkRepository(),
        connectionManager = connectionManager,
        channelCloseCoordinator = object : ChannelCloseCoordinator {
            override fun start() = Unit
            override suspend fun requestClose(bufferId: Long) = Unit
        },
        readMarkerRepository = object : ReadMarkerSnapshotter {
            override suspend fun latestIncoming(bufferIds: Collection<Long>): List<BufferReadMarker> = emptyList()
        },
        settingsRepository = object : SettingsRepository {
            override val settings = MutableStateFlow(Settings(ThemeMode.SYSTEM, true, DeliveryMode.PERSISTENT_SOCKET))
            override suspend fun setThemeMode(m: ThemeMode) = Unit
            override suspend fun setDynamicColor(enabled: Boolean) = Unit
            override suspend fun setDeliveryMode(m: DeliveryMode) = Unit
            override suspend fun setLayoutDensity(d: LayoutDensity) = Unit
            override suspend fun setNickColorsEnabled(enabled: Boolean) = Unit
            override suspend fun setNickColorPalette(p: NickColorPalette) = Unit
            override suspend fun setNickColorOverride(nick: String, hue: Int?) = Unit
            override suspend fun setFriend(nick: String, isFriend: Boolean) = Unit
            override suspend fun setFool(nick: String, isFool: Boolean) = Unit
            override suspend fun setFoolsMode(m: FoolsMode) = Unit
            override suspend fun setShowJoinPartQuit(show: Boolean) = Unit
            override suspend fun setAvatarStyle(style: AvatarStyle) = Unit
            override suspend fun setChatWallpaper(w: io.github.trevarj.motd.data.prefs.ChatWallpaper) = Unit
            override suspend fun setShowComposerEmoji(show: Boolean) = Unit
            override suspend fun setChatSoundsEnabled(enabled: Boolean) = Unit
        },
        onboardingPrefs = object : OnboardingPrefs {
            override val completed = flowOf(true)
            override suspend fun markCompleted() = Unit
        },
        savedStateHandle = SavedStateHandle(),
    )

    /** Mirrors an XMPP row: no capability override, so the default (null) means "use verbatim". */
    @Test
    fun `a backend with no room-target syntax capability joins the raw input verbatim`() = runTest {
        val connectionManager = FakeConnectionManager(syntax = null)
        val viewModel = vm(connectionManager)

        viewModel.joinChannel(1L, "room@conference.example.org")
        runCurrent()

        assertEquals(listOf("room@conference.example.org"), connectionManager.joinedTargets)
    }

    /** Mirrors an IRC row: the capability prepends "#", exactly like the pre-fix hardcoded shared-UI
     *  transform did -- only now decided by the backend, not shared UI. */
    @Test
    fun `a backend with an IRC-shaped room-target syntax capability joins the transformed target`() = runTest {
        val connectionManager = FakeConnectionManager(syntax = RoomTargetSyntax { "#${it.trim()}" })
        val viewModel = vm(connectionManager)

        viewModel.joinChannel(1L, "motd")
        runCurrent()

        assertEquals(listOf("#motd"), connectionManager.joinedTargets)
    }
}
