package io.github.trevarj.motd.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import dagger.hilt.android.AndroidEntryPoint
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.ui.chat.ComposerDraftStore
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

/**
 * Direct-reply / mark-read notification actions. The RemoteInput reply is forwarded to
 * [ConnectionManager.sendMessage]; the mark-read action to [ConnectionManager.markRead].
 */
@AndroidEntryPoint
class ReplyReceiver : BroadcastReceiver() {
    @Inject lateinit var connectionManager: ConnectionManager

    @Inject @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    @Inject lateinit var diagnostics: DiagnosticLogger

    @Inject lateinit var notifications: MotdNotifications

    @Inject lateinit var draftStore: ComposerDraftStore

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val bufferId = intent.getLongExtra(EXTRA_BUFFER_ID, -1L)
        if (bufferId < 0) return
        when (intent.action) {
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
                if (text.isNullOrBlank()) return
                launchAsync(applicationScope, TAG) { send(bufferId, text, retry = false) }
            }

            ACTION_RETRY_REPLY -> {
                val text = intent.getStringExtra(EXTRA_REPLY_TEXT)
                if (text.isNullOrBlank()) return
                launchAsync(applicationScope, TAG) { send(bufferId, text, retry = true) }
            }

            ACTION_MARK_READ -> {
                if (!intent.hasExtra(EXTRA_UP_TO_TIME)) return
                val upTo = intent.getLongExtra(EXTRA_UP_TO_TIME, 0L)
                val eventId = intent.getLongExtra(EXTRA_UP_TO_EVENT_ID, 0L)
                launchAsync(applicationScope, TAG) {
                    connectionManager.markRead(bufferId, TimelineAnchor(upTo, eventId))
                }
            }

            else -> {}
        }
    }

    private suspend fun send(
        bufferId: Long,
        text: String,
        retry: Boolean,
    ) = deliverNotificationReply(
        retry = retry,
        send = { connectionManager.sendMessage(bufferId, text) },
        preserveDraft = { preserveRejectedDraft(bufferId, text) },
        releaseDraft = { clearRetriedDraft(bufferId, text) },
        notifyFailed = { reason ->
            Log.w(TAG, "notification reply rejected: $reason")
            diagnostics.record("notification_reply", "send_rejected") {
                mapOf("buffer_id" to bufferId, "reason" to reason.name, "retry" to retry)
            }
            notifications.onReplyFailed(bufferId, text, reason)
        },
        notifyResolved = { notifications.onReplyFailureResolved(bufferId) },
    )

    /** Never clobber an in-progress composer draft; the rejected text is appended to it. */
    private suspend fun preserveRejectedDraft(
        bufferId: Long,
        text: String,
    ) {
        runCatching {
            val existing = draftStore.loadDraft(bufferId)
            draftStore.saveDraft(
                bufferId = bufferId,
                text = mergeRejectedReply(existing?.text, text),
                replyToEventId = existing?.replyToEventId,
            )
        }.onFailure { Log.w(TAG, "preserving rejected reply failed", it) }
    }

    /** Drop the preserved copy once the retry lands, unless the user has since edited the draft. */
    private suspend fun clearRetriedDraft(
        bufferId: Long,
        text: String,
    ) {
        runCatching {
            val existing = draftStore.loadDraft(bufferId) ?: return@runCatching
            val remaining = withoutRetriedReply(existing.text, text) ?: return@runCatching
            draftStore.saveDraft(
                bufferId = bufferId,
                text = remaining,
                replyToEventId = existing.replyToEventId,
            )
        }.onFailure { Log.w(TAG, "clearing retried reply draft failed", it) }
    }

    companion object {
        const val ACTION_REPLY = "io.github.trevarj.motd.service.REPLY"
        const val ACTION_RETRY_REPLY = "io.github.trevarj.motd.service.RETRY_REPLY"
        const val ACTION_MARK_READ = "io.github.trevarj.motd.service.MARK_READ"
        const val EXTRA_BUFFER_ID = "bufferId"
        const val EXTRA_REPLY_TEXT = "replyText"
        const val EXTRA_UP_TO_TIME = "upToTime"
        const val EXTRA_UP_TO_EVENT_ID = "upToEventId"
        const val KEY_REPLY = "key_reply"
        private const val TAG = "ReplyReceiver"
    }
}

/**
 * A rejected send persisted nothing, so the RemoteInput UI's completion animation is a lie and no
 * failed timeline row exists to retry from. Keep the text in the buffer's composer draft — the same
 * place an in-app rejection leaves it — and raise a retryable failure notification. A successful
 * retry retires both.
 */
internal suspend fun deliverNotificationReply(
    retry: Boolean,
    send: suspend () -> SendAcceptance,
    preserveDraft: suspend () -> Unit,
    releaseDraft: suspend () -> Unit,
    notifyFailed: suspend (SendRejectionReason) -> Unit,
    notifyResolved: suspend () -> Unit,
) {
    when (val acceptance = send()) {
        is SendAcceptance.Accepted -> {
            if (retry) {
                releaseDraft()
                notifyResolved()
            }
        }

        is SendAcceptance.Rejected -> {
            // The retry path already preserved the text on the original rejection.
            if (!retry) preserveDraft()
            notifyFailed(acceptance.reason)
        }
    }
}

/** Append a rejected reply to whatever the composer already holds, preserving both. */
internal fun mergeRejectedReply(
    existing: String?,
    rejected: String,
): String = if (existing.isNullOrBlank()) rejected else "$existing\n$rejected"

/**
 * The draft text left after a retried reply is accepted, or null when the draft no longer contains
 * the preserved copy (the user edited or replaced it) and must be left untouched.
 */
internal fun withoutRetriedReply(
    existing: String?,
    retried: String,
): String? =
    when {
        existing == null -> null
        existing == retried -> ""
        existing.endsWith("\n$retried") -> existing.removeSuffix("\n$retried")
        else -> null
    }
