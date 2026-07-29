package io.github.trevarj.motd.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BackendRegistryTest {
    private class FakeBackend(id: String) : ChatBackend {
        override val protocol = ProtocolId(id)
        override val sessions get() = InertConnectionManager
    }

    @Test
    fun `resolves each registered backend by its protocol`() {
        val first = FakeBackend("first-proto")
        val second = FakeBackend("second-proto")
        val registry = BackendRegistry(setOf(first, second))

        assertEquals(first, registry.backendFor(ProtocolId("first-proto")))
        assertEquals(second, registry.backendFor(ProtocolId("second-proto")))
    }

    @Test
    fun `unknown protocol resolves to null instead of failing`() {
        val registry = BackendRegistry(setOf(FakeBackend("first-proto")))

        assertNull(registry.backendFor(ProtocolId("from-a-newer-build")))
    }

    @Test
    fun `duplicate protocol registration fails at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackendRegistry(setOf(FakeBackend("dup"), FakeBackend("dup")))
        }
    }
}
