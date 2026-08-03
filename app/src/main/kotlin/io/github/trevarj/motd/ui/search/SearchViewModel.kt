package io.github.trevarj.motd.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.SearchHit
import io.github.trevarj.motd.data.repo.SearchRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** Filter scope for the search screen. "current" only offered when launched with a bufferId. */
enum class SearchScope { ALL, CURRENT }

/** Result group: one buffer's hits under a header. */
data class SearchGroup(
    val bufferId: Long,
    val bufferDisplayName: String,
    val networkName: String,
    val hits: List<SearchHit>,
)

data class SearchUiState(
    val rawQuery: String = "",
    val scope: SearchScope = SearchScope.ALL,
    /** True when this screen was launched scoped to a buffer (enables the "current" chip). */
    val hasBufferScope: Boolean = false,
    val groups: List<SearchGroup> = emptyList(),
    val searching: Boolean = false,
)

/**
 * Parsed query: the FTS text and an optional client-side `from:nick` sender filter.
 * Pure so it is trivially testable and keeps the ViewModel thin.
 */
data class ParsedQuery(val text: String, val fromNick: String?)

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
fun isEmptySearchQuery(raw: String): Boolean =
    parseSearchQuery(raw).let { it.text.isBlank() && it.fromNick == null }

/** One logical result request. Results from an older key must never render under a newer one. */
private data class SearchKey(
    val rawQuery: String = "",
    val scope: SearchScope = SearchScope.ALL,
    val bufferId: Long? = null,
) {
    val hasBufferScope: Boolean get() = bufferId != null

    fun emptyState() = SearchUiState(
        rawQuery = rawQuery,
        scope = scope,
        hasBufferScope = hasBufferScope,
    )

    fun loadingState() = emptyState().copy(searching = true)
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
) : ViewModel() {

    /**
     * State changes atomically by logical query/scope key. This lets the UI clear old results
     * before the debounce while also preventing a late flow emission for a superseded key.
     */
    private val searchKey = MutableStateFlow(SearchKey())

    fun init(bufferId: Long?) {
        searchKey.update {
            it.copy(
                bufferId = bufferId,
                scope = if (bufferId == null) SearchScope.ALL else SearchScope.CURRENT,
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<SearchUiState> =
        searchKey
            .flatMapLatest { key ->
                val parsed = parseSearchQuery(key.rawQuery)
                if (isEmptySearchQuery(key.rawQuery)) {
                    flowOf(key.emptyState())
                } else {
                    flow {
                        // Publish the key immediately. Only the repository call is debounced.
                        emit(key.loadingState())
                        delay(QUERY_DEBOUNCE_MS)
                        val scopeId = if (key.scope == SearchScope.CURRENT) key.bufferId else null
                        searchRepository.search(parsed.text, scopeId).collect { hits ->
                            // flatMapLatest cancels the old collector. The explicit key guard
                            // also blocks a result racing a synchronous key replacement.
                            if (searchKey.value == key) {
                                val filtered = parsed.fromNick?.let { nick ->
                                    hits.filter { it.message.sender.equals(nick, ignoreCase = true) }
                                } ?: hits
                                emit(
                                    SearchUiState(
                                        rawQuery = key.rawQuery,
                                        scope = key.scope,
                                        hasBufferScope = key.hasBufferScope,
                                        groups = groupHits(filtered),
                                        searching = false,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = SearchUiState(),
            )

    fun onQueryChange(q: String) { searchKey.update { it.copy(rawQuery = q) } }

    fun onScopeChange(s: SearchScope) { searchKey.update { it.copy(scope = s) } }

    private companion object {
        /** Wait for a typing pause before hitting the Room FTS query. */
        const val QUERY_DEBOUNCE_MS = 250L
    }
}

/** Group hits by buffer, preserving overall recency order (hits already time-ordered by DAO). */
fun groupHits(hits: List<SearchHit>): List<SearchGroup> =
    hits.groupBy { it.message.bufferId }
        .map { (bufferId, groupHits) ->
            val first = groupHits.first()
            SearchGroup(
                bufferId = bufferId,
                bufferDisplayName = first.bufferDisplayName,
                networkName = first.networkName,
                hits = groupHits,
            )
        }
