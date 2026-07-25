package io.github.trevarj.motd.xmpp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BiboumiAddressesTest {
    @Test
    fun room_prettyName_stripsIrcPrefix_andKeepsChannelHash() {
        assertEquals(
            "#systemcrafters · libera.chat",
            biboumiRoomDisplayName("#systemcrafters%irc.libera.chat@irc.xmpp.glvortex.net"),
        )
    }

    @Test
    fun room_prettyName_serverWithoutIrcPrefix_keptWhole() {
        assertEquals(
            "#chan · chat.freenode.net",
            biboumiRoomDisplayName("#chan%chat.freenode.net@gw.example.net"),
        )
    }

    @Test
    fun room_plainMuc_isNotPrettified() {
        assertNull(biboumiRoomDisplayName("room@conference.example.net"))
    }

    @Test
    fun room_weirdPercentPlacement_isRejected() {
        assertNull(biboumiRoomDisplayName("%irc.libera.chat@gw.example.net")) // empty channel
        assertNull(biboumiRoomDisplayName("#chan%@gw.example.net")) // empty server
        assertNull(biboumiRoomDisplayName("#a%b%c@gw.example.net")) // multiple '%'
        assertNull(biboumiRoomDisplayName("#chan%irc.libera.chat")) // no '@'
    }

    @Test
    fun nick_prettyName_dropsServerSuffix() {
        assertEquals("someone", biboumiNickDisplayName("someone!irc.libera.chat@irc.xmpp.glvortex.net"))
    }

    @Test
    fun nick_plainJid_isNotPrettified() {
        assertNull(biboumiNickDisplayName("bob@example.net"))
    }

    @Test
    fun nick_weirdBangPlacement_isRejected() {
        assertNull(biboumiNickDisplayName("!irc.libera.chat@gw.example.net")) // empty nick
        assertNull(biboumiNickDisplayName("nick!@gw.example.net")) // empty server
        assertNull(biboumiNickDisplayName("nick!irc.libera.chat")) // no '@'
    }
}
