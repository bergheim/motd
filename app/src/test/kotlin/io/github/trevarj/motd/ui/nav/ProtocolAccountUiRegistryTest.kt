package io.github.trevarj.motd.ui.nav

import io.github.trevarj.motd.backend.ChatBackend
import io.github.trevarj.motd.backend.InertConnectionManager
import io.github.trevarj.motd.backend.ProtocolId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Mirrors [io.github.trevarj.motd.backend.BackendRegistryTest]'s style for the UI-side registry. */
class ProtocolAccountUiRegistryTest {

    private class FakeBackend(id: String) : ChatBackend {
        override val protocol = ProtocolId(id)
        override val sessions get() = InertConnectionManager
    }

    private class FakeAccountUi(id: String, override val createRoute: Any = Unit) : ProtocolAccountUi {
        override val protocol = ProtocolId(id)
        override val labelRes: Int = 0
        override fun editRoute(networkId: Long): Any = "${protocol.value}-edit-$networkId"
    }

    @Test
    fun `entries list exactly the registered backends, sorted by protocol id`() {
        val registry = ProtocolAccountUiRegistry(
            backends = setOf(FakeBackend("xmpp"), FakeBackend("irc")),
            uis = setOf(FakeAccountUi("irc"), FakeAccountUi("xmpp")),
        )

        assertEquals(listOf("irc", "xmpp"), registry.entries.map { it.protocol.value })
    }

    @Test
    fun `uiFor resolves each registered protocol`() {
        val ircUi = FakeAccountUi("irc")
        val xmppUi = FakeAccountUi("xmpp")
        val registry = ProtocolAccountUiRegistry(
            backends = setOf(FakeBackend("irc"), FakeBackend("xmpp")),
            uis = setOf(ircUi, xmppUi),
        )

        assertEquals(ircUi, registry.uiFor(ProtocolId("irc")))
        assertEquals(xmppUi, registry.uiFor(ProtocolId("xmpp")))
    }

    @Test
    fun `unknown protocol resolves to null instead of failing`() {
        val registry = ProtocolAccountUiRegistry(
            backends = setOf(FakeBackend("irc")),
            uis = setOf(FakeAccountUi("irc")),
        )

        assertNull(registry.uiFor(ProtocolId("from-a-newer-build")))
    }

    @Test
    fun `a registered backend with no matching UI fails fast at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolAccountUiRegistry(
                backends = setOf(FakeBackend("irc"), FakeBackend("xmpp")),
                uis = setOf(FakeAccountUi("irc")),
            )
        }
    }

    @Test
    fun `editRoute for a networkId comes from the matching protocol's Ui`() {
        val registry = ProtocolAccountUiRegistry(
            backends = setOf(FakeBackend("xmpp")),
            uis = setOf(FakeAccountUi("xmpp")),
        )

        assertEquals("xmpp-edit-42", registry.uiFor(ProtocolId("xmpp"))?.editRoute(42))
    }
}
