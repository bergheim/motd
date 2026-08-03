package io.github.trevarj.motd.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.attachment.AttachmentSource
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.attachment.normalizedConfig
import io.github.trevarj.motd.audio.AudioActivityTracker
import io.github.trevarj.motd.audio.AudioPlaybackController
import io.github.trevarj.motd.audio.AudioWaveform
import io.github.trevarj.motd.audio.CompletedVoiceRecording
import io.github.trevarj.motd.audio.VoiceConfig
import io.github.trevarj.motd.audio.VoiceMessageSender
import io.github.trevarj.motd.audio.VoiceRecorder
import io.github.trevarj.motd.audio.VoiceRecordingProfile
import io.github.trevarj.motd.audio.VoiceSendProgress
import io.github.trevarj.motd.audio.VoiceSendRequest
import io.github.trevarj.motd.audio.VoicePrefs
import io.github.trevarj.motd.ui.nav.ChatRoute
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VoiceRecordingUi(
    val elapsedMs: Long,
    val locked: Boolean,
    val waveform: AudioWaveform = AudioWaveform.EMPTY,
)

data class StagedVoiceMessage(
    val file: File,
    val durationMs: Long,
    val mimeType: String,
    val extension: String,
    val sizeBytes: Long,
    val encrypted: Boolean,
    val destination: PasteBackendConfig?,
    val waveform: AudioWaveform,
) {
    val source: AttachmentSource.LocalFile
        get() = AttachmentSource.LocalFile(file, file.name, mimeType, sizeBytes)
}

data class VoiceMessageUiState(
    val config: VoiceConfig = VoiceConfig(),
    val recording: VoiceRecordingUi? = null,
    val staged: StagedVoiceMessage? = null,
    val progress: VoiceSendProgress? = null,
    val error: String? = null,
    val notice: String? = null,
)

internal const val VOICE_PERMISSION_DENIED_ERROR =
    "Microphone permission is required to record voice messages. Enable it in system settings and try again."

@HiltViewModel
class VoiceMessageViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val recorder: VoiceRecorder,
    private val sender: VoiceMessageSender,
    private val prefs: VoicePrefs,
    private val activityTracker: AudioActivityTracker,
    private val playbackController: AudioPlaybackController,
) : ViewModel() {
    private val route: ChatRoute = savedStateHandle.toRoute<ChatRoute>()
    private val bufferId: Long = route.bufferId
    private val _state = MutableStateFlow(VoiceMessageUiState())
    val state: StateFlow<VoiceMessageUiState> = _state.asStateFlow()
    private val config = prefs.config.stateIn(viewModelScope, SharingStarted.Eagerly, VoiceConfig())
    private var recordingStartedAtMs: Long = 0L
    private var timerJob: Job? = null
    private var sendJob: Job? = null
    private val recordingAmplitudes = mutableListOf<Int>()

    init {
        viewModelScope.launch {
            config.collectLatest { config ->
                _state.update { current ->
                    current.copy(config = config)
                }
            }
        }
    }

    fun startRecording(locked: Boolean) {
        if (_state.value.recording != null) return
        playbackController.pause()
        val active = try {
            recorder.start(
                VoiceRecordingProfile(
                    quality = config.value.quality,
                    noiseReduction = config.value.noiseReduction,
                ),
            )
        } catch (error: Exception) {
            _state.update { it.copy(error = error.message ?: "Could not start recording.") }
            return
        }
        recordingStartedAtMs = active.startedAtMs
        recordingAmplitudes.clear()
        activityTracker.setRecording(true)
        _state.update {
            it.copy(
                recording = VoiceRecordingUi(elapsedMs = 0L, locked = locked),
                error = null,
            )
        }
        if (active.processingFallback) {
            viewModelScope.launch {
                if (prefs.takeNoiseFallbackNotice()) {
                    _state.update {
                        it.copy(notice = "Device noise reduction is unavailable. Recording uses the natural microphone.")
                    }
                }
            }
        }
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                delay(RECORDING_TICK_MS)
                val elapsed = System.currentTimeMillis() - recordingStartedAtMs
                recorder.currentAmplitude()?.let(recordingAmplitudes::add)
                if (elapsed >= MAX_RECORDING_MS) {
                    stopRecording()
                    break
                }
                _state.update { current ->
                    current.copy(
                        recording = current.recording?.copy(
                            elapsedMs = elapsed,
                            waveform = AudioWaveform.fromAmplitudes(recordingAmplitudes),
                        ),
                    )
                }
            }
        }
    }

    fun lockRecording() {
        _state.update { it.copy(recording = it.recording?.copy(locked = true)) }
    }

    fun stopRecording() {
        if (_state.value.recording == null) return
        timerJob?.cancel()
        timerJob = null
        activityTracker.setRecording(false)
        val completed = recorder.stop()
        recordingAmplitudes.clear()
        _state.update { current ->
            current.copy(
                recording = null,
                staged = completed?.toStaged(current.config),
                error = if (completed == null) "Recording was too short." else null,
            )
        }
    }

    fun stopForBackground() {
        if (_state.value.recording != null) stopRecording()
    }

    fun cancelRecording() {
        timerJob?.cancel()
        timerJob = null
        recorder.cancel()
        recordingAmplitudes.clear()
        activityTracker.setRecording(false)
        _state.update { it.copy(recording = null, error = null) }
    }

    fun deleteStaged() {
        _state.value.staged?.let { staged ->
            dismissIfPreviewing(staged.file)
            staged.file.delete()
        }
        _state.update { it.copy(staged = null, progress = null, error = null) }
    }

    fun toggleEncryption() {
        _state.update { current ->
            current.copy(staged = current.staged?.copy(encrypted = !current.staged.encrypted))
        }
    }

    fun setDestination(config: PasteBackendConfig?) {
        val normalized = config?.let(::normalizedConfig)
        viewModelScope.launch { prefs.setRememberedDestination(normalized) }
        _state.update { current ->
            current.copy(staged = current.staged?.copy(destination = normalized))
        }
    }

    fun setEncryptionDefault(enabled: Boolean) = viewModelScope.launch {
        prefs.setEncryptionDefault(enabled)
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /** A denied Android permission must remain visible instead of looking like a broken control. */
    fun recordingPermissionDenied() {
        _state.update { it.copy(error = VOICE_PERMISSION_DENIED_ERROR) }
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun send() {
        val staged = _state.value.staged ?: return
        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            try {
                sender.send(
                    VoiceSendRequest(
                        bufferId = bufferId,
                        file = staged.file,
                        durationMs = staged.durationMs,
                        mimeType = staged.mimeType,
                        extension = staged.extension,
                        sizeBytes = staged.sizeBytes,
                        waveform = staged.waveform,
                        encrypt = staged.encrypted,
                        destination = staged.destination,
                    ),
                ).collect { progress ->
                    _state.update { it.copy(progress = progress, error = null) }
                    if (progress is VoiceSendProgress.Complete) {
                        dismissIfPreviewing(staged.file)
                        staged.file.delete()
                        _state.update { it.copy(staged = null, progress = null) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update {
                    it.copy(progress = null, error = error.message ?: "Voice message failed.")
                }
            }
        }
    }

    override fun onCleared() {
        sendJob?.cancel()
        cancelRecording()
        _state.value.staged?.let { staged ->
            dismissIfPreviewing(staged.file)
            staged.file.delete()
        }
    }

    private fun dismissIfPreviewing(file: File) {
        playbackController.dismiss("voice:${file.toURI()}")
    }

    private fun CompletedVoiceRecording.toStaged(config: VoiceConfig): StagedVoiceMessage =
        StagedVoiceMessage(
            file = file,
            durationMs = durationMs,
            mimeType = mimeType,
            extension = extension,
            sizeBytes = sizeBytes,
            encrypted = config.encryptionDefault,
            destination = config.rememberedDestination,
            waveform = waveform,
        )

    private companion object {
        const val RECORDING_TICK_MS = 250L
        const val MAX_RECORDING_MS = 30L * 60L * 1000L
    }
}
