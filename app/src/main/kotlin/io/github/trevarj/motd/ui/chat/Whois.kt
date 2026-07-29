package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.db.UserEntity
import io.github.trevarj.motd.service.PresenceState

/**
 * Parsed WHOIS details for the nick sheet (plans/16 §5.8). Every field is optional because a server
 * may omit any numeric; the sheet renders only the lines it has.
 */
data class WhoisInfo(
    val nick: String,
    val username: String? = null,
    val host: String? = null,
    val realname: String? = null,
    val server: String? = null,
    val serverInfo: String? = null,
    val account: String? = null,
    val channels: List<String> = emptyList(),
    val idleSecs: Long? = null,
    val signonEpochSecs: Long? = null,
    val awayMessage: String? = null,
    val away: Boolean? = null,
)

/** Nick-sheet state: the target nick plus its WHOIS details once they land (plans/16 §5.8). */
data class NickSheetState(
    val nick: String,
    val cached: UserEntity? = null,
    val whois: WhoisInfo? = null,
    val presence: PresenceState? = null,
) {
    val details: WhoisInfo? get() = mergeUserDetails(nick, cached, whois)
}

/** WHOIS is the fresher overlay; cached WHOX/NAMES data fills fields it did not return. */
fun mergeUserDetails(nick: String, cached: UserEntity?, whois: WhoisInfo?): WhoisInfo? {
    if (cached == null) return whois
    val cachedHost = cached.hostmask?.substringAfter('@', missingDelimiterValue = "")?.ifBlank { null }
    return (whois ?: WhoisInfo(nick)).copy(
        username = whois?.username ?: cached.username,
        host = whois?.host ?: cachedHost,
        realname = whois?.realname ?: cached.realname,
        account = whois?.account ?: cached.account,
        away = whois?.away ?: cached.away,
    )
}
