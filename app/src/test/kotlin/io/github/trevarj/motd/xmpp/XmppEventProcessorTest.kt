package io.github.trevarj.motd.xmpp

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.Protocol
import io.github.trevarj.motd.data.sync.MessageNotifier
import io.github.trevarj.motd.data.sync.TypingTrackerImpl
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

@RunWith(RobolectricTestRunner::class)
class XmppEventProcessorTest {
    private lateinit var db: MotdDatabase
    private lateinit var p: XmppEventProcessor
    private var nid: Long = 0

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
            .allowMainThreadQueries().build()
        p = XmppEventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop)
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

    private suspend fun rows(bufferId: Long): List<MessageEntity> =
        db.messageDao().pagingSource(bufferId).load(
            PagingSource.LoadParams.Refresh(null, 100, false),
        ).let { (it as PagingSource.LoadResult.Page).data }

    @Test
    fun chatMessage_createsQueryBuffer_andRow() = runTest {
        p.process(nid, XmppEvent.ChatMessage("Alice@Example.net", "hi", "s1", null))
        val buf = db.bufferDao().byName(nid, "alice@example.net")!!
        assertEquals(BufferType.QUERY, buf.type)
        assertEquals("hi", rows(buf.id).single().text)
        assertFalse(rows(buf.id).single().hasMention)
    }

    @Test
    fun duplicateStanzaId_sameSender_isDeduped() = runTest {
        p.process(nid, XmppEvent.ChatMessage("alice@example.net", "hi", "s1", null))
        p.process(nid, XmppEvent.ChatMessage("alice@example.net", "hi again", "s1", null))
        val buf = db.bufferDao().byName(nid, "alice@example.net")!!
        assertEquals(1, rows(buf.id).size)
        assertEquals("hi", rows(buf.id).single().text)
    }

    @Test
    fun sameStanzaId_differentSenders_bothKept() = runTest {
        p.process(nid, XmppEvent.ChatMessage("alice@example.net", "from alice", "s1", null))
        p.process(nid, XmppEvent.ChatMessage("bob@example.net", "from bob", "s1", null))
        val aliceBuf = db.bufferDao().byName(nid, "alice@example.net")!!
        val bobBuf = db.bufferDao().byName(nid, "bob@example.net")!!
        assertEquals(1, rows(aliceBuf.id).size)
        assertEquals(1, rows(bobBuf.id).size)
    }

    @Test
    fun pending_confirmedBySendConfirmed() = runTest {
        val buf = p.ensureQueryBuffer(nid, "bob@x.net")
        val id = p.createPending(nid, buf, "yo", "o1")!!
        p.process(nid, XmppEvent.SendConfirmed("o1"))
        assertNull(db.messageDao().byId(id)!!.pendingLabel)
        assertEquals("o1", db.messageDao().byId(id)!!.msgid)
    }

    @Test
    fun mucSendConfirmedBeforeReflection_thenReflection_noDuplicate() = runTest {
        // Regression: the stream-level SendConfirmed ack can race ahead of the room's own
        // reflection for a MUC send, clearing pendingLabel first. The later reflection must still
        // resolve to a no-op confirm (correlated by msgid), never a duplicate insert.
        p.process(nid, XmppEvent.MucSelfJoined("room@conf.x.net", listOf("me")))
        val bufferId = db.bufferDao().byName(nid, "room@conf.x.net")!!.id
        val id = p.createPending(nid, bufferId, "hey", "o1")!!
        p.process(nid, XmppEvent.SendConfirmed("o1"))
        p.process(nid, XmppEvent.MucMessage("room@conf.x.net", "me", "hey", "o1", null))
        val afterAck = rows(bufferId)
        assertEquals(1, afterAck.size)
        val row = db.messageDao().byId(id)!!
        assertNull(row.pendingLabel)
        assertFalse(row.failed)
    }

    @Test
    fun mucReflection_confirmsPending_notDuplicated() = runTest {
        p.process(nid, XmppEvent.MucSelfJoined("room@conf.x.net", listOf("me")))
        val bufferId = db.bufferDao().byName(nid, "room@conf.x.net")!!.id
        val id = p.createPending(nid, bufferId, "hey", "o1")!!
        p.process(nid, XmppEvent.MucMessage("room@conf.x.net", "me", "hey", "o1", null))
        val all = rows(bufferId)
        assertEquals(1, all.size)
        assertNull(db.messageDao().byId(id)!!.pendingLabel)
    }

    @Test
    fun failAllPending_flipsToFailed() = runTest {
        val buf = p.ensureQueryBuffer(nid, "eve@x.net")
        val id = p.createPending(nid, buf, "hi", "o2")!!
        p.failAllPending(nid)
        val row = db.messageDao().byId(id)!!
        assertTrue(row.failed)
        assertNull(row.pendingLabel)
    }

    @Test
    fun failPending_flipsOnlyMatchingRow_andIsNoOpAfterConfirm() = runTest {
        val buf = p.ensureQueryBuffer(nid, "carl@x.net")
        val keep = p.createPending(nid, buf, "one", "keep")!!
        val doomed = p.createPending(nid, buf, "two", "boom")!!
        p.failPending(nid, "boom")
        assertTrue(db.messageDao().byId(doomed)!!.failed)
        assertNull(db.messageDao().byId(doomed)!!.pendingLabel)
        // The unrelated row is untouched and still pending.
        assertFalse(db.messageDao().byId(keep)!!.failed)
        assertEquals("keep", db.messageDao().byId(keep)!!.pendingLabel)
        // Confirm then time out: the already-confirmed row must not be flipped to failed.
        p.process(nid, XmppEvent.SendConfirmed("keep"))
        p.failPending(nid, "keep")
        assertFalse(db.messageDao().byId(keep)!!.failed)
        assertNull(db.messageDao().byId(keep)!!.pendingLabel)
    }

    @Test
    fun mucSelfJoined_setsJoined_andReplacesMembers() = runTest {
        p.process(nid, XmppEvent.MucOccupantJoined("room@conf.x.net", "stale"))
        p.process(nid, XmppEvent.MucSelfJoined("room@conf.x.net", listOf("me", "alice")))
        val room = db.bufferDao().byName(nid, "room@conf.x.net")!!
        assertTrue(room.joined)
        val members = db.memberDao().allNow(room.id).map { it.nick }.toSet()
        assertEquals(setOf("me", "alice"), members)
    }

    @Test
    fun mucJoinFailed_writesError_clearsJoined() = runTest {
        p.process(nid, XmppEvent.MucSelfJoined("room@conf.x.net", listOf("me")))
        p.process(nid, XmppEvent.MucJoinFailed("room@conf.x.net", "banned"))
        val room = db.bufferDao().byName(nid, "room@conf.x.net")!!
        assertFalse(room.joined)
        assertEquals(MessageKind.ERROR, rows(room.id).first().kind)
    }

    @Test
    fun mucKicked_incrementsMembershipCycle() = runTest {
        p.process(nid, XmppEvent.MucSelfJoined("room@conf.x.net", listOf("me")))
        val before = db.bufferDao().byName(nid, "room@conf.x.net")!!
        p.process(nid, XmppEvent.MucKicked("room@conf.x.net", "spamming"))
        val after = db.bufferDao().byName(nid, "room@conf.x.net")!!
        assertFalse(after.joined)
        assertEquals(before.membershipCycle + 1, after.membershipCycle)
        assertEquals(MessageKind.KICK, rows(after.id).first().kind)
    }

    @Test
    fun rosterName_updatesQueryDisplayName() = runTest {
        val buf = p.ensureQueryBuffer(nid, "dave@x.net")
        p.process(nid, XmppEvent.RosterUpdated(listOf(RosterContact("dave@x.net", "Dave"))))
        assertEquals("Dave", db.bufferDao().rawById(buf)!!.displayName)
    }

    @Test
    fun mucMessage_hasMention_wordBoundary_selfExcluded() = runTest {
        p.process(nid, XmppEvent.MucSelfJoined("room@conf.x.net", listOf("me")))
        val room = db.bufferDao().byName(nid, "room@conf.x.net")!!
        p.process(nid, XmppEvent.MucMessage("room@conf.x.net", "alice", "hey me, look", "s2", null))
        p.process(nid, XmppEvent.MucMessage("room@conf.x.net", "alice", "meeting later", "s3", null))
        p.process(nid, XmppEvent.MucMessage("room@conf.x.net", "me", "me talking about me", "s4", null))
        val byStanza = rows(room.id).associateBy { it.msgid }
        assertTrue(byStanza.getValue("s2").hasMention)
        assertFalse(byStanza.getValue("s3").hasMention)
        assertFalse(byStanza.getValue("s4").hasMention)
    }

    @Test
    fun ensureServerBuffer_isNamedAfterAccount() = runTest {
        val id = p.ensureServerBuffer(nid)
        val buf = db.bufferDao().rawById(id)!!
        assertEquals(BufferType.SERVER, buf.type)
        assertEquals("me@glvortex.net", buf.displayName)
    }
}
