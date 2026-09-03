package io.github.trevarj.motd.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.audio.AudioWaveform

private const val WAVEFORM_SAMPLE_COUNT = 48
private const val WAVE_CYCLE_DURATION_MILLIS = 1_400
private const val WAVE_CYCLES = 1.5f
private const val WAVE_TRAVEL = 0.12f

/** Compact audio timeline used by both received audio and staged voice-message previews. */
@Composable
fun WaveformScrubber(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    seed: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    waveform: AudioWaveform? = null,
    playing: Boolean = false,
) {
    val fraction = value.coerceIn(0f, 1f)
    val samples =
        remember(seed, waveform) {
            normalizeWaveformHeights(
                waveform
                    ?.normalized
                    ?.takeIf { it.isNotEmpty() }
                    ?.resampleBars(WAVEFORM_SAMPLE_COUNT)
                    ?: waveformBars(seed, WAVEFORM_SAMPLE_COUNT),
            )
        }
    val waveProgress = remember { Animatable(0f) }
    val ribbonPath = remember { Path() }
    val sampleHeights = remember(samples.size) { FloatArray(samples.size) }
    val sampleOffsets = remember(samples.size) { FloatArray(samples.size) }
    val playedColor = MaterialTheme.colorScheme.tertiary
    val remainingColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
    val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)

    LaunchedEffect(playing) {
        if (!playing) {
            waveProgress.snapTo(0f)
            return@LaunchedEffect
        }
        while (true) {
            waveProgress.snapTo(0f)
            waveProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = WAVE_CYCLE_DURATION_MILLIS, easing = LinearEasing),
            )
        }
    }

    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(36.dp)
                .semantics {
                    contentDescription = "Audio position"
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    setProgress { target ->
                        if (!enabled) return@setProgress false
                        onValueChange(target.coerceIn(0f, 1f))
                        onValueChangeFinished()
                        true
                    }
                }.pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)

                        fun update(x: Float) {
                            onValueChange((x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f))
                        }
                        update(down.position.x)
                        down.consume()
                        var pressed = true
                        while (pressed) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            update(change.position.x)
                            pressed = change.pressed
                            change.consume()
                        }
                        onValueChangeFinished()
                    }
                },
    ) {
        if (samples.size < 2 || size.width <= 0f || size.height <= 0f) return@Canvas

        val centerY = size.height / 2f
        val maximumHalfHeight = (centerY - 4.dp.toPx()).coerceAtLeast(0f)
        val maximumTravel = maximumHalfHeight * WAVE_TRAVEL
        val lastIndex = samples.lastIndex
        val phase = waveProgress.value * (Math.PI * 2.0)

        samples.forEachIndexed { index, sample ->
            val xFraction = index.toFloat() / lastIndex
            sampleHeights[index] = maximumHalfHeight * sample
            sampleOffsets[index] =
                if (playing) {
                    (
                        kotlin.math.sin(
                            xFraction * Math.PI * 2.0 * WAVE_CYCLES - phase,
                        ) * maximumTravel
                    ).toFloat()
                } else {
                    0f
                }
        }

        ribbonPath.reset()
        var previousX = 0f
        var previousY = centerY + sampleOffsets[0] - sampleHeights[0]
        ribbonPath.moveTo(previousX, previousY)
        for (index in 1..lastIndex) {
            val x = size.width * index / lastIndex
            val y = centerY + sampleOffsets[index] - sampleHeights[index]
            val controlOffset = (x - previousX) / 2f
            ribbonPath.cubicTo(
                previousX + controlOffset,
                previousY,
                x - controlOffset,
                y,
                x,
                y,
            )
            previousX = x
            previousY = y
        }
        previousX = size.width
        previousY = centerY + sampleOffsets[lastIndex] + sampleHeights[lastIndex]
        ribbonPath.lineTo(previousX, previousY)
        for (index in (lastIndex - 1) downTo 0) {
            val x = size.width * index / lastIndex
            val y = centerY + sampleOffsets[index] + sampleHeights[index]
            val controlOffset = (previousX - x) / 2f
            ribbonPath.cubicTo(
                previousX - controlOffset,
                previousY,
                x + controlOffset,
                y,
                x,
                y,
            )
            previousX = x
            previousY = y
        }
        ribbonPath.close()

        drawPath(ribbonPath, if (enabled) remainingColor else disabledColor)
        if (enabled && fraction > 0f) {
            clipRect(right = size.width * fraction) {
                drawPath(ribbonPath, playedColor)
            }
        }
    }
}

internal fun normalizeWaveformHeights(samples: List<Float>): List<Float> {
    val maximum = samples.maxOrNull()?.takeIf { it > 0f } ?: return samples
    return samples.map { (it / maximum).coerceIn(0.08f, 1f) }
}

internal fun List<Float>.resampleBars(count: Int): List<Float> {
    if (isEmpty()) return emptyList()
    return List(count.coerceAtLeast(1)) { index ->
        val start = index * size / count
        val end = ((index + 1) * size / count).coerceAtLeast(start + 1)
        subList(start.coerceAtMost(lastIndex), end.coerceAtMost(size)).maxOrNull().orEmptyPeak()
    }
}

private fun Float?.orEmptyPeak(): Float = (this ?: 0f).coerceIn(0.08f, 1f)

internal fun waveformBars(
    seed: String,
    count: Int,
): List<Float> {
    var state = seed.hashCode().takeIf { it != 0 } ?: 0x6d2b79f5
    return List(count.coerceAtLeast(1)) { index ->
        state = state xor (state shl 13)
        state = state xor (state ushr 17)
        state = state xor (state shl 5)
        val noise = (state and Int.MAX_VALUE) / Int.MAX_VALUE.toFloat()
        val envelope = 0.65f + 0.35f * kotlin.math.abs(kotlin.math.sin((index + 1) * 0.72f))
        (0.2f + noise * 0.65f * envelope).coerceIn(0.2f, 0.9f)
    }
}
