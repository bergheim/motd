package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.db.UserEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [parseWhois] itself now lives with the IRC adapter (service/IrcProtocolCommands.kt, tested by
 * service/ParseWhoisTest.kt) — it takes IrcMessage, which shared UI must not import.
 * [mergeUserDetails] stays here: it is pure WhoisInfo/UserEntity merging with no protocol type in
 * sight.
 */
class WhoisParserTest {
    @Test fun cached_whox_fills_missing_whois_fields_without_overriding_newer_values() {
        val cached = UserEntity(
            networkId = 1,
            nick = "alice",
            username = "cached-user",
            account = "cached-account",
            away = true,
            hostmask = "cached-user@cached.host",
            realname = "Cached Real",
        )
        val merged = mergeUserDetails(
            "Alice",
            cached,
            WhoisInfo(nick = "Alice", username = "fresh-user", host = "fresh.host"),
        )!!
        assertEquals("fresh-user", merged.username)
        assertEquals("fresh.host", merged.host)
        assertEquals("cached-account", merged.account)
        assertEquals("Cached Real", merged.realname)
        assertEquals(true, merged.away)

        val cachedOnly = mergeUserDetails("Alice", cached, null)!!
        assertEquals("cached-user", cachedOnly.username)
        assertEquals("cached.host", cachedOnly.host)
    }
}
