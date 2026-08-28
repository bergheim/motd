package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.irc.client.hasMessageRedactionCap
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRedactionWriteBoundaryTest {
    private val channel =
        BufferEntity(
            id = 1,
            networkId = 1,
            name = "#internal-alias",
            displayName = "#room",
            type = BufferType.CHANNEL,
        )

    @Test
    fun `capability requires message tags and message redaction`() {
        assertTrue(hasMessageRedactionCap(setOf("message-tags", "draft/message-redaction")))
        assertFalse(hasMessageRedactionCap(setOf("draft/message-redaction")))
        assertFalse(hasMessageRedactionCap(setOf("message-tags")))
    }

    @Test
    fun `invalid targets and failed writes are rejected`() =
        runTest {
            assertFalse(attemptMessageRedactionWrite(null, "m1") { true })
            assertFalse(attemptMessageRedactionWrite(channel.copy(type = BufferType.SERVER), "m1") { true })
            assertFalse(attemptMessageRedactionWrite(channel.copy(pendingCloseAt = 1), "m1") { true })
            listOf("", ":trailing", "two ids", "bad\rmsgid").forEach { msgid ->
                assertFalse(attemptMessageRedactionWrite(channel, msgid) { true })
            }
            assertFalse(attemptMessageRedactionWrite(channel, "m1") { false })
            assertFalse(attemptMessageRedactionWrite(channel, "m1") { error("socket closed") })
        }

    @Test
    fun `accepted redaction writes one exact IRC line`() =
        runTest {
            val lines = mutableListOf<String>()

            assertTrue(
                attemptMessageRedactionWrite(channel, "opaque/id=7") { message ->
                    lines += message.serialize()
                    true
                },
            )

            assertEquals(listOf("REDACT #room opaque/id=7"), lines)
        }
}
