package io.github.trevarj.motd.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import io.github.trevarj.motd.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class AudioWaveformAnalyzer
    @Inject
    constructor(
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        suspend fun analyze(file: File): AudioWaveform? =
            withContext(ioDispatcher) {
                runCatching { analyzeBlocking(file) }.getOrNull()
            }

        private fun analyzeBlocking(file: File): AudioWaveform? {
            val extractor = MediaExtractor()
            var decoder: MediaCodec? = null
            try {
                extractor.setDataSource(file.absolutePath)
                val track =
                    (0 until extractor.trackCount).firstOrNull { index ->
                        extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    } ?: return null
                extractor.selectTrack(track)
                val inputFormat = extractor.getTrackFormat(track)
                val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
                val durationUs = inputFormat.getLong(MediaFormat.KEY_DURATION).takeIf { it > 0 }
                decoder =
                    MediaCodec.createDecoderByType(mime).apply {
                        configure(inputFormat, null, null, 0)
                        start()
                    }
                val info = MediaCodec.BufferInfo()
                val peaks = IntArray(AudioWaveform.DISPLAY_PEAKS)
                val chunkPeaks = mutableListOf<Int>()
                var inputEnded = false
                var outputEnded = false
                var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
                while (!outputEnded) {
                    if (!inputEnded) {
                        val inputIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val input = decoder.getInputBuffer(inputIndex) ?: continue
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val outputIndex = decoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outputFormat = decoder.outputFormat
                            pcmEncoding =
                                if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                    outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                } else {
                                    AudioFormat.ENCODING_PCM_16BIT
                                }
                        }

                        MediaCodec.INFO_TRY_AGAIN_LATER -> {}

                        else -> {
                            if (outputIndex >= 0) {
                                decoder.getOutputBuffer(outputIndex)?.let { output ->
                                    output.position(info.offset)
                                    output.limit(info.offset + info.size)
                                    val peak = peakAmplitude(output.slice().order(ByteOrder.nativeOrder()), pcmEncoding)
                                    if (durationUs != null) {
                                        val bin =
                                            ((info.presentationTimeUs * peaks.size) / durationUs)
                                                .toInt()
                                                .coerceIn(0, peaks.lastIndex)
                                        if (peak > peaks[bin]) peaks[bin] = peak
                                    } else {
                                        chunkPeaks += peak
                                    }
                                }
                                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                decoder.releaseOutputBuffer(outputIndex, false)
                            }
                        }
                    }
                }
                val amplitudes = if (durationUs != null) peaks.toList() else chunkPeaks
                return AudioWaveform.fromAmplitudes(amplitudes)
            } finally {
                runCatching { decoder?.stop() }
                runCatching { decoder?.release() }
                extractor.release()
            }
        }

        private fun peakAmplitude(
            buffer: java.nio.ByteBuffer,
            encoding: Int,
        ): Int =
            when (encoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> {
                    val samples = buffer.asFloatBuffer()
                    var peak = 0f
                    while (samples.hasRemaining()) peak = maxOf(peak, abs(samples.get()))
                    (peak.coerceIn(0f, 1f) * 32_767).toInt()
                }

                AudioFormat.ENCODING_PCM_8BIT -> {
                    var peak = 0
                    while (buffer.hasRemaining()) peak = maxOf(peak, abs((buffer.get().toInt() and 0xff) - 128) * 256)
                    peak.coerceAtMost(32_767)
                }

                else -> {
                    val samples = buffer.asShortBuffer()
                    var peak = 0
                    while (samples.hasRemaining()) peak = maxOf(peak, abs(samples.get().toInt()))
                    peak.coerceAtMost(32_767)
                }
            }

        private companion object {
            const val DEQUEUE_TIMEOUT_US = 10_000L
        }
    }
