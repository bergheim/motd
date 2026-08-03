package io.github.trevarj.motd.backend

import io.github.trevarj.motd.ui.chat.WhoisInfo

/**
 * Outcome of [ProtocolCommands.sendRawLine]. [SENT] means the line parsed and was handed to the
 * transport; [INVALID] means it did not parse as a protocol command (the composer's existing
 * "invalid command" snackbar); [UNSUPPORTED] means this backend has no notion of raw protocol
 * commands at all (unreachable for the IRC adapter today, reserved for a backend that implements
 * [ProtocolCommands] for some operations but not this one).
 */
enum class RawLineOutcome { SENT, INVALID, UNSUPPORTED }

/**
 * Optional per-network capability for protocol-defined chat operations that have no first-class
 * neutral contract of their own: changing your own handle, announcing a room topic or away status,
 * sending a raw protocol command line, looking up a participant, and channel/room moderation
 * (docs/backend-neutral-xmpp-rollout.md capability list names "raw protocol commands" as an example
 * optional capability). A backend exposes no [ProtocolCommands] instance for a network it has no
 * live session for, or that has no notion of these operations at all.
 *
 * Reached only through [io.github.trevarj.motd.service.ConnectionManager.protocolCommands]. General
 * chat UI must call through that seam and must never hold a protocol client handle directly
 * ("Remove the client escape hatch"). Every `target`/`member` parameter below is a protocol-native
 * identifier (an IRC channel name and nick today); XMPP rooms/occupants map onto the same shape
 * later.
 */
interface ProtocolCommands {
    /** Change the caller's own handle/nick on this network. True if the request reached the wire. */
    suspend fun setSelfHandle(handle: String): Boolean

    /** Set [target]'s topic/subject. True if the request reached the wire. */
    suspend fun setTopic(target: String, topic: String): Boolean

    /** Announce away status; null/blank clears it. True if the request reached the wire. */
    suspend fun setAway(message: String?): Boolean

    /** Parse and send one protocol-native command line verbatim; see [RawLineOutcome]. */
    suspend fun sendRawLine(line: String): RawLineOutcome

    /**
     * Look up a participant's protocol-native profile (IRC WHOIS today). Null when the backend has
     * nothing to report or does not support the lookup; the backend may still kick off other
     * best-effort enrichment (e.g. IRC WHOX) that lands through its normal event/persistence path
     * rather than this return value.
     */
    suspend fun lookupParticipant(target: String): WhoisInfo?

    /** Remove [member] from [target], with an optional reason. True if the request reached the wire. */
    suspend fun kick(target: String, member: String, reason: String?): Boolean

    /**
     * Grant/revoke a protocol-defined flag token on [member] within [target] — e.g. IRC channel
     * mode "+o"/"-o". True if the request reached the wire. XMPP MUC roles/affiliations map onto
     * this later.
     */
    suspend fun setMemberFlag(target: String, member: String, flag: String): Boolean

    /** Ban [member] from [target]. True if the request reached the wire. */
    suspend fun banMember(target: String, member: String): Boolean

    /**
     * Protocol-defined member-flag precedence, most privileged first (e.g. IRC "~&@%+"), used for
     * moderation gating. Null when the backend has no such ordering.
     */
    fun memberFlagOrder(): String?
}
