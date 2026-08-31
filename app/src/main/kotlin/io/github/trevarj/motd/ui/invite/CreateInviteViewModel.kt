package io.github.trevarj.motd.ui.invite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ircTarget
import io.github.trevarj.motd.data.prefs.BouncerKindPrefs
import io.github.trevarj.motd.data.prefs.CertTrustStore
import io.github.trevarj.motd.data.prefs.InviteEnrollmentStore
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.invite.JoinInviteCodec
import io.github.trevarj.motd.invite.JoinInviteV1
import io.github.trevarj.motd.irc.client.BouncerNetwork
import io.github.trevarj.motd.service.ConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

private const val DEFAULT_TLS_PORT = 6697
private const val DEFAULT_PLAIN_PORT = 6667
private const val INVITE_ENDPOINT_TIMEOUT_MS = 10_000L

data class CreateInviteUiState(
    val loading: Boolean = true,
    val invite: JoinInviteV1? = null,
    val channelKey: String = "",
    val includeKeyConfirmed: Boolean = false,
    val qrText: String? = null,
    val error: String? = null,
)

@HiltViewModel
class CreateInviteViewModel
    @Inject
    constructor(
        private val buffers: BufferRepository,
        private val networks: NetworkRepository,
        private val connections: ConnectionManager,
        private val endpointResolver: InviteEndpointResolver,
        private val certs: CertTrustStore,
        private val enrollment: InviteEnrollmentStore,
    ) : ViewModel() {
        private val _state = MutableStateFlow(CreateInviteUiState())
        val state: StateFlow<CreateInviteUiState> = _state.asStateFlow()
        private var initialized = false

        fun init(bufferId: Long) {
            if (initialized) return
            initialized = true
            viewModelScope.launch {
                try {
                    val buffer = buffers.observeBuffer(bufferId).first() ?: error("Conversation no longer exists")
                    if (buffer.type != BufferType.CHANNEL) error("Only channels can be invited to")
                    val network = networks.networkById(buffer.networkId) ?: error("Network no longer exists")
                    val endpoint = endpointResolver.resolve(network)
                    val normalize = connections.clientFor(network.id)?.isupport?.let { support -> support::normalize } ?: { value: String -> value.lowercase() }
                    val storedKey = enrollment.channelKey(network.id, normalize(buffer.ircTarget)).orEmpty()
                    val pin = if (network.role == NetworkRole.DIRECT) certs.pinnedFor(endpoint.host, endpoint.port) else null
                    val invite =
                        JoinInviteV1(
                            networkName = network.name,
                            host = endpoint.host,
                            port = endpoint.port,
                            tls = endpoint.tls,
                            channel = buffer.ircTarget,
                            certSha256 = pin,
                        )
                    _state.value = CreateInviteUiState(loading = false, invite = invite, channelKey = storedKey)
                    rebuild()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    _state.value = CreateInviteUiState(loading = false, error = error.message ?: "Invite unavailable")
                }
            }
        }

        fun editChannelKey(value: String) {
            _state.value = _state.value.copy(channelKey = value, includeKeyConfirmed = value.isBlank(), error = null)
            rebuild()
        }

        fun confirmChannelKey() {
            _state.value = _state.value.copy(includeKeyConfirmed = true)
            rebuild()
        }

        fun removeChannelKey() {
            _state.value = _state.value.copy(channelKey = "", includeKeyConfirmed = true)
            rebuild()
        }

        private fun rebuild() {
            val state = _state.value
            val base = state.invite ?: return
            if (state.channelKey.isNotBlank() && !state.includeKeyConfirmed) {
                _state.value = state.copy(qrText = null)
                return
            }
            runCatching {
                val invite = base.copy(channelKey = state.channelKey.takeIf(String::isNotBlank))
                _state.value =
                    state.copy(
                        invite = invite,
                        qrText = JoinInviteCodec.installUri(invite),
                        error = null,
                    )
            }.onFailure { _state.value = state.copy(qrText = null, error = it.message) }
        }
    }

class InviteEndpointResolver
    @Inject
    constructor(
        private val connections: ConnectionManager,
        private val bouncerKinds: BouncerKindPrefs,
    ) {
        internal suspend fun resolve(network: NetworkEntity): InviteEndpoint =
            when (network.role) {
                NetworkRole.BOUNCER_ROOT -> error("Bouncer control connections cannot be shared")
                NetworkRole.DIRECT -> resolveDirectInviteEndpoint(network, network.id in bouncerKinds.zncNetworkIds.first())
                NetworkRole.BOUNCER_CHILD -> resolveSojuEndpoint(network)
            }

        private suspend fun resolveSojuEndpoint(child: NetworkEntity): InviteEndpoint {
            val rootId = child.parentId ?: error("Bouncer network has no root connection")
            val netId = child.bouncerNetId ?: error("Bouncer network has no upstream identifier")
            val client = connections.clientFor(rootId) ?: error("Connect the bouncer before creating an invitation")
            val attrs =
                resolveBouncerInviteAttrs(client.bouncerNetworks.value, netId, client::bouncerListNetworks)
                    ?: error("Bouncer did not return upstream details")
            if (!attrs["pass"].isNullOrBlank()) error("This upstream requires a password that invitations do not share")
            val host = attrs["host"]?.takeIf(String::isNotBlank) ?: error("Bouncer did not return an upstream host")
            if (host.startsWith("/", true) || host.startsWith("irc+unix", true)) error("Unix IRC endpoints cannot be shared")
            if ("://" in host) error("Bouncer returned an invalid upstream host")
            val tls =
                when (attrs["tls"]) {
                    null, "1" -> true
                    "0" -> false
                    else -> error("Bouncer returned invalid TLS metadata")
                }
            val port = attrs["port"]?.toIntOrNull() ?: if (tls) DEFAULT_TLS_PORT else DEFAULT_PLAIN_PORT
            if (port !in 1..65535) error("Bouncer returned an invalid upstream port")
            return InviteEndpoint(host, port, tls)
        }
    }

internal suspend fun resolveBouncerInviteAttrs(
    cached: Map<String, Map<String, String>>,
    netId: String,
    refresh: suspend () -> List<BouncerNetwork>,
): Map<String, String>? =
    cached[netId]
        ?: withTimeoutOrNull(INVITE_ENDPOINT_TIMEOUT_MS) {
            refresh().firstOrNull { it.netId == netId }?.attrs
        }

internal data class InviteEndpoint(
    val host: String,
    val port: Int,
    val tls: Boolean,
)

internal fun resolveDirectInviteEndpoint(
    network: NetworkEntity,
    isZnc: Boolean,
): InviteEndpoint {
    if (network.role != NetworkRole.DIRECT) error("Only direct network rows have direct endpoints")
    if (isZnc || network.saslUser?.contains('/') == true || network.serverPassword?.let { '/' in it } == true) {
        error("This bouncer does not expose its upstream IRC endpoint")
    }
    if (!network.serverPassword.isNullOrBlank()) error("This server requires a password that invitations do not share")
    return InviteEndpoint(network.host, network.port, network.tls)
}
