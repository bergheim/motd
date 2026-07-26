package io.github.trevarj.motd.data.repo

import androidx.paging.PagingSource
import androidx.sqlite.db.SupportSQLiteQuery
import io.github.trevarj.motd.data.db.FirehoseRow
import io.github.trevarj.motd.data.db.MessageDao
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.Protocol
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FirehosePagingDbTest {
    private lateinit var db: MotdDatabase
    private var networkA = 0L
    private var networkB = 0L
    private var bufferA = 0L
    private var bufferB = 0L

    @Before
    fun setUp() = runTest {
        db = inMemoryDb()
        networkA = db.networkDao().insert(network(name = "libera"))
        networkB = db.networkDao().insert(network(name = "oftc"))
        bufferA = db.bufferDao().insert(buffer(networkA, "#a"))
        bufferB = db.bufferDao().insert(buffer(networkB, "#b"))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun interleavesConversationRowsReverseChronoDroppingJoinsAndNetworkScopedFools() = runTest {
        // The configured fool "Ann[ie]" folds to "ann{ie}" under RFC1459 (brackets folded) but stays
        // "ann[ie]" under ASCII. Both troll rows carry the identical stored actor "ann{ie}", so the
        // fool is muted only on network A (RFC1459) and remains visible on network B (ASCII).
        db.messageDao().insertAll(
            listOf(
                message(bufferA, "a-oldest", sender = "alice", serverTime = 100, dedupKey = "a1"),
                message(bufferB, "b-mid", sender = "bob", serverTime = 200, dedupKey = "b1"),
                message(
                    bufferA, "a-join", sender = "carol", serverTime = 250,
                    dedupKey = "a-join", kind = MessageKind.JOIN,
                ),
                message(bufferA, "troll-A", sender = "ann{ie}", serverTime = 300, dedupKey = "a-troll"),
                message(bufferB, "troll-B", sender = "ann{ie}", serverTime = 350, dedupKey = "b-troll"),
                message(bufferB, "b-newest", sender = "bob", serverTime = 400, dedupKey = "b2"),
            ),
        )
        val spec = MessageVisibilitySpec(fools = setOf("Ann[ie]"), foolsMode = FoolsMode.HIDE)
        val networks = listOf(
            FirehoseNetwork(networkA, IrcIdentityRules.from("rfc1459", null)),
            FirehoseNetwork(networkB, IrcIdentityRules.from("ascii", null)),
        )

        val rows = db.messageDao().firehoseList(firehosePagingQuery(spec, networks))

        // Reverse-chronological, JOIN dropped, troll dropped on A but kept on B.
        assertEquals(
            listOf("b-newest", "troll-B", "b-mid", "a-oldest"),
            rows.map { it.message.text },
        )
        assertFalse(rows.any { it.message.text == "a-join" })
        assertFalse(rows.any { it.message.text == "troll-A" })
        assertTrue(rows.any { it.message.text == "troll-B" })

        // Projection tags populated from the joins.
        val newest = rows.first()
        assertEquals("#b", newest.bufferDisplayName)
        assertEquals("oftc", newest.networkName)
        assertEquals(Protocol.IRC, newest.networkProtocol)
    }
}

// Drain the firehose PagingSource into a list without Paging machinery (mirrors MessageDaoTest).
private suspend fun MessageDao.firehoseList(query: SupportSQLiteQuery): List<FirehoseRow> {
    val result = firehosePagingSource(query).load(
        PagingSource.LoadParams.Refresh(
            key = null,
            loadSize = 100,
            placeholdersEnabled = false,
        ),
    )
    return (result as PagingSource.LoadResult.Page).data
}
