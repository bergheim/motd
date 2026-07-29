package io.github.trevarj.motd.backend

import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.TimelineEventId
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.SendAcceptance
import io.github.trevarj.motd.service.SendRejectionReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Session manager for a backend whose session layer has not landed yet, and for contract-test
 * fakes. Broadcast lifecycle operations are inert; per-network operations are unreachable until
 * the backend can own network rows, so sends are rejected and buffer creation fails loudly.
 */
object InertConnectionManager : ConnectionManager {
    override val connectionStates: StateFlow<Map<Long, ConnectionState>> = MutableStateFlow(emptyMap())
    override suspend fun startAll() = Unit
    override suspend fun stopAll() = Unit
    override suspend fun connect(networkId: Long) = Unit
    override suspend fun disconnect(networkId: Long) = Unit
    override suspend fun reconnectStale() = Unit

    override suspend fun sendMessage(
        bufferId: Long,
        text: String,
        replyToEventId: TimelineEventId?,
    ): SendAcceptance = SendAcceptance.Rejected(SendRejectionReason.BUFFER_NOT_FOUND)

    override suspend fun sendTyping(bufferId: Long, state: String) = Unit
    override suspend fun sendReact(bufferId: Long, msgid: String, emoji: String) = Unit
    override suspend fun joinChannel(networkId: Long, channel: String) = Unit
    override suspend fun partChannel(bufferId: Long, reason: String?) = Unit

    override suspend fun ensureQueryBuffer(networkId: Long, nick: String): Long =
        error("this backend has no session layer yet (docs/backend-neutral-xmpp-rollout.md)")

    override suspend fun ensureServerBuffer(networkId: Long): Long =
        error("this backend has no session layer yet (docs/backend-neutral-xmpp-rollout.md)")

    override suspend fun markRead(bufferId: Long, anchor: TimelineAnchor) = Unit
    override suspend fun evaluatePushMode() = Unit
    override val certPrompts: StateFlow<List<CertPrompt>> = MutableStateFlow(emptyList())
    override suspend fun trustCert(prompt: CertPrompt) = Unit
    override fun dismissCertPrompt(prompt: CertPrompt) = Unit
}
