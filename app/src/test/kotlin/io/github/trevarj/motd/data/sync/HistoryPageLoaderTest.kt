package io.github.trevarj.motd.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.client.ChatHistoryReference
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.HistoryReferenceType
import io.github.trevarj.motd.irc.client.IrcCommandException
import io.github.trevarj.motd.irc.client.IrcDisconnectedException
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.Prefix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Fetch-primitive coverage for [HistoryPageLoader], driving a single directional page from a
 * caller-supplied boundary against scripted responses. These mirror the boundary-handling cases the
 * [ChatHistoryRemoteMediator] previously owned inline: the msgid→timestamp fallback, the
 * non-advancing/saturated loop guards, and advertised-boundary trimming.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryPageLoaderTest {
    private lateinit var db: MotdDatabase
    private lateinit var processor: EventProcessor
    private lateinit var loader: HistoryPageLoader
    private var networkId = 0L
    private var bufferId = 0L

    @Before fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java).allowMainThreadQueries().build()
        processor = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop)
        loader = HistoryPageLoader(processor)
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

    /** Scripts BEFORE responses and records the requests issued, mirroring the mediator's fake. */
    private inner class FakeHistory(
        val before: ArrayDeque<List<IrcEvent>> = ArrayDeque(),
        val failureFor: ((ChatHistoryRequest) -> Throwable?)? = null,
        val responseFor: ((ChatHistoryRequest) -> ChatHistoryResponse.Messages?)? = null,
        val referenceTypes: Set<HistoryReferenceType> = setOf(
            HistoryReferenceType.TIMESTAMP,
            HistoryReferenceType.MSGID,
        ),
    ) : HistoryPageLoader.HistorySource {
        val requests = mutableListOf<ChatHistoryRequest>()
        override suspend fun availability(): HistoryAvailability = HistoryAvailability.Ready(referenceTypes, 100)
        override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
            requests += req
            failureFor?.invoke(req)?.let { throw it }
            responseFor?.invoke(req)?.let { return it }
            return when (req.subcommand) {
                ChatHistoryRequest.Subcommand.BEFORE -> messages(before.removeFirstOrNull() ?: emptyList())
                else -> messages(emptyList())
            }
        }
    }

    private suspend fun rowCount(): Int = db.messageDao().pagingSource(bufferId).load(
        androidx.paging.PagingSource.LoadParams.Refresh(null, 100, false),
    ).let { (it as androidx.paging.PagingSource.LoadResult.Page).data.size }

    private suspend fun loadOlder(
        history: HistoryPageLoader.HistorySource,
        boundary: ChatHistoryReference,
        pageSize: Int = 50,
    ) = loader.loadPage(
        networkId,
        bufferId,
        "#chan",
        HistoryPageLoader.Direction.OLDER,
        history,
        pageSize,
        boundary = boundary,
    )

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

        val result = loadOlder(history, ChatHistoryReference("OpaqueCase", 500))

        assertTrue(result is HistoryPageLoader.PageResult.Loaded)
        assertFalse((result as HistoryPageLoader.PageResult.Loaded).endOfDirection)
        assertEquals(
            listOf("msgid=OpaqueCase", "timestamp=1970-01-01T00:00:00.500Z"),
            history.requests.map { it.bound1 },
        )
        assertNull(db.historyCursorDao().byRoom(bufferId)?.oldestMsgid)
        assertEquals(100L, db.historyCursorDao().byRoom(bufferId)?.oldestServerTime)
    }

    @Test
    fun unchangedBeforeBoundaryStopsWithoutClaimingCompletion() = runTest {
        processor.process(networkId, chatMsg("seed", 500))
        val history = FakeHistory(
            responseFor = { request ->
                messages(listOf(chatMsg("seed", 500)))
                    .takeIf { request.subcommand == ChatHistoryRequest.Subcommand.BEFORE }
            },
        )

        val result = loadOlder(history, ChatHistoryReference("seed", 500))

        // A non-advancing cursor would refetch forever: stop this direction without marking the
        // buffer's history complete.
        assertTrue((result as HistoryPageLoader.PageResult.Loaded).endOfDirection)
        assertFalse(db.bufferDao().observeById(bufferId)!!.historyComplete)
    }

    @Test
    fun saturatedTimestampOnlyBeforeStopsInsteadOfSkippingBoundaryPeers() = runTest {
        processor.process(networkId, chatMsg("seed", 500))
        val history = FakeHistory(
            before = ArrayDeque(listOf(listOf(chatMsg("a", 100), chatMsg("b", 100)))),
            referenceTypes = setOf(HistoryReferenceType.TIMESTAMP),
        )

        val result = loadOlder(history, ChatHistoryReference("seed", 500), pageSize = 2)

        assertTrue((result as HistoryPageLoader.PageResult.Loaded).endOfDirection)
        assertEquals("timestamp=1970-01-01T00:00:00.500Z", history.requests.single().bound1)
        assertEquals(3, rowCount())
        assertFalse(db.bufferDao().observeById(bufferId)!!.historyComplete)
        assertFalse(db.historyCursorDao().byRoom(bufferId)!!.historyComplete)
    }

    @Test
    fun requestTimeoutSurfacesAsRetryableFailureNotCancellation() = runTest {
        processor.process(networkId, chatMsg("seed", 500))
        // A hung request must become a retryable transport failure. If the timeout escaped as a
        // CancellationException, the mediator would rethrow it to Paging and freeze the direction's
        // LoadState at Loading behind a stale pending request.
        val history = object : HistoryPageLoader.HistorySource {
            override suspend fun availability(): HistoryAvailability = HistoryAvailability.Ready(
                setOf(HistoryReferenceType.TIMESTAMP, HistoryReferenceType.MSGID),
                100,
            )
            override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse =
                awaitCancellation()
        }

        var observed: Throwable? = null
        try {
            loader.loadPage(
                networkId,
                bufferId,
                "#chan",
                HistoryPageLoader.Direction.OLDER,
                history,
                boundary = ChatHistoryReference("seed", 500),
            )
        } catch (error: Throwable) {
            observed = error
        }

        assertTrue(observed is IrcDisconnectedException)
        assertFalse(observed is CancellationException)
        assertFalse(db.bufferDao().observeById(bufferId)!!.historyComplete)
    }

    @Test
    fun leaderCancelledMidFlightDoesNotPoisonAnActiveFollower() = runTest {
        processor.process(networkId, chatMsg("seed", 500))
        val firstFetchStarted = CompletableDeferred<Unit>()
        val requests = mutableListOf<ChatHistoryRequest>()
        val history = object : HistoryPageLoader.HistorySource {
            override suspend fun availability(): HistoryAvailability = HistoryAvailability.Ready(
                setOf(HistoryReferenceType.TIMESTAMP, HistoryReferenceType.MSGID),
                100,
            )
            override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
                requests += req
                if (requests.size == 1) {
                    // The leader's fetch hangs until its caller (a replaced Pager generation) is
                    // cancelled mid-flight.
                    firstFetchStarted.complete(Unit)
                    awaitCancellation()
                }
                return messages(listOf(chatMsg("older", 100)))
            }
        }
        val leader = launch {
            loadOlder(history, ChatHistoryReference("seed", 500))
        }
        firstFetchStarted.await()
        // A live generation's mediator joins the same (network, room, OLDER) flight as follower.
        val follower = async {
            loadOlder(history, ChatHistoryReference("seed", 500))
        }
        runCurrent()
        leader.cancelAndJoin()

        // The follower must not adopt the leader's cancellation (that would freeze the live
        // generation's append LoadState at Loading); it retries and completes the page itself.
        val result = follower.await()
        assertTrue((result as HistoryPageLoader.PageResult.Loaded).primaryCount == 1)
        assertEquals(2, requests.size)
        assertEquals(2, rowCount())
    }

    @Test
    fun followerCancellationDoesNotCancelTheLeaderFlight() = runTest {
        processor.process(networkId, chatMsg("seed", 500))
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val requests = mutableListOf<ChatHistoryRequest>()
        val history = object : HistoryPageLoader.HistorySource {
            override suspend fun availability(): HistoryAvailability = HistoryAvailability.Ready(
                setOf(HistoryReferenceType.TIMESTAMP, HistoryReferenceType.MSGID),
                100,
            )
            override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
                requests += req
                fetchStarted.complete(Unit)
                releaseFetch.await()
                return messages(listOf(chatMsg("older", 100)))
            }
        }
        val leader = async {
            loadOlder(history, ChatHistoryReference("seed", 500))
        }
        fetchStarted.await()
        val follower = launch {
            loadOlder(history, ChatHistoryReference("seed", 500))
        }
        runCurrent()
        follower.cancelAndJoin()
        releaseFetch.complete(Unit)

        // Awaiting a shared flight must not propagate a follower's cancellation into the leader.
        val result = leader.await()
        assertTrue((result as HistoryPageLoader.PageResult.Loaded).primaryCount == 1)
        assertEquals(1, requests.size)
        assertEquals(2, rowCount())
    }

    @Test
    fun timestampOnlyAdvertisementTrimsMsgidFromPersistedBoundary() = runTest {
        processor.process(networkId, chatMsg("seed", 500))
        // The server returns a page carrying a msgid, but the network never advertised MSGID: the
        // stored cursor must be trimmed to the timestamp so a later BEFORE never sends that msgid.
        val history = FakeHistory(
            before = ArrayDeque(listOf(listOf(chatMsg("older", 100)))),
            referenceTypes = setOf(HistoryReferenceType.TIMESTAMP),
        )

        val result = loadOlder(history, ChatHistoryReference("seed", 500))

        assertTrue(result is HistoryPageLoader.PageResult.Loaded)
        assertEquals(
            listOf("timestamp=1970-01-01T00:00:00.500Z"),
            history.requests.map { it.bound1 },
        )
        assertNull(db.historyCursorDao().byRoom(bufferId)?.oldestMsgid)
        assertEquals(100L, db.historyCursorDao().byRoom(bufferId)?.oldestServerTime)
    }

    @Test
    fun msgidRejectionWithoutTimestampFallbackSurfacesTheServersOriginalError() = runTest {
        processor.process(networkId, chatMsg("OpaqueOnly", 500))
        val rejection = IrcCommandException("CHATHISTORY", "INVALID_MSGREFTYPE", "try timestamp")
        // MSGID-only advertisement: a runtime rejection leaves no timestamp fallback selector.
        val history = FakeHistory(
            failureFor = { request -> rejection.takeIf { request.bound1 == "msgid=OpaqueOnly" } },
            referenceTypes = setOf(HistoryReferenceType.MSGID),
        )

        val observed = runCatching {
            loadOlder(history, ChatHistoryReference("OpaqueOnly", null))
        }.exceptionOrNull()

        // The loader must rethrow the server's own diagnostics (the exact exception instance, so
        // MediatorResult.Error carries them) instead of a misleading "no advertised selector"
        // failure fabricated after the pre-checks already proved a selector existed.
        assertSame(rejection, observed)
        assertEquals(1, history.requests.size)
    }

    @Test
    fun timeoutWhileQueuedBehindTheWireLockSurfacesAsRetryableFailure() = runTest {
        processor.process(networkId, chatMsg("seed", 500))
        val requests = mutableListOf<ChatHistoryRequest>()
        val history = object : HistoryPageLoader.HistorySource {
            override suspend fun availability(): HistoryAvailability = HistoryAvailability.Ready(
                setOf(HistoryReferenceType.TIMESTAMP, HistoryReferenceType.MSGID),
                100,
            )
            override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
                requests += req
                return awaitCancellation()
            }
        }
        // A hung LATEST occupies the wire lock for its whole timeout window.
        val hung = async {
            runCatching {
                loader.loadPage(networkId, bufferId, "#chan", HistoryPageLoader.Direction.LATEST, history)
            }
        }
        runCurrent()

        // The caller's budget bounds the WHOLE operation, lock wait included: the queued OLDER
        // fetch must fail with the same retryable transport failure at its deadline instead of
        // stretching its budget behind the busy wire (it never reaches the socket).
        val queued = runCatching {
            loadOlder(history, ChatHistoryReference("seed", 500))
        }
        assertTrue(queued.exceptionOrNull() is IrcDisconnectedException)
        assertTrue(hung.await().exceptionOrNull() is IrcDisconnectedException)
        assertEquals(1, requests.size)
    }

    @Test
    fun concurrentIdenticalOlderLoadsCoalesceOntoOneWireRequest() = runTest {
        processor.process(networkId, chatMsg("seed", 500))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val history = object : HistoryPageLoader.HistorySource {
            override suspend fun availability(): HistoryAvailability = HistoryAvailability.Ready(
                setOf(HistoryReferenceType.TIMESTAMP, HistoryReferenceType.MSGID),
                100,
            )
            override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
                calls++
                entered.complete(Unit)
                release.await()
                return messages(listOf(chatMsg("older", 100)))
            }
        }

        // Two live Pager generations issue the identical (network, room, OLDER) page. Only the leader
        // hits the wire; the follower joins its in-flight fetch.
        val leader = async { loadOlder(history, ChatHistoryReference("seed", 500)) }
        entered.await()
        val follower = async { loadOlder(history, ChatHistoryReference("seed", 500)) }
        runCurrent()
        release.complete(Unit)

        assertTrue((leader.await() as HistoryPageLoader.PageResult.Loaded).primaryCount == 1)
        assertTrue((follower.await() as HistoryPageLoader.PageResult.Loaded).primaryCount == 1)
        assertEquals(1, calls)
        assertEquals(2, rowCount())
    }

    @Test
    fun distinctRoomsOnTheSameNetworkSerializeOnTheWire() = runTest {
        db.bufferDao().insert(
            BufferEntity(networkId = networkId, name = "#other", displayName = "#other", type = BufferType.CHANNEL),
        )
        val otherId = db.bufferDao().byName(networkId, "#other")!!.id
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val history = object : HistoryPageLoader.HistorySource {
            override suspend fun availability(): HistoryAvailability = HistoryAvailability.Ready(
                setOf(HistoryReferenceType.TIMESTAMP, HistoryReferenceType.MSGID),
                100,
            )
            override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
                order += "start:${req.target}"
                if (req.target == "#chan") {
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                }
                return messages(listOf(chatMsg("older", 100)))
            }
        }

        val first = launch {
            loader.loadPage(
                networkId, bufferId, "#chan", HistoryPageLoader.Direction.OLDER, history,
                boundary = ChatHistoryReference("seedChan", 500),
            )
        }
        firstEntered.await()
        val second = async {
            loader.loadPage(
                networkId, otherId, "#other", HistoryPageLoader.Direction.OLDER, history,
                boundary = ChatHistoryReference("seedOther", 400),
            )
        }
        runCurrent()

        // The second room's fetch is blocked on the shared per-network wire lock: it has not started.
        assertEquals(listOf("start:#chan"), order)
        releaseFirst.complete(Unit)
        second.await()
        first.join()
        assertEquals(listOf("start:#chan", "start:#other"), order)
    }

    @Test
    fun gapDirectedOlderFillResolvesTheTargetedGap() = runTest {
        processor.process(networkId, chatMsg("seed", 500))
        val gapId = db.historyGapDao().insert(
            HistoryGapEntity(
                roomId = bufferId,
                olderMsgid = "mid",
                olderServerTime = 300,
                newerMsgid = "seed",
                newerServerTime = 500,
            ),
        )
        val history = FakeHistory(
            responseFor = { request ->
                messages(listOf(chatMsg("old", 100)), endOfHistory = true)
                    .takeIf { request.subcommand == ChatHistoryRequest.Subcommand.BEFORE }
            },
        )

        val result = loader.loadPage(
            networkId, bufferId, "#chan", HistoryPageLoader.Direction.OLDER, history,
            gapId = gapId,
            boundary = ChatHistoryReference("seed", 500),
        )

        // A terminal older page routed into the focused gap reaches its older boundary and closes it.
        assertTrue((result as HistoryPageLoader.PageResult.Loaded).endOfDirection)
        assertTrue(db.historyGapDao().forRoom(bufferId).isEmpty())
        assertTrue(db.messageDao().byMsgid(bufferId, "old") != null)
    }
}
