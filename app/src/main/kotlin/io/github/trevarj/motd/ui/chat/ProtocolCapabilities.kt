package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.db.Protocol

/**
 * Per-protocol composer/UI affordances (Task 9). IRC keeps every existing behavior; XMPP disables
 * IRC-only slash commands, reactions (no shared-reaction wire mechanism yet), and reply threading,
 * while keeping 1:1 typing notifications in scope.
 */
data class ProtocolCapabilities(
    val slashCommands: Boolean,
    val reactions: Boolean,
    val replies: Boolean,
    val typing: Boolean,
) {
    companion object {
        val IRC = ProtocolCapabilities(slashCommands = true, reactions = true, replies = true, typing = true)
        val XMPP = ProtocolCapabilities(slashCommands = false, reactions = false, replies = false, typing = true)

        fun forProtocol(p: Protocol) = if (p == Protocol.XMPP) XMPP else IRC

        /** Commands still meaningful on XMPP; everything else is rejected client-side. */
        fun xmppAllowed(cmd: ChatCommand): Boolean =
            cmd is ChatCommand.Message || cmd is ChatCommand.Join || cmd is ChatCommand.Part ||
                cmd is ChatCommand.Query || cmd is ChatCommand.Msg || cmd is ChatCommand.None
    }
}
