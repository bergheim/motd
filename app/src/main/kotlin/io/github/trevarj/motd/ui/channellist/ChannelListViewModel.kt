package io.github.trevarj.motd.ui.channellist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.irc.client.ChannelListing
import io.github.trevarj.motd.backend.ConnectionState
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.ircbackend.IrcSessions
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.ui.nav.ChannelListRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
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
    val connState: ConnectionState = ConnectionState.Disconnected,
    val initialized: Boolean = false,
    val query: String = "",
    val listings: List<ChannelListing> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val isRoot: Boolean = false,
    /** Whether ISUPPORT ELIST 'U' lets a blank LIST be bounded server-side (interim IRC read). */
    val popularListAvailable: Boolean = false,
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
    /** From [ConnectionManager.supportsRoomDiscovery] (review fix, P2 finding); defaults `true` so
     *  the pre-[start] synthetic state never flashes [ChannelBrowserAvailability.UNSUPPORTED]. */
    val supportsDiscovery: Boolean = true,
) {
    val isReady: Boolean get() = connState is ConnectionState.Ready
    val availability: ChannelBrowserAvailability
        get() = channelBrowserAvailability(initialized, isRoot, connState, supportsDiscovery)
}

@HiltViewModel
class ChannelListViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val networkRepository: NetworkRepository,
    private val bufferRepository: BufferRepository,
    private val connectionManager: ConnectionManager,
    private val ircSessions: IrcSessions,
) : ViewModel() {

    private val networkId: Long = savedStateHandle.toRoute<ChannelListRoute>().networkId

    private val _state = MutableStateFlow(ChannelListUiState(networkId = networkId))
    val state: StateFlow<ChannelListUiState> = _state.asStateFlow()

    private var started = false
    private var fetchJob: Job? = null
    private var activeFetchQuery: String? = null
    private var queuedFetchQuery: String? = null

    /**
     * Idempotent entry point: mirrors connection state and auto-fetches once Ready. Checks
     * [ConnectionManager.supportsRoomDiscovery] first (review fix, P2 finding) and settles
     * immediately on [ChannelBrowserAvailability.UNSUPPORTED] — `initialized = true` alongside it, so
     * the screen does not get stuck showing [ChannelBrowserAvailability.INITIALIZING] forever — for a
     * backend with no such capability, never reaching the IRC-owned [ircSessions] accessor or
     * [fetch]'s poll-and-timeout at all.
     */
    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            val network = networkRepository.networkById(networkId)
            val supportsDiscovery = connectionManager.supportsRoomDiscovery(networkId)
            _state.value = _state.value.copy(
                networkName = network?.name.orEmpty(),
                isRoot = network?.role == NetworkRole.BOUNCER_ROOT,
                supportsDiscovery = supportsDiscovery,
                initialized = true,
            )
            if (!supportsDiscovery) return@launch
            connectionManager.connectionStates.collect { states ->
                // Live-session freshness read via the IRC-owned accessor. Reachable only when
                // supportsDiscovery is true (review fix — see start()'s KDoc): [ircSessions] itself
                // stays IRC-owned, not neutralized, but a backend with no room-discovery capability
                // now never reaches this collector, let alone this accessor, at all.
                val rawClientState = ircSessions.sessionFor(networkId)?.state?.value
                val clientState = rawClientState?.toConnectionState()
                val rules = connectionManager.liveIdentityRules(networkId)
                    ?: _state.value.identityRules
                val conn = channelBrowserConnectionState(states[networkId], clientState)
                val popularListAvailable = rawClientState?.let(::supportsPopularChannelList) == true
                val current = _state.value
                val normalizedJoined = normalizeChannelNames(current.persistedJoinedChannels, rules)
                val pendingChannelNames = reconcilePendingChannelNames(
                    current.pendingChannelNames,
                    normalizedJoined,
                    rules,
                    conn is ConnectionState.Ready,
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
                    popularListAvailable = popularListAvailable,
                )
                // A local result cap does not bound the server response. Only auto-fetch when
                // ELIST U guarantees the broad request is filtered before transmission.
                if (conn is ConnectionState.Ready &&
                    popularListAvailable &&
                    !_state.value.loaded &&
                    !_state.value.isRoot
                ) {
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
                    current.connState is ConnectionState.Ready,
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
                    current.connState is ConnectionState.Ready,
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

    /** Fetch (or re-fetch) via LIST/ELIST, then sort by user count descending. */
    fun fetch() = fetch(_state.value.query)

    /** Fetch the query submitted by the visible field, synchronizing it before request queuing. */
    fun fetch(requestedQuery: String) {
        val current = _state.value
        val s = if (current.query == requestedQuery) {
            current
        } else {
            current.copy(query = requestedQuery).also { _state.value = it }
        }
        if (s.isRoot || !s.isReady) return
        if (requestedQuery.isBlank() && !s.popularListAvailable) {
            // Never turn a blank refresh into a full-network LIST on servers without ELIST U.
            _state.value = s.copy(
                listings = emptyList(),
                loading = false,
                loaded = false,
                error = null,
            )
            return
        }
        if (fetchJob?.isActive == true) {
            if (shouldQueueChannelListFetch(activeFetchQuery, requestedQuery)) {
                queuedFetchQuery = requestedQuery
            }
            return
        }
        startFetch(requestedQuery)
    }

    private fun startFetch(query: String) {
        val s = _state.value
        if (s.isRoot || !s.isReady) return
        val args = listArgsFor(query)
        activeFetchQuery = query
        _state.value = s.copy(loading = true, error = null)
        fetchJob = viewModelScope.launch {
            val client = withTimeoutOrNull(CLIENT_WAIT_TIMEOUT_MS) {
                var current = ircSessions.sessionFor(networkId)
                while (current == null) {
                    delay(CLIENT_WAIT_POLL_MS)
                    current = ircSessions.sessionFor(networkId)
                }
                current
            }
            val result = if (client == null) {
                Result.failure(IllegalStateException("Channel listing is not available yet. Try again."))
            } else runCatching {
                client.listChannels(
                    mask = args.mask,
                    minUsers = args.minUsers,
                    cap = channelListLimit(query),
                )
            }
            val latest = _state.value
            if (shouldApplyChannelListFetchResult(query, latest.query)) {
                _state.value = latest.copy(
                    loading = false,
                    loaded = result.isSuccess,
                    listings = result.getOrNull()?.let(::sortListings) ?: latest.listings,
                    error = result.exceptionOrNull()?.message,
                )
            } else {
                _state.value = latest.copy(loading = false)
            }
            activeFetchQuery = null
            queuedFetchQuery?.let { queued ->
                queuedFetchQuery = null
                if (_state.value.isReady && !_state.value.isRoot) startFetch(queued)
            }
        }
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

// IRC-state mapper for the live-session freshness read above; goes away with the neutral
// room-discovery capability (docs/backend-neutral-xmpp-rollout.md).
private fun io.github.trevarj.motd.irc.event.IrcClientState.toConnectionState(): ConnectionState = when (this) {
    io.github.trevarj.motd.irc.event.IrcClientState.Disconnected -> ConnectionState.Disconnected
    io.github.trevarj.motd.irc.event.IrcClientState.Connecting -> ConnectionState.Connecting
    io.github.trevarj.motd.irc.event.IrcClientState.Registering -> ConnectionState.Authenticating
    is io.github.trevarj.motd.irc.event.IrcClientState.Ready -> ConnectionState.Ready(nick)
    is io.github.trevarj.motd.irc.event.IrcClientState.Failed -> ConnectionState.Failed(reason, fatal)
}
