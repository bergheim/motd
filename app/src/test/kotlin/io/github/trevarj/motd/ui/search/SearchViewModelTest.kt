package io.github.trevarj.motd.ui.search

import app.cash.turbine.test
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.SearchHit
import io.github.trevarj.motd.data.repo.SearchRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Query pipeline: every logical query/scope key immediately clears stale rows and publishes
 * loading, while only the repository call is debounced. The component test covers the local IME
 * value's one-frame coherence guard; these tests exercise keyed cancellation at the ViewModel seam.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private fun hit(bufferId: Long, text: String, sender: String = "alice") = SearchHit(
        message = MessageEntity(
            id = bufferId, bufferId = bufferId, serverTime = 1_000L,
            sender = sender, kind = MessageKind.PRIVMSG, text = text, dedupKey = "k$bufferId",
        ),
        bufferDisplayName = "#kotlin", networkName = "Libera",
    )

    /** Counts search() invocations so we can assert the debounce collapses rapid keystrokes. */
    private class FakeSearchRepository(
        private val result: List<SearchHit>,
        val calls: AtomicInteger = AtomicInteger(0),
    ) : SearchRepository {
        override fun search(query: String, bufferId: Long?): Flow<List<SearchHit>> {
            calls.incrementAndGet()
            return flowOf(result)
        }
    }

    private data class SearchRequest(val query: String, val bufferId: Long?)

    /** A keyed, replaying source lets tests deliver results after its collector was cancelled. */
    private class ControlledSearchRepository : SearchRepository {
        private val flows = mutableMapOf<SearchRequest, MutableSharedFlow<List<SearchHit>>>()
        val calls = mutableListOf<SearchRequest>()

        override fun search(query: String, bufferId: Long?): Flow<List<SearchHit>> {
            val request = SearchRequest(query, bufferId)
            calls += request
            return flowFor(request)
        }

        fun emit(query: String, bufferId: Long?, hits: List<SearchHit>) {
            check(flowFor(SearchRequest(query, bufferId)).tryEmit(hits))
        }

        private fun flowFor(request: SearchRequest) = flows.getOrPut(request) {
            MutableSharedFlow(replay = 1)
        }
    }

    @Test
    fun blank_query_emits_empty_results_without_hitting_the_repo() = runTest {
        val repo = FakeSearchRepository(emptyList())
        val vm = SearchViewModel(repo)

        vm.state.test {
            assertEquals(SearchUiState(), awaitItem()) // initial
            vm.onQueryChange("   ")
            runCurrent()
            assertTrue("blank query yields no result groups", awaitItem().groups.isEmpty())
            assertEquals("blank query must not query the DB", 0, repo.calls.get())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun rapid_typing_is_debounced_into_a_single_db_query() = runTest {
        val repo = FakeSearchRepository(listOf(hit(1, "coroutine builder")))
        val vm = SearchViewModel(repo)

        vm.state.test {
            awaitItem() // initial

            // Simulate keystrokes faster than the debounce window.
            vm.onQueryChange("c")
            runCurrent()
            assertEquals("c", awaitItem().rawQuery)
            advanceTimeBy(50)
            vm.onQueryChange("co")
            runCurrent()
            assertEquals("co", awaitItem().rawQuery)
            advanceTimeBy(50)
            vm.onQueryChange("cor")
            runCurrent()
            assertEquals("cor", awaitItem().rawQuery)
            advanceTimeBy(50)
            vm.onQueryChange("coroutine")
            runCurrent()
            val loading = awaitItem()
            assertEquals("coroutine", loading.rawQuery)
            assertTrue(loading.searching)
            // Not yet past the debounce window: no query should have fired.
            assertEquals("no DB hit before the typing pause", 0, repo.calls.get())

            // Past the debounce window: exactly one query fires and results arrive.
            advanceTimeBy(300)
            val results = awaitItem()
            assertEquals("rapid typing collapses to one DB query", 1, repo.calls.get())
            assertEquals("coroutine", results.rawQuery)
            assertEquals(1, results.groups.size)
            assertEquals(1L, results.groups.first().bufferId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun clear_resets_selection_and_composition() {
        val editing = TextFieldValue(
            text = "coroutine",
            selection = TextRange(2, 7),
            composition = TextRange(0, 9),
        )

        val cleared = clearedSearchText()

        assertEquals("", cleared.text)
        assertEquals(TextRange.Zero, cleared.selection)
        assertEquals(null, cleared.composition)
        assertTrue(editing != cleared)
    }

    @Test
    fun query_change_immediately_clears_old_results_and_ignores_late_results() = runTest {
        val repo = ControlledSearchRepository()
        repo.emit("alpha", null, listOf(hit(1, "alpha result")))
        val vm = SearchViewModel(repo)

        vm.state.test {
            awaitItem()
            vm.onQueryChange("alpha")
            runCurrent()
            val alphaLoading = awaitItem()
            assertEquals("alpha", alphaLoading.rawQuery)
            assertTrue(alphaLoading.searching)
            assertTrue(alphaLoading.groups.isEmpty())

            advanceTimeBy(250)
            runCurrent()
            val alphaResults = awaitItem()
            assertEquals("alpha", alphaResults.rawQuery)
            assertEquals(1, alphaResults.groups.size)

            vm.onQueryChange("beta")
            runCurrent()
            val betaLoading = awaitItem()
            assertEquals("beta", betaLoading.rawQuery)
            assertTrue(betaLoading.searching)
            assertTrue(betaLoading.groups.isEmpty())

            repo.emit("alpha", null, listOf(hit(1, "late alpha result")))
            runCurrent()
            expectNoEvents()

            advanceTimeBy(250)
            runCurrent()
            repo.emit("beta", null, listOf(hit(2, "beta result")))
            runCurrent()
            val betaResults = awaitItem()
            assertEquals("beta", betaResults.rawQuery)
            assertTrue(!betaResults.searching)
            assertEquals("beta result", betaResults.groups.single().hits.single().message.text)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun scope_change_immediately_clears_old_results_and_ignores_late_scope_results() = runTest {
        val repo = ControlledSearchRepository()
        val vm = SearchViewModel(repo)

        vm.state.test {
            awaitItem()
            vm.init(bufferId = 7L)
            runCurrent()
            awaitItem() // blank, current-buffer scope
            vm.onScopeChange(SearchScope.ALL)
            runCurrent()
            awaitItem() // blank, global scope

            repo.emit("alpha", null, listOf(hit(1, "global alpha result")))
            vm.onQueryChange("alpha")
            runCurrent()
            awaitItem() // global loading
            advanceTimeBy(250)
            runCurrent()
            assertEquals("global alpha result", awaitItem().groups.single().hits.single().message.text)

            vm.onScopeChange(SearchScope.CURRENT)
            runCurrent()
            val currentLoading = awaitItem()
            assertEquals(SearchScope.CURRENT, currentLoading.scope)
            assertTrue(currentLoading.searching)
            assertTrue(currentLoading.groups.isEmpty())

            repo.emit("alpha", null, listOf(hit(1, "late global alpha result")))
            runCurrent()
            expectNoEvents()

            advanceTimeBy(250)
            runCurrent()
            repo.emit("alpha", 7L, listOf(hit(7, "current alpha result")))
            runCurrent()
            val currentResults = awaitItem()
            assertEquals(SearchScope.CURRENT, currentResults.scope)
            assertTrue(!currentResults.searching)
            assertEquals("current alpha result", currentResults.groups.single().hits.single().message.text)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun clear_immediately_removes_results_and_ignores_late_results() = runTest {
        val repo = ControlledSearchRepository()
        repo.emit("alpha", null, listOf(hit(1, "alpha result")))
        val vm = SearchViewModel(repo)

        vm.state.test {
            awaitItem()
            vm.onQueryChange("alpha")
            runCurrent()
            awaitItem() // alpha loading
            advanceTimeBy(250)
            runCurrent()
            assertEquals(1, awaitItem().groups.size)

            vm.onQueryChange("")
            runCurrent()
            assertEquals(SearchUiState(), awaitItem())

            repo.emit("alpha", null, listOf(hit(1, "late alpha result")))
            runCurrent()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
