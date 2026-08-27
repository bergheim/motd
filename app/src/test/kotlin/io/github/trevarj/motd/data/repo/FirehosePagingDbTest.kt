package io.github.trevarj.motd.data.repo

import androidx.paging.PagingSource
import androidx.sqlite.db.SupportSQLiteQuery
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.EventRedirectEntity
import io.github.trevarj.motd.data.db.FirehoseRow
import io.github.trevarj.motd.data.db.MessageDao
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.message
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.visibility.FirehoseNetwork
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.data.visibility.firehosePagingQuery
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The firehose query against a real database: ordering, fool scoping, and what never appears. */
@RunWith(RobolectricTestRunner::class)
class FirehosePagingDbTest {
    private lateinit var db: MotdDatabase
    private var networkA = 0L
    private var networkB = 0L
    private var bufferA = 0L
    private var bufferB = 0L

    @Before
    fun setUp() =
        runTest {
            db = inMemoryDb()
            networkA = db.networkDao().insert(network(name = "libera"))
            networkB = db.networkDao().insert(network(name = "oftc"))
            bufferA = db.bufferDao().insert(buffer(networkA, "#a"))
            bufferB = db.bufferDao().insert(buffer(networkB, "#b"))
        }

    @After
    fun tearDown() = db.close()

    @Test
    fun interleavesConversationRowsAcrossNetworksAndScopesFoolsToTheirOwnNetwork() =
        runTest {
            // The configured fool "Ann[ie]" folds to "ann{ie}" under RFC1459 (brackets fold) and
            // stays "ann[ie]" under ASCII. Both troll rows carry the stored actor "ann{ie}", so the
            // mute applies on network A and not on network B.
            db.messageDao().insertAll(
                listOf(
                    message(bufferA, "a-oldest", sender = "alice", serverTime = 100, dedupKey = "a1"),
                    message(bufferB, "b-mid", sender = "bob", serverTime = 200, dedupKey = "b1"),
                    message(
                        bufferA,
                        "a-join",
                        sender = "carol",
                        serverTime = 250,
                        dedupKey = "a-join",
                        kind = MessageKind.JOIN,
                    ),
                    message(bufferA, "troll-A", sender = "ann{ie}", serverTime = 300, dedupKey = "a-troll"),
                    message(bufferB, "troll-B", sender = "ann{ie}", serverTime = 350, dedupKey = "b-troll"),
                    message(bufferB, "b-newest", sender = "bob", serverTime = 400, dedupKey = "b2"),
                ),
            )
            // COLLAPSE, not HIDE: the firehose mutes fools in either mode.
            val spec = MessageVisibilitySpec(fools = setOf("Ann[ie]"), foolsMode = FoolsMode.COLLAPSE)
            val networks =
                listOf(
                    FirehoseNetwork(networkA, IrcIdentityRules.from("rfc1459", null)),
                    FirehoseNetwork(networkB, IrcIdentityRules.from("ascii", null)),
                )

            val rows = db.messageDao().firehoseRows(firehosePagingQuery(spec, networks))

            assertEquals(
                listOf("b-newest", "troll-B", "b-mid", "a-oldest"),
                rows.map { it.message.text },
            )
            val newest = rows.first()
            assertEquals("#b", newest.bufferDisplayName)
            assertEquals("oftc", newest.networkName)
        }

    @Test
    fun dropsRedirectedRowsAndRoomsThatLeftTheStream() =
        runTest {
            val dismissed = db.bufferDao().insert(buffer(networkA, "eve", type = BufferType.QUERY).copy(dismissed = true))
            val archived = db.bufferDao().insert(buffer(networkA, "#archived").copy(archived = true))
            val closing = db.bufferDao().insert(buffer(networkA, "#closing").copy(pendingCloseAt = 5_000))
            val redirected = db.bufferDao().insert(buffer(networkA, "#renamed").copy(redirectToRoomId = bufferA))
            val console = db.bufferDao().insert(buffer(networkB, "libera", type = BufferType.SERVER))
            val ids =
                db.messageDao().insertAll(
                    listOf(
                        message(bufferA, "canonical", serverTime = 100, dedupKey = "c1"),
                        message(bufferA, "lost-the-merge", serverTime = 110, dedupKey = "c2"),
                        message(dismissed, "dismissed", serverTime = 120, dedupKey = "d1"),
                        message(archived, "archived", serverTime = 130, dedupKey = "d2"),
                        message(closing, "closing", serverTime = 140, dedupKey = "d3"),
                        message(redirected, "redirected", serverTime = 150, dedupKey = "d4"),
                        message(console, "console", serverTime = 160, dedupKey = "d5"),
                    ),
                )
            db.canonicalTimelineDao().upsertEventRedirect(
                EventRedirectEntity(losingEventId = ids[1], canonicalEventId = ids[0]),
            )

            val rows =
                db.messageDao().firehoseRows(
                    firehosePagingQuery(MessageVisibilitySpec(), networks = emptyList()),
                )

            assertEquals(listOf("canonical"), rows.map { it.message.text })
        }
}

// Drain the firehose PagingSource into a list without Paging machinery (mirrors MessageDaoTest).
private suspend fun MessageDao.firehoseRows(query: SupportSQLiteQuery): List<FirehoseRow> {
    val result =
        firehosePagingSource(query).load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 100, placeholdersEnabled = false),
        )
    return (result as PagingSource.LoadResult.Page).data
}
