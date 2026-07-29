package io.github.trevarj.motd.service

import io.github.trevarj.motd.backend.BackendRegistry
import io.github.trevarj.motd.backend.ChatBackend
import io.github.trevarj.motd.backend.ConnectionState
import io.github.trevarj.motd.backend.ProtocolId
import io.github.trevarj.motd.backend.ReactionCapability
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.TimelineEventId
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.merge

/**
 * The shared seam implementation: one registry lookup per operation, never a protocol switch
 * (docs/backend-neutral-xmpp-rollout.md). Per-network and per-buffer operations dispatch to the
 * owning backend's session manager; lifecycle operations broadcast to every backend; state flows
 * are the union of the per-backend flows, whose key sets are disjoint because each backend only
 * reports its own networks. With a single registered backend every path reduces to that backend's
 * own manager, byte-for-byte.
 */
@Singleton
class CompositeConnectionManager @Inject constructor(
    private val registry: BackendRegistry,
    backends: Set<@JvmSuppressWildcards ChatBackend>,
    private val db: MotdDatabase,
) : ConnectionManager {
    private val ordered = backends.sortedBy { it.protocol.value }
    private val sessionList by lazy { ordered.map { it.sessions } }

    private suspend fun sessionsForNetwork(networkId: Long): ConnectionManager? =
        db.networkDao().byId(networkId)
            ?.let { registry.backendFor(ProtocolId(it.protocol)) }
            ?.sessions

    private suspend fun sessionsForBuffer(bufferId: Long): ConnectionManager? =
        db.bufferDao().rawById(bufferId)?.let { sessionsForNetwork(it.networkId) }

    private suspend fun sessionsForEvent(eventId: Long): ConnectionManager? =
        db.messageDao().byId(eventId)?.let { sessionsForBuffer(it.bufferId) }

    override val connectionStates: StateFlow<Map<Long, ConnectionState>> by lazy {
        CombinedStateFlow(sessionList.map { it.connectionStates }) { maps -> union(maps) }
    }
    override val rosterStates: StateFlow<Map<Long, RosterLoadState>> by lazy {
        CombinedStateFlow(sessionList.map { it.rosterStates }) { maps -> union(maps) }
    }
    override val presenceStates: StateFlow<Map<PresenceKey, PresenceState>> by lazy {
        CombinedStateFlow(sessionList.map { it.presenceStates }) { maps -> union(maps) }
    }
    override val lagStates: StateFlow<Map<Long, Long?>> by lazy {
        CombinedStateFlow(sessionList.map { it.lagStates }) { maps -> union(maps) }
    }
    override val serverPushAvailable: StateFlow<Boolean> by lazy {
        CombinedStateFlow(sessionList.map { it.serverPushAvailable }) { flags -> flags.any { it } }
    }
    override val attachmentUploadEndpoints: StateFlow<Map<Long, String>> by lazy {
        CombinedStateFlow(sessionList.map { it.attachmentUploadEndpoints }) { maps -> union(maps) }
    }
    override val reactionCapabilities: StateFlow<Map<Long, ReactionCapability>> by lazy {
        CombinedStateFlow(sessionList.map { it.reactionCapabilities }) { maps -> union(maps) }
    }
    override val certPrompts: StateFlow<List<CertPrompt>> by lazy {
        CombinedStateFlow(sessionList.map { it.certPrompts }) { lists -> lists.flatten() }
    }
    override val channelJoinOutcomes: Flow<ChannelJoinOutcome> by lazy {
        merge(*sessionList.map { it.channelJoinOutcomes }.toTypedArray())
    }

    override fun liveIdentityRules(networkId: Long): IrcIdentityRules? =
        sessionList.firstNotNullOfOrNull { it.liveIdentityRules(networkId) }

    override fun historyAvailability(networkId: Long): HistoryAvailability? =
        sessionList.firstNotNullOfOrNull { it.historyAvailability(networkId) }

    override suspend fun startAll() = sessionList.forEach { it.startAll() }
    override suspend fun stopAll() = sessionList.forEach { it.stopAll() }
    override suspend fun reconnectStale() = sessionList.forEach { it.reconnectStale() }
    override suspend fun evaluatePushMode() = sessionList.forEach { it.evaluatePushMode() }

    override suspend fun connect(networkId: Long) {
        sessionsForNetwork(networkId)?.connect(networkId)
    }

    override suspend fun disconnect(networkId: Long) {
        sessionsForNetwork(networkId)?.disconnect(networkId)
    }

    override suspend fun joinChannel(networkId: Long, channel: String) {
        sessionsForNetwork(networkId)?.joinChannel(networkId, channel)
    }

    override suspend fun ensureQueryBuffer(networkId: Long, nick: String): Long =
        sessionsForNetwork(networkId)?.ensureQueryBuffer(networkId, nick)
            ?: error("no backend for network $networkId")

    override suspend fun ensureServerBuffer(networkId: Long): Long =
        sessionsForNetwork(networkId)?.ensureServerBuffer(networkId)
            ?: error("no backend for network $networkId")

    override suspend fun sendMessage(
        bufferId: Long,
        text: String,
        replyToEventId: TimelineEventId?,
    ): SendAcceptance = sessionsForBuffer(bufferId)?.sendMessage(bufferId, text, replyToEventId)
        ?: SendAcceptance.Rejected(SendRejectionReason.BUFFER_NOT_FOUND)

    override suspend fun retryMessage(eventId: TimelineEventId): SendAcceptance =
        sessionsForEvent(eventId)?.retryMessage(eventId)
            ?: SendAcceptance.Rejected(SendRejectionReason.EVENT_NOT_RETRYABLE)

    override suspend fun sendTyping(bufferId: Long, state: String) {
        sessionsForBuffer(bufferId)?.sendTyping(bufferId, state)
    }

    override suspend fun sendReact(bufferId: Long, msgid: String, emoji: String) {
        sessionsForBuffer(bufferId)?.sendReact(bufferId, msgid, emoji)
    }

    override suspend fun partChannel(bufferId: Long, reason: String?) {
        sessionsForBuffer(bufferId)?.partChannel(bufferId, reason)
    }

    override suspend fun partChannelForClose(bufferId: Long, reason: String?): Boolean =
        sessionsForBuffer(bufferId)?.partChannelForClose(bufferId, reason) ?: false

    override suspend fun requestMembers(bufferId: Long, force: Boolean) {
        sessionsForBuffer(bufferId)?.requestMembers(bufferId, force)
    }

    override suspend fun markRead(bufferId: Long, anchor: TimelineAnchor) {
        sessionsForBuffer(bufferId)?.markRead(bufferId, anchor)
    }

    override suspend fun acceptInvite(messageId: Long) {
        sessionsForEvent(messageId)?.acceptInvite(messageId)
    }

    override suspend fun dismissInvite(messageId: Long) {
        sessionsForEvent(messageId)?.dismissInvite(messageId)
    }

    override suspend fun trustCert(prompt: CertPrompt) {
        sessionsForNetwork(prompt.networkId)?.trustCert(prompt)
    }

    override fun dismissCertPrompt(prompt: CertPrompt) {
        // Non-suspending, so no row lookup: broadcasting is safe — managers ignore foreign prompts.
        sessionList.forEach { it.dismissCertPrompt(prompt) }
    }

    private fun <K, V> union(maps: List<Map<K, V>>): Map<K, V> =
        maps.fold(emptyMap()) { acc, map -> acc + map }
}
