package io.github.trevarj.motd.data.sync

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity

/**
 * Low-latency foreground chat sonification, kept separate from Android notifications.
 *
 * Consumes the neutral canonical row only; no IRC wire type crosses this boundary (plans/backend-
 * neutral-xmpp-rollout §Shared MOTD behavior).
 */
interface ChatSoundPlayer {
    suspend fun onIncoming(bufferId: Long, type: BufferType, message: MessageEntity)

    suspend fun onOutgoingAccepted(bufferId: Long)

    object Noop : ChatSoundPlayer {
        override suspend fun onIncoming(
            bufferId: Long,
            type: BufferType,
            message: MessageEntity,
        ) = Unit

        override suspend fun onOutgoingAccepted(bufferId: Long) = Unit
    }
}
