package io.github.trevarj.motd.service

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.BuildConfig
import io.github.trevarj.motd.R
import io.github.trevarj.motd.audio.AudioActivityTracker
import io.github.trevarj.motd.audio.AudioPlaybackController
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.identityRules
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.sync.ChatSoundPlayer
import io.github.trevarj.motd.data.visibility.MessageVisibilityPolicy
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlin.math.pow

internal enum class ChatSoundCue {
    SEND,
    RECEIVE,
}

internal fun shouldPlayIncomingChatSound(
    enabled: Boolean,
    foregroundBufferId: Long?,
    bufferId: Long,
    type: BufferType,
    muted: Boolean,
    senderIsFool: Boolean,
): Boolean = enabled &&
    foregroundBufferId == bufferId &&
    type != BufferType.SERVER &&
    !muted &&
    !senderIsFool

internal fun shouldPlayOutgoingChatSound(
    enabled: Boolean,
    foregroundBufferId: Long?,
    bufferId: Long,
    muted: Boolean,
): Boolean = enabled && foregroundBufferId == bufferId && !muted

internal fun isFoolForChatSound(
    fools: Set<String>,
    identityRules: IrcIdentityRules,
    senderAccount: String?,
    normalizedActor: String,
): Boolean = MessageVisibilityPolicy(
    MessageVisibilitySpec(fools = fools),
    identityRules,
).matchesFoolIdentity(senderAccount, normalizedActor)

internal fun incomingChatSoundCue(
    enabled: Boolean,
    foregroundBufferId: Long?,
    bufferId: Long,
    type: BufferType,
    muted: Boolean,
    senderIsFool: Boolean,
): ChatSoundCue? = ChatSoundCue.RECEIVE.takeIf {
    shouldPlayIncomingChatSound(
        enabled = enabled,
        foregroundBufferId = foregroundBufferId,
        bufferId = bufferId,
        type = type,
        muted = muted,
        senderIsFool = senderIsFool,
    )
}

internal fun outgoingChatSoundCue(
    enabled: Boolean,
    foregroundBufferId: Long?,
    bufferId: Long,
    muted: Boolean,
): ChatSoundCue? = ChatSoundCue.SEND.takeIf {
    shouldPlayOutgoingChatSound(
        enabled = enabled,
        foregroundBufferId = foregroundBufferId,
        bufferId = bufferId,
        muted = muted,
    )
}

/**
 * Process-lifetime receive melody. Only eligible RECEIVE cues advance it; SEND is deliberately
 * inert so outgoing activity cannot alter an incoming conversation's cadence.
 */
internal class ChatReceiveMelody {
    private var lastEligibleBufferId: Long? = null
    private var lastEligibleAtNanos: Long = Long.MIN_VALUE
    private var nextIndex: Int = 0

    fun playbackRate(cue: ChatSoundCue, bufferId: Long, nowNanos: Long): Float = synchronized(this) {
        if (cue == ChatSoundCue.SEND) return@synchronized NORMAL_RATE

        if (
            lastEligibleBufferId != bufferId ||
            nowNanos - lastEligibleAtNanos >= RESET_AFTER_SILENCE_NANOS
        ) {
            nextIndex = 0
        }
        val rate = RECEIVE_RATES[nextIndex]
        nextIndex = (nextIndex + 1) % RECEIVE_RATES.size
        lastEligibleBufferId = bufferId
        lastEligibleAtNanos = nowNanos
        rate
    }

    private companion object {
        const val NORMAL_RATE = 1f
        const val RESET_AFTER_SILENCE_NANOS = 2_000_000_000L
        val RECEIVE_RATES = floatArrayOf(
            NORMAL_RATE,
            2.0.pow(2.0 / 12.0).toFloat(),
            2.0.pow(4.0 / 12.0).toFloat(),
            2.0.pow(7.0 / 12.0).toFloat(),
        )
    }
}

internal data class PendingChatSoundPlayback(
    val cue: ChatSoundCue,
    val playbackRate: Float,
)

internal class ChatSoundReadinessQueue {
    private val pending = EnumMap<ChatSoundCue, PendingChatSoundPlayback>(ChatSoundCue::class.java)

    fun request(cue: ChatSoundCue, playbackRate: Float, ready: Boolean): PendingChatSoundPlayback? =
        synchronized(this) {
            val playback = PendingChatSoundPlayback(cue, playbackRate)
            if (ready) {
                playback
            } else {
                pending[cue] = playback
                null
            }
        }

    fun markLoaded(cue: ChatSoundCue): PendingChatSoundPlayback? = synchronized(this) {
        pending.remove(cue)
    }

    fun markLoadFailed(cue: ChatSoundCue) = synchronized(this) {
        pending.remove(cue)
    }
}

/**
 * Process-lifetime sonification using two small, original PCM samples. Sonification attributes
 * keep the cues subordinate to Android's ringer/silent policy without requesting audio focus.
 */
@Singleton
internal class SoundPoolChatSoundBackend @Inject constructor(
    @ApplicationContext context: Context,
    private val diagnostics: DiagnosticLogger,
) {
    private val soundPool = try {
        SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
    } catch (error: Exception) {
        trace("create_failed", null, "error" to error::class.simpleName)
        null
    }
    private val sampleIds = EnumMap<ChatSoundCue, Int>(ChatSoundCue::class.java)
    private val cuesBySampleId = ConcurrentHashMap<Int, ChatSoundCue>()
    private val readySampleIds = ConcurrentHashMap.newKeySet<Int>()
    private val readinessQueue = ChatSoundReadinessQueue()

    init {
        soundPool?.setOnLoadCompleteListener { _, sampleId, status ->
            val cue = cuesBySampleId[sampleId]
            if (status == LOAD_SUCCESS) {
                readySampleIds += sampleId
                trace("loaded", cue, "status" to status)
                cue?.let { loadedCue ->
                    readinessQueue.markLoaded(loadedCue)?.let(::playReady)
                }
            } else {
                cue?.let(readinessQueue::markLoadFailed)
                trace("load_failed", cue, "status" to status)
            }
        }
        register(context, ChatSoundCue.SEND, R.raw.chat_send)
        register(context, ChatSoundCue.RECEIVE, R.raw.chat_receive)
    }

    private fun register(context: Context, cue: ChatSoundCue, resourceId: Int) {
        val pool = soundPool ?: return
        val sampleId = try {
            pool.load(context, resourceId, LOAD_PRIORITY)
        } catch (error: Exception) {
            trace("load_failed", cue, "error" to error::class.simpleName)
            return
        }
        if (sampleId == LOAD_FAILED) {
            trace("load_rejected", cue)
            return
        }
        sampleIds[cue] = sampleId
        cuesBySampleId[sampleId] = cue
    }

    fun play(cue: ChatSoundCue, playbackRate: Float = NORMAL_RATE) {
        val sampleId = sampleIds[cue]
        if (sampleId == null) {
            trace("not_ready", cue)
            return
        }
        val playback = readinessQueue.request(cue, playbackRate, sampleId in readySampleIds)
        if (playback == null) {
            trace("queued_not_ready", cue)
            return
        }
        playReady(playback)
    }

    private fun playReady(playback: PendingChatSoundPlayback) {
        val pool = soundPool ?: return
        val sampleId = sampleIds[playback.cue]
        if (sampleId == null || sampleId !in readySampleIds) {
            trace("not_ready", playback.cue)
            return
        }
        val streamId = try {
            pool.play(
                sampleId,
                PLAYBACK_VOLUME,
                PLAYBACK_VOLUME,
                PLAYBACK_PRIORITY,
                NO_LOOP,
                playback.playbackRate,
            )
        } catch (error: Exception) {
            trace("play_failed", playback.cue, "error" to error::class.simpleName)
            return
        }
        if (streamId == PLAY_FAILED) {
            trace("play_failed", playback.cue)
        } else {
            trace("played", playback.cue)
        }
    }

    private fun trace(event: String, cue: ChatSoundCue?, vararg fields: Pair<String, Any?>) {
        diagnostics.record("chat_sound", event) {
            buildMap {
                cue?.let { put("cue", it.name.lowercase()) }
                fields.forEach { (key, value) -> put(key, value) }
            }
        }
        if (BuildConfig.DEBUG && Log.isLoggable(LOG_TAG, Log.DEBUG)) {
            Log.d(
                LOG_TAG,
                buildString {
                    append(event)
                    cue?.let { append(" cue=").append(it.name.lowercase()) }
                    fields.forEach { (key, value) -> append(' ').append(key).append('=').append(value) }
                },
            )
        }
    }

    private companion object {
        const val LOG_TAG = "MotdChatSound"
        const val MAX_STREAMS = 2
        const val LOAD_PRIORITY = 1
        const val LOAD_SUCCESS = 0
        const val LOAD_FAILED = 0
        const val PLAYBACK_VOLUME = 1f
        const val PLAYBACK_PRIORITY = 1
        const val NO_LOOP = 0
        const val NORMAL_RATE = 1f
        const val PLAY_FAILED = 0
    }
}

@Singleton
class AndroidChatSoundPlayer @Inject internal constructor(
    private val db: MotdDatabase,
    private val foregroundBufferTracker: ForegroundBufferTracker,
    private val settingsRepository: SettingsRepository,
    private val backend: SoundPoolChatSoundBackend,
    private val audioPlaybackController: AudioPlaybackController,
    private val audioActivityTracker: AudioActivityTracker,
) : ChatSoundPlayer {
    private val receiveMelody = ChatReceiveMelody()

    override suspend fun onIncoming(
        bufferId: Long,
        type: BufferType,
        message: MessageEntity,
    ) = playIncoming(
        bufferId = bufferId,
        type = type,
        senderAccount = message.senderAccount,
        normalizedActor = message.normalizedActor,
    )

    private suspend fun playIncoming(
        bufferId: Long,
        type: BufferType,
        senderAccount: String?,
        normalizedActor: String,
    ) {
        val buffer = db.bufferDao().observeById(bufferId) ?: return
        if (audioActivityTracker.recording.value || audioPlaybackController.state.value.playing) return
        val settings = settingsRepository.settings.first()
        val identityRules = db.networkIdentityDao().byNetwork(buffer.networkId)?.identityRules
            ?: IrcIdentityRules()
        val cue = incomingChatSoundCue(
            enabled = settings.chatSoundsEnabled,
            foregroundBufferId = foregroundBufferTracker.foregroundBufferId.value,
            bufferId = bufferId,
            type = type,
            muted = buffer.muted,
            senderIsFool = isFoolForChatSound(
                settings.fools,
                identityRules,
                senderAccount,
                normalizedActor,
            ),
        ) ?: return
        backend.play(
            cue,
            receiveMelody.playbackRate(cue, bufferId, android.os.SystemClock.elapsedRealtimeNanos()),
        )
    }

    override suspend fun onOutgoingAccepted(bufferId: Long) {
        val buffer = db.bufferDao().observeById(bufferId) ?: return
        if (audioActivityTracker.recording.value || audioPlaybackController.state.value.playing) return
        val settings = settingsRepository.settings.first()
        val cue = outgoingChatSoundCue(
            enabled = settings.chatSoundsEnabled,
            foregroundBufferId = foregroundBufferTracker.foregroundBufferId.value,
            bufferId = bufferId,
            muted = buffer.muted,
        ) ?: return
        backend.play(
            cue,
            receiveMelody.playbackRate(cue, bufferId, android.os.SystemClock.elapsedRealtimeNanos()),
        )
    }
}
