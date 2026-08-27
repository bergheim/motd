package io.github.trevarj.motd.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ircTarget
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BufferStoreTest {
    private lateinit var db: MotdDatabase
    private var networkId = 0L

    @Before
    fun setUp() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            db =
                Room
                    .inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            networkId =
                db.networkDao().insert(
                    NetworkEntity(
                        name = "test",
                        role = NetworkRole.DIRECT,
                        host = "irc.example",
                        port = 6697,
                        nick = "me",
                        username = "me",
                        realname = "Me",
                    ),
                )
        }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun concurrentGetOrCreate_convergesOnOneBuffer() =
        runTest {
            val store = BufferStore(db)
            val rows =
                (1..20)
                    .map {
                        async { store.getOrCreate(networkId, "#room", "#Room", BufferType.CHANNEL) }
                    }.awaitAll()

            assertEquals(1, rows.map { it.id }.distinct().size)
            assertEquals(rows.first().networkId, db.bufferDao().byName(networkId, "#room")?.networkId)
        }

    @Test
    fun independentBufferColumns_retainConcurrentUpdates() =
        runTest {
            val buffer = BufferStore(db).getOrCreate(networkId, "#room", "#Room", BufferType.CHANNEL)
            listOf(
                async { db.bufferDao().setPinned(buffer.id, true) },
                async { db.bufferDao().setMuted(buffer.id, true) },
                async { db.bufferDao().setTopic(buffer.id, "topic", "setter") },
                async { db.bufferDao().setJoined(buffer.id, true) },
                async { db.bufferDao().advanceReadMarker(buffer.id, 4_000) },
                async { db.bufferDao().setOldestFetchedTime(buffer.id, 1_000) },
                async { db.bufferDao().markHistoryComplete(buffer.id) },
            ).awaitAll()

            val updated = db.bufferDao().observeById(buffer.id)!!
            assertEquals(true, updated.pinned)
            assertEquals(true, updated.muted)
            assertEquals("topic", updated.topic)
            assertEquals("setter", updated.topicSetBy)
            assertEquals(true, updated.joined)
            assertEquals(4_000L, updated.readMarkerTime)
            assertEquals(1_000L, updated.oldestFetchedTime)
            assertEquals(true, updated.historyComplete)
        }

    @Test
    fun provisionalQueryPromotesToChannelWithoutReplacingItsRoom() =
        runTest {
            val store = BufferStore(db)
            val provisional = store.getOrCreate(networkId, "+room", "+Room", BufferType.QUERY)

            val promoted = store.getOrCreate(networkId, "+room", "+Room", BufferType.CHANNEL)

            assertEquals(provisional.id, promoted.id)
            assertEquals(BufferType.CHANNEL, promoted.type)
            assertEquals(promoted.id, store.resolveChannelRoom(networkId, "+room")?.id)
            assertEquals(null, store.resolveQueryRoom(networkId, "+room", account = null))
        }

    @Test
    fun accountBoundQueryIsNotPromotedWhenChantypesLaterClassifiesItsNameAsChannel() =
        runTest {
            val store = BufferStore(db)
            val query = store.getOrCreate(networkId, "+room", "+Room", BufferType.QUERY)
            store.bindQueryIdentity(query.id, networkId, "+room", "+Room", "account")

            val channel = store.getOrCreate(networkId, "+room", "+Room", BufferType.CHANNEL)

            assertEquals(BufferType.QUERY, db.bufferDao().observeById(query.id)?.type)
            assertEquals(BufferType.CHANNEL, channel.type)
            assertEquals("+Room", channel.ircTarget)
            assertEquals(channel.id, store.resolveChannelRoom(networkId, "+room")?.id)
            assertEquals(query.id, store.resolveQueryRoom(networkId, "+room", "account")?.id)
        }

    @Test
    fun bouncerServIsAlwaysTheConsole_whicheverTypeTheCallerAsksFor() =
        runTest {
            bouncerRoot()
            val store = BufferStore(db)

            val requestedAsQuery = store.getOrCreate(networkId, "bouncerserv", "BouncerServ", BufferType.QUERY)
            val requestedAgain =
                store.getOrCreate(
                    networkId,
                    "bouncerserv",
                    "BouncerServ",
                    BufferType.QUERY,
                    initiallyDismissed = true,
                )

            assertEquals(BufferType.SERVER, requestedAsQuery.type)
            assertEquals(requestedAsQuery.id, requestedAgain.id)
            assertEquals(BufferType.SERVER, db.bufferDao().byName(networkId, "bouncerserv")?.type)
            assertNull(store.resolveQueryRoom(networkId, "bouncerserv", account = null))
            // No disambiguated second row: the query request resolved onto the console itself.
            assertNull(db.bufferDao().byName(networkId, "bouncerserv\u0000query"))
        }

    /** Off a bouncer root the nick belongs to whoever grabbed it, so the DM room must stay a query. */
    @Test
    fun bouncerServOnAPlainNetworkIsAnOrdinaryQuery() =
        runTest {
            val store = BufferStore(db)

            val room = store.getOrCreate(networkId, "bouncerserv", "BouncerServ", BufferType.QUERY)

            assertEquals(BufferType.QUERY, room.type)
            assertEquals(room.id, store.resolveQueryRoom(networkId, "bouncerserv", account = null)?.id)
        }

    private suspend fun bouncerRoot() {
        val row = db.networkDao().byId(networkId)!!
        db.networkDao().update(row.copy(role = NetworkRole.BOUNCER_ROOT))
    }

    @Test
    fun channelRenameRetiresOldAliasAndKeepsRoster() =
        runTest {
            val store = BufferStore(db)
            val old = store.getOrCreate(networkId, "#old", "#Old", BufferType.CHANNEL)
            db.memberDao().upsert(MemberEntity(old.id, "alice", "@"))

            val renamed = store.renameChannel(networkId, "#old", "#new", "#New")!!

            assertEquals(old.id, renamed.id)
            assertEquals("#new", db.bufferDao().rawById(old.id)?.name)
            assertEquals("#New", db.bufferDao().rawById(old.id)?.displayName)
            assertNull(store.resolveChannelRoom(networkId, "#old"))
            assertEquals(old.id, store.resolveChannelRoom(networkId, "#new")?.id)
            assertEquals(listOf(MemberEntity(old.id, "alice", "@")), db.memberDao().allNow(old.id))
        }

    @Test
    fun channelRenameMergesExistingDestinationWithoutNameCollision() =
        runTest {
            val store = BufferStore(db)
            val old = store.getOrCreate(networkId, "#old", "#Old", BufferType.CHANNEL)
            val destination = store.getOrCreate(networkId, "#new", "#New", BufferType.CHANNEL)
            db.memberDao().upsert(MemberEntity(old.id, "alice", "@"))

            val renamed = store.renameChannel(networkId, "#old", "#new", "#New")!!

            assertEquals(old.id, renamed.id)
            assertEquals("#new", db.bufferDao().rawById(old.id)?.name)
            assertEquals(old.id, db.bufferDao().rawById(destination.id)?.redirectToRoomId)
            assertNull(store.resolveChannelRoom(networkId, "#old"))
            assertEquals(old.id, store.resolveChannelRoom(networkId, "#new")?.id)
            assertEquals(listOf(MemberEntity(old.id, "alice", "@")), db.memberDao().allNow(old.id))
        }
}
