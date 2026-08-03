package io.github.trevarj.motd.backend

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.EventAliasNamespace
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObservationOrigin
import io.github.trevarj.motd.data.db.RoomId
import io.github.trevarj.motd.data.db.TimeProvenance
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.TimelineEventEntity
import io.github.trevarj.motd.data.sync.CanonicalTimelineStore
import io.github.trevarj.motd.data.sync.IngestResult
import io.github.trevarj.motd.data.sync.TimelineObservation
import io.github.trevarj.motd.ircbackend.IrcChatBackend
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.PresenceKey
import io.github.trevarj.motd.service.PresenceState
import io.github.trevarj.motd.service.RosterLoadState
import io.github.trevarj.motd.service.SendAcceptance
import io.github.trevarj.motd.service.SendRejectionReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
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
 * PR 1 acceptance-gate contract suite (docs/backend-neutral-xmpp-rollout.md): "a fake third backend
 * passes the same contract suite... and variants with semantics IRC lacks: own messages delivered
 * from another session, distinct sender-supplied and archive-assigned identifiers, and out-of-order
 * history pages." This file proves those facts land through [CanonicalTimelineStore] alone, with no
 * second, backend-private Room write path.
 */
@RunWith(RobolectricTestRunner::class)
class BackendContractTest {

    /** Trivial open-registry participant; only [protocol] distinguishes it from [IrcChatBackend]. */
    private class FakeChatBackend(
        override val protocol: ProtocolId = ProtocolId(FAKE_PROTOCOL),
    ) : ChatBackend {
        override val sessions get() = InertConnectionManager
    }

    /**
     * Stand-in for a backend-owned processor — this fake backend's counterpart to IRC's
     * `EventProcessor`. It builds [TimelineObservation]s and hands them to [CanonicalTimelineStore];
     * it has no private write path of its own, matching "Persistence and writer ownership"
     * (docs/backend-neutral-xmpp-rollout.md): every backend has exactly one processor, and every
     * processor persists only through the shared canonical repositories.
     */
    private class FakeBackendProcessor(private val store: CanonicalTimelineStore) {
        fun observation(
            networkId: Long,
            bufferId: RoomId,
            sender: String,
            text: String,
            serverTime: Long,
            origin: ObservationOrigin,
            timeProvenance: TimeProvenance,
            isSelf: Boolean = false,
            msgid: String? = null,
            pendingLabel: String? = null,
            label: String? = pendingLabel,
            batchId: String? = null,
            selfAttributionAuthoritative: Boolean = false,
            connectionGeneration: Long? = 1L,
        ): TimelineObservation {
            val event = TimelineEventEntity(
                bufferId = bufferId,
                msgid = msgid,
                serverTime = serverTime,
                sender = sender,
                kind = MessageKind.PRIVMSG,
                text = text,
                isSelf = isSelf,
                pendingLabel = pendingLabel,
                dedupKey = msgid ?: pendingLabel?.let { "pending:$it" } ?: "dk:$serverTime:$sender:$text",
            )
            return TimelineObservation(
                networkId = networkId,
                event = event,
                origin = origin,
                connectionGeneration = connectionGeneration,
                label = label,
                batchId = batchId,
                timeProvenance = timeProvenance,
                selfAttributionAuthoritative = selfAttributionAuthoritative,
            )
        }

        /** The only write path: straight through the shared canonical store, like a real processor. */
        suspend fun deliver(observation: TimelineObservation): IngestResult = store.ingest(observation)

        suspend fun deliverBatch(observations: List<TimelineObservation>): List<IngestResult> =
            store.ingestBatch(observations)
    }

    private lateinit var db: MotdDatabase
    private lateinit var store: CanonicalTimelineStore
    private lateinit var processor: FakeBackendProcessor
    private var networkId: Long = 0

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
            .allowMainThreadQueries().build()
        store = CanonicalTimelineStore(db)
        processor = FakeBackendProcessor(store)
        networkId = db.networkDao().insert(
            NetworkEntity(
                name = "fakenet", role = NetworkRole.DIRECT,
                host = "chat.fake.invalid", port = 5555,
                nick = "me", username = "me", realname = "Me",
                protocol = FAKE_PROTOCOL,
            ),
        )
    }

    @After fun tearDown() { db.close() }

    private suspend fun createBuffer(name: String = "#chan"): RoomId =
        db.bufferDao().insert(
            BufferEntity(networkId = networkId, name = name, displayName = name, type = BufferType.CHANNEL),
        )

    private suspend fun rows(bufferId: RoomId): List<MessageEntity> =
        db.messageDao().pagingSource(bufferId).load(
            PagingSource.LoadParams.Refresh(null, 100, false),
        ).let { (it as PagingSource.LoadResult.Page).data }

    @Test
    fun `fake backend registers and resolves through the open registry`() {
        val irc = IrcChatBackend(dagger.Lazy { error("sessions are not resolved by registry lookups") })
        val fake = FakeChatBackend()
        val registry = BackendRegistry(setOf(irc, fake))

        assertEquals(irc, registry.backendFor(IrcChatBackend.IRC_PROTOCOL))
        assertEquals(fake, registry.backendFor(fake.protocol))
        assertNull(registry.backendFor(ProtocolId("unknown-proto")))
    }

    @Test
    fun `live ingestion lands exactly once through the shared canonical path`() = runTest {
        val bufferId = createBuffer()
        val observation = processor.observation(
            networkId = networkId,
            bufferId = bufferId,
            sender = "alice",
            text = "hello over the fake wire",
            serverTime = 1_000,
            origin = ObservationOrigin.LIVE,
            timeProvenance = TimeProvenance.LOCAL_CLOCK,
            msgid = "fake-live-1",
        )

        val first = processor.deliver(observation)
        val second = processor.deliver(observation)

        assertTrue(first is IngestResult.Inserted)
        assertTrue(second is IngestResult.Merged)
        val row = rows(bufferId).single()
        assertEquals("fake-live-1", row.msgid)
        assertEquals(
            listOf(EventAliasNamespace.MSGID),
            db.canonicalTimelineDao().aliasesFor(row.id).map { it.namespace },
        )
    }

    @Test
    fun `own message delivered from another session lands as self without pending machinery`() = runTest {
        val bufferId = createBuffer()
        // Carbon-shaped: the backend already knows this is self (e.g. XEP-0280 <sent> carbon), so
        // it never goes through this session's own pending-send/label bookkeeping.
        val carbon = processor.observation(
            networkId = networkId,
            bufferId = bufferId,
            sender = "me",
            text = "sent from my other device",
            serverTime = 2_000,
            origin = ObservationOrigin.LIVE,
            timeProvenance = TimeProvenance.LOCAL_CLOCK,
            isSelf = true,
            msgid = "carbon-1",
            selfAttributionAuthoritative = true,
        )

        // Live delivery, then a redundant catch-up-shaped redelivery of the identical observation.
        // ObservationOrigin today is only LIVE/PUSH/HISTORY/LOCAL_SEND (Entities.kt) — there is no
        // distinct "background catch-up" origin yet; the rollout doc defers that ingestion-context
        // split to the post-baseline XMPP cross-device work (MAM/carbons/stream-resumption). The
        // overlap is modeled here as a second LIVE delivery of the same msgid, which is the closest
        // existing origin and still proves idempotent, pending-free self-attribution.
        val first = processor.deliver(carbon)
        val second = processor.deliver(carbon)

        assertTrue(first is IngestResult.Inserted)
        assertTrue(second is IngestResult.Merged)
        val row = rows(bufferId).single()
        assertTrue(row.isSelf)
        assertNull(row.pendingLabel)
        assertFalse(row.failed)
        assertEquals("carbon-1", row.msgid)
        assertTrue(
            db.canonicalTimelineDao().aliasesFor(row.id).any { it.namespace == EventAliasNamespace.MSGID },
        )
    }

    @Test
    fun `sender-supplied and archive-assigned identifiers reconcile on one row`() = runTest {
        val bufferId = createBuffer()

        // 1. Sender-supplied identifier: a local send, pending on its own label.
        val pendingSend = processor.observation(
            networkId = networkId,
            bufferId = bufferId,
            sender = "me",
            text = "outbound over the fake wire",
            serverTime = 3_000,
            origin = ObservationOrigin.LOCAL_SEND,
            timeProvenance = TimeProvenance.LOCAL_CLOCK,
            isSelf = true,
            pendingLabel = "client-1",
        )
        val inserted = processor.deliver(pendingSend)
        assertTrue(inserted is IngestResult.Inserted)
        val pendingRow = rows(bufferId).single()
        assertEquals("client-1", pendingRow.pendingLabel)
        assertEquals(
            listOf(EventAliasNamespace.LABEL),
            db.canonicalTimelineDao().aliasesFor(pendingRow.id).map { it.namespace },
        )

        // 2. Archive-assigned identifier: the same send, echoed back with both the original label
        // and the durable id the backend's archive assigned it.
        val echo = processor.observation(
            networkId = networkId,
            bufferId = bufferId,
            sender = "me",
            text = "outbound over the fake wire",
            serverTime = 3_050,
            origin = ObservationOrigin.LIVE,
            timeProvenance = TimeProvenance.LOCAL_CLOCK,
            isSelf = true,
            msgid = "archive-9",
            label = "client-1",
        )
        val enriched = processor.deliver(echo)

        assertTrue(enriched is IngestResult.Enriched)
        val row = rows(bufferId).single()
        assertNull(row.pendingLabel)
        assertEquals("archive-9", row.msgid)
        assertEquals(
            setOf(EventAliasNamespace.LABEL, EventAliasNamespace.MSGID),
            db.canonicalTimelineDao().aliasesFor(row.id).map { it.namespace }.toSet(),
        )
    }

    @Test
    fun `out-of-order history pages keep order and dedup overlap`() = runTest {
        val bufferId = createBuffer()

        fun historyObservation(msgid: String, serverTime: Long, text: String, batchId: String) =
            processor.observation(
                networkId = networkId,
                bufferId = bufferId,
                sender = "alice",
                text = text,
                serverTime = serverTime,
                origin = ObservationOrigin.HISTORY,
                timeProvenance = TimeProvenance.SERVER_TAG,
                msgid = msgid,
                batchId = batchId,
            )

        val newerPage = listOf(
            historyObservation("m3", 3_000, "third", batchId = "page-newer"),
            historyObservation("m4", 4_000, "fourth", batchId = "page-newer"),
        )
        // The older page is fetched second (a paging backend backfilling behind a live/newer page)
        // and its newest entry duplicates the newer page's oldest entry at the page boundary.
        val olderPageWithOverlap = listOf(
            historyObservation("m1", 1_000, "first", batchId = "page-older"),
            historyObservation("m2", 2_000, "second", batchId = "page-older"),
            historyObservation("m3", 3_000, "third", batchId = "page-older"),
        )

        processor.deliverBatch(newerPage)
        processor.deliverBatch(olderPageWithOverlap)

        val ordered = rows(bufferId).sortedBy { it.serverTime }
        assertEquals(listOf("m1", "m2", "m3", "m4"), ordered.map { it.msgid })
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_000L), ordered.map { it.serverTime })
        assertEquals(4, rows(bufferId).size)
    }

    @Test
    fun `a backend without optional capabilities plugs into the seam defaults`() = runTest {
        // Only the members ConnectionManager declares without a default are implemented here,
        // trivially, to prove a capability-subtracted backend needs no shared-code changes.
        val bare = object : ConnectionManager {
            override val connectionStates: StateFlow<Map<Long, ConnectionState>> =
                MutableStateFlow<Map<Long, ConnectionState>>(emptyMap())
            override val certPrompts: StateFlow<List<CertPrompt>> =
                MutableStateFlow<List<CertPrompt>>(emptyList())

            override suspend fun startAll() = Unit
            override suspend fun stopAll() = Unit
            override suspend fun connect(networkId: Long) = Unit
            override suspend fun disconnect(networkId: Long) = Unit
            override suspend fun reconnectStale() = Unit
            override suspend fun sendMessage(
                bufferId: Long,
                text: String,
                replyToEventId: Long?,
            ): SendAcceptance = SendAcceptance.Rejected(SendRejectionReason.UNSUPPORTED_BUFFER)
            override suspend fun sendTyping(bufferId: Long, state: String) = Unit
            override suspend fun sendReact(bufferId: Long, msgid: String, emoji: String) = Unit
            override suspend fun joinChannel(networkId: Long, channel: String) = Unit
            override suspend fun partChannel(bufferId: Long, reason: String?) = Unit
            override suspend fun ensureQueryBuffer(networkId: Long, nick: String): Long = 0L
            override suspend fun ensureServerBuffer(networkId: Long): Long = 0L
            override suspend fun markRead(bufferId: Long, anchor: TimelineAnchor) = Unit
            override suspend fun evaluatePushMode() = Unit
            override suspend fun trustCert(prompt: CertPrompt) = Unit
            override fun dismissCertPrompt(prompt: CertPrompt) = Unit
        }

        assertEquals(emptyMap<Long, RosterLoadState>(), bare.memberLoadStates.value)
        assertEquals(emptyMap<PresenceKey, PresenceState>(), bare.presenceStates.value)
        assertEquals(emptyMap<Long, Long?>(), bare.lagStates.value)
        assertFalse(bare.serverPushAvailable.value)
        assertEquals(emptyMap<Long, String>(), bare.attachmentUploadEndpoints.value)
        assertEquals(emptyMap<Long, ReactionCapability>(), bare.reactionCapabilities.value)
        assertNull(bare.liveIdentityRules(networkId))
        assertNull(bare.historyAvailability(networkId))
        assertNull(bare.protocolCommands(networkId))
        assertTrue(bare.channelJoinOutcomes.toList().isEmpty())
        // Review fix additions: room-target syntax and room discovery are equally optional.
        assertNull(bare.roomTargetSyntax(networkId))
        assertFalse(bare.supportsRoomDiscovery(networkId))
    }

    private companion object {
        const val FAKE_PROTOCOL = "fake-proto"
    }
}
