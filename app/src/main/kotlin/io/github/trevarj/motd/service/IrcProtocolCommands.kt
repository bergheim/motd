package io.github.trevarj.motd.service

import io.github.trevarj.motd.backend.ProtocolCommands
import io.github.trevarj.motd.backend.RawLineOutcome
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.ui.channelinfo.banMask
import io.github.trevarj.motd.ui.channelinfo.prefixOrderFrom
import io.github.trevarj.motd.ui.chat.WhoisInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * IRC-backed [ProtocolCommands], bound to one live [IrcClient] (docs/backend-neutral-xmpp-rollout.md
 * "Remove the client escape hatch"). [ConnectionManagerImpl.protocolCommands] constructs a fresh
 * instance per lookup, so it is always current-session; callers must not cache it across suspension
 * points, matching the [io.github.trevarj.motd.ircbackend.IrcSessions] contract this replaces for
 * general chat UI. [scope] fires best-effort background enrichment (WHOX) that must not delay or be
 * cancelled by the caller's own coroutine.
 */
class IrcProtocolCommands(
    private val client: IrcClient,
    private val scope: CoroutineScope,
) : ProtocolCommands {

    override suspend fun setSelfHandle(handle: String): Boolean =
        client.sendIfConnected(IrcMessage(command = "NICK", params = listOf(handle)))

    override suspend fun setTopic(target: String, topic: String): Boolean =
        client.sendIfConnected(IrcMessage(command = "TOPIC", params = listOf(target, topic)))

    override suspend fun setAway(message: String?): Boolean =
        client.sendIfConnected(IrcMessage(command = "AWAY", params = listOfNotNull(message)))

    /**
     * Parses exactly like the former `ChatViewModel.submitRawLine`/`ChatCommand.RawLine` sites: an
     * unparseable line or a blank command is [RawLineOutcome.INVALID]; otherwise the message is
     * fired (not gated on live transport, matching the previous fire-and-forget `client.send`).
     */
    override suspend fun sendRawLine(line: String): RawLineOutcome {
        val msg = runCatching { IrcMessage.parse(line) }.getOrNull()
        if (msg == null || msg.command.isBlank()) return RawLineOutcome.INVALID
        client.send(msg)
        return RawLineOutcome.SENT
    }

    /**
     * WHOX (when advertised) is kicked off in [scope] and never awaited here: its rows flow through
     * EventProcessor into UserEntity on their own, independent of whether this call's caller is
     * still around (plans/16 §5.8). The returned [WhoisInfo] comes only from a correlated
     * labeled-response WHOIS; without `labeled-response` a plain WHOIS is still sent so its numerics
     * surface through the normal server-buffer path, but this call reports null.
     */
    override suspend fun lookupParticipant(target: String): WhoisInfo? {
        if (client.isupport["WHOX"] != null) {
            scope.launch { runCatching { client.whox(target) } }
        }
        val whoisMsg = IrcMessage(command = "WHOIS", params = listOf(target))
        return if (client.hasCap("labeled-response")) {
            val lines = runCatching { client.sendLabeled(whoisMsg) }.getOrNull().orEmpty()
            parseWhois(lines)
        } else {
            runCatching { client.send(whoisMsg) }
            null
        }
    }

    override suspend fun kick(target: String, member: String, reason: String?): Boolean {
        val params = if (reason.isNullOrBlank()) listOf(target, member) else listOf(target, member, reason)
        return client.sendIfConnected(IrcMessage(command = "KICK", params = params))
    }

    override suspend fun setMemberFlag(target: String, member: String, flag: String): Boolean =
        client.sendIfConnected(IrcMessage(command = "MODE", params = listOf(target, flag, member)))

    override suspend fun banMember(target: String, member: String): Boolean =
        client.sendIfConnected(IrcMessage(command = "MODE", params = listOf(target, "+b", banMask(member))))

    override fun memberFlagOrder(): String = prefixOrderFrom(client.isupport.prefixModes)
}

/**
 * Fold WHOIS numerics from a labeled response into a [WhoisInfo]. Recognized numerics:
 *
 * - `311` RPL_WHOISUSER: params = [me, nick, user, host, "*", realname]
 * - `312` RPL_WHOISSERVER: params = [me, nick, server, serverInfo]
 * - `301` RPL_AWAY: params = [me, nick, awayMessage]
 * - `317` RPL_WHOISIDLE: params = [me, nick, idleSecs, (signon), ...]
 * - `319` RPL_WHOISCHANNELS: params = [me, nick, channelList]
 * - `330` RPL_WHOISACCOUNT: params = [me, nick, account, "is logged in as"]
 *
 * Returns null when neither a `311` nor a `318` (end-of-WHOIS) is present, i.e. the response does
 * not describe a real WHOIS (plans/16 §5.8 acceptance).
 */
fun parseWhois(lines: List<IrcMessage>): WhoisInfo? {
    val has311 = lines.any { it.command == "311" }
    val has318 = lines.any { it.command == "318" }
    if (!has311 && !has318) return null

    // Nick comes from the 311/318 second param; fall back to any WHOIS numeric's nick param.
    val nick = lines.firstNotNullOfOrNull { it.params.getOrNull(1)?.takeIf { n -> n.isNotEmpty() } }
        ?: return null

    var info = WhoisInfo(nick = nick)
    for (msg in lines) {
        val p = msg.params
        when (msg.command) {
            "311" -> info = info.copy(
                username = p.getOrNull(2),
                host = p.getOrNull(3),
                realname = p.getOrNull(5),
            )
            "312" -> info = info.copy(server = p.getOrNull(2), serverInfo = p.getOrNull(3))
            "301" -> info = info.copy(awayMessage = p.getOrNull(2), away = true)
            "317" -> info = info.copy(
                idleSecs = p.getOrNull(2)?.toLongOrNull(),
                signonEpochSecs = p.getOrNull(3)?.toLongOrNull(),
            )
            "319" -> info = info.copy(
                channels = p.getOrNull(2)?.trim()?.split(' ')?.filter { it.isNotEmpty() }.orEmpty(),
            )
            "330" -> info = info.copy(account = p.getOrNull(2))
        }
    }
    return info
}
