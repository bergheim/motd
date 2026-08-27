package io.github.trevarj.motd.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectJoinBatchingTest {
    @Test
    fun `channels already replayed by the server are excluded`() {
        val toJoin =
            channelsNeedingJoin(
                remembered = listOf("#a", "#b", "#c"),
                replayedNormalized = setOf("#a", "#c"),
                normalize = { it },
            )

        assertEquals(listOf("#b"), toJoin)
    }

    @Test
    fun `normalization is applied before comparing against the replayed set`() {
        val toJoin =
            channelsNeedingJoin(
                remembered = listOf("#Libera", "#other"),
                replayedNormalized = setOf("#libera"),
                normalize = { it.lowercase() },
            )

        assertEquals(listOf("#other"), toJoin)
    }

    @Test
    fun `nothing replayed means every remembered channel still needs a self JOIN`() {
        val toJoin = channelsNeedingJoin(listOf("#a", "#b"), emptySet()) { it }

        assertEquals(listOf("#a", "#b"), toJoin)
    }

    @Test
    fun `everything replayed means nothing needs a self JOIN`() {
        val toJoin = channelsNeedingJoin(listOf("#a", "#b"), setOf("#a", "#b")) { it }

        assertTrue(toJoin.isEmpty())
    }

    @Test
    fun `a handful of short channel names fit in one batch`() {
        val batches = chunkChannelsForJoin(listOf("#a", "#b", "#c"))

        assertEquals(listOf(listOf("#a", "#b", "#c")), batches)
    }

    @Test
    fun `fifteen legal 50-byte channel names do not fit one wire-safe batch`() {
        // Reproduces the exact scenario the fixed-count JOIN_BATCH_SIZE = 15 batching missed: 15
        // legal 50-byte channel names produce a ~771-byte JOIN line, over IrcMessage's 512-byte
        // limit. Byte-budget batching must split this into more than one line.
        val channels = (1..15).map { "#" + "a".repeat(49) }

        val batches = chunkChannelsForJoin(channels)

        assertTrue("expected more than one batch, got ${batches.size}", batches.size > 1)
        for (batch in batches) {
            val lineBytes = batch.joinToString(",").toByteArray(Charsets.UTF_8).size
            assertTrue("batch line was $lineBytes bytes: $batch", lineBytes <= 500)
        }
    }

    @Test
    fun `every remembered channel appears exactly once across all batches`() {
        val channels = (1..40).map { "#channel$it" }

        val batches = chunkChannelsForJoin(channels)

        assertEquals(channels, batches.flatten())
    }

    @Test
    fun `empty input produces no batches`() {
        assertTrue(chunkChannelsForJoin(emptyList()).isEmpty())
    }

    @Test
    fun `a single channel name longer than the budget still gets its own batch`() {
        val hugeChannel = "#" + "x".repeat(600)

        val batches = chunkChannelsForJoin(listOf(hugeChannel, "#normal"))

        assertEquals(listOf(listOf(hugeChannel), listOf("#normal")), batches)
    }
}
