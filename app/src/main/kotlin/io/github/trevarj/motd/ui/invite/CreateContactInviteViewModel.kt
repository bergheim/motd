package io.github.trevarj.motd.ui.invite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.CertTrustStore
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.invite.JoinInviteCodec
import io.github.trevarj.motd.invite.JoinInviteV2
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.ConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ContactInviteNetwork(
    val id: Long,
    val name: String,
    val nick: String,
)

data class CreateContactInviteUiState(
    val loading: Boolean = true,
    val networks: List<ContactInviteNetwork> = emptyList(),
    val selectedNetworkId: Long? = null,
    val invite: JoinInviteV2? = null,
    val qrText: String? = null,
    val error: String? = null,
)

@HiltViewModel
class CreateContactInviteViewModel
    @Inject
    constructor(
        private val networks: NetworkRepository,
        private val connections: ConnectionManager,
        private val endpointResolver: InviteEndpointResolver,
        private val certs: CertTrustStore,
    ) : ViewModel() {
        private val _state = MutableStateFlow(CreateContactInviteUiState())
        val state: StateFlow<CreateContactInviteUiState> = _state.asStateFlow()
        private val selectedNetworkId = MutableStateFlow<Long?>(null)
        private var initialized = false
        private var preferredNetworkId: Long? = null

        fun init(preferredNetworkId: Long?) {
            if (initialized) return
            initialized = true
            this.preferredNetworkId = preferredNetworkId
            viewModelScope.launch {
                combine(
                    networks.observeNetworks(),
                    connections.connectionStates,
                    selectedNetworkId,
                ) { rows, states, selected -> Triple(rows, states, selected) }
                    .collectLatest { (rows, states, requestedId) ->
                        val readyRows =
                            rows.mapNotNull { network ->
                                val ready = states[network.id] as? IrcClientState.Ready
                                if (ready == null || network.role == NetworkRole.BOUNCER_ROOT) null else network to ready
                            }
                        val choices = readyRows.map { (network, ready) -> ContactInviteNetwork(network.id, network.name, ready.nick) }
                        val effectiveId =
                            requestedId?.takeIf { id -> choices.any { it.id == id } }
                                ?: this@CreateContactInviteViewModel.preferredNetworkId?.takeIf { id -> choices.any { it.id == id } }
                                ?: choices.firstOrNull()?.id
                        if (effectiveId != requestedId) {
                            selectedNetworkId.value = effectiveId
                            return@collectLatest
                        }
                        buildInvite(readyRows, choices, effectiveId)
                    }
            }
        }

        fun selectNetwork(networkId: Long) {
            if (_state.value.networks.any { it.id == networkId }) selectedNetworkId.value = networkId
        }

        private suspend fun buildInvite(
            readyRows: List<Pair<NetworkEntity, IrcClientState.Ready>>,
            choices: List<ContactInviteNetwork>,
            networkId: Long?,
        ) {
            val selected = readyRows.firstOrNull { it.first.id == networkId }
            if (selected == null) {
                _state.value =
                    CreateContactInviteUiState(
                        loading = false,
                        networks = choices,
                        selectedNetworkId = networkId,
                    )
                return
            }
            _state.value =
                CreateContactInviteUiState(
                    loading = false,
                    networks = choices,
                    selectedNetworkId = networkId,
                )
            try {
                val (network, ready) = selected
                val endpoint = endpointResolver.resolve(network)
                val pin = if (network.role == NetworkRole.DIRECT) certs.pinnedFor(endpoint.host, endpoint.port) else null
                val invite =
                    JoinInviteV2(
                        networkName = network.name,
                        host = endpoint.host,
                        port = endpoint.port,
                        tls = endpoint.tls,
                        contactNick = ready.nick,
                        certSha256 = pin,
                    )
                _state.value =
                    _state.value.copy(
                        invite = invite,
                        qrText = JoinInviteCodec.installUri(invite),
                        error = null,
                    )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(error = error.message ?: "Invite unavailable")
            }
        }
    }
