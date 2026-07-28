package io.github.trevarj.motd.ircbackend

import io.github.trevarj.motd.backend.ChatBackend
import io.github.trevarj.motd.backend.ProtocolId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-side IRC adapter root (docs/backend-neutral-xmpp-rollout.md). Owns the "irc" discriminator;
 * the neutral session/capability surface accretes here as callers migrate off IRC-typed seams.
 */
@Singleton
class IrcChatBackend @Inject constructor() : ChatBackend {
    override val protocol: ProtocolId = IRC_PROTOCOL

    companion object {
        /** Persisted discriminator for IRC rows; every pre-v21 network row carries it implicitly. */
        val IRC_PROTOCOL = ProtocolId("irc")
    }
}
