package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.agentwire.AGENTWIRE_TAG
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.Isupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentwireMappingTest {
    @Test
    fun `direct chat and tag messages retain only client-only tags`() {
        val mapper = EventMapper({ "me" }, { Isupport() })
        val raw = "@account=bot;${AGENTWIRE_TAG}=payload :bot!u@h PRIVMSG #agents :hello"
        val chat = mapper.map(IrcMessage.parse(raw)) as IrcEvent.ChatMessage

        assertEquals(mapOf(AGENTWIRE_TAG to "payload"), chat.ctx.clientTags)
        assertFalse("account" in chat.ctx.clientTags)

        val tag = mapper.map(IrcMessage.parse("@account=bot;${AGENTWIRE_TAG}=state :bot!u@h TAGMSG #agents")) as IrcEvent.TagMessage
        assertEquals("state", tag.ctx.clientTags[AGENTWIRE_TAG])
    }

    @Test
    fun `multiline plan keeps protocol tag only on opening batch`() {
        val plan = planChatMessage(
            target = "#agents",
            text = "first\nsecond",
            replyToMsgid = null,
            label = null,
            multilineLimits = MultilineLimits(maxBytes = 4096, maxLines = 10),
            protocolTags = mapOf(AGENTWIRE_TAG to "payload"),
        ) as MultilineSendPlan.Batch

        assertEquals("payload", plan.opening.tags[AGENTWIRE_TAG])
        assertTrue(plan.components.all { AGENTWIRE_TAG !in it.tags })
        assertTrue(plan.components.all { it.tags.keys.all { key -> key == "batch" || key == "draft/multiline-concat" } })
    }
}
