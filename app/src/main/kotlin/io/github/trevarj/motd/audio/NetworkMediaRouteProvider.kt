package io.github.trevarj.motd.audio

import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObfsMode
import io.github.trevarj.motd.data.prefs.CertTrustStore
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefs
import io.github.trevarj.motd.service.LocalSocksProvider
import io.github.trevarj.motd.service.PinningTrustManager
import io.github.trevarj.motd.service.resolveTransportProxy
import kotlinx.coroutines.flow.first
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext

data class NetworkMediaRoute(
    val networkId: Long,
    val endpoint: NetworkEntity,
    val proxy: Proxy?,
    val proxyError: String?,
    val authorizationHeader: String?,
    val endpointPinnedSha256: String? = null,
    private val release: () -> Unit = {},
) : AutoCloseable {
    fun open(
        url: String,
        authenticated: Boolean = false,
    ): HttpURLConnection {
        val parsedUrl = URL(url)
        val connection =
            if (proxy != null) {
                parsedUrl.openConnection(proxy)
            } else {
                parsedUrl.openConnection()
            } as HttpURLConnection
        if (
            connection is HttpsURLConnection &&
            endpointPinnedSha256 != null &&
            parsedUrl.host.equals(endpoint.host, ignoreCase = true)
        ) {
            val port = parsedUrl.port.takeIf { it >= 0 } ?: parsedUrl.defaultPort
            val trustManager = PinningTrustManager(parsedUrl.host, port, endpointPinnedSha256)
            connection.sslSocketFactory =
                SSLContext
                    .getInstance("TLS")
                    .apply {
                        init(null, arrayOf(trustManager), null)
                    }.socketFactory
            connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
        if (authenticated) {
            authorizationHeader?.let { connection.setRequestProperty("Authorization", it) }
        }
        return connection
    }

    override fun close() = release()
}

/** Narrow seam over [NetworkMediaRouteProvider] so HTTP repositories are testable without Room. */
fun interface MediaRouteResolver {
    suspend fun routeForNetwork(networkId: Long): NetworkMediaRoute?
}

/**
 * Whether the app-global fetch stacks (the process Coil loader, ExoPlayer) may load network content
 * for one network. Those stacks cannot be routed per-network, so an obfuscated transport answers
 * false by default and the UI withholds that content instead of fetching it directly — unless the
 * user opts into direct media on proxied networks, trading tunnel privacy for previews that load.
 */
fun interface DirectMediaPolicy {
    suspend fun directMediaAllowed(networkId: Long): Boolean
}

@Singleton
class NetworkMediaRouteProvider
    @Inject
    constructor(
        private val db: MotdDatabase,
        private val localSocksProvider: LocalSocksProvider,
        private val certTrustStore: CertTrustStore,
        private val contentPreviewPrefs: ContentPreviewPrefs,
    ) : MediaRouteResolver,
        DirectMediaPolicy {
        override suspend fun routeForNetwork(networkId: Long): NetworkMediaRoute? {
            val row = db.networkDao().byId(networkId) ?: return null
            val endpoint =
                if (row.role == NetworkRole.BOUNCER_CHILD) {
                    row.parentId?.let { db.networkDao().byId(it) } ?: row
                } else {
                    row
                }
            val resolved = resolveTransportProxy(endpoint, localSocksProvider, ownerKey = "media-$networkId")
            return NetworkMediaRoute(
                networkId = networkId,
                endpoint = endpoint,
                proxy = resolved.proxy,
                proxyError = resolved.error,
                authorizationHeader =
                    endpoint.basicAuthorizationHeader(
                        childNetworkSelector = row.bouncerNetId.takeIf { row.role == NetworkRole.BOUNCER_CHILD },
                    ),
                endpointPinnedSha256 = certTrustStore.pinnedFor(endpoint.host, endpoint.port),
                release = resolved.release,
            )
        }

        override suspend fun directMediaAllowed(networkId: Long): Boolean {
            // Unknown networks always fail closed: the opt-in cannot rescue a fetch whose transport
            // policy we cannot even resolve.
            val row = db.networkDao().byId(networkId) ?: return false
            // A bouncer child shares its physical endpoint (and therefore its transport policy) with
            // the bouncer root, exactly as routeForNetwork does above.
            val endpoint =
                if (row.role == NetworkRole.BOUNCER_CHILD) {
                    row.parentId?.let { db.networkDao().byId(it) } ?: row
                } else {
                    row
                }
            if (endpoint.obfsMode == null || endpoint.obfsMode == ObfsMode.NONE) return true
            // Obfuscated transport: the global stacks would fetch outside the tunnel. Permit that only
            // when the user has explicitly opted in, accepting that the device IP reaches the media host.
            return contentPreviewPrefs.config.first().directMediaOnProxiedNetworks
        }
    }

/** Known IRC networks whose app-global media requests may use the device connection directly. */
internal fun directMediaAllowedNetworkIds(
    networks: List<NetworkEntity>,
    directMediaOnProxiedNetworks: Boolean,
): Set<Long> {
    val byId = networks.associateBy(NetworkEntity::id)
    return networks
        .asSequence()
        .filter { row ->
            val endpoint =
                if (row.role == NetworkRole.BOUNCER_CHILD) {
                    row.parentId?.let(byId::get) ?: row
                } else {
                    row
                }
            endpoint.obfsMode == null || endpoint.obfsMode == ObfsMode.NONE || directMediaOnProxiedNetworks
        }.mapTo(mutableSetOf(), NetworkEntity::id)
}

internal fun NetworkEntity.basicAuthorizationHeader(childNetworkSelector: String? = null): String? {
    if (!saslMechanism.equals("PLAIN", ignoreCase = true)) return null
    val baseUser =
        saslUser?.takeIf(String::isNotBlank)
            ?: username.takeIf(String::isNotBlank)
            ?: nick.takeIf(String::isNotBlank)
            ?: return null
    val user = childNetworkSelector?.takeIf(String::isNotBlank)?.let { "$baseUser/$it" } ?: baseUser
    val password = saslPassword?.takeIf(String::isNotBlank) ?: return null
    val token = Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))
    return "Basic $token"
}
