package io.github.trevarj.motd.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformScrubberTest {
    @Test
    fun `waveform is stable bounded and specific to an audio item`() {
        val first = waveformBars("voice:first", 48)

        assertEquals(first, waveformBars("voice:first", 48))
        assertNotEquals(first, waveformBars("voice:second", 48))
        assertEquals(48, first.size)
        assertTrue(first.all { it in 0.2f..0.9f })
    }

    @Test
    fun `real waveform is resampled to stable bar count`() {
        val bars = listOf(0.1f, 0.5f, 0.9f).resampleBars(6)

        assertEquals(6, bars.size)
        assertTrue(bars.all { it in 0.08f..1f })
        assertEquals(bars, listOf(0.1f, 0.1f, 0.5f, 0.5f, 0.9f, 0.9f))
    }

    @Test
    fun `waveform heights zoom to the available range`() {
        assertEquals(
            listOf(0.25f, 0.5f, 1f),
            normalizeWaveformHeights(listOf(1f, 2f, 4f)),
        )
        assertEquals(emptyList<Float>(), normalizeWaveformHeights(emptyList()))
    }

    @Test
    fun `voice speed cycles through compact player options`() {
        assertEquals(1.5f, nextVoiceSpeed(1f))
        assertEquals(2f, nextVoiceSpeed(1.5f))
        assertEquals(1f, nextVoiceSpeed(2f))
    }
}
