package io.github.trevarj.motd.ui.channellist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.Protocol
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.irc.client.ChannelListing
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.XmppConnectionSurface
import io.github.trevarj.motd.ui.nav.ChannelListRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * Channel-browser UI state (plans/16 §5.7).
 *
 * [loaded] distinguishes "fetched, no results" from "not fetched yet". [isRoot] disables
 * browsing for an unbound soju BOUNCER_ROOT (LIST is meaningless on the root connection).
 */
data class ChannelListUiState(
    val networkId: Long = 0,
    val networkName: String = "",
    val connState: IrcClientState = IrcClientState.Disconnected,
    val initialized: Boolean = false,
    val query: String = "",
    val listings: List<ChannelListing> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val isRoot: Boolean = false,
    val error: String? = null,
    val identityRules: IrcIdentityRules = IrcIdentityRules(),
    /** Raw names sent in this Ready session, awaiting authoritative Room self-JOIN. */
    val pendingChannelNames: Set<String> = emptySet(),
    /** [pendingChannelNames] normalized with [identityRules] for duplicate and UI matching. */
    val pendingChannels: Set<String> = emptySet(),
    /** Durable joined CHANNEL names before applying the active server CASEMAPPING. */
    val persistedJoinedChannels: Set<String> = emptySet(),
    /** Persisted joined names normalized with [identityRules] for channel-browser matching. */
    val joinedChannels: Set<String> = emptySet(),
    val joinError: String? = null,
) {
    val isReady: Boolean get() = connState is IrcClientState.Ready
    val availability: ChannelBrowserAvailability
        get() = channelBrowserAvailability(initialized, isRoot, connState)
}

@HiltViewModel
class ChannelListViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val networkRepository: NetworkRepository,
    private val bufferRepository: BufferRepository,
    private val connectionManager: ConnectionManager,
    private val xmppConnectionSurface: XmppConnectionSurface,
) : ViewModel() {

    private val networkId: Long = savedStateHandle.toRoute<ChannelListRoute>().networkId

    private val _state = MutableStateFlow(ChannelListUiState(networkId = networkId))
    val state: StateFlow<ChannelListUiState> = _state.asStateFlow()

    private var started = false

    /** Set once in [start] from the network's persisted row; IRC unless proven XMPP. */
    private var protocol: Protocol = Protocol.IRC

    /** Idempotent entry point: mirrors connection state and auto-fetches once Ready. */
    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            val network = networkRepository.networkById(networkId)
            protocol = network?.protocol ?: Protocol.IRC
            _state.value = _state.value.copy(
                networkName = network?.name.orEmpty(),
                isRoot = network?.role == NetworkRole.BOUNCER_ROOT,
            )
            connectionManager.connectionStates.collect { states ->
                val clientState = connectionManager.clientFor(networkId)?.state?.value
                val rules = connectionManager.clientFor(networkId)?.isupport?.identityRules
                    ?: _state.value.identityRules
                val conn = channelBrowserConnectionState(states[networkId], clientState)
                val current = _state.value
                val normalizedJoined = normalizeChannelNames(current.persistedJoinedChannels, rules)
                val pendingChannelNames = reconcilePendingChannelNames(
                    current.pendingChannelNames,
                    normalizedJoined,
                    rules,
                    conn is IrcClientState.Ready,
                )
                _state.value = current.copy(
                    connState = conn,
                    initialized = true,
                    identityRules = rules,
                    joinedChannels = normalizedJoined,
                    pendingChannelNames = pendingChannelNames,
                    pendingChannels = normalizeChannelNames(
                        pendingChannelNames,
                        rules,
                    ),
                )
                // Auto-fetch a bounded set of the busiest channels. ELIST 'U' applies the
                // population floor server-side; other servers stream into the bounded collector.
                if (conn is IrcClientState.Ready && !_state.value.loaded && !_state.value.isRoot) {
                    fetch()
                }
            }
        }
        viewModelScope.launch {
            bufferRepository.observeJoinedChannelNames(networkId).collect { joined ->
                val current = _state.value
                val normalizedJoined = normalizeChannelNames(joined, current.identityRules)
                val pendingChannelNames = reconcilePendingChannelNames(
                    current.pendingChannelNames,
                    normalizedJoined,
                    current.identityRules,
                    current.connState is IrcClientState.Ready,
                )
                _state.value = current.copy(
                    persistedJoinedChannels = joined,
                    joinedChannels = normalizedJoined,
                    pendingChannelNames = pendingChannelNames,
                    pendingChannels = normalizeChannelNames(
                        pendingChannelNames,
                        current.identityRules,
                    ),
                )
            }
        }
        viewModelScope.launch {
            connectionManager.channelJoinOutcomes.collect { outcome ->
                val rejection = outcome as? io.github.trevarj.motd.service.ChannelJoinOutcome.Rejected
                    ?: return@collect
                if (rejection.networkId != networkId) return@collect
                val current = _state.value
                val pendingChannelNames = pendingChannelNamesAfterJoinRejection(
                    current.pendingChannelNames,
                    rejection.channel,
                    current.identityRules,
                    current.connState is IrcClientState.Ready,
                ) ?: return@collect
                _state.value = current.copy(
                    pendingChannelNames = pendingChannelNames,
                    pendingChannels = normalizeChannelNames(pendingChannelNames, current.identityRules),
                    joinError = rejection.reason,
                )
            }
        }
    }

    fun onQueryChange(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    /** Fetch (or re-fetch): LIST/ELIST for IRC, MUC service discovery for XMPP. Sorted by user
     *  count descending (a no-op order-preserving pass for XMPP, which has no user counts). */
    fun fetch() {
        val s = _state.value
        if (s.loading || s.isRoot || !s.isReady) return
        _state.value = s.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = if (protocol == Protocol.XMPP) fetchXmppRooms(s.query) else fetchIrcChannels(s.query)
            _state.value = _state.value.copy(
                loading = false,
                loaded = result.isSuccess,
                listings = result.getOrNull()?.let(::sortListings) ?: _state.value.listings,
                error = result.exceptionOrNull()?.message,
            )
        }
    }

    private suspend fun fetchIrcChannels(query: String): Result<List<ChannelListing>> {
        val args = listArgsFor(query)
        val client = withTimeoutOrNull(CLIENT_WAIT_TIMEOUT_MS) {
            var current = connectionManager.clientFor(networkId)
            while (current == null) {
                delay(CLIENT_WAIT_POLL_MS)
                current = connectionManager.clientFor(networkId)
            }
            current
        }
        return if (client == null) {
            Result.failure(IllegalStateException("Channel listing is not available yet. Try again."))
        } else runCatching {
            client.listChannels(mask = args.mask, minUsers = args.minUsers, cap = channelListLimit(query))
        }
    }

    private suspend fun fetchXmppRooms(query: String): Result<List<ChannelListing>> = runCatching {
        filterChannelListings(xmppConnectionSurface.listRooms(networkId).map(::toChannelListing), query)
    }

    /** Send a JOIN and retain its pending state until EventProcessor persists our self-JOIN. */
    fun join(channel: String) {
        val current = _state.value
        if (!current.isReady) return
        val normalized = current.identityRules.normalize(channel)
        if (normalized in current.pendingChannels || normalized in current.joinedChannels) return
        _state.value = current.copy(
            pendingChannelNames = current.pendingChannelNames + channel,
            pendingChannels = current.pendingChannels + normalized,
            joinError = null,
        )
        viewModelScope.launch {
            try {
                connectionManager.joinChannel(networkId, channel)
            } catch (cancelled: CancellationException) {
                val latest = _state.value
                val pendingChannelNames = removePendingChannelName(
                    latest.pendingChannelNames,
                    channel,
                    latest.identityRules,
                )
                _state.value = latest.copy(
                    pendingChannelNames = pendingChannelNames,
                    pendingChannels = normalizeChannelNames(pendingChannelNames, latest.identityRules),
                )
                throw cancelled
            } catch (error: Exception) {
                val latest = _state.value
                val pendingChannelNames = removePendingChannelName(
                    latest.pendingChannelNames,
                    channel,
                    latest.identityRules,
                )
                _state.value = latest.copy(
                    pendingChannelNames = pendingChannelNames,
                    pendingChannels = normalizeChannelNames(pendingChannelNames, latest.identityRules),
                    joinError = error.message,
                )
            }
        }
    }

    private companion object {
        const val CLIENT_WAIT_TIMEOUT_MS = 2_000L
        const val CLIENT_WAIT_POLL_MS = 50L
    }
}
