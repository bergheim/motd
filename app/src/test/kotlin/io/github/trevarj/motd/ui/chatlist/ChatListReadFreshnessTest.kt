package io.github.trevarj.motd.ui.chatlist

import androidx.lifecycle.SavedStateHandle
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MuteBacklogSuppression
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.GlobalFeedPrefs
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.OnboardingPrefs
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.ThemeMode
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.AppVisibility
import io.github.trevarj.motd.service.BufferReadMarker
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ChannelCloseCoordinator
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.DeliveryMode
import io.github.trevarj.motd.service.HistoryResyncController
import io.github.trevarj.motd.service.HistoryResyncState
import io.github.trevarj.motd.service.HistorySyncStatus
import io.github.trevarj.motd.service.ReadMarkerSnapshotter
import io.github.trevarj.motd.testing.NoopConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * What the chat list is holding at the instant navigation composes it again.
 *
 * Opening a chat disposes this pane on a phone, so the tests below model a visit as "cancel the
 * pane's collection, let the sharing timeout expire, change the data, compose again" — and assert
 * against `state.value`, because that single read IS the pane's first frame.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatListReadFreshnessTest {
    private class FakeBufferRepository(
        private val rows: Flow<List<ChatListRow>>,
    ) : BufferRepository {
        override fun observeChatList(): Flow<List<ChatListRow>> = rows

        override fun observeBuffer(id: Long): Flow<BufferEntity?> = flowOf(null)

        override fun observeMembers(bufferId: Long): Flow<List<MemberEntity>> = flowOf(emptyList())

        override suspend fun setPinned(
            id: Long,
            pinned: Boolean,
        ) = Unit

        override suspend fun setMuted(
            id: Long,
            muted: Boolean,
        ): MuteBacklogSuppression? = null

        override suspend fun setLayoutDensityOverride(
            id: Long,
            layout: LayoutDensity?,
        ): Boolean = true

        override suspend fun setPresenceModeOverride(
            id: Long,
            mode: PresenceMode?,
        ): Boolean = true

        override suspend fun deleteBuffer(id: Long) = Unit
    }

    private class FakeNetworkRepository : NetworkRepository {
        override fun observeNetworks(): Flow<List<NetworkEntity>> = flowOf(emptyList())

        override suspend fun addNetwork(n: NetworkEntity): Long = 0

        override suspend fun updateNetwork(n: NetworkEntity) = Unit

        override suspend fun deleteNetwork(id: Long) = Unit

        override suspend fun reorderNetworks(orderedIds: List<Long>) = Unit

        override suspend fun networkById(id: Long): NetworkEntity? = null

        override suspend fun childrenOf(rootId: Long): List<NetworkEntity> = emptyList()
    }

    private class FakeConnectionManager : NoopConnectionManager() {
        override val connectionStates = MutableStateFlow<Map<Long, IrcClientState>>(emptyMap())

        override suspend fun ensureQueryBuffer(
            networkId: Long,
            nick: String,
        ): Long = 0

        override suspend fun ensureServerBuffer(networkId: Long): Long = 0

        override suspend fun markRead(
            bufferId: Long,
            anchor: io.github.trevarj.motd.data.db.TimelineAnchor,
        ) = Unit
    }

    private class FakeSettingsRepository : SettingsRepository {
        override val settings =
            MutableStateFlow(
                Settings(ThemeMode.SYSTEM, true, DeliveryMode.PERSISTENT_SOCKET),
            )

        override suspend fun setThemeMode(m: ThemeMode) = Unit

        override suspend fun setDynamicColor(enabled: Boolean) = Unit

        override suspend fun setDeliveryMode(m: DeliveryMode) = Unit

        override suspend fun setLayoutDensity(d: LayoutDensity) = Unit

        override suspend fun setNickColorsEnabled(enabled: Boolean) = Unit

        override suspend fun setNickColorPalette(p: NickColorPalette) = Unit

        override suspend fun setNickColorOverride(
            nick: String,
            hue: Int?,
        ) = Unit

        override suspend fun setFriend(
            nick: String,
            isFriend: Boolean,
        ) = Unit

        override suspend fun setFool(
            nick: String,
            isFool: Boolean,
        ) = Unit

        override suspend fun setFoolsMode(m: FoolsMode) = Unit

        override suspend fun setPresenceMode(m: PresenceMode) = Unit

        override suspend fun setAvatarStyle(style: AvatarStyle) = Unit

        override suspend fun setChatWallpaper(w: io.github.trevarj.motd.data.prefs.ChatWallpaper) = Unit

        override suspend fun setShowComposerEmoji(show: Boolean) = Unit

        override suspend fun setShowComposerFormattingTools(show: Boolean) = Unit

        override suspend fun setChatSoundsEnabled(enabled: Boolean) = Unit

        override suspend fun setHistorySyncDepth(d: io.github.trevarj.motd.data.prefs.HistorySyncDepth) = Unit

        override suspend fun setAutoAwayEnabled(enabled: Boolean) = Unit

        override suspend fun setAutoAwayMinutes(minutes: Int) = Unit

        override suspend fun setAutoAwayMessage(message: String) = Unit
    }

    /** The process lifecycle the test drives by hand. */
    private class FakeAppVisibility(
        onScreen: Boolean,
    ) : AppVisibility {
        private val _onScreen = MutableStateFlow(onScreen)
        override val onScreen: StateFlow<Boolean> = _onScreen.asStateFlow()

        fun set(value: Boolean) {
            _onScreen.value = value
        }
    }

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(
        rows: Flow<List<ChatListRow>>,
        visibility: AppVisibility,
    ) = ChatListViewModel(
        bufferRepository = FakeBufferRepository(rows),
        networkRepository = FakeNetworkRepository(),
        connectionManager = FakeConnectionManager(),
        historyResync =
            object : HistoryResyncController {
                override fun syncStatus(bufferId: Long) = flowOf<HistorySyncStatus>(HistorySyncStatus.Idle)

                override suspend fun reconcileBuffer(
                    buffer: BufferEntity,
                    client: IrcClient,
                    isCurrent: () -> Boolean,
                ) = HistoryResyncState.Idle

                override suspend fun reconcilePendingMessage(
                    buffer: BufferEntity,
                    client: IrcClient,
                    isCurrent: () -> Boolean,
                ) = HistoryResyncState.Idle
            },
        channelCloseCoordinator =
            object : ChannelCloseCoordinator {
                override fun start() = Unit

                override suspend fun requestClose(bufferId: Long) = Unit
            },
        readMarkerRepository =
            object : ReadMarkerSnapshotter {
                override suspend fun latestIncoming(bufferIds: Collection<Long>): List<BufferReadMarker> = emptyList()
            },
        settingsRepository = FakeSettingsRepository(),
        onboardingPrefs =
            object : OnboardingPrefs {
                override val completed = flowOf(true)

                override suspend fun markCompleted() = Unit
            },
        globalFeedPrefs =
            object : GlobalFeedPrefs {
                override val enabled = flowOf(false)

                override suspend fun setEnabled(enabled: Boolean) = Unit
            },
        savedStateHandle = SavedStateHandle(),
        appVisibility = visibility,
    )

    private fun unreadRow(count: Int) =
        ChatListRow(
            bufferId = 7,
            networkId = 1,
            networkName = "libera",
            displayName = "#kotlin",
            type = BufferType.CHANNEL,
            pinned = false,
            muted = false,
            lastMessageText = "hey",
            lastMessageSender = "alice",
            lastMessageTime = 100,
            unreadCount = count,
            mentionCount = 0,
        )

    /** The chat-list pane, composed. Cancelling it is navigation disposing the destination. */
    private fun TestScope.composePane(viewModel: ChatListViewModel): Job = launch { viewModel.state.collect {} }.also { runCurrent() }

    private fun unreadCount(viewModel: ChatListViewModel) =
        viewModel.state.value.rows
            .single()
            .unreadCount

    @Test
    fun `a chat read while the pane was away is already read on the frame it comes back`() =
        runTest {
            val rows = MutableStateFlow(listOf(unreadRow(3)))
            val visibility = FakeAppVisibility(onScreen = true)
            val viewModel = vm(rows, visibility)

            val pane = composePane(viewModel)
            assertEquals(3, unreadCount(viewModel))

            // Open the chat: navigation disposes this pane, and the visit outlasts the sharing timeout.
            pane.cancel()
            advanceTimeBy(30_000)
            runCurrent()

            // The reader clears the room; EventProcessor advances the marker and Room republishes.
            rows.value = listOf(unreadRow(0))
            runCurrent()

            // Back. This read is the first frame, before any restarted flow could emit into it.
            assertEquals(0, unreadCount(viewModel))
            composePane(viewModel).cancel()
        }

    @Test
    fun `the chat list stops observing while the app is off screen`() =
        runTest {
            val rows = MutableStateFlow(listOf(unreadRow(3)))
            val visibility = FakeAppVisibility(onScreen = true)
            val viewModel = vm(rows, visibility)

            val pane = composePane(viewModel)
            assertEquals(3, unreadCount(viewModel))

            // Home button: the pane goes with the app, and nothing may keep querying behind it.
            pane.cancel()
            visibility.set(false)
            advanceTimeBy(30_000)
            runCurrent()

            rows.value = listOf(unreadRow(9))
            runCurrent()
            assertEquals(3, unreadCount(viewModel))

            // Foregrounding re-arms the observation without waiting for a pane to compose.
            visibility.set(true)
            runCurrent()
            assertEquals(9, unreadCount(viewModel))
        }
}
