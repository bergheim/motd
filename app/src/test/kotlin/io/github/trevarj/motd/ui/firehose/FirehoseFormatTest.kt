package io.github.trevarj.motd.ui.firehose

import io.github.trevarj.motd.data.db.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Test

class FirehoseFormatTest {
    @Test
    fun actionRendersWithLeadingStar() {
        assertEquals("* nick waves", firehoseBody("nick", "waves", MessageKind.ACTION))
    }

    @Test
    fun privmsgRendersSenderColonText() {
        assertEquals("nick: hello", firehoseBody("nick", "hello", MessageKind.PRIVMSG))
    }

    @Test
    fun noticeRendersSenderColonText() {
        assertEquals("nick: heads up", firehoseBody("nick", "heads up", MessageKind.NOTICE))
    }
}
