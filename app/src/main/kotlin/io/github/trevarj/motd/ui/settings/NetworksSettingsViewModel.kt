package io.github.trevarj.motd.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.prefs.BouncerKindPrefs
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.ConnectionManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class NetworksSettingsUiState(
    val networks: List<NetworkEntity> = emptyList(),
    val zncNetworkIds: Set<Long> = emptySet(),
    val connectionStates: Map<Long, IrcClientState> = emptyMap(),
)

@HiltViewModel
class NetworksSettingsViewModel
    @Inject
    constructor(
        networkRepository: NetworkRepository,
        bouncerKindPrefs: BouncerKindPrefs,
        connectionManager: ConnectionManager,
    ) : ViewModel() {
        val state =
            combine(
                networkRepository.observeNetworks(),
                bouncerKindPrefs.zncNetworkIds,
                connectionManager.connectionStates,
                ::NetworksSettingsUiState,
            ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetworksSettingsUiState())
    }
