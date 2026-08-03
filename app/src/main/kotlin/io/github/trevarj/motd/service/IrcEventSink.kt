package io.github.trevarj.motd.service

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.proto.IrcIdentityRules

/**
 * IRC-adapter ingestion seams. These are intentionally IRC-typed: every producer is an adapter
 * component (live socket events, synthesized diagnostics, decrypted push payloads, CHATHISTORY
 * pages), and EventProcessor is the IRC backend's sole processor writing through the shared
 * canonical repositories (docs/backend-neutral-xmpp-rollout.md). Shared code never touches these.
 */
/** Sole IRC→Room write path. Implemented by EventProcessor (WP5); ConnectionManager delegates
 *  its pending-send insert here; push (WP9) feeds decrypted lines through it. WP1 stub-binds. */
interface IrcEventSink {
    suspend fun process(networkId: Long, event: io.github.trevarj.motd.irc.event.IrcEvent)

    /** Persist a push-delivered event without treating it as live IRC session state. */
    suspend fun processPush(networkId: Long, event: io.github.trevarj.motd.irc.event.IrcEvent)

    /** Persist one completed protocol page together with its exact primary-message boundaries. */
    suspend fun persistHistoryPage(
        networkId: Long,
        request: io.github.trevarj.motd.irc.client.ChatHistoryRequest,
        response: io.github.trevarj.motd.irc.client.ChatHistoryResponse.Messages,
        expectedRoomId: Long? = null,
    ): Long
}

private val JOIN_FAILURE_NUMERICS = setOf("403", "405", "471", "473", "474", "475", "476")

/** Extracts only JOIN-specific numeric and IRCv3 FAIL replies; unrelated server errors stay inert. */
internal fun channelJoinOutcome(
    networkId: Long,
    event: IrcEvent,
    identityRules: IrcIdentityRules,
): ChannelJoinOutcome.Rejected? {
    val (params, reason) = when (event) {
        is IrcEvent.ServerError -> if (event.code in JOIN_FAILURE_NUMERICS) {
            event.params to event.text.ifBlank { event.code }
        } else {
            return null
        }
        is IrcEvent.StandardReply -> {
            if (event.severity != IrcEvent.StandardReplySeverity.FAIL ||
                !event.commandName.equals("JOIN", ignoreCase = true)
            ) return null
            event.context to event.description.ifBlank { event.code }
        }
        else -> return null
    }
    val channel = params.firstOrNull(identityRules::isChannel) ?: return null
    return ChannelJoinOutcome.Rejected(networkId, channel, reason)
}
