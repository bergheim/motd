package io.github.trevarj.motd.data.visibility

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.EventRedirectEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkIdentityEntity
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.message
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.prefs.FoolsMode
import java.util.Collections
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MessageVisibilityReaderTest {
    private lateinit var db: MotdDatabase
    private lateinit var reader: MessageVisibilityReader
    private val observedQueries = Collections.synchronizedList(mutableListOf<String>())
    private var networkId = 0L
    private var bufferId = 0L

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback(
                { sql, _ -> observedQueries += sql },
                Executor(Runnable::run),
            )
            .build()
        reader = MessageVisibilityReader(db)
        networkId = db.networkDao().insert(network())
        bufferId = db.bufferDao().insert(buffer(networkId, "#test", readMarkerTime = 50))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun chatListFallsBackPastFoolAndExcludesFoolUnreadAndMention() = runTest {
        db.messageDao().insertAll(
            listOf(
                message(bufferId, "meaningful", sender = "bob", serverTime = 100, dedupKey = "good"),
                message(
                    bufferId,
                    "ignored fool",
                    sender = "alice",
                    serverTime = 200,
                    dedupKey = "fool",
                    hasMention = true,
                ),
            ),
        )
        val raw = db.bufferDao().observeChatList().first()
        val resolved = reader.resolveChatList(raw, spec(FoolsMode.COLLAPSE)).single()

        assertEquals("meaningful", resolved.lastMessageText)
        assertEquals("bob", resolved.lastMessageSender)
        assertEquals(100L, resolved.lastMessageTime)
        assertEquals(1, resolved.unreadCount)
        assertEquals(0, resolved.mentionCount)
    }

    @Test
    fun chatListReevaluatesCanonicalIdentityAfterMessageEnrichment() = runTest {
        val eventId = db.messageDao().insertAll(
            listOf(
                message(
                    bufferId,
                    "identity arrives later",
                    sender = "renamed-user",
                    serverTime = 200,
                    dedupKey = "identity-enrichment",
                ),
            ),
        ).single()
        val spec = MessageVisibilitySpec(fools = setOf("stable-account"))
        val raw = db.bufferDao().observeChatList().first()
        assertEquals("identity arrives later", reader.resolveChatList(raw, spec).single().lastMessageText)

        val initial = db.messageDao().byId(eventId)!!
        db.messageDao().update(initial.copy(senderAccount = "stable-account"))

        val enrichedRaw = db.bufferDao().observeChatList().first()
        val enriched = reader.resolveChatList(enrichedRaw, spec).single()
        assertNull(enriched.lastMessageText)
        assertEquals(0, enriched.unreadCount)
    }

    @Test
    fun timelineIndexIncludesCollapsedFoolButNotHiddenFoolOrHiddenJoin() = runTest {
        val ids = db.messageDao().insertAll(
            listOf(
                message(bufferId, "target", sender = "bob", serverTime = 100, dedupKey = "target"),
                message(bufferId, "fool", sender = "alice", serverTime = 200, dedupKey = "fool"),
                message(
                    bufferId,
                    "join",
                    sender = "carol",
                    serverTime = 300,
                    dedupKey = "join",
                    kind = MessageKind.JOIN,
                ),
            ),
        )
        assertEquals(
            1,
            reader.countTimelineNewer(
                bufferId,
                100,
                ids[0],
                spec(FoolsMode.COLLAPSE, showJoinPartQuit = false),
            ),
        )
        assertEquals(
            0,
            reader.countTimelineNewer(
                bufferId,
                100,
                ids[0],
                spec(FoolsMode.HIDE, showJoinPartQuit = false),
            ),
        )
    }

    @Test
    fun viewportUnreadCountUsesPolicyTimelinePositionsWithoutPagingSnapshots() = runTest {
        db.messageDao().insertAll(
            listOf(
                message(bufferId, "older", sender = "bob", serverTime = 100, dedupKey = "older"),
                message(bufferId, "fool", sender = "alice", serverTime = 200, dedupKey = "fool"),
                message(bufferId, "own", sender = "me", serverTime = 300, dedupKey = "own").copy(isSelf = true),
                message(bufferId, "newer", sender = "bob", serverTime = 400, dedupKey = "newer"),
                message(
                    bufferId,
                    "join",
                    sender = "carol",
                    serverTime = 500,
                    dedupKey = "join",
                    kind = MessageKind.JOIN,
                ),
            ),
        )
        val marker = io.github.trevarj.motd.data.db.TimelineAnchor(50, 0)

        assertEquals(
            1,
            reader.countVisibleUnreadInTimelinePrefix(
                bufferId,
                beforeIndex = 4,
                after = marker,
                maxCount = 100,
                spec = spec(FoolsMode.COLLAPSE),
            ),
        )
        assertEquals(
            2,
            reader.countVisibleUnreadInTimelinePrefix(
                bufferId,
                beforeIndex = 4,
                after = marker,
                maxCount = 100,
                spec = spec(FoolsMode.HIDE),
            ),
        )
    }

    @Test
    fun deepViewportUnreadCountCapsWithoutFalseZeroAfterPageDrops() = runTest {
        db.messageDao().insertAll(
            List(600) { index ->
                message(
                    bufferId,
                    "message-$index",
                    sender = "bob",
                    serverTime = index.toLong() + 1,
                    dedupKey = "deep-$index",
                )
            },
        )

        assertEquals(
            100,
            reader.countVisibleUnreadInTimelinePrefix(
                bufferId,
                beforeIndex = 550,
                after = io.github.trevarj.motd.data.db.TimelineAnchor(0, 0),
                maxCount = 100,
                spec = MessageVisibilitySpec(),
            ),
        )
    }

    @Test
    fun readerUsesPersistedNetworkCasemapForFoolPredicates() = runTest {
        db.networkIdentityDao().upsert(
            NetworkIdentityEntity(networkId, caseMapping = "ascii"),
        )
        val ids = db.messageDao().insertAll(
            listOf(
                message(
                    bufferId,
                    "ascii fool",
                    sender = "[Alice",
                    serverTime = 100,
                    dedupKey = "ascii-fool",
                ).copy(normalizedActor = "[alice"),
                message(bufferId, "target", sender = "bob", serverTime = 50, dedupKey = "target"),
            ),
        )
        val spec = MessageVisibilitySpec(
            fools = setOf("[alice"),
            foolsMode = FoolsMode.HIDE,
        )

        assertEquals(0, reader.countTimelineNewer(bufferId, 50, ids[1], spec))
        assertEquals(ids[1], reader.latestEffectiveAnchor(bufferId, spec)?.id)
    }

    @Test
    fun savedAnchorAndUnreadSkipFoolRowsAndRawTailStillAdvances() = runTest {
        val ids = db.messageDao().insertAll(
            listOf(
                message(bufferId, "older", sender = "bob", serverTime = 100, dedupKey = "older"),
                message(bufferId, "fool", sender = "alice", serverTime = 200, dedupKey = "fool"),
                message(
                    bufferId,
                    "part",
                    sender = "carol",
                    serverTime = 300,
                    dedupKey = "part",
                    kind = MessageKind.PART,
                ),
            ),
        )
        val spec = spec(FoolsMode.COLLAPSE, showJoinPartQuit = false)
        val anchor = reader.resolveSavedAnchor(bufferId, null, 200, ids[1], spec)

        assertEquals(ids[0], anchor?.id)
        assertEquals(
            100L,
            reader.firstVisibleUnreadAnchor(
                bufferId,
                io.github.trevarj.motd.data.db.TimelineAnchor(50, 0),
                spec,
            )?.serverTime,
        )
        assertEquals(
            io.github.trevarj.motd.data.db.TimelineAnchor(300L, ids[2]),
            reader.latestRawAnchor(bufferId),
        )
    }

    @Test
    fun firstUnreadRespectsTheBoundedRecentIsland() = runTest {
        val ids = db.messageDao().insertAll(
            listOf(
                message(bufferId, "older unread", sender = "bob", serverTime = 100, dedupKey = "old"),
                message(bufferId, "recent unread", sender = "bob", serverTime = 900, dedupKey = "recent"),
            ),
        )
        val recentBoundary = io.github.trevarj.motd.data.db.TimelineAnchor(900, ids[1])

        val result = reader.firstVisibleUnreadAnchor(
            bufferId = bufferId,
            after = io.github.trevarj.motd.data.db.TimelineAnchor(50, 0),
            spec = spec(FoolsMode.COLLAPSE),
            bounds = io.github.trevarj.motd.data.visibility.MessageWindowBounds(
                lowerBoundary = recentBoundary,
            ),
        )

        assertEquals(recentBoundary, result)
    }

    @Test
    fun rawTailObserverFollowsRedirectChangedWithoutMessageInvalidation() = runTest {
        val networkId = db.bufferDao().observeById(bufferId)!!.networkId
        val winnerId = db.bufferDao().insert(buffer(networkId, "#winner"))
        val eventId = db.messageDao().insertAll(
            listOf(message(winnerId, "winner tail", serverTime = 700, dedupKey = "winner-tail")),
        ).single()
        val observedInitialState = CompletableDeferred<Unit>()
        val redirected = async {
            reader.observeLatestRawAnchor(bufferId)
                .onEach { if (it == null) observedInitialState.complete(Unit) }
                .first { it?.eventId == eventId }
        }

        observedInitialState.await()
        db.roomAliasDao().markRedirect(bufferId, winnerId)

        assertEquals(
            io.github.trevarj.motd.data.db.TimelineAnchor(700, eventId),
            redirected.await(),
        )
    }

    @Test
    fun msgidlessSavedAnchorFollowsRedirectAndCorrectedTimestamp() = runTest {
        val winnerId = db.messageDao().insertAll(
            listOf(
                message(
                    bufferId,
                    "history",
                    sender = "bob",
                    serverTime = 500,
                    dedupKey = "winner",
                ),
            ),
        ).single()
        val loserId = db.messageDao().insertAll(
            listOf(
                message(
                    bufferId,
                    "live",
                    sender = "bob",
                    serverTime = 200,
                    dedupKey = "loser",
                ),
            ),
        ).single()
        db.canonicalTimelineDao().upsertEventRedirect(EventRedirectEntity(loserId, winnerId))
        db.messageDao().deleteById(loserId)

        val anchor = reader.resolveSavedAnchor(
            bufferId = bufferId,
            msgid = null,
            serverTime = 200,
            id = loserId,
            spec = spec(FoolsMode.COLLAPSE),
        )

        assertEquals(winnerId, anchor?.id)
        assertEquals(500L, anchor?.serverTime)
    }

    @Test
    fun foolOnlyBufferHasNoAnchorOrVisibleUnread() = runTest {
        val id = db.messageDao().insertAll(
            listOf(message(bufferId, "fool", sender = "alice", serverTime = 100, dedupKey = "fool")),
        ).single()
        val spec = spec(FoolsMode.COLLAPSE)
        val resolved = reader.resolveChatList(db.bufferDao().observeChatList().first(), spec).single()

        assertNull(reader.resolveSavedAnchor(bufferId, null, 100, id, spec))
        assertNull(
            reader.firstVisibleUnreadAnchor(
                bufferId,
                io.github.trevarj.motd.data.db.TimelineAnchor(50, 0),
                spec,
            ),
        )
        assertNull(resolved.lastMessageText)
        assertNull(resolved.lastMessageTime)
        assertEquals(0, resolved.unreadCount)
        assertEquals(0, resolved.mentionCount)
    }

    @Test
    fun longFoolTailUsesTargetedQueriesForPreviewEffectiveBottomAndSavedAnchor() = runTest {
        val meaningfulId = db.messageDao().insertAll(
            listOf(message(bufferId, "meaningful", sender = "bob", serverTime = 1, dedupKey = "good")),
        ).single()
        var newestFoolId = 0L
        repeat(5) { chunk ->
            newestFoolId = db.messageDao().insertAll(
                List(1_000) { offset ->
                    val index = chunk * 1_000 + offset
                    message(
                        bufferId,
                        "fool-$index",
                        sender = "alice",
                        serverTime = 2L + index,
                        dedupKey = "fool-$index",
                    )
                },
            ).last()
        }

        val raw = db.bufferDao().observeChatList().first()
        observedQueries.clear()
        val resolved = reader.resolveChatList(raw, spec(FoolsMode.HIDE)).single()
        assertEquals("meaningful", resolved.lastMessageText)
        assertEquals(meaningfulId, reader.latestEffectiveAnchor(bufferId, spec(FoolsMode.HIDE))?.id)
        assertEquals(
            meaningfulId,
            reader.resolveSavedAnchor(
                bufferId = bufferId,
                msgid = null,
                serverTime = 5_001,
                id = newestFoolId,
                spec = spec(FoolsMode.HIDE),
            )?.id,
        )
        assertEquals(0, reader.countTimelineNewer(bufferId, 1, meaningfulId, spec(FoolsMode.HIDE)))
        assertFalse(observedQueries.any { it.contains(" OFFSET ", ignoreCase = true) })
    }

    @Test
    fun nearestUnreadMentionBelowViewportWalksNewestToOldestAndExcludesFoolsAndRead() = runTest {
        val ids = db.messageDao().insertAll(
            listOf(
                message(bufferId, "old", sender = "bob", serverTime = 100, dedupKey = "old"),
                message(bufferId, "fool", sender = "alice", serverTime = 200, dedupKey = "fool", hasMention = true),
                message(bufferId, "mention-a", sender = "bob", serverTime = 300, dedupKey = "m-a", hasMention = true),
                message(bufferId, "mention-b", sender = "bob", serverTime = 400, dedupKey = "m-b", hasMention = true),
                message(bufferId, "plain", sender = "bob", serverTime = 500, dedupKey = "plain"),
            ),
        )
        val marker = io.github.trevarj.motd.data.db.TimelineAnchor(50, 0)
        val spec = spec(FoolsMode.COLLAPSE)
        // Reversed indices: 0=plain(500), 1=mention-b(400), 2=mention-a(300), 3=fool(200), 4=old(100).

        // firstVisible=3 => below viewport = {0,1,2}; nearest unread mention = mention-a (index 2).
        assertEquals(
            2,
            reader.nearestUnreadMentionBelowIndex(bufferId, beforeIndex = 3, after = marker, spec = spec),
        )
        // firstVisible=2 => below = {0,1}; nearest = mention-b (index 1). Repeated taps walk downward.
        assertEquals(
            1,
            reader.nearestUnreadMentionBelowIndex(bufferId, beforeIndex = 2, after = marker, spec = spec),
        )
        // firstVisible=1 => below = {0}; plain(500) has no mention => null (fall through to bottom).
        assertNull(reader.nearestUnreadMentionBelowIndex(bufferId, beforeIndex = 1, after = marker, spec = spec))
        // firstVisible=4 => below = {0,1,2,3}; fool(200) is timeline-visible but visibleUnread excludes
        // it, so the nearest mention is still mention-a (index 2), never the fool.
        assertEquals(
            2,
            reader.nearestUnreadMentionBelowIndex(bufferId, beforeIndex = 4, after = marker, spec = spec),
        )
        // Read marker advanced past mention-b(400): neither mention is unread-after-marker, so null.
        val pastMarker = io.github.trevarj.motd.data.db.TimelineAnchor(450, ids[3])
        assertNull(
            reader.nearestUnreadMentionBelowIndex(bufferId, beforeIndex = 3, after = pastMarker, spec = spec),
        )
        // No viewport prefix to search => null without querying.
        assertNull(reader.nearestUnreadMentionBelowIndex(bufferId, beforeIndex = 0, after = marker, spec = spec))
    }

    @Test
    fun viewportQueriesUseTheFocusedHistoryIslandBounds() = runTest {
        val ids = db.messageDao().insertAll(
            listOf(
                message(bufferId, "old plain", sender = "bob", serverTime = 200, dedupKey = "old-plain"),
                message(
                    bufferId,
                    "old mention",
                    sender = "bob",
                    serverTime = 300,
                    dedupKey = "old-mention",
                    hasMention = true,
                ),
                message(bufferId, "recent plain", sender = "bob", serverTime = 900, dedupKey = "recent-plain"),
                message(
                    bufferId,
                    "recent mention",
                    sender = "bob",
                    serverTime = 1_000,
                    dedupKey = "recent-mention",
                    hasMention = true,
                ),
            ),
        )
        val marker = io.github.trevarj.motd.data.db.TimelineAnchor(50, 0)
        val bounds = MessageWindowBounds(
            upperBoundary = io.github.trevarj.motd.data.db.TimelineAnchor(300, ids[1]),
        )

        assertEquals(
            2,
            reader.countVisibleUnreadInTimelinePrefix(
                bufferId,
                beforeIndex = 2,
                after = marker,
                maxCount = 100,
                spec = MessageVisibilitySpec(),
                bounds = bounds,
            ),
        )
        assertEquals(
            ids[1],
            reader.nearestUnreadMentionBelow(
                bufferId,
                beforeIndex = 2,
                after = marker,
                spec = MessageVisibilitySpec(),
                bounds = bounds,
            )?.id,
        )
    }

    private fun spec(mode: FoolsMode, showJoinPartQuit: Boolean = true) = MessageVisibilitySpec(
        showJoinPartQuit = showJoinPartQuit,
        fools = setOf("alice"),
        foolsMode = mode,
    )
}
