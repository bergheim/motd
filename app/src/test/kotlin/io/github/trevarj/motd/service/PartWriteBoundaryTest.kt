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
class PartWriteBoundaryTest {

    @Test
    fun `missing buffer or client is not accepted`() = runTest {
        assertFalse(writeChannelPartIfReady(null, null, null))
        assertFalse(
            writeChannelPartIfReady(
                BufferEntity(1, 1, "#room", "#room", BufferType.CHANNEL),
                null,
                null,
            ),
        )
    }

    @Test
    fun `non-ready client is not accepted`() = runTest {
        val transport = RecordingTransport()
        val client = IrcClient(
            IrcClientConfig("irc.example", 6697, true, "me", "me", "Me"),
            TransportFactory { _, _, _, _, _ -> transport },
            CoroutineScope(SupervisorJob() + coroutineContext),
        )

        assertFalse(
            writeChannelPartIfReady(
                BufferEntity(1, 1, "#room", "#room", BufferType.CHANNEL),
                null,
                client,
            ),
        )
    }

    @Test
    fun `false write and write exception are not accepted by the boundary`() = runTest {
        val buffer = BufferEntity(1, 1, "#room", "#room", BufferType.CHANNEL)
        assertFalse(attemptChannelPartWrite(buffer, null) { false })
        val failure = IllegalStateException("socket closed")
        val thrown = runCatching {
            attemptChannelPartWrite(buffer, null) { throw failure }
        }.exceptionOrNull()
        assertEquals(failure, thrown)
    }

    @Test
    fun `accepted write uses the IRC target and does not require a server echo`() = runTest {
        val transport = RecordingTransport()
        val client = readyClient(transport)
        val buffer = BufferEntity(
            id = 1,
            networkId = 1,
            name = "#internal-alias",
            displayName = "#room",
            type = BufferType.CHANNEL,
        )

        assertTrue(writeChannelPartIfReady(buffer, null, client))
        assertEquals("PART #room", transport.sent.last())
    }

    private suspend fun TestScope.readyClient(transport: RecordingTransport): IrcClient {
        val client = IrcClient(
            IrcClientConfig("irc.example", 6697, true, "me", "me", "Me"),
            TransportFactory { _, _, _, _, _ -> transport },
            CoroutineScope(SupervisorJob() + coroutineContext),
        )
        client.start()
        runCurrent()
        val caps = "batch message-tags server-time"
        transport.feed(":srv CAP * LS :$caps")
        runCurrent()
        transport.feed(":srv CAP me ACK :$caps")
        transport.feed(":srv 005 me CHANTYPES=# :supported")
        transport.feed(":srv 001 me :Welcome")
        runCurrent()
        check(client.state.value is IrcClientState.Ready)
        return client
    }

    private class RecordingTransport : IrcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()

        override suspend fun connect() = Unit
        override val incoming = inbound.consumeAsFlow()
        override suspend fun send(line: String) { sent += line }
        override suspend fun close() { inbound.close() }
        suspend fun feed(line: String) { inbound.send(line) }
    }
}
