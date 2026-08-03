package io.github.trevarj.motd.data.sync

import android.content.Context
import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingConfig
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.repo.HistoryWindowFocus
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryReference
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.HistoryReferenceType
import io.github.trevarj.motd.irc.client.IrcCommandException
import io.github.trevarj.motd.irc.client.IrcDisconnectedException
import io.github.trevarj.motd.irc.client.IrcTimeoutException
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.HistoryEventMetadata
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.event.ServerTimeSource
import io.github.trevarj.motd.irc.proto.Prefix
import io.github.trevarj.motd.irc.proto.IrcMessage
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Load-logic coverage for [ChatHistoryRemoteMediator], the on-open backfill. The gap this guards:
 * with SKIP_INITIAL_REFRESH, an empty buffer never gets a REFRESH, so Paging drives APPEND past the
 * end boundary — which must LATEST-seed the newest page instead of bailing on a null oldest bound.
 */
@OptIn(ExperimentalPagingApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatHistoryRemoteMediatorTest {
    private lateinit var db: MotdDatabase
    private lateinit var processor: EventProcessor
    private var networkId = 0L
    private var bufferId = 0L

    @Before fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java).allowMainThreadQueries().build()
        processor = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop)
        networkId = db.networkDao().insert(
            NetworkEntity(name = "libera", role = NetworkRole.DIRECT, host = "h", port = 6697, nick = "me", username = "me", realname = "Me"),
        )
        processor.onRegistered(networkId, "me", emptyMap())
        db.bufferDao().insert(BufferEntity(networkId = networkId, name = "#chan", displayName = "#chan", type = BufferType.CHANNEL))
        bufferId = db.bufferDao().byName(networkId, "#chan")!!.id
    }

    @After fun tearDown() { db.close() }

    private fun chatMsg(msgid: String, time: Long) = IrcEvent.ChatMessage(
        ctx = MessageContext(msgid, time, null, "b", null),
        kind = IrcEvent.ChatKind.PRIVMSG, source = Prefix("alice"), target = "#chan", text = msgid,
        isSelf = false, replyToMsgid = null,
    )

    private fun messages(
        events: List<IrcEvent>,
        endOfHistory: Boolean = false,
    ): ChatHistoryResponse.Messages {
        val references = events.mapNotNull { event ->
            val ctx = when (event) {
                is IrcEvent.ChatMessage -> event.ctx
                is IrcEvent.TagMessage -> event.ctx
                else -> null
            } ?: return@mapNotNull null
            ChatHistoryReference(ctx.msgid, ctx.serverTime)
        }
        return ChatHistoryResponse.Messages(
            events,
            oldest = references.firstOrNull(),
            newest = references.lastOrNull(),
            endOfHistory = endOfHistory,
            primaryMessageCount = references.size,
        )
    }

    /** Scripts LATEST + BEFORE responses and records the subcommands issued. */
    private inner class FakeHistory(
        val hasChatHistory: Boolean = true,
        val offline: Boolean = false,
        val latest: List<IrcEvent> = emptyList(),
        val before: ArrayDeque<List<IrcEvent>> = ArrayDeque(),
        val latestEndOfHistory: Boolean = false,
        val beforeEndOfHistory: Boolean = false,
        val failure: Throwable? = null,
        val failureFor: ((ChatHistoryRequest) -> Throwable?)? = null,
        val responseFor: ((ChatHistoryRequest) -> ChatHistoryResponse.Messages?)? = null,
        val referenceTypes: Set<HistoryReferenceType> = setOf(
            HistoryReferenceType.TIMESTAMP,
            HistoryReferenceType.MSGID,
        ),
    ) : ChatHistoryRemoteMediator.HistorySource {
        val calls = mutableListOf<ChatHistoryRequest.Subcommand>()
        val requests = mutableListOf<ChatHistoryRequest>()
        override suspend fun availability(): HistoryAvailability = if (offline) {
            HistoryAvailability.NegotiatingOrOffline
        } else if (hasChatHistory) {
            HistoryAvailability.Ready(
                referenceTypes,
                100,
            )
        } else {
            HistoryAvailability.Unsupported
        }
        override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
            calls += req.subcommand
            requests += req
            (failureFor?.invoke(req) ?: failure)?.let { throw it }
            responseFor?.invoke(req)?.let { return it }
            return when (req.subcommand) {
                ChatHistoryRequest.Subcommand.LATEST -> messages(latest, latestEndOfHistory)
                ChatHistoryRequest.Subcommand.BEFORE -> messages(
                    before.removeFirstOrNull() ?: emptyList(),
                    beforeEndOfHistory,
                )
                else -> messages(emptyList())
            }
        }
    }

    private fun mediator(
        history: FakeHistory,
        pageSize: Int = 50,
        focus: HistoryWindowFocus = HistoryWindowFocus.Recent,
    ) =
        ChatHistoryRemoteMediator(
            bufferId,
            db.bufferDao(),
            db.messageDao(),
            processor,
            history,
            pageSize,
            db.historyCursorDao(),
            db.historyGapDao(),
            focus,
        )

    private fun emptyState() = PagingState<Int, MessageEntity>(
        pages = emptyList(),
        anchorPosition = null,
        config = PagingConfig(pageSize = 50, prefetchDistance = 25, enablePlaceholders = false),
        leadingPlaceholderCount = 0,
    )

    private suspend fun load(m: ChatHistoryRemoteMediator, type: LoadType) = m.load(type, emptyState())

    private suspend fun rowCount(): Int = db.messageDao().pagingSource(bufferId).load(
        androidx.paging.PagingSource.LoadParams.Refresh(null, 100, false),
    ).let { (it as androidx.paging.PagingSource.LoadResult.Page).data.size }

    @Test
    fun appendOnEmptyBuffer_seedsLatest() = runTest {
        // Fresh/cleared store: Paging drives APPEND past the empty end boundary. The mediator must
        // LATEST-seed instead of returning end-of-pagination with nothing fetched (the reported bug).
        val history = FakeHistory(latest = listOf(chatMsg("a", 100), chatMsg("b", 200)))
        val result = load(mediator(history), LoadType.APPEND)

        assertTrue(result is RemoteMediator.MediatorResult.Success)
        assertFalse((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertEquals(listOf(ChatHistoryRequest.Subcommand.LATEST), history.calls)
        assertEquals(2, rowCount())
    }

    @Test
    fun oversizedTerminalLatestKeepsTheDiscardedOlderIntervalPageable() = runTest {
        val history = FakeHistory(
            latest = (1..1_000).map { chatMsg("m$it", it.toLong()) },
            before = ArrayDeque(listOf(listOf(chatMsg("m950", 950)))),
            latestEndOfHistory = true,
        )

        val first = load(mediator(history, pageSize = 50), LoadType.APPEND)
        val reopened = load(mediator(history, pageSize = 50), LoadType.APPEND)

        assertFalse((first as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertFalse((reopened as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertEquals(51, rowCount())
        assertFalse(db.bufferDao().observeById(bufferId)!!.historyComplete)
        assertFalse(db.historyCursorDao().byRoom(bufferId)!!.historyComplete)
        assertEquals(
            listOf(ChatHistoryRequest.Subcommand.LATEST, ChatHistoryRequest.Subcommand.BEFORE),
            history.calls,
        )
        assertEquals("msgid=m951", history.requests.last().bound1)
    }

    @Test
    fun malformedTimestampOnlyOverdeliveryIsRejectedInsteadOfInventingEpochBoundary() {
        val malformed = listOf(1L, 2L).map { time ->
            chatMsg("m$time", time).let { event ->
                event.copy(
                    ctx = event.ctx.copy(
                        msgid = null,
                        serverTime = 0,
                        serverTimeSource = ServerTimeSource.UNKNOWN,
                    ),
                )
            }
        }
        val page = messages(malformed, endOfHistory = true)
        val request = ChatHistoryRequest(
            ChatHistoryRequest.Subcommand.LATEST,
            "#chan",
            limit = 1,
        )

        val failure = runCatching { page.boundedToRequest(request) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("usable retained boundary"))
    }

    @Test
    fun validTaggedTimestampOnlyOverdeliveryRetainsAdvertisedTime() {
        val events = listOf(100L, 200L).map { time ->
            chatMsg("m$time", time).let { event -> event.copy(ctx = event.ctx.copy(msgid = null)) }
        }
        val request = ChatHistoryRequest(
            ChatHistoryRequest.Subcommand.LATEST,
            "#chan",
            limit = 1,
        )

        val bounded = messages(events, endOfHistory = true).boundedToRequest(request)

        assertNull(bounded.oldest?.msgid)
        assertEquals(200L, bounded.oldest?.serverTime)
        assertFalse(bounded.endOfHistory)
    }

    @Test
    fun rawHistoryContextSurvivesPrimaryOverdeliveryBounding() {
        val rawContext = IrcEvent.Raw(
            IrcMessage(
                tags = mapOf(
                    "draft/chathistory-context" to "",
                    "time" to "1970-01-01T00:00:00.150Z",
                ),
                command = "TAGMSG",
                params = listOf("#chan"),
            ),
        )
        val page = messages(
            listOf(chatMsg("m1", 100), rawContext, chatMsg("m2", 200)),
            endOfHistory = true,
        ).copy(primaryMessageCount = 2)

        val bounded = page.boundedToRequest(
            ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#chan", limit = 1),
        )

        assertEquals(1, bounded.primaryMessageCount)
        assertTrue(rawContext in bounded.events)
        assertEquals("m2", bounded.newest?.msgid)
    }

    @Test
    fun typedNetworkBatchSuppliesItsOpeningBoundaryDuringOverdeliveryBounding() {
        val networkBatch = IrcEvent.NetworkBatch(
            kind = IrcEvent.NetworkBatchKind.NETSPLIT,
            serverA = "a.example",
            serverB = "b.example",
            events = emptyList(),
            target = "#chan",
            historyMetadata = HistoryEventMetadata(
                isContext = false,
                msgid = "split-event",
                serverTime = 150,
            ),
        )
        val page = messages(
            listOf(chatMsg("m1", 100), networkBatch, chatMsg("m2", 200)),
            endOfHistory = true,
        ).copy(primaryMessageCount = 3)

        val bounded = page.boundedToRequest(
            ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#chan", limit = 2),
        )

        assertEquals(2, bounded.primaryMessageCount)
        assertEquals(listOf(networkBatch, chatMsg("m2", 200)), bounded.events)
        assertEquals("split-event", bounded.oldest?.msgid)
        assertEquals(150L, bounded.oldest?.serverTime)
    }

    @Test
    fun appendOnEmptyBuffer_noServerHistory_endsPagination() = runTest {
        // Empty local AND empty server: LATEST returns nothing → end of pagination, no rows.
        val history = FakeHistory(latest = emptyList())
        val result = load(mediator(history), LoadType.APPEND)

        assertTrue(result is RemoteMediator.MediatorResult.Success)
        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertEquals(0, rowCount())
        assertTrue(db.bufferDao().observeById(bufferId)!!.historyComplete)
        assertTrue(db.historyCursorDao().byRoom(bufferId)!!.historyComplete)
    }

    @Test
    fun refreshOnEmptyBuffer_seedsLatest() = runTest {
        // Explicit REFRESH (e.g. swipe-to-refresh) on an empty buffer also LATEST-seeds.
        val history = FakeHistory(latest = listOf(chatMsg("a", 100)))
        load(mediator(history), LoadType.REFRESH)

        assertEquals(listOf(ChatHistoryRequest.Subcommand.LATEST), history.calls)
        assertEquals(1, rowCount())
    }

    @Test
    fun appendWithLocalHistory_pagesBefore() = runTest {
        // Non-empty buffer: APPEND pages OLDER via BEFORE, never LATEST.
        processor.process(networkId, chatMsg("seed", 500))
        val history = FakeHistory(before = ArrayDeque(listOf(listOf(chatMsg("older", 100)))))
        val result = load(mediator(history), LoadType.APPEND)

        assertTrue(result is RemoteMediator.MediatorResult.Success)
        assertEquals(listOf(ChatHistoryRequest.Subcommand.BEFORE), history.calls)
        assertEquals("msgid=seed", history.requests.single().bound1)
        assertEquals(2, rowCount())
    }

    @Test
    fun recentAppendWithLocalHistory_pagesExactlyOneBeforePagePerLoad() = runTest {
        // Scroll-driven Recent paging: each APPEND fetches exactly one BEFORE page and the next
        // APPEND advances the cursor to the previous page's oldest boundary.
        processor.process(networkId, chatMsg("seed", 500))
        val history = FakeHistory(
            before = ArrayDeque(
                listOf(
                    listOf(chatMsg("older-1", 100)),
                    listOf(chatMsg("older-2", 50)),
                ),
            ),
        )
        val mediator = mediator(history)

        val first = load(mediator, LoadType.APPEND)
        assertFalse((first as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertEquals(listOf(ChatHistoryRequest.Subcommand.BEFORE), history.calls)
        assertEquals("msgid=seed", history.requests.single().bound1)
        assertEquals(2, rowCount())

        val second = load(mediator, LoadType.APPEND)
        assertFalse((second as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertEquals(
            listOf(ChatHistoryRequest.Subcommand.BEFORE, ChatHistoryRequest.Subcommand.BEFORE),
            history.calls,
        )
        assertEquals("msgid=older-1", history.requests.last().bound1)
        assertEquals(3, rowCount())
    }

    @Test
    fun recentRefreshWithLocalRows_isNoop() = runTest {
        // Local rows already paint; REFRESH must not fetch and must leave APPEND to drive older.
        processor.process(networkId, chatMsg("seed", 500))
        val history = FakeHistory()

        val result = load(mediator(history), LoadType.REFRESH)

        assertFalse((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertTrue(history.calls.isEmpty())
        assertEquals(1, rowCount())
    }

    @Test
    fun recentPrependEndsPagination() = runTest {
        // Under Recent focus live events supply newer messages, so PREPEND ends immediately.
        processor.process(networkId, chatMsg("seed", 500))
        val history = FakeHistory()

        val result = load(mediator(history), LoadType.PREPEND)

        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertTrue(history.calls.isEmpty())
    }

    @Test
    fun unreadFocusedPrependPagesAfterAndShrinksOnlyTheNewerGap() = runTest {
        processor.process(networkId, chatMsg("old", 100))
        processor.process(networkId, chatMsg("recent", 900))
        db.historyGapDao().insert(
            HistoryGapEntity(
                roomId = bufferId,
                olderMsgid = "old",
                olderServerTime = 100,
                newerMsgid = "recent",
                newerServerTime = 900,
            ),
        )
        val history = FakeHistory(
            responseFor = { request ->
                if (request.subcommand == ChatHistoryRequest.Subcommand.AFTER) {
                    messages(listOf(chatMsg("next-1", 101), chatMsg("next-50", 150)))
                } else {
                    null
                }
            },
        )

        val result = load(
            mediator(history, focus = HistoryWindowFocus.Around(100)),
            LoadType.PREPEND,
        )

        assertFalse((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertEquals(listOf(ChatHistoryRequest.Subcommand.AFTER), history.calls)
        assertEquals("msgid=old", history.requests.single().bound1)
        val gap = db.historyGapDao().forRoom(bufferId).single()
        assertEquals(150L, gap.olderServerTime)
        assertEquals(900L, gap.newerServerTime)
    }

    @Test
    fun unreadFocusedTerminalAfterStopsAndRetainsAnIncompleteExhaustedGap() = runTest {
        processor.process(networkId, chatMsg("old", 100))
        processor.process(networkId, chatMsg("recent", 900))
        db.historyGapDao().insert(
            HistoryGapEntity(0, bufferId, "old", 100, "recent", 900),
        )
        val history = FakeHistory(
            responseFor = { request ->
                messages(emptyList(), endOfHistory = true)
                    .takeIf { request.subcommand == ChatHistoryRequest.Subcommand.AFTER }
            },
        )

        val result = load(
            mediator(history, focus = HistoryWindowFocus.Around(100)),
            LoadType.PREPEND,
        )

        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertFalse(db.historyGapDao().forRoom(bufferId).single().recoverable)

        val reopened = load(
            mediator(history, focus = HistoryWindowFocus.Around(100)),
            LoadType.PREPEND,
        )
        assertTrue((reopened as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertEquals(1, history.requests.size)
    }

    @Test
    fun unreadFocusedNonAdvancingAfterStopsWithoutLooping() = runTest {
        processor.process(networkId, chatMsg("old", 100))
        processor.process(networkId, chatMsg("recent", 900))
        db.historyGapDao().insert(
            HistoryGapEntity(0, bufferId, "old", 100, "recent", 900),
        )
        val history = FakeHistory(
            responseFor = { request ->
                messages(listOf(chatMsg("old", 100)))
                    .takeIf { request.subcommand == ChatHistoryRequest.Subcommand.AFTER }
            },
        )

        val result = load(
            mediator(history, focus = HistoryWindowFocus.Around(100)),
            LoadType.PREPEND,
        )

        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertEquals(1, history.requests.size)
        assertEquals("old", db.historyGapDao().forRoom(bufferId).single().olderMsgid)
    }

    @Test
    fun recentAppendPagesBeforeTheRecentIslandInsteadOfTheGlobalOldestCursor() = runTest {
        processor.process(networkId, chatMsg("old", 100))
        processor.process(networkId, chatMsg("recent-boundary", 851))
        db.historyGapDao().insert(
            HistoryGapEntity(
                roomId = bufferId,
                olderMsgid = "old",
                olderServerTime = 100,
                newerMsgid = "recent-boundary",
                newerServerTime = 851,
            ),
        )
        val history = FakeHistory(
            before = ArrayDeque(
                listOf(listOf(chatMsg("older-page", 801), chatMsg("newer-page", 850))),
            ),
        )

        val result = load(mediator(history), LoadType.APPEND)

        assertFalse((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertEquals("msgid=recent-boundary", history.requests.single().bound1)
        val gap = db.historyGapDao().forRoom(bufferId).single()
        assertEquals(100L, gap.olderServerTime)
        assertEquals(801L, gap.newerServerTime)
    }

    @Test
    fun appendWithLocalHistory_emptyBefore_setsHistoryComplete() = runTest {
        processor.process(networkId, chatMsg("seed", 500))
        val history = FakeHistory(before = ArrayDeque())
        val result = load(mediator(history), LoadType.APPEND)

        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertTrue(db.bufferDao().observeById(bufferId)!!.historyComplete)
    }

    @Test
    fun appendWithExplicitEnd_marksHistoryCompleteAfterIngestingPage() = runTest {
        processor.process(networkId, chatMsg("seed", 500))
        val history = FakeHistory(
            before = ArrayDeque(listOf(listOf(chatMsg("oldest", 100)))),
            beforeEndOfHistory = true,
        )

        val result = load(mediator(history), LoadType.APPEND)

        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertTrue(db.bufferDao().observeById(bufferId)!!.historyComplete)
        assertEquals(2, rowCount())
    }

    @Test
    fun shortBeforePageDoesNotComplete_andReturnedMsgidBecomesNextCursor() = runTest {
        processor.process(networkId, chatMsg("seed", 500))
        val history = FakeHistory(
            before = ArrayDeque(
                listOf(
                    listOf(chatMsg("older", 100)),
                    emptyList(),
                ),
            ),
        )

        val first = load(mediator(history), LoadType.APPEND)
        val second = load(mediator(history), LoadType.APPEND)

        assertFalse((first as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertTrue((second as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertEquals("msgid=older", history.requests[1].bound1)
    }

    @Test
    fun msgidRejectionFallsBackToAdvertisedTimestampAndPersistsFallbackRequest() = runTest {
        processor.process(networkId, chatMsg("OpaqueCase", 500))
        val history = FakeHistory(
            before = ArrayDeque(listOf(listOf(chatMsg("older", 100)))),
            failureFor = { request ->
                if (request.bound1 == "msgid=OpaqueCase") {
                    IrcCommandException("CHATHISTORY", "INVALID_MSGREFTYPE", "try timestamp")
                } else {
                    null
                }
            },
        )

        val result = load(mediator(history), LoadType.APPEND)

        assertFalse((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertEquals(
            listOf("msgid=OpaqueCase", "timestamp=1970-01-01T00:00:00.500Z"),
            history.requests.map { it.bound1 },
        )
        assertNull(db.historyCursorDao().byRoom(bufferId)?.oldestMsgid)
        assertEquals(100L, db.historyCursorDao().byRoom(bufferId)?.oldestServerTime)
    }

    @Test
    fun timestampFallbackMarksOnlyTheExactSelectedEqualTimeGapExhausted() = runTest {
        val firstId = db.historyGapDao().insert(
            HistoryGapEntity(0, bufferId, "a", 100, "b", 500),
        )
        val secondId = db.historyGapDao().insert(
            HistoryGapEntity(0, bufferId, "c", 200, "d", 500),
        )
        val history = FakeHistory(
            failureFor = { request ->
                IrcCommandException("CHATHISTORY", "INVALID_MSGREFTYPE", "try timestamp")
                    .takeIf { request.bound1?.startsWith("msgid=") == true }
            },
            responseFor = { request ->
                messages(emptyList(), endOfHistory = true)
                    .takeIf { request.bound1?.startsWith("timestamp=") == true }
            },
        )

        val result = load(mediator(history), LoadType.APPEND)

        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        val selectedMsgid = history.requests.first().bound1?.removePrefix("msgid=")
        val gaps = db.historyGapDao().forRoom(bufferId).associateBy { it.id }
        val selectedId = if (selectedMsgid == "b") firstId else secondId
        val untouchedId = if (selectedId == firstId) secondId else firstId
        assertFalse(checkNotNull(gaps[selectedId]).recoverable)
        assertTrue(checkNotNull(gaps[untouchedId]).recoverable)
        assertEquals("timestamp=1970-01-01T00:00:00.500Z", history.requests.last().bound1)
    }

    @Test
    fun nonReferenceFailuresDoNotFallBackFromMsgidToTimestamp() = runTest {
        processor.process(networkId, chatMsg("OpaqueCase", 500))
        listOf(
            IrcTimeoutException("before"),
            IrcDisconnectedException("CHATHISTORY", "lost connection"),
            IOException("read failed"),
            IrcCommandException("CHATHISTORY", "MESSAGE_ERROR", "request rejected"),
        ).forEach { failure ->
            val history = FakeHistory(failure = failure)
            val result = load(mediator(history), LoadType.APPEND)

            assertTrue(result is RemoteMediator.MediatorResult.Error)
            assertEquals(listOf("msgid=OpaqueCase"), history.requests.map { it.bound1 })
        }
        assertFalse(db.bufferDao().observeById(bufferId)!!.historyComplete)
    }

    @Test
    fun invalidMsgidDoesNotUseUnadvertisedTimestampFallback() = runTest {
        processor.process(networkId, chatMsg("OpaqueCase", 500))
        val history = FakeHistory(
            failure = IrcCommandException(
                "CHATHISTORY",
                "INVALID_MSGREFTYPE",
                "timestamp was not advertised",
            ),
            referenceTypes = setOf(HistoryReferenceType.MSGID),
        )

        val result = load(mediator(history), LoadType.APPEND)

        assertTrue(result is RemoteMediator.MediatorResult.Error)
        assertEquals(listOf("msgid=OpaqueCase"), history.requests.map { it.bound1 })
    }

    @Test
    fun timestampOnlyAdvertisementNeverSendsMsgid() = runTest {
        processor.process(networkId, chatMsg("OpaqueCase", 500))
        val history = FakeHistory(
            before = ArrayDeque(listOf(listOf(chatMsg("older", 100)))),
            referenceTypes = setOf(HistoryReferenceType.TIMESTAMP),
        )

        val result = load(mediator(history), LoadType.APPEND)

        assertTrue(result is RemoteMediator.MediatorResult.Success)
        assertEquals(
            listOf("timestamp=1970-01-01T00:00:00.500Z"),
            history.requests.map { it.bound1 },
        )
    }

    @Test
    fun saturatedTimestampOnlyLatestSeedKeepsOlderBackfillAlive() = runTest {
        // A fresh buffer's first LATEST seed on a timestamp-only wire (soju advertises
        // MSGREFTYPES=timestamp) is saturated and msgid-less. That used to make EventProcessor write
        // a zero-width `recoverable = false` gap on the page's oldest row, and this test used to pin
        // the consequence: the seed reported terminal and a reopened mediator issued NO further
        // request, so a fresh buffer could never fetch a single older page. Both halves of that
        // expectation were wrong at the source — an interval whose two edges name the same row holds
        // no messages, and `recoverable = false` means "the server proved this empty", which a
        // saturated page never proves. With the write removed the seed leaves no gap behind and the
        // next APPEND pages older from the boundary the seed established.
        val history = FakeHistory(
            latest = listOf(chatMsg("a", 100), chatMsg("b", 100)),
            before = ArrayDeque(listOf(listOf(chatMsg("older1", 50), chatMsg("older2", 60)))),
            referenceTypes = setOf(HistoryReferenceType.TIMESTAMP),
        )
        val mediator = mediator(history, pageSize = 2)

        val result = load(mediator, LoadType.APPEND)

        assertFalse((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertEquals(2, rowCount())
        assertFalse(db.bufferDao().observeById(bufferId)!!.historyComplete)
        assertFalse(db.historyCursorDao().byRoom(bufferId)!!.historyComplete)
        assertTrue("a saturated seed records no durable gap", db.historyGapDao().forRoom(bufferId).isEmpty())

        val next = load(mediator, LoadType.APPEND)

        assertFalse((next as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertEquals(4, rowCount())
        assertEquals(
            listOf(null, "timestamp=1970-01-01T00:00:00.100Z"),
            history.requests.map { it.bound1 },
        )
    }

    @Test
    fun saturatedTimestampOnlyBeforeKeepsPagingFromTheRecededBoundary() = runTest {
        // Cursor-driven BEFORE, same wire regime, same removed write. Paging older past a saturated
        // msgid-less edge can still skip messages that share the edge's timestamp — the protocol
        // offers no selector for them — but that risk is per-fetch and is why
        // HistoryPageLoader.cannotSafelyPageBefore ends THIS fetch there and why history is never
        // marked complete. It is not a reason to stop the direction forever: the old behavior did not
        // recover those peers either (the gap it wrote was unrecoverable, so nothing ever fetched
        // it), it merely also discarded every older page. The mediator now continues while the
        // boundary keeps receding, and stops on the server's own terminal page.
        processor.process(networkId, chatMsg("seed", 500))
        val history = FakeHistory(
            before = ArrayDeque(
                listOf(listOf(chatMsg("a", 100), chatMsg("b", 100))),
            ),
            referenceTypes = setOf(HistoryReferenceType.TIMESTAMP),
        )
        val mediator = mediator(history, pageSize = 2)

        val result = load(mediator, LoadType.APPEND)

        assertFalse((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertEquals(3, rowCount())
        assertFalse(db.bufferDao().observeById(bufferId)!!.historyComplete)
        assertTrue("a saturated page records no durable gap", db.historyGapDao().forRoom(bufferId).isEmpty())

        // The scripted wire is exhausted, so the next BEFORE is the server's empty (terminal) page:
        // the direction ends on proof, and only then is history complete.
        val reopened = load(mediator, LoadType.APPEND)
        assertTrue((reopened as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertEquals(
            listOf("timestamp=1970-01-01T00:00:00.500Z", "timestamp=1970-01-01T00:00:00.100Z"),
            history.requests.map { it.bound1 },
        )
        assertTrue(db.bufferDao().observeById(bufferId)!!.historyComplete)
    }

    @Test
    fun freshTimestampOnlyBufferBackfillsPastTheSeedPage() = runTest {
        // The hosted-CI shape end to end, with no hand-written gap anywhere: an EMPTY buffer, a
        // saturated msgid-less LATEST seed, then a cursor-driven BEFORE ladder. Before the fix the
        // seed wrote an unrecoverable zero-width gap and this whole ladder measured exactly one
        // request; now every saturated page keeps the direction alive and only the server's empty
        // page ends it.
        val history = FakeHistory(
            latest = listOf(chatMsg("a", 100), chatMsg("b", 110)),
            before = ArrayDeque(
                listOf(
                    listOf(chatMsg("c", 80), chatMsg("d", 90)),
                    listOf(chatMsg("e", 60), chatMsg("f", 70)),
                    emptyList(),
                ),
            ),
            referenceTypes = setOf(HistoryReferenceType.TIMESTAMP),
        )
        val mediator = mediator(history, pageSize = 2)

        val ends = (1..4).map {
            (load(mediator, LoadType.APPEND) as RemoteMediator.MediatorResult.Success)
                .endOfPaginationReached
        }

        assertEquals(listOf(false, false, false, true), ends)
        assertEquals(
            listOf(
                null,
                "timestamp=1970-01-01T00:00:00.100Z",
                "timestamp=1970-01-01T00:00:00.080Z",
                "timestamp=1970-01-01T00:00:00.060Z",
            ),
            history.requests.map { it.bound1 },
        )
        assertEquals(6, rowCount())
        assertTrue(db.historyGapDao().forRoom(bufferId).isEmpty())
        assertTrue(db.bufferDao().observeById(bufferId)!!.historyComplete)
    }

    @Test
    fun unchangedBeforeBoundaryStopsTheMediatorWithoutClaimingCompletion() = runTest {
        processor.process(networkId, chatMsg("seed", 500))
        val history = FakeHistory(
            responseFor = { request ->
                if (request.subcommand == ChatHistoryRequest.Subcommand.BEFORE) {
                    messages(listOf(chatMsg("seed", 500)))
                } else {
                    null
                }
            },
        )

        val result = load(mediator(history), LoadType.APPEND)

        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertEquals(1, rowCount())
        assertFalse(db.bufferDao().observeById(bufferId)!!.historyComplete)
    }

    @Test
    fun timestampOnlySaturatedAppendKeepsPagingUntilHistoryIsExhausted() = runTest {
        // Hosted-CI wire: soju advertises MSGREFTYPES=timestamp, so every advertised boundary is a
        // bare timestamp and every SATURATED page trips the loader's cannotSafelyPageBefore guard.
        // That guard means "not safe from THIS cursor", never "no older history"; Paging treats
        // endOfPaginationReached as permanently terminal for APPEND, so reporting it would end older
        // backfill after page one. While the focused gap's edge keeps receding, APPEND must report
        // more.
        processor.process(networkId, chatMsg("marker", 10))
        processor.process(networkId, chatMsg("row212", 212))
        db.historyGapDao().insert(
            HistoryGapEntity(
                roomId = bufferId,
                olderMsgid = null,
                olderServerTime = 10,
                newerMsgid = null,
                newerServerTime = 212,
            ),
        )
        val history = FakeHistory(
            before = ArrayDeque(
                listOf(
                    listOf(chatMsg("row200", 200), chatMsg("row210", 210)),
                    listOf(chatMsg("row180", 180), chatMsg("row190", 190)),
                    listOf(chatMsg("row5", 5), chatMsg("row8", 8)),
                    emptyList(),
                ),
            ),
            referenceTypes = setOf(HistoryReferenceType.TIMESTAMP),
        )
        val mediator = mediator(history, pageSize = 2)

        val ends = (1..4).map {
            (load(mediator, LoadType.APPEND) as RemoteMediator.MediatorResult.Success)
                .endOfPaginationReached
        }

        // Three saturated timestamp-only pages keep paging; only the server's empty page ends it.
        assertEquals(listOf(false, false, false, true), ends)
        assertEquals(
            listOf(
                "timestamp=1970-01-01T00:00:00.212Z",
                "timestamp=1970-01-01T00:00:00.200Z",
                "timestamp=1970-01-01T00:00:00.180Z",
                "timestamp=1970-01-01T00:00:00.005Z",
            ),
            history.requests.map { it.bound1 },
        )
        assertEquals(8, rowCount())
        assertTrue(db.historyGapDao().forRoom(bufferId).isEmpty())
        assertTrue(db.bufferDao().observeById(bufferId)!!.historyComplete)
    }

    @Test
    fun timestampOnlyAppendThatCannotAdvanceItsBoundaryStopsInsteadOfLooping() = runTest {
        // Anti-livelock guard for the progress rule: the server echoes only the boundary row, so
        // nothing is persisted and the gap's newer edge stays at 212 (only its msgid is stripped by
        // the timestamp-only advertisement). The next BEFORE would repeat this request verbatim, so
        // APPEND must report terminal instead of having Paging hammer the wire. Terminality is
        // idempotent: a real Pager stops after the first result, and a repeated load never claims
        // "more" nor persists anything.
        processor.process(networkId, chatMsg("marker", 10))
        processor.process(networkId, chatMsg("row212", 212))
        db.historyGapDao().insert(
            HistoryGapEntity(
                roomId = bufferId,
                olderMsgid = null,
                olderServerTime = 10,
                newerMsgid = "row212",
                newerServerTime = 212,
            ),
        )
        val history = FakeHistory(
            responseFor = { request ->
                messages(listOf(chatMsg("row212", 212)))
                    .takeIf { request.subcommand == ChatHistoryRequest.Subcommand.BEFORE }
            },
            referenceTypes = setOf(HistoryReferenceType.TIMESTAMP),
        )
        val mediator = mediator(history, pageSize = 2)

        val first = load(mediator, LoadType.APPEND)
        val second = load(mediator, LoadType.APPEND)

        assertTrue((first as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertTrue((second as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertEquals(2, rowCount())
        assertFalse(db.bufferDao().observeById(bufferId)!!.historyComplete)
        val gap = db.historyGapDao().forRoom(bufferId).single()
        assertTrue(gap.recoverable)
        assertEquals(212L, gap.newerServerTime)
    }

    @Test
    fun saturatedMsgidAppendLadderIsUnchanged() = runTest {
        // The msgid wire never trips the saturation guard, so this pins that the progress rule keeps
        // the existing one-page-per-load backfill ladder byte-for-byte.
        processor.process(networkId, chatMsg("seed", 500))
        val history = FakeHistory(
            before = ArrayDeque(
                listOf(
                    listOf(chatMsg("a1", 400), chatMsg("a2", 450)),
                    listOf(chatMsg("b1", 300), chatMsg("b2", 350)),
                    emptyList(),
                ),
            ),
        )
        val mediator = mediator(history, pageSize = 2)

        val ends = (1..3).map {
            (load(mediator, LoadType.APPEND) as RemoteMediator.MediatorResult.Success)
                .endOfPaginationReached
        }

        assertEquals(listOf(false, false, true), ends)
        assertEquals(
            listOf("msgid=seed", "msgid=a1", "msgid=b1"),
            history.requests.map { it.bound1 },
        )
        assertEquals(5, rowCount())
        assertTrue(db.bufferDao().observeById(bufferId)!!.historyComplete)
    }

    @Test
    fun serverProvenEmptyGapRemainderStopsAppendEvenAfterTheBoundaryReceded() = runTest {
        // Progress must not outrank a server-proven-empty remainder: the page advanced the gap's
        // newer edge AND persisted a row, but the terminal response that never reached the older
        // boundary marks the remainder unrecoverable, so APPEND is genuinely finished.
        processor.process(networkId, chatMsg("marker", 10))
        processor.process(networkId, chatMsg("row212", 212))
        db.historyGapDao().insert(
            HistoryGapEntity(
                roomId = bufferId,
                olderMsgid = "marker",
                olderServerTime = 10,
                newerMsgid = "row212",
                newerServerTime = 212,
            ),
        )
        val history = FakeHistory(
            before = ArrayDeque(listOf(listOf(chatMsg("row200", 200)))),
            beforeEndOfHistory = true,
        )

        val result = load(mediator(history), LoadType.APPEND)

        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertEquals(3, rowCount())
        val gap = db.historyGapDao().forRoom(bufferId).single()
        assertFalse(gap.recoverable)
        assertEquals(200L, gap.newerServerTime)
    }

    @Test
    fun timestampOnlySaturatedPrependKeepsClosingTheReconnectGap() = runTest {
        // Reconnect shape under a focused (Around) window: a recoverable catch-up gap sits between
        // the last pre-disconnect row and the newest island. cannotSafelyPageAfter carries the same
        // timestamp-saturation clause as its BEFORE twin, so on a timestamp-only wire every full
        // catch-up page would permanently terminate PREPEND — leaving the gap open, and with it the
        // Around window's upper boundary, so nothing newer than the gap can ever be presented.
        processor.process(networkId, chatMsg("old", 100))
        processor.process(networkId, chatMsg("recent", 900))
        db.historyGapDao().insert(
            HistoryGapEntity(
                roomId = bufferId,
                olderMsgid = "old",
                olderServerTime = 100,
                newerMsgid = "recent",
                newerServerTime = 900,
            ),
        )
        val pages = ArrayDeque(
            listOf(
                listOf(chatMsg("n1", 110), chatMsg("n2", 120)),
                listOf(chatMsg("n3", 130), chatMsg("n4", 140)),
                listOf(chatMsg("n5", 150), chatMsg("n6", 910)),
            ),
        )
        val history = FakeHistory(
            responseFor = { request ->
                if (request.subcommand == ChatHistoryRequest.Subcommand.AFTER) {
                    messages(pages.removeFirstOrNull() ?: emptyList())
                } else {
                    null
                }
            },
            referenceTypes = setOf(HistoryReferenceType.TIMESTAMP),
        )
        val mediator = mediator(history, pageSize = 2, focus = HistoryWindowFocus.Around(100))

        val ends = (1..4).map {
            (load(mediator, LoadType.PREPEND) as RemoteMediator.MediatorResult.Success)
                .endOfPaginationReached
        }

        // The two saturated AFTER pages keep the newer direction alive; the third crosses the gap's
        // newer edge and closes it, which is the one honest reason to end PREPEND. The fourth load
        // finds no gap and never touches the wire.
        assertEquals(listOf(false, false, true, true), ends)
        assertEquals(3, history.requests.size)
        assertTrue(db.historyGapDao().forRoom(bufferId).isEmpty())
        assertEquals(8, rowCount())
    }

    @Test
    fun positivePrimaryCountWithoutAdvertisedBoundaryIsAnIncompleteError() = runTest {
        processor.process(networkId, chatMsg("seed", 500))
        val malformed = ChatHistoryResponse.Messages(
            events = listOf(chatMsg("unbounded", 100)),
            oldest = null,
            newest = null,
            endOfHistory = false,
            primaryMessageCount = 1,
        )
        val history = FakeHistory(responseFor = { malformed })

        val result = load(mediator(history), LoadType.APPEND)

        assertTrue(result is RemoteMediator.MediatorResult.Error)
        assertEquals(listOf(ChatHistoryRequest.Subcommand.BEFORE), history.calls)
        assertEquals(1, rowCount())
        assertFalse(db.bufferDao().observeById(bufferId)!!.historyComplete)
    }

    @Test
    fun nonemptyContextOnlyLatestCompletesFromZeroPrimaryCount() = runTest {
        val contextOnly = ChatHistoryResponse.Messages(
            events = listOf(chatMsg("context", 100)),
            oldest = null,
            newest = null,
            endOfHistory = false,
            primaryMessageCount = 0,
        )
        val history = FakeHistory(responseFor = { contextOnly })

        val result = load(mediator(history), LoadType.APPEND)

        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertEquals(1, rowCount())
        assertTrue(db.bufferDao().observeById(bufferId)!!.historyComplete)
    }

    @Test
    fun latestExplicitEndPersistsCompletionWithNonEmptyPage() = runTest {
        val history = FakeHistory(
            latest = listOf(chatMsg("only", 100)),
            latestEndOfHistory = true,
        )

        val result = load(mediator(history), LoadType.APPEND)

        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertTrue(db.bufferDao().observeById(bufferId)!!.historyComplete)
        assertEquals("only", db.historyCursorDao().byRoom(bufferId)?.oldestMsgid)
    }

    @Test
    fun cancellationIsRethrownInsteadOfBecomingMediatorError() = runTest {
        val cancellation = CancellationException("cancel history")
        val history = FakeHistory(failure = cancellation)

        var observed: Throwable? = null
        try {
            load(mediator(history), LoadType.APPEND)
        } catch (error: Throwable) {
            observed = error
        }

        assertTrue(observed === cancellation)
        assertFalse(db.bufferDao().observeById(bufferId)!!.historyComplete)
    }

    @Test
    fun serverBufferNeverIssuesHistoryRequests() = runTest {
        // The mediator is attached unconditionally, but a SERVER console has no CHATHISTORY target:
        // every LoadType must end pagination locally without touching the wire.
        db.bufferDao().insert(
            BufferEntity(networkId = networkId, name = "server", displayName = "server", type = BufferType.SERVER),
        )
        val serverBufferId = db.bufferDao().byName(networkId, "server")!!.id
        val history = FakeHistory(latest = listOf(chatMsg("a", 100)))
        val mediator = ChatHistoryRemoteMediator(
            serverBufferId,
            db.bufferDao(),
            db.messageDao(),
            processor,
            history,
            50,
            db.historyCursorDao(),
            db.historyGapDao(),
            HistoryWindowFocus.Recent,
        )

        listOf(LoadType.REFRESH, LoadType.PREPEND, LoadType.APPEND).forEach { type ->
            val result = load(mediator, type)
            assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        }

        assertTrue(history.calls.isEmpty())
    }

    @Test
    fun noCap_paginatesLocalOnly() = runTest {
        val history = FakeHistory(hasChatHistory = false, latest = listOf(chatMsg("a", 100)))
        val result = load(mediator(history), LoadType.APPEND)

        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertTrue(history.calls.isEmpty())
        assertEquals(0, rowCount())
    }

    @Test
    fun offlineHistoryIsRetryableAndDoesNotMarkCompletion() = runTest {
        val history = FakeHistory(offline = true)

        val result = load(mediator(history), LoadType.APPEND)

        assertTrue(result is RemoteMediator.MediatorResult.Error)
        assertTrue(history.calls.isEmpty())
        assertFalse(db.bufferDao().observeById(bufferId)!!.historyComplete)
    }
}
