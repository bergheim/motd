package io.github.trevarj.motd.xmpp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.Protocol
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.sync.CanonicalTimelineStore
import io.github.trevarj.motd.data.sync.MessageNotifier
import io.github.trevarj.motd.data.sync.TypingTrackerImpl
import io.github.trevarj.motd.irc.event.IrcEvent
import kotlinx.coroutines.test.runTest
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
 * XMPP notification wiring + read-marker clearing (Tier-1 parity). Uses a recording
 * [MessageNotifier] to assert exactly which incoming messages present a notification and that
 * reading a buffer advances its local anchor (clearing the badge, cancelling the notification).
 */
@RunWith(RobolectricTestRunner::class)
class XmppNotificationTest {
    private lateinit var db: MotdDatabase
    private lateinit var p: XmppEventProcessor
    private lateinit var notifier: RecordingNotifier
    private var nid: Long = 0

    private class Posted(
        val bufferId: Long,
        val type: BufferType,
        val hasMention: Boolean,
        val eventId: Long,
        val sender: String,
        val text: String,
    )

    private class RecordingNotifier : MessageNotifier {
        val posted = mutableListOf<Posted>()
        val reads = mutableListOf<Pair<Long, TimelineAnchor>>()

        override suspend fun onIncoming(
            networkId: Long, bufferId: Long, type: BufferType, hasMention: Boolean, message: IrcEvent.ChatMessage,
        ) = Unit

        override suspend fun onCanonicalIncoming(
            networkId: Long, bufferId: Long, type: BufferType, hasMention: Boolean, eventId: Long, message: IrcEvent.ChatMessage,
        ) {
            posted += Posted(bufferId, type, hasMention, eventId, message.source.nick, message.text)
        }

        override suspend fun onRead(bufferId: Long, anchor: TimelineAnchor) {
            reads += bufferId to anchor
        }
    }

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
            .allowMainThreadQueries().build()
        notifier = RecordingNotifier()
        p = XmppEventProcessor(db, TypingTrackerImpl(), notifier, CanonicalTimelineStore(db))
        nid = db.networkDao().insert(
            NetworkEntity(
                name = "glvortex", protocol = Protocol.XMPP, role = NetworkRole.DIRECT,
                host = "xmpp.glvortex.net", port = 5222,
                nick = "me", username = "me", realname = "Me",
                jid = "me@glvortex.net",
            ),
        )
    }

    @After fun tearDown() { db.close() }

    @Test
    fun directMessage_notifies() = runTest {
        p.process(nid, XmppEvent.ChatMessage("alice@example.net", "ping", "s1", null))
        val post = notifier.posted.single()
        assertEquals(BufferType.QUERY, post.type)
        assertFalse(post.hasMention) // DMs notify regardless of mention
        assertEquals("ping", post.text)
        assertEquals("alice@example.net", post.sender)
    }

    @Test
    fun mucMention_notifies_butPlainChatterDoesNot() = runTest {
        p.process(nid, XmppEvent.MucSelfJoined("room@conf.x.net", listOf("me")))
        p.process(nid, XmppEvent.MucMessage("room@conf.x.net", "alice", "hey me!", "s2", null))
        p.process(nid, XmppEvent.MucMessage("room@conf.x.net", "alice", "just chatter", "s3", null))
        val post = notifier.posted.single()
        assertEquals(BufferType.CHANNEL, post.type)
        assertTrue(post.hasMention)
        assertEquals("hey me!", post.text)
    }

    @Test
    fun ownMucMessage_neverNotifies() = runTest {
        p.process(nid, XmppEvent.MucSelfJoined("room@conf.x.net", listOf("me")))
        // A self line that is not a pending reflection still must not notify (even naming our nick).
        p.process(nid, XmppEvent.MucMessage("room@conf.x.net", "me", "note to me about me", "s9", null))
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun replayedHistory_isSuppressed() = runTest {
        p.process(nid, XmppEvent.MucSelfJoined("room@conf.x.net", listOf("me")))
        // A delay-stamped stanza is join-backlog / catch-up history, not a live message.
        p.process(nid, XmppEvent.MucMessage("room@conf.x.net", "alice", "old mention of me", "s4", 1_000L))
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun duplicateStanza_notifiesOnce() = runTest {
        p.process(nid, XmppEvent.ChatMessage("alice@example.net", "ping", "s1", null))
        p.process(nid, XmppEvent.ChatMessage("alice@example.net", "ping", "s1", null))
        assertEquals(1, notifier.posted.size)
    }

    @Test
    fun markReadLocal_advancesAnchor_andCancelsViaOnRead() = runTest {
        p.process(nid, XmppEvent.ChatMessage("alice@example.net", "ping", "s1", null))
        val before = db.bufferDao().byName(nid, "alice@example.net")!!
        assertNull(before.localReadAnchorTime) // badge is unread before reading

        val eventId = notifier.posted.single().eventId
        val row = db.messageDao().byId(eventId)!!
        p.markReadLocal(before.id, TimelineAnchor(row.serverTime, row.id))

        val after = db.bufferDao().byName(nid, "alice@example.net")!!
        assertEquals(row.serverTime, after.localReadAnchorTime) // anchor advanced → badge clears
        assertEquals(before.id, notifier.reads.single().first) // notification cancellation fired
    }
}
