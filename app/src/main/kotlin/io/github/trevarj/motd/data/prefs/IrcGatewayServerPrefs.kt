package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Cap on remembered IRC servers per XMPP gateway network. */
const val MAX_RECENT_IRC_SERVERS = 8

/**
 * Remembers the IRC servers a user has recently joined through an XMPP↔IRC gateway, per network, so
 * the humane join sheet can seed its server dropdown most-recent-first. Mirrors the lightweight
 * DataStore prefs pattern (e.g. [BouncerKindPrefs]); ordering matters here, so each network's list
 * is stored as a newline-delimited string rather than an unordered string set.
 */
interface IrcGatewayServerPrefs {
    fun recentServers(networkId: Long): Flow<List<String>>
    suspend fun remember(networkId: Long, server: String)
}

private val Context.ircGatewayServersDataStore by preferencesDataStore("irc_gateway_servers")

private fun serversKey(networkId: Long) = stringPreferencesKey("servers_$networkId")

@Singleton
class IrcGatewayServerPrefsImpl @Inject constructor(
    @ApplicationContext context: Context,
) : IrcGatewayServerPrefs {
    private val store = context.ircGatewayServersDataStore

    override fun recentServers(networkId: Long): Flow<List<String>> =
        store.data.map { prefs -> decodeServers(prefs[serversKey(networkId)]) }

    override suspend fun remember(networkId: Long, server: String) {
        store.edit { prefs ->
            val current = decodeServers(prefs[serversKey(networkId)])
            prefs[serversKey(networkId)] = encodeServers(prependRecentServer(current, server))
        }
    }
}

/** Test/default collaborator for components constructed outside Hilt. */
object NoopIrcGatewayServerPrefs : IrcGatewayServerPrefs {
    override fun recentServers(networkId: Long): Flow<List<String>> = flowOf(emptyList())
    override suspend fun remember(networkId: Long, server: String) = Unit
}

/**
 * Pure recents update: put [server] first (trimmed), drop any prior case-insensitive duplicate, and
 * cap the list at [cap]. A blank server leaves [existing] unchanged. Kept pure so the ordering/dedup
 * contract is unit-testable without a DataStore.
 */
internal fun prependRecentServer(
    existing: List<String>,
    server: String,
    cap: Int = MAX_RECENT_IRC_SERVERS,
): List<String> {
    val trimmed = server.trim()
    if (trimmed.isEmpty()) return existing
    val deduped = existing.filterNot { it.equals(trimmed, ignoreCase = true) }
    return (listOf(trimmed) + deduped).take(cap)
}

private fun decodeServers(raw: String?): List<String> =
    raw?.split('\n')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()

private fun encodeServers(servers: List<String>): String = servers.joinToString("\n")
