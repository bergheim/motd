package io.github.trevarj.motd.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ircRoomTargetSyntax] preserves, byte-for-byte, the pre-capability shared-UI
 * `NewConversationSheet.channelJoinTarget` behavior these three assertions used to cover directly
 * (review fix, P2 finding: shared UI must not itself assume an IRC-shaped `#`-prefix convention —
 * see [ConnectionManager.roomTargetSyntax]'s KDoc).
 */
class IrcRoomTargetSyntaxTest {
    @Test
    fun addsChannelPrefix() {
        assertEquals("#motd", ircRoomTargetSyntax("motd"))
    }

    @Test
    fun preservesAdditionalPrefixForDoubleHashChannels() {
        assertEquals("##motd", ircRoomTargetSyntax("#motd"))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals("#motd", ircRoomTargetSyntax("  motd  "))
    }
}
