package io.github.trevarj.motd.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.paging.PagingData
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.DccTransferEntity
import io.github.trevarj.motd.data.db.EventRedirectEntity
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkIdentityEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.UserEntity
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.ChatWallpaper
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefs
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.ReplyConfig
import io.github.trevarj.motd.data.prefs.ReplyPrefs
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.ThemeMode
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.HistoryWindowFocus
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.data.repo.LinkPreviewRepository
import io.github.trevarj.motd.data.repo.MessageRepository
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.data.sync.TypingTrackerImpl
import io.github.trevarj.motd.data.visibility.MessageVisibilityReader
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.dcc.DccTransferController
import io.github.trevarj.motd.audio.AudioAttachment
import io.github.trevarj.motd.audio.AudioMetadata
import io.github.trevarj.motd.audio.AudioMetadataRepository
import io.github.trevarj.motd.audio.AudioPlaybackController
import io.github.trevarj.motd.audio.AudioPlaybackState
import io.github.trevarj.motd.backend.ConnectionState
import io.github.trevarj.motd.backend.ReactionCapability
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcClientConfig
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.transport.IrcTransport
import io.github.trevarj.motd.irc.transport.TransportFactory
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.DeliveryMode
import io.github.trevarj.motd.service.ForegroundBufferTracker
import io.github.trevarj.motd.service.HistoryResyncCoordinator
import io.github.trevarj.motd.service.HistoryResyncController
import io.github.trevarj.motd.service.HistoryResyncState
import io.github.trevarj.motd.service.HistorySyncStatus
import io.github.trevarj.motd.service.IrcEventSink
import io.github.trevarj.motd.service.PresenceKey
import io.github.trevarj.motd.service.PresenceState
import io.github.trevarj.motd.service.RosterLoadState
import io.github.trevarj.motd.service.TypingTracker
import io.github.trevarj.motd.ui.components.ReplyPreviewData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: MotdDatabase
    private lateinit var network: NetworkEntity
    private lateinit var channel: BufferEntity
    private lateinit var query: BufferEntity
    private lateinit var processor: EventProcessor

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        network = NetworkEntity(
            name = "test",
            role = NetworkRole.DIRECT,
            host = "irc.example",
            port = 6697,
            nick = "me",
            username = "me",
            realname = "Me",
        ).let { it.copy(id = db.networkDao().insert(it)) }
        channel = BufferEntity(
            networkId = network.id,
            name = "#room",
            displayName = "#room",
            type = BufferType.CHANNEL,
        ).let { it.copy(id = db.bufferDao().insert(it)) }
        query = BufferEntity(
            networkId = network.id,
            name = "alice",
            displayName = "alice",
            type = BufferType.QUERY,
        ).let { it.copy(id = db.bufferDao().insert(it)) }
        processor = EventProcessor(db, TypingTrackerImpl(), io.github.trevarj.motd.data.sync.MessageNotifier.Noop)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `message submission sends reply metadata and stops typing`() = runTest {
        val manager = FakeConnectionManager(network.id, ConnectionState.Ready("me"))
        val vm = viewModel(channel, manager)
        vm.state.first { it.buffer != null }
        val parent = message(channel.id, "parent", msgid = "parent-1", sender = "alice", id = 88)
        vm.setReply(parent)
        vm.state.first { it.replyTo?.msgid == "parent-1" }
        vm.saveDraft("answer")

        val revisionBeforeSubmit = vm.composerDraft.value.revision
        val submission = vm.submit("answer", {}, {})
        val (clearedState, clearedDraft) = combine(vm.state, vm.composerDraft) { state, draft ->
            state to draft
        }.first { (state, draft) ->
            state.replyTo == null && draft.text.isEmpty() && draft.revision > revisionBeforeSubmit
        }
        submission.join()

        assertEquals(listOf(SentMessage(channel.id, "answer", parent.id)), manager.messages)
        assertEquals(listOf(channel.id to "done"), manager.typing)
        assertNull(clearedState.replyTo)
        assertNull(db.composerDraftDao().byRoom(channel.id))
        assertEquals("", clearedDraft.text)
    }

    @Test
    fun `send rejection retains draft text and reply`() = runTest {
        val manager = FakeConnectionManager(network.id, sendAccepted = false)
        val vm = viewModel(channel, manager)
        vm.state.first { it.buffer != null }
        val parent = message(channel.id, "parent", msgid = "parent-1", sender = "alice", id = 88)
        vm.setReply(parent)
        vm.saveDraft("answer")

        val submission = vm.submit("answer", {}, {})
        submission.join()

        assertEquals("answer", db.composerDraftDao().byRoom(channel.id)?.text)
        assertEquals(88L, db.composerDraftDao().byRoom(channel.id)?.replyToEventId)
        assertEquals(parent, vm.state.value.replyTo)
        assertEquals("answer", vm.composerDraft.value.text)
    }

    @Test
    fun `parted channel exposes parted state and surfaces not-in-channel rejection`() = runTest {
        val parted = BufferEntity(
            networkId = network.id,
            name = "#left",
            displayName = "#left",
            type = BufferType.CHANNEL,
            joined = false,
        ).let { it.copy(id = db.bufferDao().insert(it)) }
        val manager = FakeConnectionManager(
            network.id,
            sendRejection = io.github.trevarj.motd.service.SendRejectionReason.NOT_IN_CHANNEL,
        )
        val vm = viewModel(parted, manager)
        vm.state.first { it.buffer != null }

        assertTrue(vm.state.value.parted)

        vm.saveDraft("hello?")
        vm.submit("hello?", {}, {}).join()

        assertTrue(manager.messages.isEmpty())
        assertEquals(ChatUiEvent.NotInChannel, vm.uiEvents.value.single().value)
    }

    @Test
    fun `retry while not in channel surfaces not-in-channel rejection`() = runTest {
        val parted = BufferEntity(
            networkId = network.id,
            name = "#left",
            displayName = "#left",
            type = BufferType.CHANNEL,
            joined = false,
        ).let { it.copy(id = db.bufferDao().insert(it)) }
        val manager = FakeConnectionManager(
            network.id,
            retryRejection = io.github.trevarj.motd.service.SendRejectionReason.NOT_IN_CHANNEL,
        )
        val messages = FakeMessageRepository()
        val vm = viewModel(parted, manager, messages = messages)
        vm.state.first { it.buffer != null }
        val failed = message(parted.id, "try again", null, "me", id = 77).copy(failed = true)

        vm.retry(failed)
        advanceUntilIdle()

        assertEquals(ChatUiEvent.NotInChannel, vm.uiEvents.value.single().value)
    }

    @Test
    fun `rapid duplicate submits of an unchanged draft send only once`() = runTest {
        val sendGate = CompletableDeferred<Unit>()
        val manager = FakeConnectionManager(network.id, sendGate = sendGate)
        val vm = viewModel(channel, manager)
        vm.state.first { it.buffer != null }
        val link = "https://crafterbin.example/paste"
        vm.saveDraft(link)

        val first = vm.submit(link, {}, {})
        runCurrent()
        manager.messageStarted.await()

        val second = vm.submit(link, {}, {})
        val third = vm.submit(link, {}, {})
        runCurrent()

        assertEquals(listOf(SentMessage(channel.id, link, null)), manager.messages)

        sendGate.complete(Unit)
        advanceUntilIdle()
        first.join()
        second.join()
        third.join()

        // A callback held by Compose until after the accepted draft clear is stale, not a new edit.
        val staleCallback = vm.submit(link, {}, {})
        advanceUntilIdle()
        staleCallback.join()

        assertEquals(listOf(SentMessage(channel.id, link, null)), manager.messages)
        assertEquals("", vm.composerDraft.value.text)
    }

    @Test
    fun `late hydration keeps fresh text and restores persisted reply`() = runTest {
        val parent = message(channel.id, "parent", msgid = "parent-1", sender = "alice", id = 88)
        ComposerDraftStore(db).saveDraft(channel.id, "old text", parent.id)
        val messages = FakeMessageRepository(listOf(parent))
        val vm = viewModel(channel, FakeConnectionManager(network.id), messages = messages)

        vm.saveDraft("fresh text")
        advanceUntilIdle()

        assertEquals("fresh text", vm.composerDraft.value.text)
        assertEquals(parent, vm.state.first { it.replyTo != null }.replyTo)
        assertEquals("fresh text", db.composerDraftDao().byRoom(channel.id)?.text)
        assertEquals(parent.id, db.composerDraftDao().byRoom(channel.id)?.replyToEventId)

        val recreated = viewModel(
            channel,
            FakeConnectionManager(network.id),
            messages = messages,
        )
        assertEquals("fresh text", recreated.composerDraft.first { it.hydrated }.text)
        assertEquals(parent, recreated.state.first { it.replyTo != null }.replyTo)
    }

    @Test
    fun `same text retyped after submit is not cleared and survives recreation`() = runTest {
        val sendGate = CompletableDeferred<Unit>()
        val manager = FakeConnectionManager(network.id, sendGate = sendGate)
        val vm = viewModel(channel, manager)
        vm.state.first { it.buffer != null }
        vm.saveDraft("answer")

        vm.submit("answer", {}, {})
        manager.messageStarted.await()
        vm.saveDraft("answer")
        sendGate.complete(Unit)
        manager.typingSent.await()

        assertEquals("answer", vm.composerDraft.value.text)
        assertEquals("answer", db.composerDraftDao().byRoom(channel.id)?.text)

        val recreated = viewModel(channel, FakeConnectionManager(network.id))
        val restored = recreated.composerDraft.first { it.hydrated }
        assertEquals("answer", restored.text)
    }

    @Test
    fun `conversation layout inherits global then persists and clears an override`() = runTest {
        val settings = FakeSettingsRepository()
        val buffers = FakeBufferRepository(channel)
        val vm = viewModel(
            channel,
            FakeConnectionManager(network.id),
            buffers = buffers,
            settings = settings,
        )
        vm.state.first { it.buffer != null }

        settings.settings.value = Settings(layoutDensity = LayoutDensity.COMPACT)
        assertEquals(
            LayoutDensity.COMPACT,
            vm.state.first { it.conversationLayout.global == LayoutDensity.COMPACT }
                .conversationLayout.effective,
        )

        vm.setConversationLayoutOverride(LayoutDensity.TWO_LINE)
        advanceUntilIdle()
        assertEquals(listOf(channel.id to LayoutDensity.TWO_LINE), buffers.layoutWrites)
        assertEquals(
            LayoutDensity.TWO_LINE,
            vm.state.first { it.conversationLayout.override == LayoutDensity.TWO_LINE }
                .conversationLayout.effective,
        )

        settings.settings.value = Settings(layoutDensity = LayoutDensity.COMFORTABLE)
        assertEquals(
            LayoutDensity.TWO_LINE,
            vm.state.first { it.conversationLayout.global == LayoutDensity.COMFORTABLE }
                .conversationLayout.effective,
        )

        vm.setConversationLayoutOverride(null)
        advanceUntilIdle()
        assertEquals(
            LayoutDensity.COMFORTABLE,
            vm.state.first { it.conversationLayout.override == null }
                .conversationLayout.effective,
        )
    }

    @Test
    fun `conversation layout write failure is surfaced without optimistic state`() = runTest {
        val buffers = FakeBufferRepository(channel).apply { layoutWriteResult = false }
        val vm = viewModel(channel, FakeConnectionManager(network.id), buffers = buffers)
        vm.state.first { it.buffer != null }

        vm.setConversationLayoutOverride(LayoutDensity.COMPACT)
        advanceUntilIdle()

        assertEquals(listOf(channel.id to LayoutDensity.COMPACT), buffers.layoutWrites)
        assertNull(vm.state.value.conversationLayout.override)
        assertEquals(
            ChatUiEvent.ConversationLayoutWriteFailed,
            vm.uiEvents.first().single().value,
        )
    }

    @Test
    fun `conversation layouts write to canonical buffers independently`() = runTest {
        val redirectedChannel = FakeBufferRepository(channel, routeId = query.id)
        val queryBuffers = FakeBufferRepository(query)
        val channelVm = viewModel(
            channel,
            FakeConnectionManager(network.id),
            routeBufferId = query.id,
            buffers = redirectedChannel,
        )
        val queryVm = viewModel(query, FakeConnectionManager(network.id), buffers = queryBuffers)
        channelVm.state.first { it.buffer?.id == channel.id }
        queryVm.state.first { it.buffer?.id == query.id }

        channelVm.setConversationLayoutOverride(LayoutDensity.COMPACT)
        queryVm.setConversationLayoutOverride(LayoutDensity.TWO_LINE)
        advanceUntilIdle()

        assertEquals(listOf(channel.id to LayoutDensity.COMPACT), redirectedChannel.layoutWrites)
        assertEquals(listOf(query.id to LayoutDensity.TWO_LINE), queryBuffers.layoutWrites)
        assertEquals(LayoutDensity.COMPACT, channelVm.state.value.conversationLayout.effective)
        assertEquals(LayoutDensity.TWO_LINE, queryVm.state.value.conversationLayout.effective)
    }

    @Test
    fun `selecting reply primes its timeline preview before repository collection`() = runTest {
        val manager = FakeConnectionManager(network.id)
        val vm = viewModel(channel, manager)
        vm.state.first { it.buffer != null }
        val parent = message(channel.id, "original text", msgid = "parent-1", sender = "alice")
        assertTrue(vm.replyPreview("parent-1").value == null)

        vm.setReply(parent)

        assertEquals(ReplyPreviewData("alice", "original text"), vm.replyPreview("parent-1").value)
    }

    @Test
    fun `msg submission creates query target and opens it`() = runTest {
        val manager = FakeConnectionManager(network.id)
        val vm = viewModel(channel, manager)
        vm.state.first { it.buffer != null }
        val opened = CompletableDeferred<Long>()
        vm.saveDraft("/msg alice hello there")

        vm.submit("/msg alice hello there", { opened.complete(it) })
        val openedBuffer = opened.await()

        assertEquals(listOf(SentMessage(query.id, "hello there", null)), manager.messages)
        assertEquals(query.id, openedBuffer)
    }

    @Test
    fun `moderation commands are ignored outside channel buffers`() = runTest {
        val manager = FakeConnectionManager(network.id)
        val vm = viewModel(query, manager)
        vm.state.first { it.buffer != null }

        vm.submit("/kick alice", {}, {})
        vm.submit("/ban alice", {}, {})
        advanceUntilIdle()

        assertTrue(manager.sentLines.isEmpty())
    }

    @Test
    fun `channel commands use wire target instead of collision-safe internal name`() = runTest {
        val transport = RecordingTransport()
        val client = testClient(transport)
        client.start()
        // Advancing virtual time here would fire the client's watchdog and close the transport.
        runCurrent()
        transport.sent.clear()
        val collisionRoom = channel.copy(
            name = "#room\u0000account:stable",
            displayName = "!WireRoom",
        )
        val vm = viewModel(
            collisionRoom,
            FakeConnectionManager(network.id, client = client),
        )
        vm.state.first { it.buffer != null }

        vm.submit("/topic reviewed topic", {}, {})
        vm.setMemberMode("alice", 'o', grant = true)
        vm.kick("bob", "reason")
        vm.ban("carol")
        runCurrent()

        val commands = transport.sent.map { IrcMessage.parse(it) }
        assertEquals(listOf("TOPIC", "MODE", "KICK", "MODE"), commands.map { it.command })
        assertTrue(commands.all { it.params.firstOrNull() == "!WireRoom" })
        assertTrue(transport.sent.none { '\u0000' in it })
        client.stop()
        runCurrent()
    }

    @Test
    fun `server buffer invalid raw command surfaces snackbar without sending`() = runTest {
        val server = BufferEntity(
            networkId = network.id,
            name = "*",
            displayName = "test",
            type = BufferType.SERVER,
        ).let { it.copy(id = db.bufferDao().insert(it)) }
        val manager = FakeConnectionManager(network.id)
        val vm = viewModel(server, manager)
        vm.state.first { it.buffer != null }

        vm.submit("/", {}, {})
        advanceUntilIdle()

        assertEquals(ChatUiEvent.InvalidCommand, vm.uiEvents.value.single().value)
        assertTrue(manager.sentLines.isEmpty())
    }

    @Test
    fun `visible ready chat does not launch redundant history reconciliation`() = runTest {
        val history = FakeHistoryResyncController()
        val manager = FakeConnectionManager(network.id, client = testClient())
        val vm = viewModel(channel, manager, history)
        vm.state.first { it.buffer != null }

        vm.onResume()
        vm.onResume()
        advanceUntilIdle()

        assertTrue(history.reconciledBuffers.isEmpty())
    }

    @Test
    fun `entry readiness distinguishes active catchup from settled or offline startup`() {
        val ready = io.github.trevarj.motd.service.ConnectionActivitySnapshot(
            states = mapOf(network.id to ConnectionState.Ready("me")),
        )
        val catchingUp = ready.copy(historyCatchUpPending = setOf(network.id))
        val connecting = io.github.trevarj.motd.service.ConnectionActivitySnapshot(
            states = mapOf(network.id to ConnectionState.Connecting),
        )
        val retrying = io.github.trevarj.motd.service.ConnectionActivitySnapshot(
            states = mapOf(network.id to ConnectionState.Failed("retry", fatal = false)),
            progressing = mapOf(network.id to true),
        )
        val terminal = io.github.trevarj.motd.service.ConnectionActivitySnapshot(
            states = mapOf(network.id to ConnectionState.Failed("fatal", fatal = true)),
        )
        val offline = io.github.trevarj.motd.service.ConnectionActivitySnapshot(
            initializationComplete = true,
        )

        assertFalse(entryHistoryReady(catchingUp, network.id))
        assertTrue(entryHistoryReady(ready, network.id))
        assertFalse(entryHistoryReady(connecting, network.id))
        assertFalse(entryHistoryReady(retrying, network.id))
        assertTrue(entryHistoryReady(terminal, network.id))
        assertTrue(entryHistoryReady(offline, network.id))
    }

    @Test
    fun `viewport read marker advances only while chat destination is resumed`() = runTest {
        val manager = FakeConnectionManager(network.id)
        val vm = viewModel(channel, manager)
        vm.state.first { it.buffer != null }
        val anchor = TimelineAnchor(serverTime = 500, eventId = 5)

        vm.markRead(anchor)
        advanceUntilIdle()
        vm.onResume()
        vm.markRead(anchor)
        advanceUntilIdle()
        vm.onPause()
        vm.markRead(TimelineAnchor(serverTime = 600, eventId = 6))
        advanceUntilIdle()

        assertEquals(listOf(channel.id to anchor), manager.readMarkers)
    }

    @Test
    fun `timeline history status follows the current buffer`() = runTest {
        val history = FakeHistoryResyncController()
        val vm = viewModel(channel, FakeConnectionManager(network.id), history)
        val collector = backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            vm.historySyncStatus.collect()
        }
        runCurrent()

        history.setSyncStatus(HistorySyncStatus.Partial("fixture"))
        runCurrent()

        assertEquals(HistorySyncStatus.Partial("fixture"), vm.historySyncStatus.value)
        collector.cancel()
    }

    @Test
    fun `server buffer never performs automatic history reconciliation`() = runTest {
        val server = BufferEntity(
            networkId = network.id,
            name = "*",
            displayName = "test",
            type = BufferType.SERVER,
        ).let { it.copy(id = db.bufferDao().insert(it)) }
        val history = FakeHistoryResyncController()
        val manager = FakeConnectionManager(network.id, client = testClient())
        val vm = viewModel(server, manager, history)
        vm.state.first { it.buffer != null }

        vm.onResume()
        advanceUntilIdle()

        assertTrue(history.reconciledBuffers.isEmpty())
    }

    @Test
    fun `ready server buffer with a read anchor does not wait for ineligible history preparation`() = runTest {
        val server = BufferEntity(
            networkId = network.id,
            name = "*",
            displayName = "test",
            type = BufferType.SERVER,
            localReadAnchorTime = 100,
            localReadAnchorEventId = 10,
        ).let { it.copy(id = db.bufferDao().insert(it)) }
        val history = FakeHistoryResyncController()
        val vm = viewModel(
            server,
            FakeConnectionManager(network.id, client = testClient()),
            history,
        )
        vm.state.first { it.buffer != null }
        vm.onResume()

        val target = checkNotNull(vm.initialTarget.first { it != null })

        assertEquals(100L, target.serverTime)
        assertTrue(history.reconciledBuffers.isEmpty())
    }

    @Test
    fun `stale redirect route uses canonical foreground buffer id`() = runTest {
        val canonical = channel.copy(id = 42)
        val foreground = FakeForegroundBufferTracker()
        val manager = FakeConnectionManager(network.id)
        val vm = viewModel(
            buffer = canonical,
            manager = manager,
            routeBufferId = channel.id,
            foreground = foreground,
        )
        vm.state.first { it.buffer != null }

        vm.onResume()
        advanceUntilIdle()

        assertEquals(canonical.id, foreground.foregroundBufferId.value)

        vm.onPause()
        advanceUntilIdle()

        assertEquals(null, foreground.foregroundBufferId.value)
    }

    @Test
    fun `reaction uses urgent history reconciliation to promote the msgid`() = runTest {
        val messages = FakeMessageRepository()
        val history = FakeHistoryResyncController { attempt ->
            if (attempt == 1) messages.msgid.value = "server-parent"
        }
        val manager = FakeConnectionManager(
            networkId = network.id,
            state = ConnectionState.Ready("me"),
            client = testClient(),
            reactionCapability = ReactionCapability(canAdd = true, canRemoveOwn = true),
        )
        val vm = viewModel(channel, manager, history, messages)
        vm.state.first { it.buffer != null }
        val pending = message(
            bufferId = channel.id,
            text = "pending parent",
            msgid = null,
            sender = "me",
            id = 42,
        )

        vm.react(pending, "👍")
        advanceUntilIdle()

        assertEquals(listOf(channel.id), history.pendingReconciledBuffers)
        assertTrue(history.reconciledBuffers.isEmpty())
        assertEquals(listOf(SentReaction(channel.id, "server-parent", "👍")), manager.reactions)
    }

    @Test
    fun `reaction allows urgent history to finish a serialized wire wait`() = runTest {
        val messages = FakeMessageRepository()
        val history = FakeHistoryResyncController { attempt ->
            if (attempt == 1) {
                delay(35_000)
                messages.msgid.value = "delayed-server-parent"
            }
        }
        val manager = FakeConnectionManager(
            networkId = network.id,
            state = ConnectionState.Ready("me"),
            client = testClient(),
            reactionCapability = ReactionCapability(canAdd = true, canRemoveOwn = true),
        )
        val vm = viewModel(channel, manager, history, messages)
        vm.state.first { it.buffer != null }

        vm.react(
            message(
                bufferId = channel.id,
                text = "pending behind history",
                msgid = null,
                sender = "me",
                id = 44,
            ),
            "👍",
        )
        advanceUntilIdle()

        assertEquals(listOf(channel.id), history.pendingReconciledBuffers)
        assertEquals(
            listOf(SentReaction(channel.id, "delayed-server-parent", "👍")),
            manager.reactions,
        )
    }

    @Test
    fun `reaction uses fast msgid without waiting for slow history`() = runTest {
        val messages = FakeMessageRepository().apply {
            msgid.value = "fast-server-parent"
        }
        val history = FakeHistoryResyncController {
            awaitCancellation()
        }
        val manager = FakeConnectionManager(
            networkId = network.id,
            state = ConnectionState.Ready("me"),
            client = testClient(),
            reactionCapability = ReactionCapability(canAdd = true, canRemoveOwn = true),
        )
        val vm = viewModel(channel, manager, history, messages)
        vm.state.first { it.buffer != null }

        vm.react(
            message(
                bufferId = channel.id,
                text = "fast parent",
                msgid = null,
                sender = "me",
                id = 43,
            ),
            "👍",
        )
        advanceUntilIdle()

        assertEquals(
            listOf(SentReaction(channel.id, "fast-server-parent", "👍")),
            manager.reactions,
        )
    }

    @Test
    fun `reaction failures enqueue typed replay-safe events`() = runTest {
        val blocked = viewModel(channel, FakeConnectionManager(network.id))
        blocked.state.first { it.buffer != null }
        blocked.react(message(channel.id, "confirmed", "m1", "alice"), "👍")
        advanceUntilIdle()
        assertEquals(ChatUiEvent.ReactionBlocked, blocked.uiEvents.value.single().value)

        val unconfirmed = viewModel(
            channel,
            FakeConnectionManager(
                networkId = network.id,
                state = ConnectionState.Ready("me"),
                reactionCapability = ReactionCapability(canAdd = true, canRemoveOwn = true),
            ),
        )
        unconfirmed.state.first { it.buffer != null }
        unconfirmed.react(message(channel.id, "pending", null, "me", id = 91), "👍")
        advanceUntilIdle()
        assertEquals(
            ChatUiEvent.ReactionTargetUnavailable,
            unconfirmed.uiEvents.value.single().value,
        )

        val sendFailure = viewModel(
            channel,
            FakeConnectionManager(
                networkId = network.id,
                state = ConnectionState.Ready("me"),
                reactionError = true,
                reactionCapability = ReactionCapability(canAdd = true, canRemoveOwn = true),
            ),
        )
        sendFailure.state.first { it.buffer != null }
        sendFailure.react(message(channel.id, "confirmed", "m2", "alice"), "👍")
        advanceUntilIdle()
        assertEquals(ChatUiEvent.ReactionSendFailed, sendFailure.uiEvents.value.single().value)
    }

    @Test
    fun `retry preserves failed row when no replacement is accepted`() = runTest {
        val messages = FakeMessageRepository()
        val manager = FakeConnectionManager(network.id, retryAccepted = false)
        val vm = viewModel(channel, manager, messages = messages)
        vm.state.first { it.buffer != null }
        val failed = message(channel.id, "try again", null, "me", id = 77).copy(failed = true)

        vm.retry(failed)
        advanceUntilIdle()

        assertTrue(messages.deletedIds.isEmpty())
        assertTrue(manager.messages.isEmpty())
        assertEquals(ChatUiEvent.SendRejected, vm.uiEvents.value.single().value)
    }

    @Test
    fun `reply jump failure queues exact retry and retry reissues opaque msgid`() = runTest {
        val messages = FakeMessageRepository()
        val vm = viewModel(channel, FakeConnectionManager(network.id), messages = messages)
        vm.state.first { it.buffer != null }
        vm.onInitialPositionHandled()
        val exact = "MiXeD/opaque=Reply"

        vm.jumpToRepliedMessage(exact)
        advanceUntilIdle()

        val queued = vm.uiEvents.value.single()
        val failure = queued.value as ChatUiEvent.ReplyJumpUnavailable
        assertEquals(exact, failure.request.msgid)
        vm.acknowledgeUiEvent(queued.id)
        assertTrue(vm.uiEvents.value.isEmpty())

        messages.resolvedByMsgid = message(channel.id, "parent", exact, "alice", id = 90)
        vm.retryReplyJump(failure.request)
        advanceUntilIdle()

        assertEquals(listOf(exact, exact), messages.requestedMsgids)
        assertEquals(exact, vm.jumpTarget.value?.expectedMsgid)
        assertEquals(90L, vm.jumpTarget.value?.expectedEventId)
    }

    @Test
    fun `newer reply jump supersedes older target and ignores stale acknowledgment`() = runTest {
        val messages = FakeMessageRepository()
        val vm = viewModel(channel, FakeConnectionManager(network.id), messages = messages)
        vm.state.first { it.buffer != null }
        vm.onInitialPositionHandled()
        messages.resolvedByMsgid = message(channel.id, "first", "first", "alice", id = 90)

        vm.jumpToRepliedMessage("first")
        advanceUntilIdle()
        val first = vm.jumpTarget.value!!

        messages.resolvedByMsgid = message(channel.id, "second", "second", "alice", id = 91)
        vm.jumpToRepliedMessage("second")
        advanceUntilIdle()
        val second = vm.jumpTarget.value!!

        assertTrue(second.requestToken > first.requestToken)
        assertEquals("second", second.expectedMsgid)
        vm.onJumpHandled(first.requestToken)
        assertEquals(second, vm.jumpTarget.value)
        vm.onJumpHandled(second.requestToken)
        assertNull(vm.jumpTarget.value)
    }

    @Test
    fun `rapid reply taps cancel an in-flight older resolve`() = runTest {
        val messages = FakeMessageRepository().apply { blockedMsgid = "slow" }
        val vm = viewModel(channel, FakeConnectionManager(network.id), messages = messages)
        vm.state.first { it.buffer != null }
        vm.onInitialPositionHandled()

        vm.jumpToRepliedMessage("slow")
        advanceUntilIdle()
        assertTrue(messages.blockedResolutionStarted.isCompleted)

        messages.resolvedByMsgid = message(channel.id, "newer", "fast", "alice", id = 92)
        vm.jumpToRepliedMessage("fast")
        advanceUntilIdle()

        assertEquals("fast", vm.jumpTarget.value?.expectedMsgid)
        assertTrue(vm.uiEvents.value.isEmpty())
    }

    @Test
    fun `persisted current identity keeps account reaction ownership while disconnected`() = runTest {
        db.networkIdentityDao().upsert(NetworkIdentityEntity(network.id, selfNick = "newNick"))
        db.userDao().upsert(
            UserEntity(
                networkId = network.id,
                nick = IrcIdentityRules().normalize("newNick"),
                account = "stable-account",
            ),
        )
        val messages = FakeMessageRepository().apply {
            reactionRows = listOf(
                ReactionEntity(
                    bufferId = channel.id,
                    targetMsgid = "target",
                    actorKey = "account:stable-account",
                    sender = "oldNick",
                    emoji = "👍",
                    serverTime = 1,
                ),
            )
        }
        val vm = viewModel(
            channel,
            FakeConnectionManager(network.id, state = ConnectionState.Disconnected),
            messages = messages,
        )
        vm.setVisibleMsgids(listOf("target"))

        val chips = vm.reactionChips.first { it["target"]?.singleOrNull()?.mine == true }
        assertTrue(chips.getValue("target").single().mine)
    }

    @Test
    fun `social toggles use rules-aware atomic preference mutation`() = runTest {
        val settings = FakeSettingsRepository()
        settings.settings.value = Settings(friends = setOf("Nick[", "nick{"))
        val vm = viewModel(
            channel,
            FakeConnectionManager(network.id),
            settings = settings,
        )
        vm.state.first { it.buffer != null }

        vm.toggleFool("NICK{")
        advanceUntilIdle()

        val mutation = settings.foolMutations.single()
        assertEquals("NICK{", mutation.nick)
        assertTrue(mutation.enabled)
        assertTrue(mutation.rules.normalize("Nick[") == mutation.rules.normalize("nick{"))
        assertFalse(settings.legacyFoolMutationCalled)
    }

    @Test
    fun `recovered unread gap positions entry at oldest unread while preserving divider boundary`() = runTest {
        val markerId = db.messageDao().insertAll(
            listOf(message(channel.id, "marker", null, "alice").copy(
                serverTime = 100,
                dedupKey = "marker",
            )),
        ).single()
        val historyIds = db.messageDao().insertAll(
            (1..513).map { ordinal ->
                message(channel.id, "history-$ordinal", null, "alice").copy(
                    serverTime = 100L + ordinal,
                    dedupKey = "history-$ordinal",
                )
            },
        )
        db.messageDao().insertAll(
            (1..3).map { ordinal ->
                message(channel.id, "live-$ordinal", "live-$ordinal", "alice").copy(
                    serverTime = 1_000L + ordinal,
                    dedupKey = "live-$ordinal",
                )
            },
        )
        val vm = viewModel(
            channel.copy(
                localReadAnchorTime = 100,
                localReadAnchorEventId = markerId,
            ),
            FakeConnectionManager(network.id),
            messages = FakeMessageRepository(
                events = listOf(checkNotNull(db.messageDao().byCanonicalId(historyIds.first()))),
                newerCount = 515,
            ),
        )

        vm.state.first { it.buffer != null }
        val divider = vm.unreadEntrySnapshot.first { it != null }
        val target = checkNotNull(vm.initialTarget.first { it != null })

        assertEquals(101L, divider?.marker?.serverTime)
        assertEquals(historyIds.first() - 1L, divider?.marker?.eventId)
        // Entry lands on the oldest unread row (history-1, with 515 rows newer than it): the first
        // unseen message tops the viewport and the rest of the unread continues below it.
        assertEquals(515, target.index)
        assertEquals(historyIds.first(), target.expectedEventId)
        assertNull(target.expectedMsgid)
        assertFalse(target.fromSavedPosition)
        // The marker target must displace an already-bottom conversation so entry actually scrolls.
        assertTrue(target.forceScrollOnEntry)
        // ChatScreen realizes the top placement from this flag: first unread tops the viewport.
        assertTrue(target.placeAtTop)
    }

    @Test
    fun `cold ready entry anchors after connection catchup publishes the recent island`() = runTest {
        val markerId = db.messageDao().insertAll(
            listOf(message(channel.id, "marker", "marker", "alice").copy(serverTime = 100)),
        ).single()
        db.messageDao().insertAll(
            listOf(message(channel.id, "stale unread", "m101", "alice").copy(serverTime = 101)),
        )
        val manager = FakeConnectionManager(
            networkId = network.id,
            state = ConnectionState.Ready("me"),
            client = testClient(),
            historyPending = setOf(network.id),
        )
        val messages = FakeMessageRepository()
        val vm = viewModel(
            channel.copy(localReadAnchorTime = 100, localReadAnchorEventId = markerId),
            manager,
            messages = messages,
        )
        vm.state.first { it.buffer != null }
        runCurrent()

        assertNull(vm.initialTarget.value)

        val recentId = db.messageDao().insertAll(
            listOf(message(channel.id, "recent unread", "m900", "alice").copy(serverTime = 900)),
        ).single()
        messages.observedBounds.value = io.github.trevarj.motd.data.visibility.MessageWindowBounds(
            lowerBoundary = TimelineAnchor(900, recentId),
        )
        messages.resolvedById = checkNotNull(db.messageDao().byCanonicalId(recentId))
        manager.finishHistoryCatchUp(network.id)

        val target = checkNotNull(vm.initialTarget.first { it != null })
        assertEquals(recentId, target.expectedEventId)
        assertEquals(900L, target.serverTime)
    }

    @Test
    fun `synthetic mute floor fallback uses positional entry without impossible identity`() = runTest {
        val messages = FakeMessageRepository(newerCount = 1)
        val vm = viewModel(
            channel.copy(localUnreadFloorTime = 100),
            FakeConnectionManager(network.id),
            messages = messages,
        )
        vm.state.first { it.buffer != null }

        val target = checkNotNull(vm.initialTarget.first { it != null })
        assertNull(target.expectedEventId)
        assertNull(target.expectedMsgid)
        assertEquals(100L, target.serverTime)
        assertTrue(target.placeAtTop)
        assertEquals(0, materializableTargetIndex(target.index, 1, hasExactIdentity = false))
    }

    @Test
    fun `hidden marker fallback uses positional entry without impossible identity`() = runTest {
        val markerId = db.messageDao().insertAll(
            listOf(
                message(channel.id, "hidden marker", "marker", "alice").copy(
                    serverTime = 100,
                    dedupKey = "marker",
                ),
            ),
        ).single()
        val settings = FakeSettingsRepository().apply {
            this.settings.value = Settings(
                fools = setOf("alice"),
                foolsMode = FoolsMode.HIDE,
            )
        }
        val vm = viewModel(
            channel.copy(localReadAnchorTime = 100, localReadAnchorEventId = markerId),
            FakeConnectionManager(network.id),
            messages = FakeMessageRepository(newerCount = 1),
            settings = settings,
        )
        vm.state.first { it.buffer != null }

        val target = checkNotNull(vm.initialTarget.first { it != null })
        assertNull(target.expectedEventId)
        assertNull(target.expectedMsgid)
        assertEquals(100L, target.serverTime)
        assertEquals(0, materializableTargetIndex(target.index, 1, hasExactIdentity = false))
    }

    @Test
    fun `initial re-resolution preserves first unread top placement`() = runTest {
        val id = db.messageDao().insertAll(
            listOf(
                message(channel.id, "target", "target", "alice").copy(
                    serverTime = 200,
                    dedupKey = "target",
                ),
            ),
        ).single()
        val targetRow = checkNotNull(db.messageDao().byCanonicalId(id))
        val vm = viewModel(
            channel,
            FakeConnectionManager(network.id),
            messages = FakeMessageRepository(events = listOf(targetRow)),
        )
        vm.state.first { it.buffer != null }

        vm.reresolveInitialOnce(
            ChatPositionTarget(
                index = 99,
                expectedEventId = id,
                expectedMsgid = "target",
                serverTime = 200,
                forceScrollOnEntry = true,
                placeAtTop = true,
            ),
        ).join()

        val repaired = checkNotNull(vm.initialTarget.value)
        assertTrue(repaired.forceScrollOnEntry)
        assertTrue(repaired.placeAtTop)
    }

    @Test
    fun `ordinary unresolved entry remains read gated without a message error`() = runTest {
        val vm = viewModel(channel, FakeConnectionManager(network.id))
        vm.state.first { it.buffer != null }

        vm.onInitialPositionUnresolved()

        assertEquals(
            EntryPositionState.Unresolved(messageUnavailable = false),
            vm.entryState.value,
        )
    }

    @Test
    fun `settled entry never downgrades to unresolved`() = runTest {
        val vm = viewModel(channel, FakeConnectionManager(network.id))
        vm.state.first { it.buffer != null }

        vm.onInitialPositionHandled()
        assertEquals(EntryPositionState.Settled, vm.entryState.value)

        // A late unresolved signal (ordinary or explicit-jump failure) must not clear the gate open.
        vm.onInitialPositionUnresolved()
        assertEquals(EntryPositionState.Settled, vm.entryState.value)
    }

    @Test
    fun `reply jump failure after concurrent entry settlement still reports unavailable`() = runTest {
        val vm = viewModel(channel, FakeConnectionManager(network.id))
        vm.state.first { it.buffer != null }

        // The tap lands while entry is Pending, so the jump would settle entry; entry then settles
        // on its own before the resolve completes NotFound.
        vm.jumpToRepliedMessage("missing")
        vm.onInitialPositionHandled()
        advanceUntilIdle()

        assertEquals(EntryPositionState.Settled, vm.entryState.value)
        val failure = vm.uiEvents.value.single().value as ChatUiEvent.ReplyJumpUnavailable
        assertEquals("missing", failure.request.msgid)
    }

    @Test
    fun `message-unavailable failure never degrades to an ordinary unresolved entry`() = runTest {
        val handle = SavedStateHandle()
        val vm = viewModel(
            channel,
            FakeConnectionManager(network.id),
            jumpToMsgid = "missing-message",
            savedStateHandle = handle,
        )
        advanceUntilIdle()
        assertEquals(
            EntryPositionState.Unresolved(messageUnavailable = true),
            vm.entryState.value,
        )

        // A later ordinary unresolved signal must not clear the durable message-unavailable report;
        // live state stays consistent with the persisted SavedState keys.
        vm.onInitialPositionUnresolved()
        assertEquals(
            EntryPositionState.Unresolved(messageUnavailable = true),
            vm.entryState.value,
        )
        assertTrue(handle.get<Boolean>("entry_position_unresolved") == true)
        assertTrue(handle.get<Boolean>("entry_message_unavailable") == true)
        assertFalse(handle.get<Boolean>("entry_position_settled") == true)
    }

    @Test
    fun `entry state restores from persisted SavedState keys after process death`() = runTest {
        val settled = viewModel(
            channel,
            FakeConnectionManager(network.id),
            restoredState = mapOf("entry_position_settled" to true),
        )
        assertEquals(EntryPositionState.Settled, settled.entryState.value)

        val unavailable = viewModel(
            channel,
            FakeConnectionManager(network.id),
            restoredState = mapOf(
                "entry_position_unresolved" to true,
                "entry_message_unavailable" to true,
            ),
        )
        assertEquals(
            EntryPositionState.Unresolved(messageUnavailable = true),
            unavailable.entryState.value,
        )

        // Settled wins even when an unresolved flag is also persisted (no downgrade on restore).
        val both = viewModel(
            channel,
            FakeConnectionManager(network.id),
            restoredState = mapOf(
                "entry_position_settled" to true,
                "entry_position_unresolved" to true,
            ),
        )
        assertEquals(EntryPositionState.Settled, both.entryState.value)
    }

    @Test
    fun `mention FAB re-resolves its exact row without leaving the source island`() = runTest {
        val mention = message(channel.id, "hello me", "mention", "alice", id = 42).copy(
            serverTime = 500,
            timelineOrder = 42,
        )
        val messages = FakeMessageRepository(
            events = listOf(mention),
            newerCount = 17,
            recentNewerCount = 3,
        )
        val vm = viewModel(
            channel.copy(localUnreadFloorTime = 100),
            FakeConnectionManager(network.id),
            messages = messages,
        )
        vm.state.first { it.buffer != null }
        vm.initialTarget.first { it != null }

        vm.focusRecentMention(
            ChatPositionTarget(
                index = 17,
                expectedEventId = mention.id,
                expectedMsgid = mention.msgid,
                serverTime = mention.serverTime,
            ),
        )
        advanceUntilIdle()

        val target = checkNotNull(vm.jumpTarget.value)
        assertEquals(3, target.index)
        assertEquals(mention.id, target.expectedEventId)
        assertEquals(mention.msgid, target.expectedMsgid)
        assertEquals(HistoryWindowFocus.Recent, messages.countedFocuses.last())
    }

    @Test
    fun `newest escape reacts when a newer gap appears beside the focused island`() = runTest {
        val mention = message(channel.id, "hello me", "mention", "alice", id = 42).copy(
            serverTime = 500,
            timelineOrder = 42,
        )
        val messages = FakeMessageRepository(events = listOf(mention), newerCount = 17)
        val vm = viewModel(
            channel.copy(localUnreadFloorTime = 100),
            FakeConnectionManager(network.id),
            messages = messages,
            jumpToTime = mention.serverTime,
            jumpToEventId = mention.id,
        )
        vm.state.first { it.buffer != null }
        vm.activeHistoryWindow.first { it.focus is HistoryWindowFocus.Around }
        val collected = mutableListOf<Boolean>()
        val collection = backgroundScope.launch {
            vm.hasNewerHistoryIsland.collect(collected::add)
        }
        runCurrent()
        assertFalse(vm.hasNewerHistoryIsland.value)

        messages.observedBounds.value = io.github.trevarj.motd.data.visibility.MessageWindowBounds(
            upperBoundary = TimelineAnchor(900, 90, 90),
        )
        runCurrent()

        assertTrue(vm.hasNewerHistoryIsland.value)
        assertEquals(
            TimelineAnchor(900, 90, 90),
            vm.activeHistoryWindow.value.bounds.upperBoundary,
        )
        assertTrue(collected.last())
        vm.focusRecentHistory()
        runCurrent()
        assertEquals(HistoryWindowFocus.Recent, vm.activeHistoryWindow.value.focus)
        collection.cancel()
    }

    @Test
    fun `missing entry message jump reports the unavailable target`() = runTest {
        val vm = viewModel(
            channel,
            FakeConnectionManager(network.id),
            jumpToMsgid = "missing-message",
        )

        advanceUntilIdle()

        assertEquals(
            EntryPositionState.Unresolved(messageUnavailable = true),
            vm.entryState.value,
        )
    }

    @Test
    fun `coalesced saved viewport follows canonical event and retains pixel offset`() = runTest {
        val winnerId = db.messageDao().insertAll(
            listOf(message(channel.id, "history", "server-id", "alice").copy(serverTime = 500)),
        ).single()
        val loserId = db.messageDao().insertAll(
            listOf(message(channel.id, "live", null, "alice").copy(serverTime = 200)),
        ).single()
        val positions = ChatScrollPositionStore().apply {
            put(
                channel.id,
                ChatScrollPosition(
                    index = 1,
                    offset = 37,
                    msgid = null,
                    serverTime = 200,
                    rowId = loserId,
                ),
            )
        }
        val vm = viewModel(
            channel,
            FakeConnectionManager(network.id),
            scrollPositions = positions,
        )
        vm.state.first { it.buffer != null }
        val restored = vm.initialTarget.first { it != null }
        assertEquals(1, restored?.index)
        assertEquals(37, restored?.offset)
        assertEquals(loserId, restored?.expectedEventId)
        assertTrue(restored?.fromSavedPosition == true)
        vm.onInitialPositionHandled()
        assertEquals(null, vm.initialTarget.value)

        db.canonicalTimelineDao().upsertEventRedirect(EventRedirectEntity(loserId, winnerId))
        db.messageDao().deleteById(loserId)

        val redirected = vm.initialTarget.first { it != null }
        assertEquals(0, redirected?.index)
        assertEquals(37, redirected?.offset)
        assertEquals(winnerId, redirected?.expectedEventId)
        assertTrue(redirected?.fromSavedPosition == true)
        assertEquals(winnerId, positions.get(channel.id)?.rowId)
        assertEquals(500L, positions.get(channel.id)?.serverTime)
        assertEquals(37, positions.get(channel.id)?.offset)
    }

    private fun viewModel(
        buffer: BufferEntity,
        manager: FakeConnectionManager,
        history: HistoryResyncController = HistoryResyncCoordinator(
            db = db,
            processor = processor,
            scope = CoroutineScope(Dispatchers.Unconfined),
        ),
        messages: MessageRepository = FakeMessageRepository(),
        routeBufferId: Long = buffer.id,
        foreground: FakeForegroundBufferTracker = FakeForegroundBufferTracker(),
        scrollPositions: ChatScrollPositionStore = ChatScrollPositionStore(),
        settings: FakeSettingsRepository = FakeSettingsRepository(),
        buffers: FakeBufferRepository = FakeBufferRepository(buffer, routeBufferId),
        jumpToMsgid: String? = null,
        jumpToTime: Long = 0,
        jumpToEventId: Long? = null,
        restoredState: Map<String, Any> = emptyMap(),
        // Injectable so a test can observe the write-through entry-position keys after transitions.
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): ChatViewModel {
        val eventSink: IrcEventSink = processor
        val routeState = mutableMapOf<String, Any>("bufferId" to routeBufferId)
        jumpToMsgid?.let { routeState["jumpToMsgid"] = it }
        if (jumpToTime > 0) routeState["jumpToTime"] = jumpToTime
        jumpToEventId?.let { routeState["jumpToEventId"] = it }
        routeState.putAll(restoredState)
        routeState.forEach { (key, value) -> savedStateHandle[key] = value }
        return ChatViewModel(
            savedStateHandle = savedStateHandle,
            messageRepository = messages,
            bufferRepository = buffers,
            networkIdentityDao = db.networkIdentityDao(),
            dccTransferDao = db.dccTransferDao(),
            dccTransferController = FakeDccTransferController(),
            connectionManager = manager,
            typingTracker = FakeTypingTracker(),
            foregroundBufferTracker = foreground,
            linkPreviewRepository = object : LinkPreviewRepository {
                override suspend fun preview(url: String): LinkPreview? = null
            },
            draftStore = ComposerDraftStore(db),
            scrollPositionStore = scrollPositions,
            eventSink = eventSink,
            settingsRepository = settings,
            replyPrefs = FakeReplyPrefs(),
            visibilityReader = MessageVisibilityReader(db),
            historyResyncCoordinator = history,
            userDao = db.userDao(),
            contentPreviewPrefs = FakeContentPreviewPrefs(),
            audioMetadataRepository = FakeAudioMetadataRepository(),
            audioPlaybackController = FakeAudioPlaybackController(),
        )
    }

    private fun testClient(transport: IrcTransport? = null) = IrcClient(
        config = IrcClientConfig("irc.example", 6697, true, "me", "me", "Me"),
        factory = TransportFactory { _, _, _, _, _ -> transport ?: error("transport is not used") },
        scope = CoroutineScope(SupervisorJob() + dispatcher),
    )

    private fun message(
        bufferId: Long,
        text: String,
        msgid: String?,
        sender: String,
        id: Long = 0,
    ) = MessageEntity(
        id = id,
        bufferId = bufferId,
        msgid = msgid,
        serverTime = 1,
        sender = sender,
        kind = MessageKind.PRIVMSG,
        text = text,
        dedupKey = msgid ?: "pending:$id",
    )

    private data class SentMessage(val bufferId: Long, val text: String, val replyTo: Long?)
    private data class SentReaction(val bufferId: Long, val msgid: String, val emoji: String)

    private class FakeDccTransferController : DccTransferController {
        override fun observeAll(): Flow<List<DccTransferEntity>> = flowOf(emptyList())
        override fun observeForNetwork(networkId: Long): Flow<List<DccTransferEntity>> = flowOf(emptyList())
        override suspend fun acceptIncoming(
            transferId: Long,
            destinationUri: Uri,
            allowPrivateEndpoint: Boolean,
        ) = Unit
        override suspend fun reject(transferId: Long) = Unit
        override suspend fun removeRecord(transferId: Long) = Unit
        override suspend fun sendFile(bufferId: Long, sourceUri: Uri, secure: Boolean) = Unit
    }

    private class FakeConnectionManager(
        networkId: Long,
        state: ConnectionState = ConnectionState.Ready("me"),
        client: IrcClient? = null,
        private val retryAccepted: Boolean = true,
        private val sendAccepted: Boolean = true,
        private val sendGate: CompletableDeferred<Unit>? = null,
        private val reactionError: Boolean = false,
        private val sendRejection: io.github.trevarj.motd.service.SendRejectionReason? = null,
        private val retryRejection: io.github.trevarj.motd.service.SendRejectionReason? = null,
        historyPending: Set<Long> = emptySet(),
        reactionCapability: ReactionCapability? = null,
    ) : ConnectionManager {
        private var currentClient: IrcClient? = client
        override val connectionStates = MutableStateFlow(mapOf(networkId to state))
        override val connectionActivity = MutableStateFlow(
            io.github.trevarj.motd.service.ConnectionActivitySnapshot(
                states = mapOf(networkId to state),
                progressing = if (client != null) mapOf(networkId to true) else emptyMap(),
                historyCatchUpPending = historyPending,
            ),
        )
        override val presenceStates: StateFlow<Map<PresenceKey, PresenceState>> =
            MutableStateFlow(emptyMap())
        override val rosterStates: StateFlow<Map<Long, RosterLoadState>> = MutableStateFlow(emptyMap())
        override val certPrompts = MutableStateFlow<List<CertPrompt>>(emptyList())
        override val reactionCapabilities: StateFlow<Map<Long, ReactionCapability>> =
            MutableStateFlow(reactionCapability?.let { mapOf(networkId to it) } ?: emptyMap())
        val messages = mutableListOf<SentMessage>()
        val reactions = mutableListOf<SentReaction>()
        val typing = mutableListOf<Pair<Long, String>>()
        val sentLines = mutableListOf<String>()
        val readMarkers = mutableListOf<Pair<Long, TimelineAnchor>>()
        val messageStarted = CompletableDeferred<Unit>()
        val typingSent = CompletableDeferred<Unit>()

        fun replaceClient(client: IrcClient?) {
            currentClient = client
        }

        fun finishHistoryCatchUp(networkId: Long) {
            connectionActivity.value = connectionActivity.value.copy(
                historyCatchUpPending = connectionActivity.value.historyCatchUpPending - networkId,
            )
        }

        fun publishState(
            networkId: Long,
            state: ConnectionState,
            progressing: Boolean = connectionActivity.value.progressing[networkId] == true,
            initialized: Boolean = true,
        ) {
            connectionStates.value = mapOf(networkId to state)
            connectionActivity.value = io.github.trevarj.motd.service.ConnectionActivitySnapshot(
                states = mapOf(networkId to state),
                progressing = if (progressing) mapOf(networkId to true) else emptyMap(),
                initializationComplete = initialized,
            )
        }

        override fun clientFor(networkId: Long): IrcClient? = currentClient
        override suspend fun startAll() = Unit
        override suspend fun stopAll() = Unit
        override suspend fun connect(networkId: Long) = Unit
        override suspend fun disconnect(networkId: Long) = Unit
        override suspend fun reconnectStale() = Unit
        override suspend fun sendMessage(bufferId: Long, text: String, replyToEventId: Long?): io.github.trevarj.motd.service.SendAcceptance {
            sendRejection?.let {
                return io.github.trevarj.motd.service.SendAcceptance.Rejected(it)
            }
            if (!sendAccepted) {
                return io.github.trevarj.motd.service.SendAcceptance.Rejected(
                    io.github.trevarj.motd.service.SendRejectionReason.PERSISTENCE_FAILED,
                )
            }
            messages += SentMessage(bufferId, text, replyToEventId)
            messageStarted.complete(Unit)
            sendGate?.await()
            return io.github.trevarj.motd.service.SendAcceptance.Accepted(listOf(1L))
        }
        override suspend fun retryMessage(eventId: Long): io.github.trevarj.motd.service.SendAcceptance =
            retryRejection?.let {
                io.github.trevarj.motd.service.SendAcceptance.Rejected(it)
            } ?: if (retryAccepted) {
                io.github.trevarj.motd.service.SendAcceptance.Accepted(listOf(eventId))
            } else {
                io.github.trevarj.motd.service.SendAcceptance.Rejected(
                    io.github.trevarj.motd.service.SendRejectionReason.EVENT_NOT_RETRYABLE,
                )
            }
        override suspend fun sendTyping(bufferId: Long, state: String) {
            typing += bufferId to state
            typingSent.complete(Unit)
        }
        override suspend fun sendReact(bufferId: Long, msgid: String, emoji: String) {
            if (reactionError) error("reaction rejected")
            reactions += SentReaction(bufferId, msgid, emoji)
        }
        override suspend fun joinChannel(networkId: Long, channel: String) = Unit
        override suspend fun partChannel(bufferId: Long, reason: String?) = Unit
        override suspend fun ensureQueryBuffer(networkId: Long, nick: String): Long = 2L
        override suspend fun ensureServerBuffer(networkId: Long): Long = 3L
        override suspend fun markRead(bufferId: Long, anchor: TimelineAnchor) {
            readMarkers += bufferId to anchor
        }
        override suspend fun evaluatePushMode() = Unit
        override suspend fun trustCert(prompt: CertPrompt) = Unit
        override fun dismissCertPrompt(prompt: CertPrompt) = Unit
        override suspend fun requestMembers(bufferId: Long, force: Boolean) = Unit
        override suspend fun acceptInvite(messageId: Long) = Unit
        override suspend fun dismissInvite(messageId: Long) = Unit
    }

    private class FakeHistoryResyncController(
        private val onReconcile: suspend (Int) -> Unit = {},
    ) : HistoryResyncController {
        private val syncStatuses = MutableStateFlow<HistorySyncStatus>(HistorySyncStatus.Idle)
        val reconciledBuffers = mutableListOf<Long>()
        val pendingReconciledBuffers = mutableListOf<Long>()

        override fun syncStatus(bufferId: Long): Flow<HistorySyncStatus> = syncStatuses
        fun setSyncStatus(status: HistorySyncStatus) { syncStatuses.value = status }

        override suspend fun reconcileBuffer(
            buffer: BufferEntity,
            client: IrcClient,
            isCurrent: () -> Boolean,
        ): HistoryResyncState {
            check(isCurrent())
            reconciledBuffers += buffer.id
            onReconcile(reconciledBuffers.size)
            return HistoryResyncState.UpToDate
        }

        override suspend fun reconcilePendingMessage(
            buffer: BufferEntity,
            client: IrcClient,
            isCurrent: () -> Boolean,
        ): HistoryResyncState {
            check(isCurrent())
            pendingReconciledBuffers += buffer.id
            onReconcile(pendingReconciledBuffers.size)
            return HistoryResyncState.UpToDate
        }
    }

    private class FakeBufferRepository(
        private val current: BufferEntity,
        private val routeId: Long = current.id,
    ) : BufferRepository {
        private val buffer = MutableStateFlow(current)
        val layoutWrites = mutableListOf<Pair<Long, LayoutDensity?>>()
        var layoutWriteResult = true

        override fun observeChatList(): Flow<List<ChatListRow>> = flowOf(emptyList())
        override fun observeBuffer(id: Long): Flow<BufferEntity?> =
            buffer.takeIf { id == routeId || id == current.id } ?: flowOf(null)
        override fun observeMembers(bufferId: Long): Flow<List<MemberEntity>> = flowOf(emptyList())
        override suspend fun setPinned(id: Long, pinned: Boolean) = Unit
        override suspend fun setMuted(id: Long, muted: Boolean) = Unit
        override suspend fun setLayoutDensityOverride(id: Long, layout: LayoutDensity?): Boolean {
            layoutWrites += id to layout
            if (layoutWriteResult) buffer.value = buffer.value.copy(layoutDensityOverride = layout)
            return layoutWriteResult
        }
        override suspend fun deleteBuffer(id: Long) = Unit
    }

    private class FakeMessageRepository(
        private val events: List<MessageEntity> = emptyList(),
        private val newerCount: Int = 0,
        private val recentNewerCount: Int = newerCount,
        private val windowBounds: io.github.trevarj.motd.data.visibility.MessageWindowBounds =
            io.github.trevarj.motd.data.visibility.MessageWindowBounds(),
    ) : MessageRepository {
        val msgid = MutableStateFlow<String?>(null)
        val deletedIds = mutableListOf<Long>()
        val requestedMsgids = mutableListOf<String>()
        var resolvedByMsgid: MessageEntity? = null
        var resolvedById: MessageEntity? = null
        val countedFocuses = mutableListOf<HistoryWindowFocus>()
        var reactionRows: List<ReactionEntity> = emptyList()
        val observedBounds = MutableStateFlow(windowBounds)
        var blockedMsgid: String? = null
        val blockedResolutionStarted = CompletableDeferred<Unit>()
        private val blockedResolutionRelease = CompletableDeferred<Unit>()

        override fun messages(
            bufferId: Long,
            visibility: MessageVisibilitySpec,
        ): Flow<PagingData<MessageEntity>> = flowOf(PagingData.empty())
        override fun reactions(bufferId: Long, msgids: List<String>): Flow<List<ReactionEntity>> =
            flowOf(reactionRows.filter { it.bufferId == bufferId && it.targetMsgid in msgids })
        override suspend fun byId(id: Long): MessageEntity? =
            resolvedById?.takeIf { it.id == id } ?: events.firstOrNull { it.id == id }
        override suspend fun byMsgid(bufferId: Long, msgid: String): MessageEntity? {
            requestedMsgids += msgid
            if (msgid == blockedMsgid) {
                blockedResolutionStarted.complete(Unit)
                blockedResolutionRelease.await()
            }
            return resolvedByMsgid?.takeIf { it.bufferId == bufferId && it.msgid == msgid }
        }
        override fun observeByMsgid(bufferId: Long, msgid: String): Flow<MessageEntity?> = flowOf(null)
        override suspend fun awaitMsgid(id: Long, timeoutMs: Long): String? =
            withTimeoutOrNull(timeoutMs) { msgid.filterNotNull().first() }
        override suspend fun countNewerThan(
            bufferId: Long,
            serverTime: Long,
            id: Long,
            visibility: MessageVisibilitySpec,
        ): Int = newerCount
        override suspend fun countNewerThan(
            bufferId: Long,
            serverTime: Long,
            id: Long,
            visibility: MessageVisibilitySpec,
            focus: HistoryWindowFocus,
        ): Int {
            countedFocuses += focus
            return if (focus is HistoryWindowFocus.Around) newerCount else recentNewerCount
        }
        override suspend fun historyWindowBounds(
            bufferId: Long,
            focus: HistoryWindowFocus,
        ) = observedBounds.value
        override suspend fun deleteMessage(id: Long) { deletedIds += id }
        override fun observeHistoryWindowBounds(
            bufferId: Long,
            focus: HistoryWindowFocus,
        ) = observedBounds
    }

    private class RecordingTransport : IrcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()

        override suspend fun connect() = Unit
        override val incoming: Flow<String> = inbound.consumeAsFlow()
        override suspend fun send(line: String) { sent += line }
        override suspend fun close() { inbound.close() }
    }

    private class FakeTypingTracker : TypingTracker {
        override fun typingNicks(bufferId: Long): StateFlow<List<String>> = MutableStateFlow(emptyList())
    }

    private class FakeForegroundBufferTracker : ForegroundBufferTracker {
        override val foregroundBufferId = MutableStateFlow<Long?>(null)
        override fun set(bufferId: Long?) { foregroundBufferId.value = bufferId }
    }

    private class FakeSettingsRepository : SettingsRepository {
        override val settings = MutableStateFlow(Settings())
        data class SocialMutation(
            val nick: String,
            val enabled: Boolean,
            val rules: IrcIdentityRules,
        )
        val foolMutations = mutableListOf<SocialMutation>()
        var legacyFoolMutationCalled = false
        override suspend fun setThemeMode(m: ThemeMode) = Unit
        override suspend fun setDynamicColor(enabled: Boolean) = Unit
        override suspend fun setDeliveryMode(m: DeliveryMode) = Unit
        override suspend fun setLayoutDensity(d: LayoutDensity) = Unit
        override suspend fun setNickColorsEnabled(enabled: Boolean) = Unit
        override suspend fun setNickColorPalette(p: NickColorPalette) = Unit
        override suspend fun setNickColorOverride(nick: String, hue: Int?) = Unit
        override suspend fun setFriend(nick: String, isFriend: Boolean) = Unit
        override suspend fun setFool(nick: String, isFool: Boolean) {
            legacyFoolMutationCalled = true
        }
        override suspend fun setFool(nick: String, isFool: Boolean, identityRules: IrcIdentityRules) {
            foolMutations += SocialMutation(nick, isFool, identityRules)
        }
        override suspend fun setFoolsMode(m: FoolsMode) = Unit
        override suspend fun setShowJoinPartQuit(show: Boolean) = Unit
        override suspend fun setAvatarStyle(style: AvatarStyle) = Unit
        override suspend fun setChatWallpaper(w: ChatWallpaper) = Unit
        override suspend fun setShowComposerEmoji(show: Boolean) = Unit
        override suspend fun setChatSoundsEnabled(enabled: Boolean) = Unit
    }

    private class FakeReplyPrefs : ReplyPrefs {
        override val config = MutableStateFlow(ReplyConfig())
        override suspend fun setVisibleChannelPrefix(enabled: Boolean) = Unit
    }

    private class FakeContentPreviewPrefs : ContentPreviewPrefs {
        override val config = MutableStateFlow(ContentPreviewConfig())
        override suspend fun setShowImages(show: Boolean) = Unit
        override suspend fun setShowLinkPreviews(show: Boolean) = Unit
    }

    private class FakeAudioMetadataRepository : AudioMetadataRepository {
        override suspend fun metadata(url: String, networkId: Long?): AudioMetadata? = null
    }

    private class FakeAudioPlaybackController : AudioPlaybackController {
        override val state = MutableStateFlow(AudioPlaybackState())
        override val waveforms = MutableStateFlow<Map<String, io.github.trevarj.motd.audio.AudioWaveform>>(emptyMap())
        override val cacheStatuses = MutableStateFlow<Map<String, io.github.trevarj.motd.audio.AudioCacheStatus>>(emptyMap())
        override fun play(request: io.github.trevarj.motd.audio.AudioPlaybackRequest, speed: Float) = Unit
        override fun toggle(request: io.github.trevarj.motd.audio.AudioPlaybackRequest) = Unit
        override fun inspectCache(attachment: AudioAttachment) = Unit
        override fun toggleActive() = Unit
        override fun pause() = Unit
        override fun dismiss(itemId: String) = Unit
        override fun cancelLoading() = Unit
        override fun retryActive() = Unit
        override fun seekTo(itemId: String, positionMs: Long) = Unit
        override fun setSpeed(itemId: String, speed: Float) = Unit
    }
}
