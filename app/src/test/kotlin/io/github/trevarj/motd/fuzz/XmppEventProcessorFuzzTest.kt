package io.github.trevarj.motd.fuzz

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.Protocol
import io.github.trevarj.motd.data.sync.MessageNotifier
import io.github.trevarj.motd.data.sync.TypingTrackerImpl
import io.github.trevarj.motd.xmpp.RosterContact
import io.github.trevarj.motd.xmpp.XmppEvent
import io.github.trevarj.motd.xmpp.XmppEventProcessor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Seeded generator over [XmppEventProcessor]: a fuzz.random-shuffled sequence of events across 2
 * senders x 2 stanza ids x {ChatMessage, MucMessage (incl. own-nick reflections), SendConfirmed,
 * RosterUpdated, MucSelfJoined, MucOccupantJoined/Left, createPending, Disconnected-then-replay}.
 * Mirrors [EventProcessorStateMachineFuzzTest]'s per-case fresh in-memory Robolectric DB and
 * `fuzz.record` tracing.
 */
@RunWith(RobolectricTestRunner::class)
class XmppEventProcessorFuzzTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun generatedXmppSequencesDedupeAndFoldMembersConsistently() = runTest {
        SeededFuzz.runSuspending(
            target = "xmpp-event-processor",
            version = 1,
            prCases = 8,
            nightlyCases = 200,
            replayTest = XmppEventProcessorFuzzTest::class.java.name,
        ) { fuzz -> runCase(fuzz) }
    }

    private suspend fun runCase(fuzz: FuzzCase) {
        val databaseName = "xmpp-fuzz-${fuzz.seed.hashCode()}-${fuzz.index}.db"
        context.deleteDatabase(databaseName)
        var db: MotdDatabase? = null
        try {
            val opened = open(databaseName)
            db = opened
            val networkId = opened.networkDao().insert(network(fuzz))
            val processor = XmppEventProcessor(opened, TypingTrackerImpl(), MessageNotifier.Noop)

            val senders = listOf("alice-${fuzz.index}@example.net", "bob-${fuzz.index}@example.net")
            val stanzaIds = listOf("s1-${fuzz.index}", "s2-${fuzz.index}")
            val roomJid = "room-${fuzz.index}@conf.example.net"
            val ourNick = "me"
            val occupantA = "occ-a-${fuzz.index}"
            val occupantB = "occ-b-${fuzz.index}"

            // Bootstrap the MUC buffer (ensureMucBuffer* is private, unreachable without an event)
            // so createPending calls below always target a real bufferId. This is setup, not part
            // of the randomized op sequence — same convention as the mirrored fuzz test's initial
            // network/message setup before its shuffled portion.
            fuzz.record("bootstrap muc-self-joined room=$roomJid")
            processor.process(networkId, XmppEvent.MucSelfJoined(roomJid, listOf(ourNick)))
            val mucBufferId = checkNotNull(opened.bufferDao().byName(networkId, roomJid)).id
            var expectedMembers = mutableSetOf(ourNick)

            val deliveredChats = mutableListOf<Pair<String, String>>()
            val confirmedOrReflectedOriginIds = mutableSetOf<String>()

            data class Op(val label: String, val run: suspend () -> Unit)

            val ops = mutableListOf<Op>()

            // ChatMessage: 2 senders x 2 stanza ids.
            for (sender in senders) {
                for (stanzaId in stanzaIds) {
                    ops += Op("chat sender=$sender stanza=$stanzaId") {
                        processor.process(networkId, XmppEvent.ChatMessage(sender, "chat-$stanzaId", stanzaId, null))
                        deliveredChats += sender to stanzaId
                    }
                }
            }

            // MucMessage: 2 senders (as occupant nicks) x 2 stanza ids, non-own-nick.
            for (sender in senders) {
                val nick = sender.substringBefore('@')
                for (stanzaId in stanzaIds) {
                    ops += Op("muc nick=$nick stanza=$stanzaId") {
                        processor.process(networkId, XmppEvent.MucMessage(roomJid, nick, "muc-$stanzaId", stanzaId, null))
                    }
                }
            }

            // Own-nick reflection with no prior pending row: exercises the plain insert path
            // (isSelf=true, pendingLabel stays null since insertDedupedMessage never sets it).
            val unpendingReflectionOriginId = "own-unpending-${stanzaIds[0]}"
            ops += Op("muc-own-unpending stanza=$unpendingReflectionOriginId") {
                processor.process(networkId, XmppEvent.MucMessage(roomJid, ourNick, "own-text", unpendingReflectionOriginId, null))
                confirmedOrReflectedOriginIds += unpendingReflectionOriginId
            }

            // RosterUpdated.
            ops += Op("roster") {
                processor.process(
                    networkId,
                    XmppEvent.RosterUpdated(senders.map { RosterContact(it, "Name-${it.substringBefore('@')}") }),
                )
            }

            // Membership evolution beyond the bootstrap join — order among these (and relative to
            // everything else) is whatever the shuffle produces; the fold is tracked dynamically in
            // the order ops actually execute, so any permutation is a valid case.
            ops += Op("muc-occupant-joined $occupantB") {
                processor.process(networkId, XmppEvent.MucOccupantJoined(roomJid, occupantB))
                expectedMembers += occupantB
            }
            ops += Op("muc-occupant-joined $occupantA") {
                // Join/leave churn on occupantA around a second MucSelfJoined snapshot.
                processor.process(networkId, XmppEvent.MucOccupantJoined(roomJid, occupantA))
                expectedMembers += occupantA
            }
            ops += Op("muc-occupant-left $occupantA") {
                processor.process(networkId, XmppEvent.MucOccupantLeft(roomJid, occupantA))
                expectedMembers -= occupantA
            }
            ops += Op("muc-self-joined-resnapshot") {
                val snapshot = listOf(ourNick, occupantB)
                processor.process(networkId, XmppEvent.MucSelfJoined(roomJid, snapshot))
                expectedMembers = snapshot.toMutableSet()
            }

            ops.shuffle(fuzz.random)
            ops.forEachIndexed { index, op ->
                fuzz.record("op[$index] ${op.label}")
                op.run()
            }

            // Causally-dependent portion: createPending must precede its SendConfirmed / reflection,
            // so these run after the commutative shuffle above rather than being spliced into it.
            // The relative order of SendConfirmed vs. the room's own reflection is itself randomized
            // (the stream-level ack can race ahead of, or trail, the MUC reflection — see
            // XmppEventProcessor.handleMucMessage's doc comment).
            val pendingOriginId = "pending-${fuzz.index}"
            fuzz.record("create-pending origin=$pendingOriginId buffer=$mucBufferId")
            processor.createPending(networkId, mucBufferId, "pending-text", pendingOriginId)
            val confirmFirst = fuzz.random.nextBoolean()
            val pendingSteps = listOf<suspend () -> Unit>(
                { processor.process(networkId, XmppEvent.SendConfirmed(pendingOriginId)) },
                {
                    processor.process(
                        networkId,
                        XmppEvent.MucMessage(roomJid, ourNick, "pending-text", pendingOriginId, null),
                    )
                },
            ).let { steps -> if (confirmFirst) steps else steps.reversed() }
            pendingSteps.forEachIndexed { index, step ->
                fuzz.record("pending-resolution[$index] confirmFirst=$confirmFirst")
                step()
            }
            confirmedOrReflectedOriginIds += pendingOriginId

            // A second, query-buffer pending send confirmed purely via SendConfirmed (no reflection).
            val queryBufferId = processor.ensureQueryBuffer(networkId, senders[0])
            val queryPendingOriginId = "query-pending-${fuzz.index}"
            fuzz.record("create-pending origin=$queryPendingOriginId buffer=$queryBufferId")
            processor.createPending(networkId, queryBufferId, "query-pending-text", queryPendingOriginId)
            fuzz.record("send-confirmed origin=$queryPendingOriginId")
            processor.process(networkId, XmppEvent.SendConfirmed(queryPendingOriginId))
            confirmedOrReflectedOriginIds += queryPendingOriginId

            // Disconnected-then-replay: reprocess a random subset of already-delivered ChatMessages
            // (same sender + stanza id) — dedup must hold, i.e. no new rows.
            fuzz.record("disconnected")
            processor.process(networkId, XmppEvent.Disconnected(reason = "generated", fatal = false))
            val shuffledDelivered = deliveredChats.shuffled(fuzz.random)
            val replayCount = fuzz.random.nextInt(0, shuffledDelivered.size + 1)
            shuffledDelivered.take(replayCount).forEachIndexed { index, (sender, stanzaId) ->
                fuzz.record("replay[$index] sender=$sender stanza=$stanzaId")
                processor.process(networkId, XmppEvent.ChatMessage(sender, "chat-$stanzaId", stanzaId, null))
            }

            // Invariant 1: no duplicate (bufferId, senderScope, stanzaId) message rows.
            val duplicateGroups = scalar(
                opened,
                """SELECT COUNT(*) FROM (
                       SELECT bufferId, normalizedActor, msgid FROM messages
                       WHERE msgid IS NOT NULL
                       GROUP BY bufferId, normalizedActor, msgid
                       HAVING COUNT(*) > 1
                   )""",
            )
            assertEquals(0, duplicateGroups)

            // Invariant 2: every origin-id that received SendConfirmed or an own-nick reflection has
            // pendingLabel == null on its row.
            for (originId in confirmedOrReflectedOriginIds) {
                assertEquals(
                    "originId=$originId must not remain pending",
                    0,
                    scalar(opened, "SELECT COUNT(*) FROM messages WHERE msgid = ? AND pendingLabel IS NOT NULL", originId),
                )
            }

            // Invariant 3: members table content equals the fold of MucSelfJoined/OccupantJoined/Left
            // ops applied in the order they actually executed.
            val actualMembers = opened.memberDao().allNow(mucBufferId).map { it.nick }.toSet()
            assertEquals(expectedMembers, actualMembers)
        } finally {
            db?.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun open(name: String): MotdDatabase =
        Room.databaseBuilder(context, MotdDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()

    private fun network(fuzz: FuzzCase) = NetworkEntity(
        name = "xmpp-fuzz-${fuzz.index}",
        protocol = Protocol.XMPP,
        role = NetworkRole.DIRECT,
        host = "xmpp.example.net",
        port = 5222,
        nick = "me",
        username = "me",
        realname = "Me",
        jid = "me-${fuzz.index}@example.net",
    )

    private fun scalar(db: MotdDatabase, query: String, vararg args: Any?): Int =
        db.openHelper.readableDatabase.query(query, args).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
}
