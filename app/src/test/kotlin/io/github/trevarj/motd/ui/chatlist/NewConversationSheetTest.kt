package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewConversationSheetTest {
    private val xmppNetwork = NetworkEntity(
        name = "xmpp",
        protocol = Protocol.XMPP,
        role = NetworkRole.DIRECT,
        host = "xmpp.example",
        port = 5222,
        nick = "me",
        username = "me",
        realname = "Me",
    )
    private val ircNetwork = xmppNetwork.copy(protocol = Protocol.IRC)

    @Test
    fun channelJoinTarget_addsChannelPrefix() {
        assertEquals("#motd", channelJoinTarget("motd"))
    }

    @Test
    fun channelJoinTarget_preservesAdditionalPrefixForDoubleHashChannels() {
        assertEquals("##motd", channelJoinTarget("#motd"))
    }

    @Test
    fun channelJoinTarget_trimsSurroundingWhitespace() {
        assertEquals("#motd", channelJoinTarget("  motd  "))
    }

    @Test
    fun joinTarget_xmpp_noHashPrefix() {
        assertEquals("room@c.x", joinTarget(xmppNetwork, "room@c.x"))
        assertEquals("#chan", joinTarget(ircNetwork, "chan"))
        assertEquals(channelJoinTarget("chan"), joinTarget(ircNetwork, "chan"))
    }

    @Test
    fun joinTarget_xmpp_trimsWhitespaceWithoutChannelPrefix() {
        assertEquals("room@c.x", joinTarget(xmppNetwork, "  room@c.x  "))
    }

    @Test
    fun joinTarget_xmpp_bareName_expandsToAccountConferenceService() {
        val net = xmppNetwork.copy(jid = "me@example.net")
        assertEquals("motd-e2e@conference.example.net", joinTarget(net, "motd-e2e"))
        assertEquals("motd-e2e@conference.example.net", joinTarget(net, "  #MOTD-E2E "))
    }

    @Test
    fun joinTarget_xmpp_bareName_fallsBackToHostWhenJidMissing() {
        assertEquals("room@conference.xmpp.example", joinTarget(xmppNetwork, "room"))
    }

    @Test
    fun joinTarget_xmpp_fullJid_neverRewritten() {
        val net = xmppNetwork.copy(jid = "me@example.net")
        assertEquals("room@muc.other.org", joinTarget(net, "room@muc.other.org"))
    }

    @Test
    fun isValidJid_acceptsUserAndRoomAddresses() {
        assertTrue(isValidJid("user@example.net"))
        assertTrue(isValidJid("room@conference.example.net"))
        assertTrue(isValidJid("user@example.net/resource"))
    }

    @Test
    fun isValidJid_rejectsMalformedAddresses() {
        assertFalse(isValidJid("nouser"))
        assertFalse(isValidJid("@example.net"))
        assertFalse(isValidJid("user@"))
        assertFalse(isValidJid("user@nodothost"))
    }
}
