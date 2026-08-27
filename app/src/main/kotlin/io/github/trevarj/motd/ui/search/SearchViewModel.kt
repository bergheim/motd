package io.github.trevarj.motd.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.SearchHit
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.SearchCoverage
import io.github.trevarj.motd.data.repo.SearchRepository
import io.github.trevarj.motd.irc.client.IrcCommandException
import io.github.trevarj.motd.irc.ext.SOJU_SEARCH_MAX_LIMIT
import io.github.trevarj.motd.irc.ext.SearchRequest
import io.github.trevarj.motd.irc.ext.SearchResultKind
import io.github.trevarj.motd.irc.ext.SearchResultMessage
import io.github.trevarj.motd.service.ConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Filter scope for the search screen. "current" is only offered when launched with a bufferId, and
 * "server" additionally requires a live client that negotiated `soju.im/search`.
 */
enum class SearchScope { ALL, CURRENT, SERVER }

/**
 * One transient server hit. There is deliberately no MessageEntity behind it: search results carry
 * no interval semantics, so they are never written to Room.
 */
data class ServerHitUi(
    val bufferId: Long,
    val sender: String,
    val text: String,
    val kind: SearchResultKind,
    /** 0 when the line carried no time tag; such a hit jumps by msgid alone. */
    val serverTime: Long,
    val msgid: String?,
)

enum class ServerSearchError { REJECTED, UNAVAILABLE }

sealed interface ServerSearchState {
    data object Idle : ServerSearchState

    data object Searching : ServerSearchState

    data class Results(
        val hits: List<ServerHitUi>,
        val truncated: Boolean,
    ) : ServerSearchState

    data class Failed(
        val error: ServerSearchError,
    ) : ServerSearchState
}

/** Result group: one buffer's hits under a header. */
data class SearchGroup(
    val bufferId: Long,
    val bufferDisplayName: String,
    val networkName: String,
    val bufferType: BufferType,
    val networkId: Long,
    val avatarOverrideModel: String?,
    val hits: List<SearchHit>,
)

data class SearchUiState(
    val rawQuery: String = "",
    val scope: SearchScope = SearchScope.ALL,
    /** True when this screen was launched scoped to a buffer (enables the "current" chip). */
    val hasBufferScope: Boolean = false,
    /**
     * Channel/DM name matches ("smart" results): shown as a row above the message-content groups
     * below, so a query matching a room's name surfaces it even when no message matches. Populated
     * only for [SearchScope.ALL] — a buffer- or server-scoped search is already about message
     * content within one target, not room discovery.
     */
    val bufferMatches: List<ChatListRow> = emptyList(),
    val groups: List<SearchGroup> = emptyList(),
    val searching: Boolean = false,
    /** What the searched corpus covers for the active scope; null until the first emission. */
    val coverage: SearchCoverage? = null,
    /** True when the local FTS page hit its row cap and older matches were not returned. */
    val truncated: Boolean = false,
    /** True when this buffer's network can run a server-side SEARCH right now. */
    val serverSearchAvailable: Boolean = false,
    val server: ServerSearchState = ServerSearchState.Idle,
)

/**
 * Parsed query: the FTS text and an optional client-side `from:nick` sender filter.
 * Pure so it is trivially testable and keeps the ViewModel thin.
 */
data class ParsedQuery(
    val text: String,
    val fromNick: String?,
)

fun parseSearchQuery(raw: String): ParsedQuery {
    val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    var fromNick: String? = null
    val rest = mutableListOf<String>()
    for (t in tokens) {
        val lower = t.lowercase()
        if (lower.startsWith("from:") && t.length > 5) {
            fromNick = t.substring(5)
        } else {
            rest.add(t)
        }
    }
    return ParsedQuery(text = rest.joinToString(" "), fromNick = fromNick)
}

/** True when [raw] contains no FTS text or sender-only filter to resolve. */
fun isEmptySearchQuery(raw: String): Boolean = parseSearchQuery(raw).let { it.text.isBlank() && it.fromNick == null }

/** One logical result request. Results from an older key must never render under a newer one. */
private data class SearchKey(
    val rawQuery: String = "",
    val scope: SearchScope = SearchScope.ALL,
    val bufferId: Long? = null,
) {
    val hasBufferScope: Boolean get() = bufferId != null

    fun emptyState() =
        SearchUiState(
            rawQuery = rawQuery,
            scope = scope,
            hasBufferScope = hasBufferScope,
        )

    fun loadingState() = emptyState().copy(searching = true)
}

/** Availability plus the transient server-search result, kept outside the keyed local pipeline. */
private data class ServerSection(
    val available: Boolean = false,
    val state: ServerSearchState = ServerSearchState.Idle,
)

@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        private val searchRepository: SearchRepository,
        private val bufferRepository: BufferRepository,
        private val connectionManager: ConnectionManager,
    ) : ViewModel() {
        /**
         * State changes atomically by logical query/scope key. This lets the UI clear old results
         * before the debounce while also preventing a late flow emission for a superseded key.
         */
        private val searchKey = MutableStateFlow(SearchKey())

        private val serverSection = MutableStateFlow(ServerSection())

        /** Canonical room the server scope targets; null until the buffer resolves. */
        private var serverBuffer: BufferEntity? = null
        private var availabilityJob: Job? = null
        private var serverJob: Job? = null

        fun init(bufferId: Long?) {
            searchKey.update {
                it.copy(
                    bufferId = bufferId,
                    scope = if (bufferId == null) SearchScope.ALL else SearchScope.CURRENT,
                )
            }
            availabilityJob?.cancel()
            serverBuffer = null
            serverSection.value = ServerSection()
            if (bufferId == null) return
            availabilityJob =
                viewModelScope.launch {
                    // Availability is a property of the live connection, so it is recomputed on every
                    // connection-state emission as well as on every buffer change.
                    combine(
                        bufferRepository.observeBuffer(bufferId),
                        connectionManager.connectionStates,
                    ) { buffer, _ -> buffer }.collect { buffer ->
                        serverBuffer = buffer
                        val available =
                            buffer != null &&
                                buffer.type != BufferType.SERVER &&
                                connectionManager.serverSearchAvailable(buffer.networkId)
                        if (!available && searchKey.value.scope == SearchScope.SERVER) {
                            // Never strand the user on a scope that can no longer answer.
                            searchKey.update { it.copy(scope = SearchScope.CURRENT) }
                            cancelServerSearch()
                        }
                        serverSection.update { it.copy(available = available) }
                    }
                }
        }

        /**
         * Run one server-side SEARCH. Submit-driven on purpose: this is a wire round trip, so it never
         * fires from typing the way the local FTS query does.
         */
        fun onServerSearchSubmit() {
            val key = searchKey.value
            if (key.scope != SearchScope.SERVER) return
            val buffer =
                serverBuffer ?: run {
                    serverSection.update { it.copy(state = ServerSearchState.Failed(ServerSearchError.UNAVAILABLE)) }
                    return
                }
            val parsed = parseSearchQuery(key.rawQuery)
            val text = parsed.text.takeIf { it.isNotBlank() }
            if (text == null && parsed.fromNick == null) {
                cancelServerSearch()
                return
            }
            cancelServerSearch()
            serverSection.update { it.copy(state = ServerSearchState.Searching) }
            serverJob =
                viewModelScope.launch {
                    val outcome =
                        try {
                            connectionManager
                                .searchMessages(
                                    buffer.networkId,
                                    SearchRequest(
                                        target = buffer.name,
                                        text = text,
                                        from = parsed.fromNick,
                                        limit = SOJU_SEARCH_MAX_LIMIT,
                                    ),
                                )?.let { raw -> raw.toResults(buffer.id) }
                                ?: ServerSearchState.Failed(ServerSearchError.UNAVAILABLE)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (rejected: IrcCommandException) {
                            // The server understood the query and refused it; retrying verbatim will not help.
                            ServerSearchState.Failed(ServerSearchError.REJECTED)
                        } catch (
                            @Suppress("TooGenericExceptionCaught") failure: Exception,
                        ) {
                            // Timeout, disconnect, or an unavailable client: all retryable from the user's side.
                            ServerSearchState.Failed(ServerSearchError.UNAVAILABLE)
                        }
                    serverSection.update { it.copy(state = outcome) }
                }
        }

        private fun List<SearchResultMessage>.toResults(
            bufferId: Long,
        ): ServerSearchState.Results =
            ServerSearchState.Results(
                hits =
                    mapNotNull { hit ->
                        // A hit with neither a time nor a msgid cannot be jumped to, so it is not a result.
                        if (hit.serverTime == null && hit.msgid == null) {
                            null
                        } else {
                            ServerHitUi(
                                bufferId = bufferId,
                                sender = hit.sender,
                                text = hit.text,
                                kind = hit.kind,
                                serverTime = hit.serverTime ?: 0L,
                                msgid = hit.msgid,
                            )
                        }
                    }.sortedByDescending { it.serverTime },
                // Measured on the raw response: soju caps at 100 regardless of the requested limit.
                truncated = size >= SOJU_SEARCH_MAX_LIMIT,
            )

        private fun cancelServerSearch() {
            serverJob?.cancel()
            serverJob = null
            serverSection.update { it.copy(state = ServerSearchState.Idle) }
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        private val localState: Flow<SearchUiState> =
            searchKey
                .flatMapLatest { key ->
                    val parsed = parseSearchQuery(key.rawQuery)
                    val scopeId = if (key.scope == SearchScope.CURRENT) key.bufferId else null
                    if (key.scope == SearchScope.SERVER) {
                        // The server scope renders its own section; the local FTS pipeline stays idle
                        // so a wire-scoped query never runs a Room search behind the user's back.
                        flowOf(key.emptyState())
                    } else if (isEmptySearchQuery(key.rawQuery)) {
                        // The corpus disclosure has to be readable before the first keystroke, so the
                        // empty state carries coverage too rather than appearing only with results.
                        searchRepository
                            .coverage(scopeId)
                            .map { coverage -> key.emptyState().copy(coverage = coverage) }
                    } else {
                        flow {
                            // Publish the key immediately. Only the repository call is debounced.
                            emit(key.loadingState())
                            delay(QUERY_DEBOUNCE_MS)
                            combine(
                                searchRepository.search(parsed.text, scopeId),
                                searchRepository.coverage(scopeId),
                            ) { result, coverage -> result to coverage }.collect { (result, coverage) ->
                                // flatMapLatest cancels the old collector. The explicit key guard
                                // also blocks a result racing a synchronous key replacement.
                                if (searchKey.value == key) {
                                    val filtered =
                                        parsed.fromNick?.let { nick ->
                                            result.hits.filter { it.message.sender.equals(nick, ignoreCase = true) }
                                        } ?: result.hits
                                    emit(
                                        SearchUiState(
                                            rawQuery = key.rawQuery,
                                            scope = key.scope,
                                            hasBufferScope = key.hasBufferScope,
                                            groups = groupHits(filtered),
                                            searching = false,
                                            coverage = coverage,
                                            // Reported from the raw DAO page: the client-side from:
                                            // filter shrinking the list does not un-truncate it.
                                            truncated = result.truncated,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }

        // Independent of the debounced local FTS pipeline above: the "smart" row is a name filter
        // over an already-loaded list, not a query, so there is no reason to make it wait.
        @OptIn(ExperimentalCoroutinesApi::class)
        private val bufferMatches: Flow<List<ChatListRow>> =
            searchKey.flatMapLatest { key ->
                val parsed = parseSearchQuery(key.rawQuery)
                if (key.scope != SearchScope.ALL || parsed.text.isBlank()) {
                    flowOf(emptyList())
                } else {
                    bufferRepository.observeChatList().map { rows -> matchingBufferRows(rows, parsed.text) }
                }
            }

        val state: StateFlow<SearchUiState> =
            combine(localState, serverSection, bufferMatches) { local, server, matches ->
                local.copy(
                    bufferMatches = matches,
                    serverSearchAvailable = server.available,
                    // Leaving the server scope is two writes — the key, then the cancel — and they
                    // reach this combine separately, so a state pairing a local scope with the
                    // previous scope's hits is observable in between. Deriving the section from the
                    // scope makes that pairing impossible to represent rather than merely unlikely,
                    // which is also what keeps the reset assertion from depending on which
                    // intermediate values survive StateFlow conflation.
                    server = if (local.scope == SearchScope.SERVER) server.state else ServerSearchState.Idle,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = SearchUiState(),
            )

        fun onQueryChange(q: String) {
            searchKey.update { it.copy(rawQuery = q) }
            // Editing invalidates the answer the server gave for the previous query.
            cancelServerSearch()
        }

        fun onScopeChange(s: SearchScope) {
            searchKey.update { it.copy(scope = s) }
            cancelServerSearch()
        }

        private companion object {
            /** Wait for a typing pause before hitting the Room FTS query. */
            const val QUERY_DEBOUNCE_MS = 250L
        }
    }

/** Cap on the "smart" channel/DM match row: a handful of best fits, not a second results list. */
internal const val BUFFER_MATCH_LIMIT = 5

/**
 * Room-name matches for the "smart" results row: non-archived, non-server rows whose display name
 * contains [query] (case-insensitive substring — this is name matching, not FTS). Pure and ordered
 * by the chat list's own order (already pinned/recency-sorted), so no extra ranking logic is needed
 * here.
 */
internal fun matchingBufferRows(
    rows: List<ChatListRow>,
    query: String,
    limit: Int = BUFFER_MATCH_LIMIT,
): List<ChatListRow> {
    if (query.isBlank()) return emptyList()
    return rows
        .asSequence()
        .filter { it.type != BufferType.SERVER && !it.archived }
        .filter { it.displayName.contains(query, ignoreCase = true) }
        .take(limit)
        .toList()
}

/** Group hits by buffer, preserving overall recency order (hits already time-ordered by DAO). */
fun groupHits(hits: List<SearchHit>): List<SearchGroup> =
    hits
        .groupBy { it.message.bufferId }
        .map { (bufferId, groupHits) ->
            val first = groupHits.first()
            SearchGroup(
                bufferId = bufferId,
                bufferDisplayName = first.bufferDisplayName,
                networkName = first.networkName,
                bufferType = first.bufferType,
                networkId = first.networkId,
                avatarOverrideModel = first.avatarOverrideModel,
                hits = groupHits,
            )
        }
