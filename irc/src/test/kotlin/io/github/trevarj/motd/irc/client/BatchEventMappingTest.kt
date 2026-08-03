package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.ext.BatchChild
import io.github.trevarj.motd.irc.ext.BatchTree
import io.github.trevarj.motd.irc.proto.IrcMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchEventMappingTest {
    private val client = IrcClient(
        IrcClientConfig("example", 6697, true, "me", "me", "Me"),
        FakeTransport().factory(),
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    @Test fun `typed netsplit survives inside chathistory`() {
        val split = batch(
            "split",
            "netsplit",
            listOf("a.example", "b.example"),
            listOf(
                BatchChild.Message(msg("@time=2026-01-01T00:00:00Z :Alice!u@h QUIT :split")),
                BatchChild.Message(msg("@time=2026-01-01T00:00:01Z :Bob!u@h QUIT :split")),
            ),
        ).let { tree ->
            tree.copy(
                opening = tree.opening.copy(
                    tags = mapOf(
                        "msgid" to "split-event",
                        "time" to "2026-01-01T00:00:02Z",
                    ),
                ),
            )
        }
        val history = batch(
            "history",
            "chathistory",
            listOf("#room"),
            listOf(BatchChild.Nested(split)),
        )

        val outer = client.mapBatchTree(history).single() as IrcEvent.PlaybackBatch
        assertEquals(IrcEvent.PlaybackSource.CHATHISTORY, outer.source)
        val event = outer.events.single() as IrcEvent.NetworkBatch
        assertEquals(IrcEvent.NetworkBatchKind.NETSPLIT, event.kind)
        assertEquals("#room", event.target)
        assertEquals(listOf("Alice", "Bob"), event.events.map { (it as IrcEvent.Quit).nick })
        assertEquals("split-event", event.historyMetadata?.msgid)
        assertEquals(1_767_225_602_000L, event.historyMetadata?.serverTime)
    }

    @Test fun `malformed known batch recursively degrades while typed child survives unknown parent`() {
        val malformed = batch(
            "bad",
            "netsplit",
            listOf("only-one-server"),
            listOf(BatchChild.Message(msg(":Alice!u@h QUIT :split"))),
        )
        assertTrue(client.mapBatchTree(malformed).single() is IrcEvent.Quit)

        val typed = batch(
            "join",
            "netjoin",
            listOf("a", "b"),
            listOf(BatchChild.Message(msg(":Alice!u@h JOIN #room"))),
        )
        val unknown = batch("outer", "vendor/unknown", emptyList(), listOf(BatchChild.Nested(typed)))
        assertTrue(client.mapBatchTree(unknown).single() is IrcEvent.NetworkBatch)
    }

    @Test fun `znc playback maps to playback batch`() {
        val playback = batch(
            "znc",
            "znc.in/playback",
            listOf("#room"),
            listOf(BatchChild.Message(msg("@time=2026-01-01T00:00:00Z :Alice!u@h PRIVMSG #room :missed"))),
        )

        val event = client.mapBatchTree(playback).single() as IrcEvent.PlaybackBatch
        assertEquals(IrcEvent.PlaybackSource.ZNC_PLAYBACK, event.source)
        assertEquals("#room", event.target)
        assertTrue(event.events.single() is IrcEvent.ChatMessage)
        assertEquals(1_767_225_600_000L, event.items.single().serverTime)
        assertEquals(null, event.items.single().msgid)
    }

    private fun batch(ref: String, type: String, params: List<String>, children: List<BatchChild>) =
        BatchTree(ref, type, params, IrcMessage(command = "BATCH", params = listOf("+$ref", type) + params), children)

    private fun msg(line: String) = IrcMessage.parse(line)
}
