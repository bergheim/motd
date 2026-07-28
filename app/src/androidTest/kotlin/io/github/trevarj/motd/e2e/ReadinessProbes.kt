package io.github.trevarj.motd.e2e

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.backend.ConnectionState
import io.github.trevarj.motd.data.repo.SearchRepository
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.ConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

class ConnectionProbe(private val connections: ConnectionManager, private val milestones: E2eMilestoneRecorder) {
    suspend fun awaitReady(id: Long, requiredCaps: Set<String>, timeoutMs: Long = 30_000): IrcClientState.Ready =
        withTimeout(timeoutMs) {
            connections.connectionStates.first { states ->
                when (val state = states[id]) {
                    is ConnectionState.Ready -> {
                        // Caps stay an IRC concern: read them from the live client, not the seam.
                        val caps = (connections.clientFor(id)?.state?.value as? IrcClientState.Ready)?.caps.orEmpty()
                        milestones.record("connection_ready", "network=$id caps=${caps.sorted().joinToString(",")}")
                        requiredCaps.all { cap -> caps.any { it == cap || it.startsWith("$cap=") } }
                    }
                    is ConnectionState.Failed -> {
                        milestones.record("connection_failed", "network=$id fatal=${state.fatal}")
                        if (state.fatal) error("fatal connection state")
                        false
                    }
                    null -> false
                    else -> {
                        milestones.record("connection_state", "network=$id state=${state::class.simpleName}")
                        false
                    }
                }
            }
            connections.clientFor(id)!!.state.value as IrcClientState.Ready
        }
}

class BufferProbe(private val buffers: BufferRepository, private val milestones: E2eMilestoneRecorder) {
    suspend fun awaitJoinedChannel(networkId: Long, channel: String, timeoutMs: Long = 20_000): Long =
        withTimeout(timeoutMs) {
            buffers.observeChatList().first { rows ->
                rows.any { row -> row.networkId == networkId && row.type == BufferType.CHANNEL && row.displayName.equals(channel, true) }
            }.first { it.networkId == networkId && it.type == BufferType.CHANNEL && it.displayName.equals(channel, true) }
                .bufferId.also { milestones.record("buffer_joined", "network=$networkId buffer=$it") }
        }
}

/** Uses the public search repository to observe the canonical event written by EventProcessor. */
class MessageLifecycleProbe(
    private val search: SearchRepository,
    private val milestones: E2eMilestoneRecorder,
) {
    suspend fun awaitCanonical(token: String, bufferId: Long, timeoutMs: Long = 20_000): MessageEntity =
        awaitCanonicalMatch(token, bufferId, timeoutMs) { it.text == token }

    suspend fun awaitCanonicalContaining(
        query: String,
        expectedSubstring: String,
        bufferId: Long,
        timeoutMs: Long = 20_000,
    ): MessageEntity = awaitCanonicalMatch(query, bufferId, timeoutMs) { it.text.contains(expectedSubstring) }

    private suspend fun awaitCanonicalMatch(
        query: String,
        bufferId: Long,
        timeoutMs: Long,
        matches: (MessageEntity) -> Boolean,
    ): MessageEntity =
        try {
            withTimeout(timeoutMs) {
                search.search(query, bufferId).first { hits ->
                    hits.count { hit ->
                        hit.message.isSelf && matches(hit.message) && hit.message.msgid != null &&
                            hit.message.pendingLabel == null && !hit.message.failed
                    } == 1
                }.single { hit ->
                    hit.message.isSelf && matches(hit.message) && hit.message.msgid != null &&
                        hit.message.pendingLabel == null && !hit.message.failed
                }.message.also { milestones.record("canonical_message", "buffer=$bufferId event=${it.id}") }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            milestones.record("canonical_timeout", "buffer=$bufferId")
            throw AssertionError("canonical message readiness timed out for buffer=$bufferId", failure)
        }
}
