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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Combines the visibility spec with each network's casemap into a single cross-buffer Paging query
 * and exposes it as a live [PagingData] stream. The firehose is derived state: no write path here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FirehoseViewModel
    @Inject
    constructor(
        settingsRepository: SettingsRepository,
        networkDao: NetworkDao,
        networkIdentityDao: NetworkIdentityDao,
        firehoseRepository: FirehoseRepository,
    ) : ViewModel() {
        /** Query inputs, kept together so an unchanged combine emission cannot restart the Pager. */
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
                    networks =
                        networks.map { network ->
                            // Persisted ISUPPORT, else the RFC1459 default a server that never
                            // advertised CASEMAPPING is held to anyway.
                            FirehoseNetwork(
                                networkId = network.id,
                                identityRules = rulesByNetwork[network.id] ?: IrcIdentityRules(),
                            )
                        },
                )
            }.distinctUntilChanged()
                .flatMapLatest { firehoseRepository.firehose(it.spec, it.networks) }
                .cachedIn(viewModelScope)

        /**
         * Whether a row has to name its network. With one network the conversation tag is already
         * unambiguous; with several, identically named channels are not, so the rows spell it out.
         */
        val showNetwork: StateFlow<Boolean> =
            networkDao
                .observeAll()
                .map { it.size > 1 }
                .distinctUntilChanged()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    }
