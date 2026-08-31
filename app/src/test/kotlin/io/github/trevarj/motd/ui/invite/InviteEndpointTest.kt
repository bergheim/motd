package io.github.trevarj.motd.ui.invite

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.service.isDirectOftcEndpoint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteEndpointTest {
    private val direct =
        NetworkEntity(
            id = 1,
            name = "Ergo",
            role = NetworkRole.DIRECT,
            host = "irc.example",
            port = 6697,
            nick = "alice",
            username = "alice",
            realname = "Alice",
        )

    @Test
    fun `direct endpoint excludes identity credentials`() {
        assertEquals(InviteEndpoint("irc.example", 6697, true), resolveDirectInviteEndpoint(direct.copy(saslUser = "alice", saslPassword = "secret"), false))
    }

    @Test
    fun `canonical OFTC TLS endpoint supports guided registration`() {
        assertTrue(direct.copy(host = "IRC.OFTC.NET.").isDirectOftcEndpoint())
        assertEquals(false, direct.copy(host = "irc.oftc.net", tls = false).isDirectOftcEndpoint())
    }

    @Test
    fun `cached soju details avoid a blocking network refresh`() =
        runTest {
            var refreshed = false
            val attrs = mapOf("host" to "irc.example", "port" to "6697", "tls" to "1")

            assertEquals(
                attrs,
                resolveBouncerInviteAttrs(mapOf("7" to attrs), "7") {
                    refreshed = true
                    emptyList()
                },
            )
            assertFalse(refreshed)
        }

    @Test
    fun `znc cloak and server pass endpoints are refused`() {
        assertThrows(IllegalStateException::class.java) { resolveDirectInviteEndpoint(direct, true) }
        assertThrows(IllegalStateException::class.java) { resolveDirectInviteEndpoint(direct.copy(saslUser = "alice/libera"), false) }
        assertThrows(IllegalStateException::class.java) { resolveDirectInviteEndpoint(direct.copy(serverPassword = "network-pass"), false) }
    }
}
