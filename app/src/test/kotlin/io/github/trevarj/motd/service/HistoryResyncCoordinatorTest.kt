package io.github.trevarj.motd.service

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dagger.Lazy
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.HistoryCursorEntity
import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.prefs.HistorySyncPrefs
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.data.sync.HistoryPageLoader
import io.github.trevarj.motd.data.sync.BufferStore
import io.github.trevarj.motd.data.sync.MessageNotifier
import io.github.trevarj.motd.data.sync.TypingTrackerImpl
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryReference
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.ChatHistoryTarget
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.HistoryReferenceType
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcCommandException
import io.github.trevarj.motd.irc.client.IrcDisconnectedException
import io.github.trevarj.motd.irc.client.IrcProtocolException
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.ext.ChatHistorySelectors
import io.github.trevarj.motd.irc.proto.Prefix
import io.github.trevarj.motd.ircbackend.IrcSessions
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryResyncCoordinatorTest {
    private lateinit var db: MotdDatabase
    private lateinit var processor: EventProcessor
    private lateinit var coordinator: HistoryResyncCoordinator
    private var networkId = 0L
    private var bufferId = 0L
    private val syncPrefs = object : HistorySyncPrefs {
        private val values = mutableMapOf<Long, Long>()
        override suspend fun lastSuccessfulSync(networkId: Long): Long? = values[networkId]
        override suspend fun setLastSuccessfulSync(networkId: Long, timestamp: Long) {
            values[networkId] = timestamp
        }
        override suspend fun clear(networkId: Long) { values.remove(networkId) }
    }

    // The public HistoryResyncController boundary resolves its own session via IrcSessions; every
    // resync/reconcile test in this file exercises the internal HistorySource-based overloads
    // directly instead, so no test needs a live session here. Always-null also exercises the
    // no-session early return of the public boundary itself (see the tests at the bottom of file).
    // Wrapped in dagger.Lazy to mirror the real constructor, which needs it lazy to avoid a Dagger
    // cycle through the IrcSessions -> ConnectionManagerImpl -> HistoryResyncCoordinator binding.
    private val ircSessions = Lazy<IrcSessions> {
        object : IrcSessions {
            override fun sessionFor(networkId: Long): IrcClient? = null
        }
    }

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        processor = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop)
        coordinator = HistoryResyncCoordinator(
            db,
            processor,
            ircSessions,
            syncPrefs,
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        networkId = db.networkDao().insert(
            NetworkEntity(
                name = "libera",
                role = NetworkRole.DIRECT,
                host = "h",
                port = 6697,
                nick = "me",
                username = "me",
                realname = "Me",
            ),
        )
        bufferId = db.bufferDao().insert(
            BufferEntity(
                networkId = networkId,
                name = "#chan",
                displayName = "#chan",
                type = BufferType.CHANNEL,
                readMarkerTime = 75,
            ),
        )
        processor.onRegistered(networkId, "me", emptyMap())
    }

    @After
    fun tearDown() = db.close()

    private fun message(msgid: String, time: Long, target: String = "#chan") = IrcEvent.ChatMessage(
        ctx = MessageContext(msgid, time, null, "batch", null),
        kind = IrcEvent.ChatKind.PRIVMSG,
        source = Prefix("alice"),
        target = target,
        text = msgid,
        isSelf = false,
        replyToMsgid = null,
    )

    private fun directMessage(msgid: String, time: Long, peer: String = "bob") = IrcEvent.ChatMessage(
        ctx = MessageContext(msgid, time, null, "batch", null),
        kind = IrcEvent.ChatKind.PRIVMSG,
        source = Prefix(peer),
        target = "me",
        text = msgid,
        isSelf = false,
        replyToMsgid = null,
    )

    private suspend fun rows(id: Long = bufferId, loadSize: Int = 500): List<MessageEntity> {
        val loaded = db.messageDao().pagingSource(id).load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = loadSize, placeholdersEnabled = false),
        ) as PagingSource.LoadResult.Page
        return loaded.data
    }

    private data class FakeResponse(
        val events: List<IrcEvent> = emptyList(),
        val targets: List<Pair<String, Long>> = emptyList(),
        val endOfHistory: Boolean = false,
        val oldest: ChatHistoryReference? = events.references().minByOrNull { it.serverTime ?: Long.MAX_VALUE },
        val newest: ChatHistoryReference? = events.references().maxByOrNull { it.serverTime ?: Long.MIN_VALUE },
        val primaryMessageCount: Int = events.size,
    )

    private companion object {
        fun List<IrcEvent>.references(): List<ChatHistoryReference> = mapNotNull { event ->
            val context = when (event) {
                is IrcEvent.ChatMessage -> event.ctx
                is IrcEvent.TagMessage -> event.ctx
                is IrcEvent.Joined -> event.ctx
                is IrcEvent.Parted -> event.ctx
                is IrcEvent.Quit -> event.ctx
                is IrcEvent.Kicked -> event.ctx
                is IrcEvent.NickChanged -> event.ctx
                is IrcEvent.TopicChanged -> event.ctx
                is IrcEvent.ModeChanged -> event.ctx
                is IrcEvent.Invited -> event.ctx
                else -> null
            }
            context?.let { ChatHistoryReference(it.msgid, it.serverTime) }
        }
    }

    private class FakeSource(
        var supported: Boolean = true,
        var msgidRefs: Boolean = true,
        var timestampRefs: Boolean = true,
        var pageLimit: Int = 100,
        var targetClassificationReady: Boolean = true,
        val channelClassifier: (String) -> Boolean = { target ->
            target.startsWith('#') || target.startsWith('&')
        },
        val responder: suspend (ChatHistoryRequest) -> FakeResponse,
    ) : HistoryResyncCoordinator.HistorySource {
        val requests = mutableListOf<ChatHistoryRequest>()
        override suspend fun availability(): HistoryAvailability = if (supported) {
            HistoryAvailability.Ready(
                buildSet {
                    if (timestampRefs) add(HistoryReferenceType.TIMESTAMP)
                    if (msgidRefs) add(HistoryReferenceType.MSGID)
                },
                pageLimit,
            )
        } else {
            HistoryAvailability.Unsupported
        }
        override fun canClassifyTargets(): Boolean = targetClassificationReady
        override fun isChannelTarget(target: String): Boolean = channelClassifier(target)
        override suspend fun chathistory(request: ChatHistoryRequest): ChatHistoryResponse {
            requests += request
            val response = responder(request)
            return if (request.subcommand == ChatHistoryRequest.Subcommand.TARGETS) {
                ChatHistoryResponse.Targets(
                    response.targets.map { (name, time) -> ChatHistoryTarget(name, time) },
                    response.endOfHistory,
                )
            } else {
                ChatHistoryResponse.Messages(
                    events = response.events,
                    oldest = response.oldest,
                    newest = response.newest,
                    primaryMessageCount = response.primaryMessageCount,
                    endOfHistory = response.endOfHistory,
                )
            }
        }
    }

    @Test
    fun transientNewDmPush_isIncludedInReconnectHistoryCatchup() = runTest {
        val target = "new-dm-history-fixture"
        val push = IrcEvent.ChatMessage(
            ctx = MessageContext(null, 400, null, null, null),
            kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix(target),
            target = "me",
            text = "transient notification",
            isSelf = false,
            replyToMsgid = null,
        )
        processor.processPush(networkId, push)
        val dmBuffer = requireNotNull(db.bufferDao().byName(networkId, target))
        assertEquals(1, db.messageDao().countForBuffer(dmBuffer.id))

        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(targets = listOf(target to 400L), endOfHistory = true)
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(
                    events = if (request.target == target) {
                        listOf(
                            push.copy(
                                ctx = push.ctx.copy(msgid = "durable-new-dm", batchId = "history"),
                            ),
                        )
                    } else {
                        emptyList()
                    },
                    targets = emptyList(),
                )
                else -> FakeResponse(emptyList(), emptyList())
            }
        }

        val result = coordinator.resyncNetwork(
            networkId,
            db.bufferDao().openTargets(networkId).map { it.id to it.name },
            source,
        )

        // History enriches the already-persisted push row in place, so the canonical row count is
        // unchanged even though its durable msgid is attached.
        assertEquals(HistoryResyncState.UpToDate, result)
        assertEquals(1, db.messageDao().countForBuffer(dmBuffer.id))
        assertTrue(db.messageDao().byMsgid(dmBuffer.id, "durable-new-dm") != null)
        assertTrue(
            source.requests.any {
                it.subcommand == ChatHistoryRequest.Subcommand.LATEST && it.target == target
            },
        )
    }

    @Test
    fun pendingMessagePromotionInterleavesBetweenResyncPages() = runTest {
        // Phase 3 replaced the bespoke bypass of the network-wide gate with per-request wire
        // serialization in the loader: an urgent pending promotion must be serviced BETWEEN two
        // pages of an in-flight network resync — never queued behind the entire pass.
        val loader = HistoryPageLoader(processor)
        coordinator = HistoryResyncCoordinator(db, processor, ircSessions, syncPrefs, backgroundScope, loader = loader)
        val otherId = db.bufferDao().insert(
            BufferEntity(networkId = networkId, name = "#other", displayName = "#other", type = BufferType.CHANNEL),
        )
        val pendingId = processor.insertPending(
            bufferId = bufferId,
            label = "local-pending",
            sender = "me",
            text = "react-now",
            replyToMsgid = null,
            kind = MessageKind.PRIVMSG,
        )
        val page1Entered = CompletableDeferred<Unit>()
        val releasePage1 = CompletableDeferred<Unit>()
        // The pass's per-buffer pages request limit RECENT_PAGE_SIZE (50); the pending promotion
        // requests the full page limit (100), which distinguishes it in the recorded request order.
        val source = FakeSource { request ->
            when {
                request.subcommand == ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(endOfHistory = true)
                request.target == "#chan" && request.limit == 50 -> {
                    page1Entered.complete(Unit)
                    releasePage1.await()
                    FakeResponse(listOf(message("m1", 100)), endOfHistory = true)
                }
                request.target == "#chan" -> FakeResponse(
                    events = listOf(
                        IrcEvent.ChatMessage(
                            ctx = MessageContext(
                                msgid = "durable-react-target",
                                serverTime = System.currentTimeMillis(),
                                account = "me",
                                batchId = "history",
                                label = null,
                            ),
                            kind = IrcEvent.ChatKind.PRIVMSG,
                            source = Prefix("me"),
                            target = "#chan",
                            text = "react-now",
                            isSelf = true,
                            replyToMsgid = null,
                        ),
                    ),
                    endOfHistory = true,
                )
                else -> FakeResponse(listOf(message("o1", 300, target = "#other")), endOfHistory = true)
            }
        }

        val pass = async {
            coordinator.resyncNetwork(
                networkId,
                listOf(bufferId to "#chan", otherId to "#other"),
                source,
            )
        }
        page1Entered.await()
        // The pass holds the loader's wire lock for its first page. The urgent promotion queues on
        // that per-request lock, taking the very next slot ahead of the pass's remaining pages.
        val pending = async { coordinator.reconcilePendingMessage(networkId, bufferId, "#chan", source) }
        runCurrent()
        releasePage1.complete(Unit)

        assertEquals(HistoryResyncState.UpToDate, pending.await())
        assertEquals(HistoryResyncState.Updated(2), pass.await())
        assertEquals("durable-react-target", db.messageDao().byCanonicalId(pendingId)?.msgid)
        assertEquals("durable-react-target", db.historyCursorDao().byRoom(bufferId)?.newestMsgid)
        // Request order on the wire is the proof: the pending LATEST (limit 100) was serviced
        // strictly between the pass's two per-buffer pages (limit 50) — i.e. before the pass had
        // even sent its final page, never queued behind the whole pass.
        assertEquals(
            listOf(
                "TARGETS:*:100",
                "LATEST:#chan:50",
                "LATEST:#chan:100",
                "LATEST:#other:50",
            ),
            source.requests.map { "${it.subcommand}:${it.target}:${it.limit}" },
        )
    }

    @Test
    fun reopeningVisibleBufferDoesNotWalkBackwardIntoOldSparseHistory() = runTest {
        processor.process(networkId, message("m1", 1))
        (103L..202L).forEach { processor.process(networkId, message("m$it", it)) }
        val source = FakeSource(pageLimit = 100) { request ->
            val events = when (request.subcommand) {
                ChatHistoryRequest.Subcommand.AFTER -> emptyList()
                ChatHistoryRequest.Subcommand.LATEST -> (103L..202L).map { message("m$it", it) }
                ChatHistoryRequest.Subcommand.BETWEEN -> (3L..102L).map { message("m$it", it) }
                else -> emptyList()
            }
            FakeResponse(events, emptyList())
        }

        assertEquals(
            HistoryResyncState.UpToDate,
            coordinator.reconcileBuffer(networkId, bufferId, "#chan", source),
        )
        assertEquals(
            listOf(ChatHistoryRequest.Subcommand.LATEST),
            source.requests.map { it.subcommand },
        )
        assertEquals(0, source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.BEFORE })
        assertTrue(rows().none { it.msgid == "m102" })
    }

    @Test
    fun automaticNetworkResyncDiscoversQueriesButNotDepartedChannels() = runTest {
        processor.process(networkId, message("seed", 100))
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(
                        targets = listOf("Alice" to 500L, "#departed" to 400L),
                        endOfHistory = true,
                    )
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(
                    if (request.target == "Alice") listOf(message("found", 500, "me")) else emptyList(),
                    emptyList(),
                )
                else -> FakeResponse(emptyList(), emptyList())
            }
        }

        val result = coordinator.resyncNetwork(networkId, listOf(bufferId to "#chan"), source)

        assertEquals(HistoryResyncState.Updated(1), result)
        val query = db.bufferDao().byName(networkId, "alice")
        assertEquals(BufferType.QUERY, query?.type)
        assertEquals("found", db.messageDao().newestMessage(query!!.id)?.msgid)
        assertEquals(null, db.bufferDao().byName(networkId, "#departed"))
        assertTrue(source.requests.any { it.subcommand == ChatHistoryRequest.Subcommand.TARGETS })
    }

    @Test
    fun automaticNetworkResyncBoundsThousandMessageGapToOneRecentPage() = runTest {
        processor.process(networkId, message("seed", 100))
        val source = FakeSource(pageLimit = 100) { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS -> FakeResponse(
                    targets = listOf("#chan" to 1_214L),
                    endOfHistory = true,
                )
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(
                    events = (1_165..1_214).map { message("m$it", it.toLong()) },
                )
                ChatHistoryRequest.Subcommand.BEFORE -> {
                    val newest = request.bound1!!.removePrefix("msgid=m").toInt() - 1
                    FakeResponse((newest - 49..newest).map { message("m$it", it.toLong()) })
                }
                else -> error("unexpected ${request.subcommand}")
            }
        }

        val result = coordinator.resyncNetwork(
            networkId,
            listOf(bufferId to "#chan"),
            source,
        )

        assertEquals(HistoryResyncState.Updated(50), result)
        val msgids = rows(loadSize = 2_000).mapNotNull { it.msgid }
        assertEquals(51, msgids.size)
        assertEquals(51, msgids.toSet().size)
        assertEquals("m1214", msgids.first())
        assertEquals("seed", msgids.last())
        assertEquals(0, source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.BEFORE })
        assertEquals(HistorySyncStatus.Idle, coordinator.syncStatus(bufferId).first())
        assertEquals(1_214L, syncPrefs.lastSuccessfulSync(networkId))
    }

    @Test
    fun automaticRecentWindowTrimsAnOversizedServerResponseBeforePersistence() = runTest {
        processor.process(networkId, message("seed", 100))
        val source = FakeSource(pageLimit = 50) { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS -> FakeResponse(
                    targets = listOf("#chan" to 1_000L),
                    endOfHistory = true,
                )
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(
                    events = (1..1_000).map { message("m$it", it.toLong()) },
                )
                ChatHistoryRequest.Subcommand.BEFORE -> FakeResponse(endOfHistory = true)
                else -> error("unexpected ${request.subcommand}")
            }
        }

        val result = coordinator.resyncNetwork(networkId, listOf(bufferId to "#chan"), source)

        assertEquals(HistoryResyncState.Updated(50), result)
        val msgids = rows(loadSize = 2_000).mapNotNull { it.msgid }
        assertEquals(51, msgids.size)
        assertTrue((951..1_000).all { "m$it" in msgids })
        assertTrue("m1" !in msgids)
        assertEquals(0, source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.BEFORE })
    }

    @Test
    fun automaticNetworkResyncUsesOneNewestPageWhenRoomAlreadyHasTheLatestRow() = runTest {
        processor.process(networkId, message("m2000", 2_000))
        syncPrefs.setLastSuccessfulSync(networkId, 0)
        val source = FakeSource(pageLimit = 100) { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS -> FakeResponse(
                    targets = listOf("#chan" to 2_000L),
                    endOfHistory = true,
                )
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(
                    events = (1_951..2_000).map { message("m$it", it.toLong()) },
                )
                ChatHistoryRequest.Subcommand.BEFORE -> {
                    val newest = request.bound1!!.removePrefix("msgid=m").toInt() - 1
                    FakeResponse((newest - 49..newest).map { message("m$it", it.toLong()) })
                }
                else -> error("unexpected ${request.subcommand}")
            }
        }

        val result = coordinator.resyncNetwork(
            networkId,
            listOf(bufferId to "#chan"),
            source,
        )

        assertEquals(HistoryResyncState.Updated(49), result)
        val msgids = rows(loadSize = 2_500).mapNotNull { it.msgid }
        assertEquals(50, msgids.size)
        assertTrue((1_951..2_000).all { "m$it" in msgids })
        assertEquals(0, source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.BEFORE })
        assertEquals("m2000", db.historyCursorDao().byRoom(bufferId)?.newestMsgid)
        assertEquals(2_000L, syncPrefs.lastSuccessfulSync(networkId))
    }

    @Test
    fun automaticNetworkResyncDoesNotRetryAfterReachingAdvertisedLatest() = runTest {
        processor.process(networkId, message("seed", 100))
        val source = FakeSource(pageLimit = 1) { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS -> FakeResponse(
                    targets = listOf("#chan" to 101L),
                    endOfHistory = true,
                )
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(
                    events = listOf(message("m101", 101)),
                    endOfHistory = true,
                )
                else -> error("unexpected ${request.subcommand}")
            }
        }

        val result = coordinator.resyncNetwork(
            networkId,
            listOf(bufferId to "#chan"),
            source,
        )

        assertEquals(HistoryResyncState.Updated(1), result)
        assertEquals(1, source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.LATEST })
        assertEquals(HistorySyncStatus.Idle, coordinator.syncStatus(bufferId).first())
        assertEquals(101L, syncPrefs.lastSuccessfulSync(networkId))
        assertEquals(listOf("m101", "seed"), rows().mapNotNull { it.msgid })
    }

    @Test
    fun automaticNetworkResyncRecommendsRetryWhileAdvertisedLatestIsMissing() = runTest {
        processor.process(networkId, message("seed", 100))
        val source = FakeSource(pageLimit = 1) { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS -> FakeResponse(
                    targets = listOf("#chan" to 102L),
                    endOfHistory = true,
                )
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(
                    events = listOf(message("m101", 101)),
                    endOfHistory = true,
                )
                else -> error("unexpected ${request.subcommand}")
            }
        }

        val result = coordinator.resyncNetwork(
            networkId,
            listOf(bufferId to "#chan"),
            source,
        )

        assertTrue(result is HistoryResyncState.Incomplete)
        val incomplete = result as HistoryResyncState.Incomplete
        assertEquals(true, incomplete.retryRecommended)
        assertTrue(shouldRetryIncompleteCatchUp(incomplete))
        assertTrue(coordinator.syncStatus(bufferId).first() is HistorySyncStatus.Partial)
        assertEquals(null, syncPrefs.lastSuccessfulSync(networkId))
        assertEquals(listOf("m101", "seed"), rows().mapNotNull { it.msgid })
    }

    @Test
    fun staleConnectionClearsTransientTimelineSyncStatus() = runTest {
        processor.process(networkId, message("seed", 100))
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        var current = true
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS -> FakeResponse(
                    targets = listOf("#chan" to 200L),
                    endOfHistory = true,
                )
                ChatHistoryRequest.Subcommand.LATEST -> {
                    requestStarted.complete(Unit)
                    releaseRequest.await()
                    FakeResponse(listOf(message("tail", 200)), endOfHistory = true)
                }
                else -> error("unexpected ${request.subcommand}")
            }
        }
        val resync = async {
            coordinator.resyncNetwork(
                networkId,
                listOf(bufferId to "#chan"),
                source,
                isCurrent = { current },
            )
        }
        requestStarted.await()

        assertEquals(HistorySyncStatus.Syncing, coordinator.syncStatus(bufferId).first())
        current = false
        releaseRequest.complete(Unit)

        assertTrue(resync.await() is HistoryResyncState.Failed)
        assertEquals(HistorySyncStatus.Idle, coordinator.syncStatus(bufferId).first())
    }

    @Test
    fun liveChantypesClassificationCanDiscoverHashPrefixedQuery() = runTest {
        db.bufferDao().deleteBuffer(bufferId)
        val source = FakeSource(channelClassifier = { false }) { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(targets = listOf("#peer" to 500L), endOfHistory = true)
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(
                    events = listOf(
                        IrcEvent.ChatMessage(
                            MessageContext("custom-query-in", 500, null, "batch", null),
                            IrcEvent.ChatKind.PRIVMSG,
                            Prefix("#peer"),
                            "me",
                            "hello",
                            false,
                            null,
                        ),
                        IrcEvent.ChatMessage(
                            MessageContext("custom-query-out", 501, null, "batch", null),
                            IrcEvent.ChatKind.PRIVMSG,
                            Prefix("me"),
                            "#peer",
                            "reply",
                            true,
                            null,
                        ),
                    ),
                    endOfHistory = true,
                )
                else -> FakeResponse(endOfHistory = true)
            }
        }

        assertEquals(HistoryResyncState.Updated(2), coordinator.resyncNetwork(networkId, emptyList(), source))
        val query = db.bufferDao().byName(networkId, "#peer")
        assertEquals(BufferType.QUERY, query?.type)
        assertEquals(2, db.messageDao().countForBuffer(query!!.id))
    }

    @Test
    fun networkWatermarkWaitsForTargetClassification() = runTest {
        val source = FakeSource(targetClassificationReady = false) { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS -> error("TARGETS must wait for CHANTYPES")
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(endOfHistory = true)
                else -> FakeResponse(endOfHistory = true)
            }
        }

        val result = coordinator.resyncNetwork(networkId, listOf(bufferId to "#chan"), source)

        assertTrue(result is HistoryResyncState.Incomplete)
        assertTrue((result as HistoryResyncState.Incomplete).awaitsTargetClassification)
        assertTrue(source.requests.none { it.subcommand == ChatHistoryRequest.Subcommand.TARGETS })
        assertEquals(null, syncPrefs.lastSuccessfulSync(networkId))
    }

    @Test
    fun freshNetworkDiscoversRetainedQueriesFromEpochAndStoresCursor() = runTest {
        db.bufferDao().deleteBuffer(bufferId)
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(targets = listOf("old-friend" to 500L), endOfHistory = true)
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(
                    if (request.target == "old-friend") listOf(message("retained", 500, "me")) else emptyList(),
                    emptyList(),
                )
                else -> FakeResponse(emptyList(), emptyList())
            }
        }

        val result = coordinator.resyncNetwork(networkId, emptyList(), source)

        assertEquals(HistoryResyncState.Updated(1), result)
        val targets = source.requests.first { it.subcommand == ChatHistoryRequest.Subcommand.TARGETS }
        assertEquals("timestamp=1970-01-01T00:00:00.000Z", targets.bound2)
        assertTrue(targets.bound1!!.matches(Regex("timestamp=.*\\.\\d{3}Z")))
        assertTrue(db.bufferDao().byName(networkId, "old-friend") != null)
        assertEquals(500L, syncPrefs.lastSuccessfulSync(networkId))
    }

    @Test
    fun freshNetworkPagesTargetsToExhaustionBeforeStoringCursor() = runTest {
        db.bufferDao().deleteBuffer(bufferId)
        val secondPageUpper = ChatHistorySelectors.timestamp(201)
        val source = FakeSource(pageLimit = 2) { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS -> FakeResponse(
                    emptyList(),
                    if (request.bound1 == secondPageUpper) {
                        listOf("middle" to 200L, "oldest" to 100L)
                    } else {
                        listOf("newest" to 300L, "middle" to 200L)
                    },
                    endOfHistory = request.bound1 == secondPageUpper,
                )
                ChatHistoryRequest.Subcommand.LATEST -> {
                    // Discovery must finish before any per-room sync can make the pass successful.
                    assertEquals(null, syncPrefs.lastSuccessfulSync(networkId))
                    val time = when (request.target) {
                        "newest" -> 300L
                        "middle" -> 200L
                        "oldest" -> 100L
                        else -> error("unexpected target ${request.target}")
                    }
                    FakeResponse(
                        listOf(message("retained-${request.target}", time, "me")),
                        emptyList(),
                    )
                }
                else -> FakeResponse(emptyList(), emptyList())
            }
        }

        val result = coordinator.resyncNetwork(networkId, emptyList(), source)

        assertEquals(HistoryResyncState.Updated(3), result)
        assertEquals(
            2,
            source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.TARGETS },
        )
        assertEquals(
            setOf("newest", "middle", "oldest"),
            source.requests.filter { it.subcommand == ChatHistoryRequest.Subcommand.LATEST }
                .map { it.target }
                .toSet(),
        )
        assertTrue(db.bufferDao().byName(networkId, "newest") != null)
        assertTrue(db.bufferDao().byName(networkId, "middle") != null)
        assertTrue(db.bufferDao().byName(networkId, "oldest") != null)
        assertEquals(300L, syncPrefs.lastSuccessfulSync(networkId))
    }

    @Test
    fun dismissedQueryIgnoresUnchangedHistoryThenRevivesForNewDm() = runTest {
        processor.process(networkId, directMessage("dm-old", 100))
        val query = db.bufferDao().byName(networkId, "bob")!!
        db.bufferDao().deleteBuffer(query.id)
        syncPrefs.setLastSuccessfulSync(networkId, 150)

        val unchanged = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(targets = listOf("bob" to 100L), endOfHistory = true)
                ChatHistoryRequest.Subcommand.AFTER -> FakeResponse(endOfHistory = true)
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(
                    events = if (request.target == "bob") listOf(directMessage("dm-old", 100)) else emptyList(),
                    endOfHistory = true,
                )
                else -> FakeResponse(endOfHistory = true)
            }
        }

        assertEquals(
            HistoryResyncState.UpToDate,
            coordinator.resyncNetwork(
                networkId,
                db.bufferDao().openTargets(networkId).map { it.id to it.name },
                unchanged,
            ),
        )
        assertTrue(db.bufferDao().rawById(query.id)!!.dismissed)
        assertEquals(0, db.messageDao().countForBuffer(query.id))

        val updated = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(targets = listOf("bob" to 200L), endOfHistory = true)
                ChatHistoryRequest.Subcommand.AFTER -> FakeResponse(
                    events = if (request.target == "bob") listOf(directMessage("dm-new", 200)) else emptyList(),
                    endOfHistory = true,
                )
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(
                    events = if (request.target == "bob") {
                        listOf(directMessage("dm-old", 100), directMessage("dm-new", 200))
                    } else {
                        emptyList()
                    },
                    endOfHistory = true,
                )
                else -> FakeResponse(endOfHistory = true)
            }
        }

        assertEquals(
            HistoryResyncState.Updated(1),
            coordinator.resyncNetwork(
                networkId,
                db.bufferDao().openTargets(networkId).map { it.id to it.name },
                updated,
            ),
        )
        assertTrue(!db.bufferDao().rawById(query.id)!!.dismissed)
        assertEquals(listOf("dm-new"), rows(query.id).mapNotNull { it.msgid })
    }

    @Test
    fun dismissedMsgidlessQueryIgnoresTargetAtExactDiscardTime() = runTest {
        val old = directMessage("ignored", 100).copy(
            ctx = MessageContext(null, 100, null, "batch", null),
            text = "msgidless-old",
        )
        processor.process(networkId, old)
        val query = db.bufferDao().byName(networkId, "bob")!!
        db.bufferDao().deleteBuffer(query.id)
        syncPrefs.setLastSuccessfulSync(networkId, 150)

        val shell = db.bufferDao().rawById(query.id)!!
        assertTrue(shell.dismissed)
        assertEquals(null, shell.historyDiscardedThroughMsgid)
        assertEquals(100L, shell.historyDiscardedThroughTime)

        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(targets = listOf("bob" to 100L), endOfHistory = true)
                ChatHistoryRequest.Subcommand.AFTER -> FakeResponse(endOfHistory = true)
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(
                    events = if (request.target == "bob") listOf(old) else emptyList(),
                    endOfHistory = true,
                )
                else -> FakeResponse(endOfHistory = true)
            }
        }

        assertEquals(
            HistoryResyncState.UpToDate,
            coordinator.resyncNetwork(
                networkId,
                db.bufferDao().openTargets(networkId).map { it.id to it.name },
                source,
            ),
        )
        assertTrue(db.bufferDao().rawById(query.id)!!.dismissed)
        assertEquals(0, db.messageDao().countForBuffer(query.id))
        assertTrue(source.requests.none { it.target == "bob" })
    }

    @Test
    fun repeatedForgetDoesNotRestorePreviousMsgidlessMessageOnOpen() = runTest {
        fun dm(text: String, time: Long) = directMessage("ignored", time).copy(
            ctx = MessageContext(null, time, null, null, null),
            text = text,
        )

        val retained = mutableListOf<IrcEvent.ChatMessage>()
        suspend fun receive(message: IrcEvent.ChatMessage) {
            retained += message
            processor.process(networkId, message)
        }
        val source = FakeSource(msgidRefs = false) { request ->
            val lowerBound = when (request.bound1) {
                null -> null
                ChatHistorySelectors.timestamp(100) -> 100L
                ChatHistorySelectors.timestamp(200) -> 200L
                ChatHistorySelectors.timestamp(300) -> 300L
                else -> error("unexpected history bound ${request.bound1}")
            }
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.AFTER,
                ChatHistoryRequest.Subcommand.LATEST,
                -> FakeResponse(
                    events = retained
                        .filter { lowerBound == null || it.ctx.serverTime > lowerBound }
                        .map { it.copy(ctx = it.ctx.copy(batchId = "history")) },
                    endOfHistory = true,
                )
                else -> FakeResponse(endOfHistory = true)
            }
        }

        receive(dm("1", 100))
        val query = db.bufferDao().byName(networkId, "bob")!!
        db.bufferDao().deleteBuffer(query.id)

        receive(dm("2", 200))
        coordinator.reconcileBuffer(networkId, query.id, "bob", source)

        assertEquals(listOf("2"), rows(query.id).map { it.text })
        assertEquals(
            ChatHistorySelectors.timestamp(100),
            source.requests.last { it.subcommand == ChatHistoryRequest.Subcommand.LATEST }.bound1,
        )

        db.bufferDao().deleteBuffer(query.id)
        receive(dm("3", 300))
        coordinator.reconcileBuffer(networkId, query.id, "bob", source)

        assertEquals(listOf("3"), rows(query.id).map { it.text })
        assertEquals(
            ChatHistorySelectors.timestamp(200),
            source.requests.last { it.subcommand == ChatHistoryRequest.Subcommand.LATEST }.bound1,
        )
    }

    @Test
    fun dismissedQueryUsesLatestWhenDiscardBoundarySelectorIsUnsupported() = runTest {
        processor.process(networkId, directMessage("dm-old", 100))
        val query = db.bufferDao().byName(networkId, "bob")!!
        db.bufferDao().deleteBuffer(query.id)
        val shell = db.bufferDao().rawById(query.id)!!
        db.bufferDao().update(shell.copy(historyDiscardedThroughTime = null))
        db.historyCursorDao().upsert(
            HistoryCursorEntity(roomId = query.id, newestMsgid = "dm-old"),
        )
        assertTrue(db.bufferDao().isDiscardedMessageId(query.id, "dm-old"))
        val source = FakeSource(msgidRefs = false) { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(targets = listOf("bob" to 200L), endOfHistory = true)
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(
                    events = if (request.target == "bob") {
                        listOf(directMessage("dm-old", 100), directMessage("dm-new", 200))
                    } else {
                        emptyList()
                    },
                    endOfHistory = true,
                )
                ChatHistoryRequest.Subcommand.AFTER -> error("unsupported discard cursor must be skipped")
                else -> FakeResponse(endOfHistory = true)
            }
        }

        val result = coordinator.resyncNetwork(
            networkId,
            db.bufferDao().openTargets(networkId).map { it.id to it.name },
            source,
        )
        assertEquals(listOf("dm-new"), rows(query.id).mapNotNull { it.msgid })
        assertEquals(HistoryResyncState.Updated(1), result)
        assertTrue(source.requests.none { it.subcommand == ChatHistoryRequest.Subcommand.AFTER })

        db.historyCursorDao().upsert(
            HistoryCursorEntity(roomId = query.id, newestMsgid = "dm-new"),
        )
        val visibleSource = FakeSource(msgidRefs = false) { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(
                    events = listOf(directMessage("dm-newer", 300)),
                    endOfHistory = true,
                )
                ChatHistoryRequest.Subcommand.AFTER -> error("visible unsupported cursor must be skipped")
                else -> FakeResponse(endOfHistory = true)
            }
        }

        assertEquals(
            HistoryResyncState.Updated(1),
            coordinator.reconcileBuffer(networkId, query.id, "bob", visibleSource),
        )
        assertTrue(visibleSource.requests.none { it.subcommand == ChatHistoryRequest.Subcommand.AFTER })
        assertEquals(listOf("dm-newer", "dm-new"), rows(query.id).mapNotNull { it.msgid })
    }

    @Test
    fun runtimeMsgidRejectionStillChecksDismissedQueryLatest() = runTest {
        processor.process(networkId, directMessage("dm-old", 100))
        val query = db.bufferDao().byName(networkId, "bob")!!
        db.bufferDao().deleteBuffer(query.id)
        val shell = db.bufferDao().rawById(query.id)!!
        db.bufferDao().update(shell.copy(historyDiscardedThroughTime = null))
        db.historyCursorDao().upsert(
            HistoryCursorEntity(roomId = query.id, newestMsgid = "dm-old"),
        )
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(targets = listOf("bob" to 200L), endOfHistory = true)
                ChatHistoryRequest.Subcommand.AFTER -> throw IrcCommandException(
                    "CHATHISTORY",
                    "INVALID_MSGREFTYPE",
                    "msgid unsupported",
                )
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(
                    events = if (request.target == "bob") listOf(directMessage("dm-new", 200)) else emptyList(),
                    endOfHistory = true,
                )
                else -> FakeResponse(endOfHistory = true)
            }
        }

        val result = coordinator.resyncNetwork(
            networkId,
            db.bufferDao().openTargets(networkId).map { it.id to it.name },
            source,
        )

        assertEquals(HistoryResyncState.Updated(1), result)
        assertEquals(listOf("dm-new"), rows(query.id).mapNotNull { it.msgid })
        assertTrue(source.requests.any { it.subcommand == ChatHistoryRequest.Subcommand.LATEST })
    }

    @Test
    fun accountRerouteDoesNotReviveDismissedQueryForRejectedHistory() = runTest {
        fun accountMessage(msgid: String, time: Long, peer: String) = IrcEvent.ChatMessage(
            ctx = MessageContext(msgid, time, "shared-account", "batch", null),
            kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix(peer),
            target = "me",
            text = msgid,
            isSelf = false,
            replyToMsgid = null,
        )
        processor.process(networkId, accountMessage("account-old", 100, "alice"))
        val query = db.bufferDao().byName(networkId, "alice")!!
        db.bufferDao().deleteBuffer(query.id)

        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(targets = listOf("newnick" to 90L), endOfHistory = true)
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(
                    events = listOf(
                        accountMessage("account-older", 90, "newnick"),
                        IrcEvent.NickChanged(
                            MessageContext("old-nick", 90, null, "batch", null),
                            from = "newnick",
                            to = "oldernick",
                            isSelf = false,
                        ),
                    ),
                    endOfHistory = true,
                )
                else -> FakeResponse(endOfHistory = true)
            }
        }

        assertEquals(
            HistoryResyncState.UpToDate,
            coordinator.resyncNetwork(networkId, emptyList(), source),
        )
        assertTrue(db.bufferDao().observeById(query.id)!!.dismissed)
        assertEquals(0, db.messageDao().countForBuffer(query.id))
        assertTrue(db.bufferDao().observeChatList().first().none { it.bufferId == query.id })
    }

    @Test
    fun accountRerouteFiltersContextAgainstSelectedRoomWithoutMerge() = runTest {
        fun accountMessage(msgid: String, time: Long, peer: String, account: String) =
            IrcEvent.ChatMessage(
                ctx = MessageContext(msgid, time, account, "batch", null),
                kind = IrcEvent.ChatKind.PRIVMSG,
                source = Prefix(peer),
                target = "me",
                text = msgid,
                isSelf = false,
                replyToMsgid = null,
            )
        processor.process(networkId, accountMessage("account-old", 100, "alice", "account-a"))
        val accountA = db.bufferDao().byName(networkId, "alice")!!
        db.bufferDao().deleteBuffer(accountA.id)
        processor.process(networkId, accountMessage("account-b", 80, "newnick", "account-b"))
        val accountB = db.bufferDao().byName(networkId, "newnick")!!
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(targets = listOf("newnick" to 90L), endOfHistory = true)
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(
                    events = listOf(
                        IrcEvent.ChatMessage(
                            MessageContext("account-a-outgoing", 90, null, "batch", null),
                            IrcEvent.ChatKind.PRIVMSG,
                            Prefix("me"),
                            "newnick",
                            "old outgoing",
                            true,
                            null,
                        ),
                        accountMessage("account-a-older", 90, "newnick", "account-a"),
                        IrcEvent.NickChanged(
                            MessageContext("old-context", 90, null, "batch", null),
                            from = "newnick",
                            to = "oldernick",
                            isSelf = false,
                        ),
                    ),
                    endOfHistory = true,
                )
                else -> FakeResponse(endOfHistory = true)
            }
        }

        assertEquals(
            HistoryResyncState.UpToDate,
            coordinator.resyncNetwork(networkId, emptyList(), source),
        )
        assertTrue(db.bufferDao().rawById(accountA.id)!!.dismissed)
        assertEquals(listOf("account-b"), rows(accountB.id).mapNotNull { it.msgid })
        assertEquals(0, db.messageDao().countForBuffer(accountA.id))
    }

    @Test
    fun accountRerouteCountsAcceptedHistoryInCanonicalRoom() = runTest {
        fun accountMessage(msgid: String, time: Long, peer: String) = IrcEvent.ChatMessage(
            ctx = MessageContext(msgid, time, "shared-account", "batch", null),
            kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix(peer),
            target = "me",
            text = msgid,
            isSelf = false,
            replyToMsgid = null,
        )
        processor.process(networkId, accountMessage("account-old", 100, "alice"))
        val query = db.bufferDao().byName(networkId, "alice")!!
        db.bufferDao().deleteBuffer(query.id)
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(targets = listOf("newnick" to 200L), endOfHistory = true)
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(
                    events = listOf(accountMessage("account-new", 200, "newnick")),
                    endOfHistory = true,
                )
                else -> FakeResponse(endOfHistory = true)
            }
        }

        assertEquals(
            HistoryResyncState.Updated(1),
            coordinator.resyncNetwork(networkId, emptyList(), source),
        )
        assertEquals("account-new", db.messageDao().newestMessage(query.id)?.msgid)
    }

    @Test
    fun networkSyncSeedsLatestWhenLiveJoinPrecedesRetainedHistoryEvenWithPriorCursor() = runTest {
        // A soju child sends the live self-JOIN as it binds. Without an initial LATEST overlap,
        // that new row becomes the AFTER cursor and hides every older retained channel message.
        // Keep a prior cursor to cover an upgrade from the behavior that already marked this
        // bouncer network successfully synced.
        syncPrefs.setLastSuccessfulSync(networkId, 2_000)
        processor.process(
            networkId,
            IrcEvent.Joined(
                ctx = MessageContext(null, 1_000, null, null, null),
                nick = "me",
                channel = "#chan",
                account = null,
                realname = null,
                isSelf = true,
            ),
        )
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(targets = listOf("#chan" to 1_000L), endOfHistory = true)
                ChatHistoryRequest.Subcommand.LATEST ->
                    FakeResponse(listOf(message("retained", 500)), emptyList())
                else -> FakeResponse(emptyList(), emptyList())
            }
        }

        val result = coordinator.resyncNetwork(networkId, listOf(bufferId to "#chan"), source)

        assertEquals(HistoryResyncState.Updated(1), result)
        assertEquals(
            listOf(
                ChatHistoryRequest.Subcommand.TARGETS,
                ChatHistoryRequest.Subcommand.LATEST,
            ),
            source.requests.map { it.subcommand },
        )
        assertEquals(0, source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.BEFORE })
        assertTrue(rows().any { it.msgid == "retained" })
    }

    @Test
    fun lateLiveJoinCanSeedHistoryAfterTargetsSkippedUnknownChannel() = runTest {
        db.bufferDao().deleteBuffer(bufferId)
        val discovery = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(targets = listOf("#late" to 500L), endOfHistory = true)
                else -> error("departed channel must not be synchronized")
            }
        }

        assertEquals(HistoryResyncState.UpToDate, coordinator.resyncNetwork(networkId, emptyList(), discovery))
        assertEquals(null, db.bufferDao().byName(networkId, "#late"))

        processor.process(
            networkId,
            IrcEvent.Joined(
                MessageContext("join-late", 600, null, null, null),
                "me",
                "#late",
                null,
                null,
                true,
            ),
        )
        val joined = db.bufferDao().byName(networkId, "#late")!!
        val latest = FakeSource { request ->
            assertEquals(ChatHistoryRequest.Subcommand.LATEST, request.subcommand)
            FakeResponse(events = listOf(message("late-history", 500, "#late")), endOfHistory = true)
        }

        assertEquals(
            HistoryResyncState.Updated(1),
            coordinator.reconcileBuffer(networkId, joined.id, "#late", latest),
        )
        assertEquals("late-history", rows(joined.id).first { it.kind == MessageKind.PRIVMSG }.msgid)
    }

    @Test
    fun channelDeletedWhileHistoryIsInFlightIsNotRecreated() = runTest {
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(targets = listOf("#chan" to 500L), endOfHistory = true)
                ChatHistoryRequest.Subcommand.LATEST -> {
                    requestStarted.complete(Unit)
                    releaseResponse.await()
                    FakeResponse(events = listOf(message("too-late", 500)), endOfHistory = true)
                }
                else -> FakeResponse(endOfHistory = true)
            }
        }
        val result = async {
            coordinator.resyncNetwork(networkId, listOf(bufferId to "#chan"), source)
        }
        requestStarted.await()

        db.bufferDao().deleteBuffer(bufferId)
        releaseResponse.complete(Unit)

        assertTrue(result.await() is HistoryResyncState.Failed)
        assertEquals(null, db.bufferDao().byName(networkId, "#chan"))
    }

    @Test
    fun reconnectUsesLastCompletedSyncSoEarlyLiveMessageCannotHideGapOrDuplicate() = runTest {
        val base = 1_700_000_000_000L
        processor.process(networkId, message("seed", base + 100))
        syncPrefs.setLastSuccessfulSync(networkId, base + 150)

        // A live line can beat the reconnect catch-up coroutine into Room.
        processor.process(networkId, message("live", base + 300))
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(targets = listOf("#chan" to (base + 300)), endOfHistory = true)
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(
                    events = listOf(message("missed", base + 200), message("live", base + 300)),
                    targets = emptyList(),
                    endOfHistory = true,
                )
                else -> FakeResponse(emptyList(), emptyList())
            }
        }

        val result = coordinator.resyncNetwork(networkId, listOf(bufferId to "#chan"), source)

        assertEquals(HistoryResyncState.Updated(1), result)
        assertEquals(listOf("live", "missed", "seed"), rows().mapNotNull { it.msgid })
        assertEquals(1, rows().count { it.msgid == "live" })
        assertTrue(
            source.requests.any { it.subcommand == ChatHistoryRequest.Subcommand.LATEST },
        )
    }

    @Test
    fun targetsShortPageContinuesAndDeduplicatesTimestampOverlap() = runTest {
        db.bufferDao().deleteBuffer(bufferId)
        var targetsPage = 0
        val source = FakeSource(pageLimit = 5) { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS -> when (targetsPage++) {
                    0 -> FakeResponse(targets = listOf("New" to 300L))
                    else -> {
                        assertEquals(ChatHistorySelectors.timestamp(301), request.bound1)
                        FakeResponse(
                            targets = listOf("NEW" to 300L, "old" to 200L),
                            endOfHistory = true,
                        )
                    }
                }
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(
                    events = listOf(
                        directMessage(
                            msgid = "latest-${request.target}",
                            time = if (request.target.equals("New", ignoreCase = true)) 300L else 200L,
                            peer = request.target,
                        ),
                    ),
                    endOfHistory = true,
                )
                else -> FakeResponse(endOfHistory = true)
            }
        }

        assertEquals(HistoryResyncState.Updated(2), coordinator.resyncNetwork(networkId, emptyList(), source))
        assertEquals(2, source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.TARGETS })
        assertEquals(2, source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.LATEST })
        assertEquals(300L, syncPrefs.lastSuccessfulSync(networkId))
    }

    @Test
    fun sojuTargetsWithoutEndMarkerAdvancePastFinalOverlapToEmptyPage() = runTest {
        db.bufferDao().deleteBuffer(bufferId)
        val source = FakeSource(pageLimit = 3) { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS -> FakeResponse(
                    targets = when (request.bound1) {
                        ChatHistorySelectors.timestamp(201) ->
                            listOf("middle" to 200L, "oldest" to 100L)
                        ChatHistorySelectors.timestamp(101) -> listOf("oldest" to 100L)
                        ChatHistorySelectors.timestamp(100) -> emptyList()
                        else -> listOf("newest" to 300L, "middle" to 200L)
                    },
                )
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(endOfHistory = true)
                else -> FakeResponse(endOfHistory = true)
            }
        }

        assertTrue(coordinator.resyncNetwork(networkId, emptyList(), source) is HistoryResyncState.Incomplete)
        assertEquals(4, source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.TARGETS })
        assertEquals(3, source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.LATEST })
        assertEquals(null, syncPrefs.lastSuccessfulSync(networkId))
    }

    @Test
    fun saturatedTargetsTimestampTieReturnsIncompleteWithoutWatermark() = runTest {
        db.bufferDao().deleteBuffer(bufferId)
        val source = FakeSource(pageLimit = 2) { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(targets = listOf("#a" to 100L, "#b" to 100L))
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(endOfHistory = true)
                else -> FakeResponse(endOfHistory = true)
            }
        }

        val result = coordinator.resyncNetwork(networkId, emptyList(), source)

        assertTrue(result is HistoryResyncState.Incomplete)
        assertEquals(null, syncPrefs.lastSuccessfulSync(networkId))
        assertEquals(2, source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.TARGETS })
    }

    @Test
    fun completeNetworkPassPersistsNewestServerPageBoundary() = runTest {
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(targets = listOf("#chan" to 500L), endOfHistory = true)
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(
                    events = listOf(message("server-high-water", 700)),
                    endOfHistory = true,
                )
                else -> FakeResponse(endOfHistory = true)
            }
        }

        assertEquals(
            HistoryResyncState.Updated(1),
            coordinator.resyncNetwork(networkId, listOf(bufferId to "#chan"), source),
        )
        assertEquals(700L, syncPrefs.lastSuccessfulSync(networkId))
    }

    @Test
    fun thousandMessageAbsencePublishesOneRecentPageAndPersistsOlderGap() = runTest {
        processor.process(networkId, message("m1000", 1_000))
        syncPrefs.setLastSuccessfulSync(networkId, 1_001)
        fun page(newest: Int): List<IrcEvent> = List(50) { offset ->
            val ordinal = newest - offset
            message("m$ordinal", ordinal.toLong())
        }
        val source = FakeSource(pageLimit = 100) { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(targets = listOf("#chan" to 2_000L), endOfHistory = true)
                ChatHistoryRequest.Subcommand.LATEST -> {
                    assertEquals(50, request.limit)
                    FakeResponse(page(2_000))
                }
                else -> error("automatic recent sync must not issue ${request.subcommand}")
            }
        }

        assertEquals(
            HistoryResyncState.Updated(50),
            coordinator.resyncNetwork(networkId, listOf(bufferId to "#chan"), source),
        )
        assertEquals(51, rows(loadSize = 1_200).size)
        assertEquals("m2000", rows(loadSize = 1_200).first().msgid)
        assertEquals(
            listOf(
                ChatHistoryRequest.Subcommand.TARGETS,
                ChatHistoryRequest.Subcommand.LATEST,
            ),
            source.requests.map { it.subcommand },
        )
        val gap = db.historyGapDao().forRoom(bufferId).single()
        assertEquals(1_000L, gap.olderServerTime)
        assertEquals(1_951L, gap.newerServerTime)
    }

    @Test
    fun completeNetworkPassCannotMoveWatermarkBackward() = runTest {
        syncPrefs.setLastSuccessfulSync(networkId, 1_000)
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(targets = listOf("#chan" to 500L), endOfHistory = true)
                ChatHistoryRequest.Subcommand.AFTER -> FakeResponse(primaryMessageCount = 0)
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(
                    events = listOf(message("older-server-boundary", 700)),
                    endOfHistory = true,
                )
                else -> FakeResponse(primaryMessageCount = 0)
            }
        }

        assertEquals(
            HistoryResyncState.Updated(1),
            coordinator.resyncNetwork(networkId, listOf(bufferId to "#chan"), source),
        )
        assertEquals(1_000L, syncPrefs.lastSuccessfulSync(networkId))
    }

    @Test
    fun incompleteTargetPassPreservesPreviousNetworkWatermark() = runTest {
        processor.process(networkId, message("seed", 100))
        syncPrefs.setLastSuccessfulSync(networkId, 1_000)
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(targets = listOf("#chan" to 2_000L), endOfHistory = true)
                ChatHistoryRequest.Subcommand.AFTER -> FakeResponse(
                    events = emptyList(),
                    oldest = ChatHistoryReference("partial", 1_500),
                    newest = null,
                    primaryMessageCount = 1,
                )
                else -> FakeResponse(endOfHistory = true)
            }
        }

        val result = coordinator.resyncNetwork(networkId, listOf(bufferId to "#chan"), source)

        assertTrue(result is HistoryResyncState.Incomplete)
        assertEquals(1_000L, syncPrefs.lastSuccessfulSync(networkId))
    }

    @Test
    fun transientLatestFailurePreservesWatermark() = runTest {
        processor.process(networkId, message("seed", 100))
        syncPrefs.setLastSuccessfulSync(networkId, 1_000)
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    FakeResponse(targets = listOf("#chan" to 2_000L), endOfHistory = true)
                ChatHistoryRequest.Subcommand.LATEST -> throw IOException("temporary transport failure")
                else -> FakeResponse(endOfHistory = true)
            }
        }

        val result = coordinator.resyncNetwork(networkId, listOf(bufferId to "#chan"), source)

        assertTrue(result is HistoryResyncState.Failed)
        assertEquals(1_000L, syncPrefs.lastSuccessfulSync(networkId))
        assertEquals(
            listOf(ChatHistoryRequest.Subcommand.TARGETS, ChatHistoryRequest.Subcommand.LATEST),
            source.requests.map { it.subcommand },
        )
    }

    @Test
    fun automaticNetworkResyncContinuesPastTargetsRequestCap() = runTest {
        db.bufferDao().deleteBuffer(bufferId)
        coordinator.targetsRequestLimit = 1
        var targetRequests = 0
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS -> if (targetRequests++ == 0) {
                    FakeResponse(targets = listOf("alice" to 200L))
                } else {
                    FakeResponse(targets = listOf("bob" to 100L), endOfHistory = true)
                }
                ChatHistoryRequest.Subcommand.LATEST -> FakeResponse(
                    events = listOf(
                        directMessage(
                            msgid = "latest-${request.target}",
                            time = if (request.target == "alice") 200L else 100L,
                            peer = request.target,
                        ),
                    ),
                    endOfHistory = true,
                )
                else -> FakeResponse(endOfHistory = true)
            }
        }

        val result = coordinator.resyncNetwork(networkId, emptyList(), source)

        assertEquals(HistoryResyncState.Updated(2), result)
        assertEquals(2, source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.TARGETS })
        val latestTargets = source.requests
            .filter { it.subcommand == ChatHistoryRequest.Subcommand.LATEST }
            .map { it.target }
            .toSet()
        assertEquals(setOf("alice", "bob"), latestTargets)
        assertEquals(200L, syncPrefs.lastSuccessfulSync(networkId))
    }

    @Test
    fun equivalentAutomaticRequestsCoalesce() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val source = FakeSource {
            calls++
            entered.complete(Unit)
            release.await()
            FakeResponse(endOfHistory = true)
        }

        val first = async { coordinator.reconcileBuffer(networkId, bufferId, "#chan", source) }
        entered.await()
        val second = async { coordinator.reconcileBuffer(networkId, bufferId, "#chan", source) }
        delay(20)
        release.complete(Unit)

        assertEquals(HistoryResyncState.UpToDate, first.await())
        assertEquals(HistoryResyncState.UpToDate, second.await())
        assertEquals(1, calls)
    }

    @Test
    fun staleInitialSyncStatusCannotReplaceNewerTerminalState() {
        val terminal = mapOf<Long, HistorySyncStatus>(
            bufferId to HistorySyncStatus.Partial("newer generation"),
        )

        assertEquals(
            terminal,
            initialSyncStatusIfCurrent(
                current = terminal,
                bufferId = bufferId,
                generation = 1,
                currentGeneration = 2,
                status = HistorySyncStatus.Syncing,
            ),
        )
    }

    @Test
    fun automaticRetryBackoffRemainsBounded() {
        assertEquals(2_000L, catchUpRetryDelayMs(0))
        assertEquals(4_000L, catchUpRetryDelayMs(1))
        assertEquals(30_000L, catchUpRetryDelayMs(20))
        assertEquals(
            false,
            shouldRetryIncompleteCatchUp(
                HistoryResyncState.Incomplete(
                    inserted = 1,
                    reason = "latest already local",
                ),
            ),
        )
    }

    // -- public HistoryResyncController boundary: no live IRC session (docs/backend-neutral-xmpp-
    // rollout.md client-escape-hatch removal). This file's ircSessions fake always returns null, so
    // every public-boundary call below takes the coordinator's own "no session" early return instead
    // of ever constructing a ClientHistorySource.

    @Test
    fun reconcileBufferWithNoLiveSessionFails() = runTest {
        val buffer = db.bufferDao().observeById(bufferId)!!

        assertTrue(coordinator.reconcileBuffer(buffer) is HistoryResyncState.Failed)
    }

    @Test
    fun reconcilePendingMessageWithNoLiveSessionFails() = runTest {
        val buffer = db.bufferDao().observeById(bufferId)!!

        assertTrue(coordinator.reconcilePendingMessage(buffer) is HistoryResyncState.Failed)
    }

    @Test
    fun fetchAroundWithNoLiveSessionReturnsFalse() = runTest {
        val buffer = db.bufferDao().observeById(bufferId)!!

        assertTrue(!coordinator.fetchAround(buffer, "#chan", "some-msgid", 1_000L, 50))
        assertTrue(rows().isEmpty())
    }
}
