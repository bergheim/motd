package io.github.trevarj.motd.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.NetworkBufferToolRow
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkIgnoreEntity
import io.github.trevarj.motd.data.repo.NetworkIgnoreRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.backend.ConnectionState
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.service.ConnectionManager
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NetworkToolsUiState(
    val networkId: Long = 0,
    val network: NetworkEntity? = null,
    val ignores: List<NetworkIgnoreEntity> = emptyList(),
    val buffers: List<NetworkBufferToolRow> = emptyList(),
    val connected: Boolean = false,
    val status: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NetworkToolsViewModel @Inject constructor(
    private val networkRepository: NetworkRepository,
    private val toolsRepository: NetworkIgnoreRepository,
    private val connectionManager: ConnectionManager,
) : ViewModel() {
    private val networkIdFlow = MutableStateFlow<Long?>(null)
    private val statusFlow = MutableStateFlow<String?>(null)

    fun init(networkId: Long) {
        networkIdFlow.value = networkId
    }

    private val networkFlow = networkIdFlow.flatMapLatest { id ->
        if (id == null) flowOf<NetworkEntity?>(null) else flow { emit(networkRepository.networkById(id)) }
    }

    private val ignoresFlow = networkIdFlow.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else toolsRepository.observeIgnores(id)
    }

    private val buffersFlow = networkIdFlow.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else toolsRepository.observeBuffers(id)
    }

    val state: StateFlow<NetworkToolsUiState> =
        combine(
            combine(
                networkIdFlow,
                networkFlow,
                ignoresFlow,
                buffersFlow,
                connectionManager.connectionStates,
            ) { networkId, network, ignores, buffers, states ->
                NetworkToolsUiState(
                    networkId = networkId ?: 0,
                    network = network,
                    ignores = ignores,
                    buffers = buffers,
                    connected = states[networkId] is ConnectionState.Ready,
                )
            },
            statusFlow,
        ) { base, status ->
            NetworkToolsUiState(
                networkId = base.networkId,
                network = base.network,
                ignores = base.ignores,
                buffers = base.buffers,
                connected = base.connected,
                status = status,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = NetworkToolsUiState(),
        )

    fun addIgnore(pattern: String) = viewModelScope.launch {
        val networkId = state.value.networkId.takeIf { it != 0L } ?: return@launch
        toolsRepository.addIgnore(networkId, pattern)
            .onSuccess { statusFlow.value = "Ignore added" }
            .onFailure { statusFlow.value = it.message ?: "Ignore failed" }
    }

    fun setIgnoreEnabled(id: Long, enabled: Boolean) = viewModelScope.launch {
        toolsRepository.setIgnoreEnabled(id, enabled)
    }

    fun deleteIgnore(id: Long) = viewModelScope.launch {
        toolsRepository.deleteIgnore(id)
    }

    fun setMuted(bufferId: Long, muted: Boolean) = viewModelScope.launch {
        toolsRepository.setMuted(bufferId, muted)
    }

    fun oper(username: String, password: String) =
        send(IrcMessage(command = "OPER", params = listOf(username.trim(), password)))

    fun kill(nick: String, reason: String) =
        send(IrcMessage(command = "KILL", params = listOf(nick.trim(), reason.trim())))

    fun mode(target: String, modes: String, args: String) =
        send(IrcMessage(command = "MODE", params = listOf(target.trim(), modes.trim()) + splitArgs(args)))

    fun rehash(server: String) =
        send(IrcMessage(command = "REHASH", params = listOfNotNull(server.trim().takeIf(String::isNotBlank))))

    fun connectServer(server: String, port: String, remote: String) =
        send(
            IrcMessage(
                command = "CONNECT",
                params = listOfNotNull(
                    server.trim(),
                    port.trim().takeIf(String::isNotBlank),
                    remote.trim().takeIf(String::isNotBlank),
                ),
            ),
        )

    fun squit(server: String, reason: String) =
        send(IrcMessage(command = "SQUIT", params = listOf(server.trim(), reason.trim())))

    private fun send(message: IrcMessage) = viewModelScope.launch {
        val networkId = state.value.networkId.takeIf { it != 0L } ?: return@launch
        val client = connectionManager.clientFor(networkId)
        if (client == null) {
            statusFlow.value = "Network is not connected"
            return@launch
        }
        val validation = runCatching { message.serialize() }.exceptionOrNull()
        if (validation != null || message.params.any(String::isBlank)) {
            statusFlow.value = validation?.message ?: "Required fields are missing"
            return@launch
        }
        runCatching { client.send(message) }
            .onSuccess { statusFlow.value = "${message.command} sent" }
            .onFailure { statusFlow.value = it.message ?: "${message.command} failed" }
    }
}

private fun splitArgs(raw: String): List<String> =
    raw.split(' ').map(String::trim).filter(String::isNotBlank)
