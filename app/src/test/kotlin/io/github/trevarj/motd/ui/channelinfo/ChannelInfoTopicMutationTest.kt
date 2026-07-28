package io.github.trevarj.motd.ui.channelinfo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.ChatWallpaper
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.ThemeMode
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.backend.ConnectionState
import io.github.trevarj.motd.ircbackend.IrcSessions
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.DeliveryMode
import io.github.trevarj.motd.service.SendAcceptance
import io.github.trevarj.motd.ui.chat.ComposerDraftStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChannelInfoTopicMutationTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var database: MotdDatabase

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MotdDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `missing client and rejected write preserve a retryable failure`() = runTest {
        val manager = FakeConnectionManager(accepted = false)
        val viewModel = viewModel(manager)
        viewModel.init(BUFFER_ID)

        viewModel.setTopic("draft survives")
        advanceUntilIdle()

        assertEquals(1, manager.attempts.size)
        assertEquals(BUFFER_ID to "draft survives", manager.attempts.single())
        assertEquals(TopicMutationState.Failed, viewModel.topicMutation.value)
    }

    @Test
    fun `write exception becomes retryable failure`() = runTest {
        val manager = FakeConnectionManager(failure = IllegalStateException("socket closed"))
        val viewModel = viewModel(manager)
        viewModel.init(BUFFER_ID)

        viewModel.setTopic("keep this text")
        advanceUntilIdle()

        assertEquals(TopicMutationState.Failed, viewModel.topicMutation.value)
    }

    @Test
    fun `accepted write is exposed before the server topic echo`() = runTest {
        val manager = FakeConnectionManager(accepted = true)
        val viewModel = viewModel(manager)
        viewModel.init(BUFFER_ID)

        viewModel.setTopic("sent, not yet echoed")
        advanceUntilIdle()

        assertEquals(TopicMutationState.Accepted, viewModel.topicMutation.value)
        assertEquals(listOf(BUFFER_ID to "sent, not yet echoed"), manager.attempts)
    }

    @Test
    fun `duplicate submit is ignored while the first write is pending`() = runTest {
        val gate = CompletableDeferred<Boolean>()
        val manager = FakeConnectionManager(gate = gate)
        val viewModel = viewModel(manager)
        viewModel.init(BUFFER_ID)

        viewModel.setTopic("first draft")
        runCurrent()
        viewModel.setTopic("second draft")

        assertEquals(TopicMutationState.Submitting, viewModel.topicMutation.value)
        assertEquals(listOf(BUFFER_ID to "first draft"), manager.attempts)
        gate.complete(true)
        advanceUntilIdle()
        assertEquals(TopicMutationState.Accepted, viewModel.topicMutation.value)
    }

    @Test
    fun `missing buffer does not emit leave navigation`() = runTest {
        val viewModel = viewModel(FakeConnectionManager(partAccepted = true))
        val events = mutableListOf<ChannelInfoOperationEvent>()
        val collector = backgroundScope.launch { viewModel.operationEvents.collect(events::add) }
        runCurrent()

        viewModel.part()
        advanceUntilIdle()

        assertEquals(LeaveMutationState.Failed, viewModel.leaveMutation.value)
        assertTrue(events.isEmpty())
        collector.cancelAndJoin()
    }

    @Test
    fun `missing client does not emit leave navigation`() = runTest {
        assertRejectedLeaveDoesNotNavigate(FakeConnectionManager(partAccepted = false))
    }

    @Test
    fun `non ready client does not emit leave navigation`() = runTest {
        assertRejectedLeaveDoesNotNavigate(FakeConnectionManager(partAccepted = false))
    }

    @Test
    fun `rejected PART write does not emit leave navigation`() = runTest {
        assertRejectedLeaveDoesNotNavigate(FakeConnectionManager(partAccepted = false))
    }

    @Test
    fun `throwing PART write does not emit leave navigation`() = runTest {
        assertRejectedLeaveDoesNotNavigate(FakeConnectionManager(partFailure = IllegalStateException("socket closed")))
    }

    @Test
    fun `accepted PART emits navigation exactly once`() = runTest {
        val manager = FakeConnectionManager(partAccepted = true)
        val viewModel = viewModel(manager)
        viewModel.init(BUFFER_ID)
        val events = mutableListOf<ChannelInfoOperationEvent>()
        val collector = backgroundScope.launch { viewModel.operationEvents.collect(events::add) }
        runCurrent()

        viewModel.part()
        viewModel.part()
        advanceUntilIdle()

        assertEquals(listOf(BUFFER_ID), manager.partAttempts)
        assertEquals(listOf(ChannelInfoOperationEvent.LeaveAccepted), events)
        assertEquals(LeaveMutationState.Idle, viewModel.leaveMutation.value)
        collector.cancelAndJoin()
    }

    private suspend fun TestScope.assertRejectedLeaveDoesNotNavigate(manager: FakeConnectionManager) {
        val viewModel = viewModel(manager)
        viewModel.init(BUFFER_ID)
        val events = mutableListOf<ChannelInfoOperationEvent>()
        val collector = backgroundScope.launch { viewModel.operationEvents.collect(events::add) }
        runCurrent()

        viewModel.part()
        advanceUntilIdle()

        assertEquals(listOf(BUFFER_ID), manager.partAttempts)
        assertEquals(LeaveMutationState.Failed, viewModel.leaveMutation.value)
        assertTrue(events.isEmpty())
        collector.cancelAndJoin()
    }

    /** No live IRC session: these tests exercise only the neutral seam's topic/part contracts. */
    private fun viewModel(manager: ConnectionManager) = ChannelInfoViewModel(
        bufferRepository = FakeBufferRepository(),
        connectionManager = manager,
        ircSessions = object : IrcSessions {
            override fun sessionFor(networkId: Long): IrcClient? = null
        },
        draftStore = ComposerDraftStore(database),
        settingsRepository = FakeSettingsRepository(),
        userDao = database.userDao(),
        networkIdentityDao = database.networkIdentityDao(),
    )

    private class FakeBufferRepository : BufferRepository {
        override fun observeChatList(): Flow<List<ChatListRow>> = flowOf(emptyList())
        override fun observeBuffer(id: Long): Flow<BufferEntity?> = flowOf(
            BufferEntity(BUFFER_ID, 1, "#room", "#room", BufferType.CHANNEL),
        )
        override fun observeMembers(bufferId: Long): Flow<List<MemberEntity>> = flowOf(emptyList())
        override suspend fun setPinned(id: Long, pinned: Boolean) = Unit
        override suspend fun setMuted(id: Long, muted: Boolean) = Unit
        override suspend fun setLayoutDensityOverride(id: Long, layout: LayoutDensity?): Boolean = true
        override suspend fun deleteBuffer(id: Long) = Unit
    }

    private class FakeConnectionManager(
        private val accepted: Boolean = false,
        private val failure: Throwable? = null,
        private val gate: CompletableDeferred<Boolean>? = null,
        private val partAccepted: Boolean = false,
        private val partFailure: Throwable? = null,
    ) : ConnectionManager {
        override val connectionStates: StateFlow<Map<Long, ConnectionState>> = MutableStateFlow(emptyMap())
        override val certPrompts = MutableStateFlow<List<CertPrompt>>(emptyList())
        val attempts = mutableListOf<Pair<Long, String>>()

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
        val partAttempts = mutableListOf<Long>()
        override suspend fun partChannelForClose(bufferId: Long, reason: String?): Boolean {
            partAttempts += bufferId
            partFailure?.let { throw it }
            return partAccepted
        }
        override suspend fun setChannelTopic(bufferId: Long, topic: String): Boolean {
            attempts += bufferId to topic
            failure?.let { throw it }
            return gate?.await() ?: accepted
        }
        override suspend fun ensureQueryBuffer(networkId: Long, nick: String): Long = 0
        override suspend fun ensureServerBuffer(networkId: Long): Long = 0
        override suspend fun markRead(bufferId: Long, anchor: io.github.trevarj.motd.data.db.TimelineAnchor) = Unit
        override suspend fun evaluatePushMode() = Unit
        override suspend fun trustCert(prompt: CertPrompt) = Unit
        override fun dismissCertPrompt(prompt: CertPrompt) = Unit
    }

    private class FakeSettingsRepository : SettingsRepository {
        override val settings = MutableStateFlow(Settings())
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
        override suspend fun setChatWallpaper(w: ChatWallpaper) = Unit
        override suspend fun setShowComposerEmoji(show: Boolean) = Unit
        override suspend fun setChatSoundsEnabled(enabled: Boolean) = Unit
    }

    private companion object { const val BUFFER_ID = 1L }
}
