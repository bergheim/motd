package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcClientConfig
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.transport.IrcTransport
import io.github.trevarj.motd.irc.transport.TransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InviteWriteBoundaryTest {
    private val joined =
        BufferEntity(
            id = 1,
            networkId = 1,
            name = "#internal-alias",
            displayName = "#room",
            type = BufferType.CHANNEL,
            joined = true,
        )

    @Test
    fun `missing wrong unjoined and closing rooms are rejected`() =
        runTest {
            assertFalse(attemptChannelInviteWrite(null, "alice") { true })
            assertFalse(attemptChannelInviteWrite(joined.copy(type = BufferType.QUERY), "alice") { true })
            assertFalse(attemptChannelInviteWrite(joined.copy(joined = false), "alice") { true })
            assertFalse(attemptChannelInviteWrite(joined.copy(pendingCloseAt = 1), "alice") { true })
        }

    @Test
    fun `malformed targets and failed writes are rejected`() =
        runTest {
            listOf("", "two nicks", "bad\rnick", ":trailing", "one,two").forEach { nick ->
                assertFalse(attemptChannelInviteWrite(joined, nick) { true })
            }
            assertFalse(attemptChannelInviteWrite(joined, "alice") { false })
            assertFalse(attemptChannelInviteWrite(joined, "alice") { error("socket closed") })
        }

    @Test
    fun `non-ready and self targets are rejected`() =
        runTest {
            val transport = RecordingTransport()
            val client = newClient(transport)
            assertFalse(writeChannelInviteIfReady(joined, "alice", client))

            client.start()
            ready(client, transport)
            assertFalse(writeChannelInviteIfReady(joined, "ME", client))
        }

    @Test
    fun `accepted invite writes exact IRC line`() =
        runTest {
            val transport = RecordingTransport()
            val client = newClient(transport)
            client.start()
            ready(client, transport)

            assertTrue(writeChannelInviteIfReady(joined, " Alice ", client))
            assertEquals("INVITE Alice #room", transport.sent.last())
        }

    private fun TestScope.newClient(transport: RecordingTransport) =
        IrcClient(
            IrcClientConfig("irc.example", 6697, true, "me", "me", "Me"),
            TransportFactory { _, _, _, _, _ -> transport },
            CoroutineScope(SupervisorJob() + coroutineContext),
        )

    private suspend fun TestScope.ready(
        client: IrcClient,
        transport: RecordingTransport,
    ) {
        runCurrent()
        val caps = "batch message-tags server-time"
        transport.feed(":srv CAP * LS :$caps")
        runCurrent()
        transport.feed(":srv CAP me ACK :$caps")
        transport.feed(":srv 005 me CHANTYPES=# CASEMAPPING=rfc1459 :supported")
        transport.feed(":srv 001 me :Welcome")
        runCurrent()
        check(client.state.value is IrcClientState.Ready)
    }

    private class RecordingTransport : IrcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()

        override suspend fun connect() = Unit

        override val incoming = inbound.consumeAsFlow()

        override suspend fun send(line: String) {
            sent += line
        }

        override suspend fun close() {
            inbound.close()
        }

        suspend fun feed(line: String) {
            inbound.send(line)
        }
    }
}
