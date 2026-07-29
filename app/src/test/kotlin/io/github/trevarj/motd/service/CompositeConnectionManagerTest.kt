package io.github.trevarj.motd.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.backend.BackendRegistry
import io.github.trevarj.motd.backend.ChatBackend
import io.github.trevarj.motd.backend.ConnectionState
import io.github.trevarj.motd.backend.ProtocolId
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.EventRedirectEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.TimelineEventId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
class CompositeConnectionManagerTest {
    private class RecordingSessions : ConnectionManager {
        val calls = mutableListOf<String>()
        val states = MutableStateFlow<Map<Long, ConnectionState>>(emptyMap())
        override val connectionStates: StateFlow<Map<Long, ConnectionState>> get() = states
        var roomDiscoverySupported = false
        var roomTarget: io.github.trevarj.motd.backend.RoomTargetSyntax? = null
        override suspend fun supportsRoomDiscovery(networkId: Long): Boolean {
            calls += "supportsRoomDiscovery:$networkId"
            return roomDiscoverySupported
        }
        override suspend fun roomTargetSyntax(networkId: Long): io.github.trevarj.motd.backend.RoomTargetSyntax? {
            calls += "roomTargetSyntax:$networkId"
            return roomTarget
        }
        override suspend fun startAll() { calls += "startAll" }
        override suspend fun stopAll() { calls += "stopAll" }
        override suspend fun connect(networkId: Long) { calls += "connect:$networkId" }
        override suspend fun disconnect(networkId: Long) { calls += "disconnect:$networkId" }
        override suspend fun reconnectStale() { calls += "reconnectStale" }
        override suspend fun sendMessage(
            bufferId: Long,
            text: String,
            replyToEventId: TimelineEventId?,
        ): SendAcceptance {
            calls += "send:$bufferId"
            return SendAcceptance.Accepted(emptyList())
        }
        override suspend fun retryMessage(eventId: TimelineEventId): SendAcceptance {
            calls += "retry:$eventId"
            return SendAcceptance.Accepted(emptyList())
        }
        override suspend fun sendTyping(bufferId: Long, state: String) { calls += "typing:$bufferId" }
        override suspend fun sendReact(bufferId: Long, msgid: String, emoji: String) { calls += "react:$bufferId" }
        override suspend fun joinChannel(networkId: Long, channel: String) { calls += "join:$networkId" }
        override suspend fun partChannel(bufferId: Long, reason: String?) { calls += "part:$bufferId" }
        override suspend fun ensureQueryBuffer(networkId: Long, nick: String): Long {
            calls += "query:$networkId"
            return 42
        }
        override suspend fun ensureServerBuffer(networkId: Long): Long {
            calls += "server:$networkId"
            return 43
        }
        override suspend fun markRead(bufferId: Long, anchor: TimelineAnchor) { calls += "read:$bufferId" }
        override suspend fun evaluatePushMode() { calls += "push" }
        override val certPrompts: StateFlow<List<CertPrompt>> = MutableStateFlow(emptyList())
        override suspend fun trustCert(prompt: CertPrompt) { calls += "trust:${prompt.networkId}" }
        override fun dismissCertPrompt(prompt: CertPrompt) { calls += "dismissCert" }
    }

    private class FakeBackend(
        id: String,
        override val sessions: ConnectionManager,
    ) : ChatBackend {
        override val protocol = ProtocolId(id)
    }

    private lateinit var db: MotdDatabase
    private val sessionsA = RecordingSessions()
    private val sessionsB = RecordingSessions()
    private val backendA = FakeBackend("proto-a", sessionsA)
    private val backendB = FakeBackend("proto-b", sessionsB)
    private lateinit var composite: CompositeConnectionManager
    private var bufferA = 0L

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        suspend fun network(id: Long, protocol: String) = db.networkDao().insert(
            NetworkEntity(
                id = id,
                name = "net-$id",
                role = NetworkRole.DIRECT,
                host = "example.org",
                port = 6697,
                nick = "me",
                username = "me",
                realname = "Me",
                protocol = protocol,
            ),
        )
        network(1, "proto-a")
        network(2, "proto-b")
        network(3, "ghost-proto")
        bufferA = db.bufferDao().insert(
            BufferEntity(networkId = 1, name = "#room", displayName = "#room", type = BufferType.CHANNEL),
        )
        composite = CompositeConnectionManager(
            registry = BackendRegistry(setOf(backendA, backendB)),
            backends = setOf(backendA, backendB),
            db = db,
        )
    }

    @After fun tearDown() = db.close()

    @Test
    fun `per-network operations dispatch to the owning backend only`() = runTest {
        composite.connect(1)
        composite.connect(2)
        composite.connect(3) // ghost protocol: no registered backend, must stay inert

        assertEquals(listOf("connect:1"), sessionsA.calls)
        assertEquals(listOf("connect:2"), sessionsB.calls)
    }

    @Test
    fun `buffer operations resolve through the owning network`() = runTest {
        val accepted = composite.sendMessage(bufferA, "hello")
        val missing = composite.sendMessage(9_999, "nope")

        assertTrue(accepted is SendAcceptance.Accepted)
        assertEquals(listOf("send:$bufferA"), sessionsA.calls)
        assertTrue(sessionsB.calls.isEmpty())
        assertEquals(SendAcceptance.Rejected(SendRejectionReason.BUFFER_NOT_FOUND), missing)
    }

    @Test
    fun `lifecycle operations broadcast to every backend`() = runTest {
        composite.startAll()
        composite.reconnectStale()

        assertEquals(listOf("startAll", "reconnectStale"), sessionsA.calls)
        assertEquals(listOf("startAll", "reconnectStale"), sessionsB.calls)
    }

    @Test
    fun `event operations follow canonical redirects to the owning backend`() = runTest {
        // Canonical coalescing deletes the losing row and records a redirect; UI actions can still
        // hold the losing id. Resolving it by plain row id would reject the retry outright.
        val winner = db.messageDao().insertAll(
            listOf(
                MessageEntity(
                    id = 500,
                    bufferId = bufferA,
                    msgid = "winner",
                    serverTime = 1,
                    sender = "alice",
                    kind = MessageKind.PRIVMSG,
                    text = "hi",
                    dedupKey = "winner",
                ),
            ),
        ).single()
        db.canonicalTimelineDao().upsertEventRedirect(
            EventRedirectEntity(losingEventId = 501, canonicalEventId = winner),
        )

        val accepted = composite.retryMessage(501)

        assertTrue(accepted is SendAcceptance.Accepted)
        assertEquals(listOf("retry:501"), sessionsA.calls)
        assertTrue(sessionsB.calls.isEmpty())
    }

    /**
     * Review fix (P2 findings): [ConnectionManager.roomTargetSyntax]/[ConnectionManager.supportsRoomDiscovery]
     * are independent of live connection state, so — unlike the synchronous, connection-derived
     * `historyAvailability`/`protocolCommands` fan-out — they route through the same persisted
     * per-network lookup `joinChannel`/`connect`/etc. already use, resolving to the OWNING backend
     * only, never a foreign one and never a network with no registered backend at all.
     */
    @Test
    fun `roomTargetSyntax and supportsRoomDiscovery resolve through the owning network only`() = runTest {
        sessionsA.roomDiscoverySupported = true
        val target = io.github.trevarj.motd.backend.RoomTargetSyntax { "#$it" }
        sessionsA.roomTarget = target

        assertTrue(composite.supportsRoomDiscovery(1)) // network 1 -> backendA -> true
        assertFalse(composite.supportsRoomDiscovery(2)) // network 2 -> backendB -> default false
        assertFalse(composite.supportsRoomDiscovery(3)) // ghost protocol -> no backend -> false

        assertEquals(target, composite.roomTargetSyntax(1))
        assertEquals(null, composite.roomTargetSyntax(2))
        assertEquals(null, composite.roomTargetSyntax(3))

        // The ghost network (no registered backend) must reach NEITHER backend at all -- unlike
        // network 2 above, which correctly does reach sessionsB (proving routing, not just presence).
        assertTrue(
            (sessionsA.calls + sessionsB.calls).none {
                it == "supportsRoomDiscovery:3" || it == "roomTargetSyntax:3"
            },
        )
    }

    @Test
    fun `connection states union across backends`() {
        sessionsA.states.value = mapOf(1L to ConnectionState.Ready("a"))
        sessionsB.states.value = mapOf(2L to ConnectionState.Connecting)

        assertEquals(
            mapOf<Long, ConnectionState>(
                1L to ConnectionState.Ready("a"),
                2L to ConnectionState.Connecting,
            ),
            composite.connectionStates.value,
        )
    }
}
