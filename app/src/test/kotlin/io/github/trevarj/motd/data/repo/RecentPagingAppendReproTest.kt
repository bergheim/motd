package io.github.trevarj.motd.data.repo

import android.content.Context
import androidx.paging.AsyncPagingDataDiffer
import androidx.paging.ExperimentalPagingApi
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.sync.ChatHistoryRemoteMediator
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.data.sync.MessageNotifier
import io.github.trevarj.motd.data.sync.TypingTrackerImpl
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.irc.client.ChatHistoryReference
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.HistoryReferenceType
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.Prefix
import androidx.paging.cachedIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Models the RequiredHeadlessE2eTest.unreadHistoryEntersAtMarkerAndRemainsCanonical open step: a
 * bounded 49-row reconnect-catch-up island (rows 212..260) fronted by a recoverable history gap down
 * to the read marker, deep older server history still fetchable. Drives the real Pager +
 * RemoteMediator + HistoryPageLoader + EventProcessor through MessageRepositoryImpl.messages(Recent)
 * via AsyncPagingDataDiffer (the same accessor path Compose's LazyPagingItems uses).
 *
 * Pins how many older (BEFORE) pages one open fetches under the possible viewport/wire regimes:
 *
 *  - entryAnchored (matches the real app: the entry row keeps a fixed index ~48 from the newest end
 *    of the reversed list): doInitialLoad auto-fires APPEND with NO hint whenever the bounded window
 *    fits inside initialLoadSize (pageSize*3 = 150), because the local source returns nextKey ==
 *    null. Each persisted page recedes the Recent lowerBoundary -> pagingContextFlow emits -> a new
 *    Pager generation repeats the auto-APPEND until the window (199) exceeds initialLoadSize. A
 *    bounded, deterministic THREE-page backfill: 49 -> 99 -> 149 -> 199, then stop.
 *  - hintFree: identical to entryAnchored (the backfill is hint-free by construction).
 *  - timestampOnly (the hosted-CI wire: soju advertises MSGREFTYPES=timestamp, so msgid boundary
 *    references are stripped): identical to entryAnchored. A saturated timestamp-only fill page
 *    must NOT poison the gap — recoverable=false is reserved for server-proven-empty intervals —
 *    so the backfill converges with the msgid wire. Regression pin for the hosted-CI failure where
 *    the catch-up gap went unrecoverable and every APPEND ended with zero pages fetched.
 *  - oldestPinned (viewport dragged to the oldest loaded row each generation): the hint keeps the
 *    append boundary within prefetchDistance, so paging continues past 199 and drains the whole
 *    scripted backlog (5 BEFORE calls, 250 rows) — adversarial-viewport characterization.
 *
 * The E2E asserts the same mechanics as a 149..199 range: a real device window also holds
 * non-fixture state rows newer than the fixture backlog (the state event replayed inside the
 * newest catch-up page plus the app's own reconnect state rows), which can absorb one 50-row page
 * of the initialLoadSize budget. This fixture is exact (zero non-fixture rows in the window), so
 * the page count pins at exactly 3.
 */
@OptIn(ExperimentalPagingApi::class, ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RecentPagingAppendReproTest {
    private lateinit var db: MotdDatabase
    private lateinit var processor: EventProcessor
    private var networkId = 0L
    private var bufferId = 0L

    @Before fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        processor = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop)
        networkId = db.networkDao().insert(
            NetworkEntity(
                name = "libera", role = NetworkRole.DIRECT, host = "h", port = 6697,
                nick = "me", username = "me", realname = "Me",
            ),
        )
        processor.onRegistered(networkId, "me", emptyMap())
        db.bufferDao().insert(
            BufferEntity(networkId = networkId, name = "#chan", displayName = "#chan", type = BufferType.CHANNEL),
        )
        bufferId = db.bufferDao().byName(networkId, "#chan")!!.id
    }

    @After fun tearDown() { db.close() }

    private fun chatMsg(msgid: String, time: Long) = IrcEvent.ChatMessage(
        ctx = MessageContext(msgid, time, null, "b", null),
        kind = IrcEvent.ChatKind.PRIVMSG, source = Prefix("alice"), target = "#chan", text = msgid,
        isSelf = false, replyToMsgid = null,
    )

    private fun messages(events: List<IrcEvent>, endOfHistory: Boolean = false): ChatHistoryResponse.Messages {
        val refs = events.mapNotNull { (it as? IrcEvent.ChatMessage)?.ctx }
            .map { ChatHistoryReference(it.msgid, it.serverTime) }
        return ChatHistoryResponse.Messages(
            events, oldest = refs.firstOrNull(), newest = refs.lastOrNull(),
            endOfHistory = endOfHistory, primaryMessageCount = refs.size,
        )
    }

    /** Scripts BEFORE responses keyed by request order; records every subcommand seen. */
    private inner class FakeHistory(
        private val beforePages: ArrayDeque<List<IrcEvent>>,
        private val referenceTypes: Set<HistoryReferenceType> =
            setOf(HistoryReferenceType.TIMESTAMP, HistoryReferenceType.MSGID),
    ) : ChatHistoryRemoteMediator.HistorySource {
        val calls = mutableListOf<ChatHistoryRequest.Subcommand>()
        override suspend fun availability() = HistoryAvailability.Ready(referenceTypes, 100)
        override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
            calls += req.subcommand
            return when (req.subcommand) {
                ChatHistoryRequest.Subcommand.BEFORE -> messages(beforePages.removeFirstOrNull() ?: emptyList())
                else -> messages(emptyList())
            }
        }
    }

    private fun differ() = AsyncPagingDataDiffer(
        diffCallback = object : DiffUtil.ItemCallback<MessageEntity>() {
            override fun areItemsTheSame(a: MessageEntity, b: MessageEntity) = a.id == b.id
            override fun areContentsTheSame(a: MessageEntity, b: MessageEntity) = a == b
        },
        updateCallback = object : ListUpdateCallback {
            override fun onInserted(position: Int, count: Int) {}
            override fun onRemoved(position: Int, count: Int) {}
            override fun onMoved(fromPosition: Int, toPosition: Int) {}
            override fun onChanged(position: Int, count: Int, payload: Any?) {}
        },
        mainDispatcher = Dispatchers.Unconfined,
        workerDispatcher = Dispatchers.Unconfined,
    )

    /** Fixture state + one open; [hint] re-applies the modeled viewport after every settle round. */
    private suspend fun TestScope.runOpenScenario(
        timestampOnlyWire: Boolean = false,
        hint: (AsyncPagingDataDiffer<MessageEntity>) -> Unit,
    ): Pair<Int, Int> {
        // 49-row reconnect-catch-up island rows 212..260, plus the read marker below the gap.
        processor.process(networkId, chatMsg("marker", 10))
        (212..260).forEach { processor.process(networkId, chatMsg("row$it", it.toLong())) }
        // Recoverable gap between the read marker and the island's oldest row, exactly as bounded
        // reconnect catch-up records it. Recent focus bounds the paging window at row212. On a
        // timestamp-only wire (soju advertises MSGREFTYPES=timestamp) the boundary references carry
        // no msgids, mirroring withAdvertisedBoundaries stripping.
        db.historyGapDao().insert(
            HistoryGapEntity(
                roomId = bufferId,
                olderMsgid = if (timestampOnlyWire) null else "marker", olderServerTime = 10,
                newerMsgid = if (timestampOnlyWire) null else "row212", newerServerTime = 212,
                recoverable = true,
            ),
        )
        // Deep older interval, exactly like ergo in the E2E (rows 1..260): each BEFORE returns a
        // full 50-row page walking older.
        val history = FakeHistory(
            ArrayDeque(
                listOf(
                    (162..211).map { chatMsg("row$it", it.toLong()) },
                    (112..161).map { chatMsg("row$it", it.toLong()) },
                    (62..111).map { chatMsg("row$it", it.toLong()) },
                    (12..61).map { chatMsg("row$it", it.toLong()) },
                ),
            ),
            referenceTypes = if (timestampOnlyWire) {
                setOf(HistoryReferenceType.TIMESTAMP)
            } else {
                setOf(HistoryReferenceType.TIMESTAMP, HistoryReferenceType.MSGID)
            },
        )
        val factory = ChatHistoryMediatorFactory { roomId, focus ->
            ChatHistoryRemoteMediator(
                roomId, db.bufferDao(), db.messageDao(), processor, history, 50,
                db.historyCursorDao(), db.historyGapDao(), focus,
            )
        }
        val repository = MessageRepositoryImpl(
            db.bufferDao(), db.networkIdentityDao(), db.messageDao(), db.reactionDao(),
            factory, db.historyGapDao(),
        )
        val differ = differ()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.messages(bufferId, MessageVisibilitySpec(), HistoryWindowFocus.Recent)
                .collectLatest { differ.submitData(it) }
        }
        // Alternate settle + viewport model until quiescent (a real list re-hints its visible rows
        // after every generation/pages update; ten rounds far exceeds the scripted backlog depth).
        repeat(10) {
            advanceUntilIdle()
            if (differ.itemCount > 0) hint(differ)
        }
        advanceUntilIdle()
        val totalRows = db.messageDao().pagingSource(bufferId).load(
            androidx.paging.PagingSource.LoadParams.Refresh(null, 500, false),
        ).let { (it as androidx.paging.PagingSource.LoadResult.Page).data.size }
        job.cancel()
        return history.calls.count { it == ChatHistoryRequest.Subcommand.BEFORE } to totalRows
    }

    @Test
    fun entryAnchoredOpenBackfillsExactlyThreePagesThenStops() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            // Real-app viewport: the unread entry row keeps a fixed index from the newest end (~48)
            // across generations; appended older pages land beyond it.
            val (beforeCalls, totalRows) = runOpenScenario { differ ->
                differ.getItem(minOf(48, differ.itemCount - 1))
            }
            println("REPRO entryAnchored: BEFORE calls = $beforeCalls, totalRows = $totalRows")
            // Hint-free doInitialLoad auto-APPEND repeats while the bounded window fits under
            // initialLoadSize (150): 49 -> 99 -> 149 -> 199, then nextKey != null stops it — the
            // bounded, deterministic per-open backfill the E2E ladder is calibrated against.
            assertEquals("BEFORE pages on one entry-anchored open", 3, beforeCalls)
            assertEquals("durable rows after one entry-anchored open", 200, totalRows)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun oldestPinnedViewportDrainsTheBacklog() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            // Adversarial viewport: dragged to the oldest loaded row after every update. The hint
            // keeps the append boundary inside prefetchDistance, so paging continues past the
            // auto-backfill limit and drains the scripted backlog (4 pages + terminal empty page).
            val (beforeCalls, totalRows) = runOpenScenario { differ ->
                differ.getItem(differ.itemCount - 1)
            }
            println("REPRO oldestPinned: BEFORE calls = $beforeCalls, totalRows = $totalRows")
            assertEquals("BEFORE pages under an oldest-pinned viewport", 5, beforeCalls)
            assertEquals("durable rows under an oldest-pinned viewport", 250, totalRows)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun timestampOnlyWireBackfillsThreePagesAndKeepsTheGapRecoverable() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            // The hosted-CI wire regime: soju 0.10.1 advertises MSGREFTYPES=timestamp, so boundary
            // references are msgid-less. A saturated timestamp-only fill page must NOT poison the
            // catch-up gap (recoverable=false is reserved for server-proven-empty intervals), so
            // the open backfills identically to the msgid wire: three deterministic pages, gap
            // still recoverable with its newer edge receded to the deepest fetched row. Regression
            // pin for the hosted-CI failure where the gap went unrecoverable and every APPEND ended
            // with zero pages fetched.
            val (beforeCalls, totalRows) = runOpenScenario(timestampOnlyWire = true) { differ ->
                differ.getItem(minOf(48, differ.itemCount - 1))
            }
            val gap = db.historyGapDao().forRoom(bufferId).single()
            println(
                "REPRO timestampOnly: BEFORE calls = $beforeCalls, totalRows = $totalRows, " +
                    "gap recoverable=${gap.recoverable} newer=${gap.newerServerTime}",
            )
            assertEquals("BEFORE pages on a timestamp-only open", 3, beforeCalls)
            assertEquals("durable rows after a timestamp-only open", 200, totalRows)
            assertEquals("gap recoverability after fill pages", true, gap.recoverable)
            assertEquals("receded gap newer edge", 62L, gap.newerServerTime)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun hintFreeOpenBackfillsExactlyThreePagesThenStops() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            // No viewport interaction at all: proves the three-page backfill is driven purely by
            // doInitialLoad's nextKey == null auto-APPEND, not by access hints.
            val (beforeCalls, totalRows) = runOpenScenario { }
            println("REPRO hintFree: BEFORE calls = $beforeCalls, totalRows = $totalRows")
            assertEquals("BEFORE pages on one hint-free open", 3, beforeCalls)
            assertEquals("durable rows after one hint-free open", 200, totalRows)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun reopenWithDeepEntryAnchorMaterializesEntryRowInInitialRefresh() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            // Reopen state: the first open already backfilled the bounded window to 199 rows
            // (the read marker sits below the gap; rows 62..260 are in-window), so the recoverable
            // catch-up gap has receded to row62. The oldest unread entry (row62) is at index 198 —
            // beyond the default newest 150-row load, i.e. the older paging boundary. Materializing
            // it by scrolling there would drive a boundary APPEND that recedes the gap and swaps the
            // Pager generation before the row can compose (the blank-timeline reopen bug). Seeding
            // the Recent Pager with entryAnchorPagingKey (the anchor shifted back by
            // initialLoadSize - pageSize, matching what ChatViewModel passes) must materialize the
            // entry row AND the newer rows below it in the FIRST refresh, with no boundary scroll:
            // Room treats a refresh key as the load's start offset, so an unshifted anchor key
            // would load the anchor plus older rows only and leave the reversed viewport below it
            // as placeholders.
            processor.process(networkId, chatMsg("marker", 10))
            (62..260).forEach { processor.process(networkId, chatMsg("row$it", it.toLong())) }
            db.historyGapDao().insert(
                HistoryGapEntity(
                    roomId = bufferId,
                    olderMsgid = "marker", olderServerTime = 10,
                    newerMsgid = "row62", newerServerTime = 62,
                    recoverable = true,
                ),
            )
            val history = FakeHistory(
                ArrayDeque(listOf((12..61).map { chatMsg("row$it", it.toLong()) })),
                referenceTypes = setOf(HistoryReferenceType.TIMESTAMP, HistoryReferenceType.MSGID),
            )
            val factory = ChatHistoryMediatorFactory { roomId, focus ->
                ChatHistoryRemoteMediator(
                    roomId, db.bufferDao(), db.messageDao(), processor, history, 50,
                    db.historyCursorDao(), db.historyGapDao(), focus,
                )
            }
            val repository = MessageRepositoryImpl(
                db.bufferDao(), db.networkIdentityDao(), db.messageDao(), db.reactionDao(),
                factory, db.historyGapDao(),
            )
            val entryIndex = 198
            val anchorKey = entryAnchorPagingKey(entryIndex)
            assertEquals("anchor key shifts back by initialLoadSize - pageSize", 98, anchorKey)

            val keyed = openAndPeekIndex(repository, initialKey = anchorKey, index = entryIndex)
            val unkeyed = openAndPeekIndex(repository, initialKey = null, index = entryIndex)

            assertEquals("entry row materialized by the keyed initial refresh", "row62", keyed.first?.msgid)
            assertEquals(
                "newer sibling below the entry materialized by the same refresh",
                "row63",
                keyed.second?.msgid,
            )
            // Without the key the same index is still an unloaded placeholder after the initial
            // refresh: pins the boundary-churn condition the initialKey removes.
            assertEquals("deep entry stays a placeholder without the key", null, unkeyed.first?.msgid)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun reopenKeySwapPresentsTheKeyedGeneration() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            // Mimics ChatViewModel's exact flow shape on reopen: the screen first collects the
            // UNKEYED Recent generation (fresh entryAnchorKey = null), then the entry computation
            // sets the anchor key, which flatMapLatest-swaps in the keyed Pager mid-collection —
            // all multicast through cachedIn like viewModel.messages. The keyed generation must
            // PRESENT (refresh completes and the entry row materializes); a swap that leaves the
            // differ's refresh stuck loading is the blank-reopen wedge.
            processor.process(networkId, chatMsg("marker", 10))
            (62..260).forEach { processor.process(networkId, chatMsg("row$it", it.toLong())) }
            db.historyGapDao().insert(
                HistoryGapEntity(
                    roomId = bufferId,
                    olderMsgid = "marker", olderServerTime = 10,
                    newerMsgid = "row62", newerServerTime = 62,
                    recoverable = true,
                ),
            )
            val history = FakeHistory(
                ArrayDeque(listOf((12..61).map { chatMsg("row$it", it.toLong()) })),
                referenceTypes = setOf(HistoryReferenceType.TIMESTAMP, HistoryReferenceType.MSGID),
            )
            val factory = ChatHistoryMediatorFactory { roomId, focus ->
                ChatHistoryRemoteMediator(
                    roomId, db.bufferDao(), db.messageDao(), processor, history, 50,
                    db.historyCursorDao(), db.historyGapDao(), focus,
                )
            }
            val repository = MessageRepositoryImpl(
                db.bufferDao(), db.networkIdentityDao(), db.messageDao(), db.reactionDao(),
                factory, db.historyGapDao(),
            )
            val keyFlow = MutableStateFlow<Int?>(null)
            // The cache scope mirrors viewModelScope (Main.immediate): an immediate dispatcher, not
            // the test's standard queue, so the multicaster runs as eagerly as production.
            val cacheScope = kotlinx.coroutines.CoroutineScope(
                UnconfinedTestDispatcher(testScheduler) + kotlinx.coroutines.SupervisorJob(),
            )
            val messages = keyFlow
                .flatMapLatest { key ->
                    repository.messages(bufferId, MessageVisibilitySpec(), HistoryWindowFocus.Recent, key)
                }
                .cachedIn(cacheScope)
            val differ = differ()
            val job = launch(UnconfinedTestDispatcher(testScheduler)) {
                messages.collectLatest { differ.submitData(it) }
            }
            advanceUntilIdle()
            // The fresh reopen frame composes the newest rows of the unkeyed generation.
            if (differ.itemCount > 0) differ.getItem(0)
            advanceUntilIdle()
            val presentedBeforeSwap = differ.itemCount
            keyFlow.value = entryAnchorPagingKey(198)
            advanceUntilIdle()
            val target = (198).takeIf { it < differ.itemCount }?.let { differ.peek(it) }
            val sibling = (197).takeIf { it < differ.itemCount }?.let { differ.peek(it) }
            println(
                "KEYSWAP before=$presentedBeforeSwap after=${differ.itemCount} " +
                    "target=${target?.msgid} sibling=${sibling?.msgid}",
            )
            assertEquals("entry row presented after the key swap", "row62", target?.msgid)
            assertEquals("newer sibling presented after the key swap", "row63", sibling?.msgid)
            job.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * Open one generation, settle the initial refresh, and peek [index] and its newer sibling
     * ([index] - 1) without an access hint. Returns target to sibling.
     */
    private suspend fun TestScope.openAndPeekIndex(
        repository: MessageRepositoryImpl,
        initialKey: Int?,
        index: Int,
    ): Pair<MessageEntity?, MessageEntity?> {
        val differ = differ()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.messages(bufferId, MessageVisibilitySpec(), HistoryWindowFocus.Recent, initialKey)
                .collectLatest { differ.submitData(it) }
        }
        advanceUntilIdle()
        // peek never registers an access, so it cannot itself hint a boundary APPEND.
        val item = index.takeIf { it < differ.itemCount }?.let { differ.peek(it) }
        val newerSibling = (index - 1).takeIf { it in 0 until differ.itemCount }?.let { differ.peek(it) }
        // Diagnostic: report the materialized run around the target so the key-shift arithmetic
        // stays verifiable against Room's actual refresh-offset semantics.
        val loaded = (0 until differ.itemCount).count { differ.peek(it) != null }
        println(
            "KEYPROBE initialKey=$initialKey itemCount=${differ.itemCount} loaded=$loaded " +
                "target=${item?.msgid} newerSibling=${newerSibling?.msgid}",
        )
        job.cancel()
        return item to newerSibling
    }
}
