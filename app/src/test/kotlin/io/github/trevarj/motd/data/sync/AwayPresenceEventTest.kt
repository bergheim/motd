package io.github.trevarj.motd.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.IrcMessage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Away state becomes low-noise presence rows only for live events. */
@RunWith(RobolectricTestRunner::class)
class AwayPresenceEventTest {
    private lateinit var db: MotdDatabase
    private lateinit var processor: EventProcessor
    private var networkId: Long = 0

    @Before
    fun setUp() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            db =
                Room
                    .inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            processor = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop)
            networkId =
                db.networkDao().insert(
                    NetworkEntity(
                        name = "libera",
                        role = NetworkRole.DIRECT,
                        host = "irc.libera.chat",
                        port = 6697,
                        nick = "me",
                        username = "me",
                        realname = "Me",
                    ),
                )
            processor.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459"))
        }

    @After fun tearDown() {
        db.close()
    }

    private suspend fun serverBuffer() = db.bufferDao().byName(networkId, "*")

    private suspend fun rows(bufferName: String) =
        db
            .messageDao()
            .pagingSource(db.bufferDao().byName(networkId, bufferName)!!.id)
            .load(
                androidx.paging.PagingSource.LoadParams
                    .Refresh(null, 100, false),
            ).let { (it as androidx.paging.PagingSource.LoadResult.Page).data }

    private suspend fun serverRows() = rows("*")

    private fun ctx(time: Long) = MessageContext(null, time, null, null, null)

    @Test
    fun liveSelfAway_insertsPresenceLines() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.SelfAwayChanged(isAway = true, text = "You have been marked as being away"),
            )
            processor.process(
                networkId,
                IrcEvent.SelfAwayChanged(isAway = false, text = "You are no longer marked as being away"),
            )
            val rows = serverRows()
            assertEquals(listOf(MessageKind.BACK, MessageKind.AWAY), rows.map { it.kind })
            assertEquals(listOf("You are back", "You are away"), rows.map { it.text })
            rows.forEach { assertEquals(true, it.isSelf) }
        }

    @Test
    fun otherUserAway_fansOutToSharedChannels_withoutDuplicatingSelf() =
        runTest {
            for (channel in listOf("#one", "#two")) {
                processor.process(networkId, IrcEvent.Joined(ctx(1), "me", channel, null, null, true))
                processor.process(networkId, IrcEvent.Joined(ctx(2), "alice", channel, null, null, false))
            }

            processor.process(networkId, IrcEvent.AwayChanged("alice", "lunch"))
            processor.process(networkId, IrcEvent.AwayChanged("alice", null))
            processor.process(networkId, IrcEvent.AwayChanged("me", "brb"))

            for (channel in listOf("#one", "#two")) {
                val presence = rows(channel).filter { it.kind == MessageKind.AWAY || it.kind == MessageKind.BACK }
                assertEquals(listOf(MessageKind.BACK, MessageKind.AWAY), presence.map { it.kind })
                assertEquals(listOf("alice is back", "alice is away (lunch)"), presence.map { it.text })
            }
        }

    @Test
    fun historySelfAway_insertsNothing() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.HistoryBatch(
                    target = "#chan",
                    events = listOf(IrcEvent.SelfAwayChanged(isAway = true, text = "You have been marked as being away")),
                ),
            )
            assertNull(serverBuffer())
        }

    @Test
    fun pushSelfAway_insertsNothing() =
        runTest {
            processor.processPush(
                networkId,
                IrcEvent.SelfAwayChanged(isAway = true, text = "You have been marked as being away"),
            )
            assertNull(serverBuffer())
        }

    @Test
    fun awayNumericsAreNoLongerRawServerInfo() =
        runTest {
            // The Raw path must not double-render them now that the typed branch owns the line.
            processor.process(
                networkId,
                IrcEvent.Raw(IrcMessage(command = "306", params = listOf("me", "You have been marked as being away"))),
            )
            processor.process(
                networkId,
                IrcEvent.Raw(IrcMessage(command = "305", params = listOf("me", "You are no longer marked as being away"))),
            )
            assertNull(serverBuffer())
        }
}
