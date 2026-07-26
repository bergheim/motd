package io.github.trevarj.motd.data.sync

import io.github.trevarj.motd.data.db.BufferType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-function truth table for the notification eligibility gate shared by IRC and XMPP. */
class NotificationGateTest {

    @Test fun ownEchoNeverNotifies() {
        assertFalse(shouldNotify(isSelf = true, type = BufferType.QUERY, hasMention = false))
        assertFalse(shouldNotify(isSelf = true, type = BufferType.CHANNEL, hasMention = true))
    }

    @Test fun serverBufferNeverNotifies() {
        // A MOTD/console line containing the nick must not fire a mention.
        assertFalse(shouldNotify(isSelf = false, type = BufferType.SERVER, hasMention = true))
    }

    @Test fun directMessageAlwaysNotifies() {
        assertTrue(shouldNotify(isSelf = false, type = BufferType.QUERY, hasMention = false))
        assertTrue(shouldNotify(isSelf = false, type = BufferType.QUERY, hasMention = true))
    }

    @Test fun channelNotifiesOnlyOnMention() {
        assertTrue(shouldNotify(isSelf = false, type = BufferType.CHANNEL, hasMention = true))
        assertFalse(shouldNotify(isSelf = false, type = BufferType.CHANNEL, hasMention = false))
    }
}
