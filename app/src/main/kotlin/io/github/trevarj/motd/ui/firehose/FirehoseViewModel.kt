package io.github.trevarj.motd.ui.firehose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.FirehoseRow
import io.github.trevarj.motd.data.db.NetworkDao
import io.github.trevarj.motd.data.db.NetworkIdentityDao
import io.github.trevarj.motd.data.db.identityRules
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.repo.FirehoseRepository
import io.github.trevarj.motd.data.visibility.FirehoseNetwork
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * Combines the fool/visibility spec with the per-network identity rules into a single cross-buffer
 * Paging query and exposes it as a live [PagingData] stream. The firehose has no own write path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FirehoseViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    networkDao: NetworkDao,
    networkIdentityDao: NetworkIdentityDao,
    firehoseRepository: FirehoseRepository,
) : ViewModel() {

    private data class Inputs(
        val spec: MessageVisibilitySpec,
        val networks: List<FirehoseNetwork>,
    )

    val items: Flow<PagingData<FirehoseRow>> =
        combine(
            settingsRepository.settings.map { MessageVisibilitySpec.from(it) },
            networkDao.observeAll(),
            networkIdentityDao.observeAll(),
        ) { spec, networks, identities ->
            val rulesByNetwork = identities.associate { it.networkId to it.identityRules }
            Inputs(
                spec = spec,
                networks = networks.map { network ->
                    FirehoseNetwork(
                        networkId = network.id,
                        identityRules = rulesByNetwork[network.id] ?: IrcIdentityRules(),
                    )
                },
            )
        }
            .distinctUntilChanged()
            .flatMapLatest { firehoseRepository.firehose(it.spec, it.networks) }
            .cachedIn(viewModelScope)
}
