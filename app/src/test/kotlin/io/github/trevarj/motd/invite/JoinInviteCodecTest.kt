package io.github.trevarj.motd.invite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class JoinInviteCodecTest {
    private val invite =
        JoinInviteV1(
            networkName = "Private Ergo",
            host = "irc.example.test",
            port = 6697,
            channel = "#friends",
            channelKey = "open-sesame",
            certSha256 = "ab".repeat(32),
        )

    @Test
    fun `v1 canonical and install links round trip with payload in HTTPS fragment`() {
        assertEquals(invite, JoinInviteCodec.parse(JoinInviteCodec.appUri(invite)))
        assertEquals(invite, JoinInviteCodec.parseScanned(JoinInviteCodec.encode(invite)))
        val install = JoinInviteCodec.installUri(invite)
        assertEquals(invite, JoinInviteCodec.parse(install))
        assertTrue(install.startsWith("https://github.com/trevarj/motd/releases/latest#motd-invite="))

        val legacyWithoutVersion = payload("""{"networkName":"Ergo","host":"irc.example","port":6697,"channel":"#friends"}""")
        assertEquals(
            JoinInviteV1(networkName = "Ergo", host = "irc.example", port = 6697, channel = "#friends"),
            JoinInviteCodec.decode(legacyWithoutVersion),
        )
    }

    @Test
    fun `v2 direct contact round trips through every envelope`() {
        val contact =
            JoinInviteV2(
                networkName = "Ergo",
                host = "irc.example",
                port = 6697,
                contactNick = "Inviter[away]",
                certSha256 = "cd".repeat(32),
            )

        assertEquals(contact, JoinInviteCodec.decode(JoinInviteCodec.encode(contact)))
        assertEquals(contact, JoinInviteCodec.parse(JoinInviteCodec.appUri(contact)))
        assertEquals(contact, JoinInviteCodec.parse(JoinInviteCodec.installUri(contact)))
    }

    @Test
    fun `rejects wrong origin commands and unsafe fields`() {
        assertThrows(InvalidJoinInviteException::class.java) {
            JoinInviteCodec.parse("https://example.test/?v=${JoinInviteCodec.encode(invite)}")
        }
        assertThrows(InvalidJoinInviteException::class.java) {
            JoinInviteCodec.decode("a".repeat(2_049))
        }
        assertThrows(InvalidJoinInviteException::class.java) {
            JoinInviteCodec.encode(invite.copy(certSha256 = "not-a-pin"))
        }
        assertThrows(InvalidJoinInviteException::class.java) {
            JoinInviteCodec.encode(invite.copy(channel = "#ok\r\nJOIN #evil"))
        }
        assertThrows(InvalidJoinInviteException::class.java) {
            JoinInviteCodec.encode(invite.copy(channelKey = "two words"))
        }
        assertThrows(InvalidJoinInviteException::class.java) {
            JoinInviteCodec.encode(invite.copy(v = 2))
        }
        val install = JoinInviteCodec.installUri(invite)
        assertThrows(InvalidJoinInviteException::class.java) {
            JoinInviteCodec.parse(install.replace("github.com", "example.test"))
        }
        assertThrows(InvalidJoinInviteException::class.java) {
            JoinInviteCodec.parse(install.replace("/latest#", "/latest?download=1#"))
        }
    }

    @Test
    fun `versions have strict fields and unsafe contact nicks are rejected`() {
        assertThrows(InvalidJoinInviteException::class.java) {
            JoinInviteCodec.decode(payload("""{"v":3,"networkName":"Ergo","host":"irc.example","port":6697,"contactNick":"alice"}"""))
        }
        assertThrows(InvalidJoinInviteException::class.java) {
            JoinInviteCodec.decode(
                payload("""{"v":2,"networkName":"Ergo","host":"irc.example","port":6697,"contactNick":"alice","channel":"#wrong"}"""),
            )
        }
        assertThrows(InvalidJoinInviteException::class.java) {
            JoinInviteCodec.decode(
                payload("""{"v":1,"networkName":"Ergo","host":"irc.example","port":6697,"channel":"#friends","contactNick":"alice"}"""),
            )
        }
        listOf("#channel", "nick:trailing", "nick name", "nick,other", "nick\r\nPRIVMSG target :owned").forEach { nick ->
            assertThrows(InvalidJoinInviteException::class.java) {
                JoinInviteCodec.encode(JoinInviteV2(networkName = "Ergo", host = "irc.example", port = 6697, contactNick = nick))
            }
        }
    }

    @Test
    fun `plain text invitation remains representable for explicit warning path`() {
        val plaintext = invite.copy(tls = false, port = 6667, certSha256 = null)
        assertEquals(plaintext, JoinInviteCodec.decode(JoinInviteCodec.encode(plaintext)))
    }

    private fun payload(json: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(StandardCharsets.UTF_8))
}
