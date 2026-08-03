package io.github.trevarj.motd.ircbackend

import io.github.trevarj.motd.backend.ChatBackend
import io.github.trevarj.motd.backend.ProtocolId
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.ConnectionManagerImpl
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-side IRC adapter root (docs/backend-neutral-xmpp-rollout.md). Owns the "irc" discriminator
 * and exposes the IRC session manager to the composite seam. Lazy breaks the Dagger cycle through
 * the registry's backend set.
 */
@Singleton
class IrcChatBackend @Inject constructor(
    private val ircSessions: dagger.Lazy<ConnectionManagerImpl>,
) : ChatBackend {
    override val protocol: ProtocolId = IRC_PROTOCOL

    override val sessions: ConnectionManager get() = ircSessions.get()

    companion object {
        /** Persisted discriminator for IRC rows; every pre-v21 network row carries it implicitly. */
        val IRC_PROTOCOL = ProtocolId("irc")
    }
}
