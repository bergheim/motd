package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.db.Protocol
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolCapabilitiesTest {
    @Test
    fun capabilities_forProtocol() {
        assertFalse(ProtocolCapabilities.forProtocol(Protocol.XMPP).reactions)
        assertTrue(ProtocolCapabilities.forProtocol(Protocol.IRC).slashCommands)
    }

    @Test
    fun xmppAllowed_filtersCommands() {
        assertTrue(ProtocolCapabilities.xmppAllowed(parseCommand("/join room@c.x")))
        assertFalse(ProtocolCapabilities.xmppAllowed(parseCommand("/whois bob")))
    }
}
