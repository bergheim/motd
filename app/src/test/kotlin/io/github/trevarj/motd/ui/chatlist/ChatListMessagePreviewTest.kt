package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.data.db.BufferType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatListMessagePreviewTest {
    @Test
    fun `voice fallback becomes a compact voice preview`() {
        assertEquals(
            ChatListMessagePreview.Voice(durationMs = 14_000),
            chatListMessagePreview("[voice 0:14 audio/ogg] https://files.example/voice.ogg"),
        )
    }

    @Test
    fun `encrypted voice fallback becomes a compact voice preview`() {
        assertEquals(
            ChatListMessagePreview.Voice(durationMs = 62_000),
            chatListMessagePreview(
                "[voice encrypted 1:02 audio/ogg] " +
                    "https://files.example/voice.motdvoice#motd-key=secret",
            ),
        )
    }

    @Test
    fun `mixed content retains its complete text`() {
        val text = "Listen to this [voice 0:14 audio/ogg] https://files.example/voice.ogg"
        assertEquals(ChatListMessagePreview.Text(text), chatListMessagePreview(text))
    }

    @Test
    fun `ordinary messages remain unchanged`() {
        assertEquals(
            ChatListMessagePreview.Text("hello from IRC"),
            chatListMessagePreview("hello from IRC"),
        )
    }

    @Test
    fun `mode change with empty sender does not render a sender label`() {
        assertNull(
            chatListPreviewSender(
                type = BufferType.CHANNEL,
                messageText = "mode +o alice",
                sender = "",
            ),
        )
    }

    @Test
    fun `channel message with a sender retains its sender label`() {
        assertEquals(
            "alice",
            chatListPreviewSender(
                type = BufferType.CHANNEL,
                messageText = "hello from IRC",
                sender = "alice",
            ),
        )
    }
}
