package io.github.trevarj.motd.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.attachment.AttachmentPrefs
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.data.prefs.AppearancePrefs
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class SettingsHomeUiState(
    val query: String = "",
    val settings: Settings = Settings(),
    val networks: List<NetworkEntity> = emptyList(),
    val appearance: AppearanceConfig = AppearanceConfig(),
    val uploads: PasteBackendConfig = PasteBackendConfig(),
)

@HiltViewModel
class SettingsHomeViewModel
    @Inject
    constructor(
        settingsRepository: SettingsRepository,
        networkRepository: NetworkRepository,
        appearancePrefs: AppearancePrefs,
        attachmentPrefs: AttachmentPrefs,
    ) : ViewModel() {
        private val query = MutableStateFlow("")

        val state: StateFlow<SettingsHomeUiState> =
            combine(
                query,
                settingsRepository.settings,
                networkRepository.observeNetworks(),
                appearancePrefs.config,
                attachmentPrefs.config,
                ::SettingsHomeUiState,
            ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsHomeUiState())

        fun setQuery(value: String) {
            query.value = value
        }
    }
