package io.github.trevarj.motd.xmpp

/**
 * Pretty display names for JIDs that route through a Biboumi XMPP↔IRC gateway. Pure and Smack-free
 * so [XmppEventProcessor] can call them while staying unit-testable.
 *
 * The processor does not know the set of gateway component domains synchronously, so these use a
 * shape heuristic instead of a domain check (documented per function). It is safe because a plain
 * MUC room JID never contains '%' in its localpart and a plain 1:1 JID never contains '!' — both
 * characters only appear in Biboumi's encoded IRC addresses (XEP-0106 would escape a literal '%'
 * to "\25" in a real localpart, so an unescaped '%' here is always Biboumi's server separator).
 */

/**
 * Display name for a Biboumi IRC-channel room JID of the form `<channel>%<server>@<gateway>`, e.g.
 * `#systemcrafters%irc.libera.chat@irc.xmpp.glvortex.net` → `#systemcrafters · libera.chat`. The
 * channel name keeps its `#`; the server drops a leading `irc.`. Returns null for any JID that is
 * not this exact shape (no '@', empty channel, empty/multi-'%' server) so the caller falls back to
 * the raw JID and never mangles a plain MUC.
 */
internal fun biboumiRoomDisplayName(roomJid: String): String? {
    val at = roomJid.indexOf('@')
    if (at <= 0) return null
    val local = roomJid.substring(0, at)
    val pct = local.indexOf('%')
    // pct <= 0 means no separator or an empty channel name ("%server"): not a gateway room.
    if (pct <= 0) return null
    val channel = local.substring(0, pct)
    val server = local.substring(pct + 1)
    // A well-formed Biboumi server part is a single hostname with no further '%'.
    if (server.isEmpty() || '%' in server) return null
    return "$channel · ${shortenIrcServer(server)}"
}

/**
 * Display name for a Biboumi private-message JID of the form `<nick>!<server>@<gateway>`, e.g.
 * `someone!irc.libera.chat@irc.xmpp.glvortex.net` → `someone`. Returns null for any JID that is not
 * this shape (no '@', empty nick, empty server) so plain 1:1 JIDs pass through unchanged.
 */
internal fun biboumiNickDisplayName(bareJid: String): String? {
    val at = bareJid.indexOf('@')
    if (at <= 0) return null
    val local = bareJid.substring(0, at)
    val bang = local.indexOf('!')
    // Exact-shape discipline (mirrors the room parser): require exactly one '!' with a non-empty
    // nick and server. bang <= 0 means no separator or an empty nick ("!server"); a second '!'
    // means the localpart is not a clean `<nick>!<server>`. Anything else stays unprettified.
    if (bang <= 0 || bang != local.lastIndexOf('!')) return null
    val server = local.substring(bang + 1)
    if (server.isEmpty()) return null
    return local.substring(0, bang)
}

/** Strip a leading "irc." label from an IRC server hostname for display ("irc.libera.chat" → "libera.chat"). */
private fun shortenIrcServer(server: String): String = server.removePrefix("irc.")
