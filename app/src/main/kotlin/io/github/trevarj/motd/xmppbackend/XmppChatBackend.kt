package io.github.trevarj.motd.xmppbackend

import io.github.trevarj.motd.backend.ChatBackend
import io.github.trevarj.motd.backend.InertConnectionManager
import io.github.trevarj.motd.backend.ProtocolId
import io.github.trevarj.motd.service.ConnectionManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-side XMPP adapter root (docs/backend-neutral-xmpp-rollout.md). Owns the "xmpp"
 * discriminator; the Smack-backed session, processor, and capabilities accrete here slice by
 * slice, always writing through the shared canonical repositories.
 */
@Singleton
class XmppChatBackend @Inject constructor() : ChatBackend {
    override val protocol: ProtocolId = XMPP_PROTOCOL

    /** Inert until the Smack session slice lands; the composite then dispatches XMPP rows here. */
    override val sessions: ConnectionManager get() = InertConnectionManager

    companion object {
        /** Persisted discriminator for XMPP rows; detail lives in `xmpp_accounts`. */
        val XMPP_PROTOCOL = ProtocolId("xmpp")
    }
}
