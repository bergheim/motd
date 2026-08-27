package io.github.trevarj.motd.ui.firehose

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.trevarj.motd.data.db.FirehoseRow
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.ui.theme.LocalLottieMotionEnabled
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

/**
 * Presentation contract of the merged stream: what a line shows, what a tap reports, and the two
 * states that are not a list.
 *
 * Drives [FirehoseContent] directly off a static [PagingData] rather than the screen, which owns a
 * Hilt ViewModel; the branch under test is entirely a function of the paging load state, which
 * `PagingData.from` sets exactly. Lives in `testDebug` because [createComposeRule] launches a
 * `ComponentActivity` that only the debug manifest declares — see `EmptyStateLayoutTest`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FirehoseScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var opened: Triple<Long, Long, Long>? = null

    private fun row(
        id: Long,
        bufferId: Long = 7L,
        buffer: String = "#kotlin",
        network: String = "Libera",
        sender: String = "nick",
        text: String = "hello there",
        kind: MessageKind = MessageKind.PRIVMSG,
        serverTime: Long = 1_700_000_000_000L,
    ) = FirehoseRow(
        message =
            MessageEntity(
                id = id,
                bufferId = bufferId,
                serverTime = serverTime,
                sender = sender,
                kind = kind,
                text = text,
                dedupKey = "dedup-$id",
            ),
        bufferDisplayName = buffer,
        networkName = network,
    )

    /** Source states for a stream that has finished refreshing, however that refresh ended. */
    private fun settled(refresh: LoadState) =
        LoadStates(
            refresh = refresh,
            prepend = LoadState.NotLoading(endOfPaginationReached = true),
            append = LoadState.NotLoading(endOfPaginationReached = true),
        )

    /** The stream is built by the caller, so recomposition cannot restart the collection. */
    private fun setContent(
        stream: Flow<PagingData<FirehoseRow>>,
        showNetwork: () -> Boolean = { false },
    ) {
        compose.setContent {
            // Motion off: the empty state holds its caption back until the ghost rows' Lottie
            // clock says so, and a stub composition never advances one.
            CompositionLocalProvider(LocalLottieMotionEnabled provides false) {
                MotdTheme(dynamicColor = false) {
                    FirehoseContent(
                        rows = stream.collectAsLazyPagingItems(),
                        showNetwork = showNetwork(),
                        onOpenMessage = { bufferId, eventId, serverTime ->
                            opened = Triple(bufferId, eventId, serverTime)
                        },
                    )
                }
            }
        }
    }

    /**
     * The first paging emission lands after composition, so every assertion waits on the branch it
     * expects rather than on a single idle pass.
     */
    private fun awaitText(text: String) =
        compose.waitUntil {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }

    private fun awaitTag(tag: String) =
        compose.waitUntil {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

    @Test
    fun aLineShowsItsConversationTagAndFormattedBodyAndReportsTheTappedRow() {
        setContent(
            flowOf(
                PagingData.from(
                    listOf(
                        row(id = 11L, text = "hello there"),
                        row(id = 12L, buffer = "#nix", sender = "ana", text = "waves", kind = MessageKind.ACTION),
                    ),
                ),
            ),
        )

        awaitText("#kotlin")
        compose.onNodeWithText("#kotlin").assertIsDisplayed()
        compose.onNodeWithText("nick: hello there").assertIsDisplayed()
        // ACTION renders as an emote, not as "sender: text".
        compose.onNodeWithText("* ana waves").assertIsDisplayed()

        compose.onNodeWithText("nick: hello there").performClick()

        compose.runOnIdle {
            // Canonical row id for identity, serverTime only as the scroll anchor.
            assertEquals(Triple(7L, 11L, 1_700_000_000_000L), opened)
        }
    }

    @Test
    fun theNetworkLineAppearsOnlyWhileMoreThanOneNetworkExists() {
        var showNetwork by mutableStateOf(false)
        setContent(flowOf(PagingData.from(listOf(row(id = 21L)))), showNetwork = { showNetwork })

        awaitText("#kotlin")
        compose.onNodeWithText("Libera").assertDoesNotExist()

        showNetwork = true
        compose.waitForIdle()

        compose.onNodeWithText("Libera").assertIsDisplayed()
    }

    @Test
    fun aSettledEmptyStreamShowsTheEmptyState() {
        // Explicitly settled: paging only publishes load states that differ from the presenter's
        // own defaults, so a plain `from(emptyList())` would leave the refresh reading as loading.
        setContent(flowOf(PagingData.from(emptyList(), settled(LoadState.NotLoading(endOfPaginationReached = true)))))

        awaitTag("empty_state_ghost_rows")
        compose.onNodeWithText("Nothing here yet").assertIsDisplayed()
    }

    @Test
    fun retryingAFailedRefreshAsksThePagerForTheStreamAgain() {
        val loads = AtomicInteger()
        val pager =
            Pager(PagingConfig(pageSize = 20, enablePlaceholders = false)) {
                FailFirstPagingSource(loads, listOf(row(id = 31L)))
            }
        setContent(pager.flow)

        awaitText("Retry")
        compose.onNodeWithText("Couldn't load the firehose").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsEnabled().performClick()

        // The tap reached the pager rather than merely being tappable: a second load ran, and its
        // page is what takes the screen out of the error state.
        awaitText("nick: hello there")
        compose.onNodeWithText("Couldn't load the firehose").assertDoesNotExist()
        assertEquals(2, loads.get())
    }

    @Test
    fun aFailedRefreshKeepsAlreadyLoadedLinesOnScreen() {
        setContent(
            flowOf(
                PagingData.from(
                    listOf(row(id = 41L)),
                    settled(LoadState.Error(IllegalStateException("boom"))),
                ),
            ),
        )

        awaitText("nick: hello there")
        compose.onNodeWithText("Couldn't load the firehose").assertDoesNotExist()
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }
}

/** Fails its first load and serves [page] afterwards, so a retry shows up as a state change. */
private class FailFirstPagingSource(
    private val loads: AtomicInteger,
    private val page: List<FirehoseRow>,
) : PagingSource<Int, FirehoseRow>() {
    override fun getRefreshKey(state: PagingState<Int, FirehoseRow>): Int? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, FirehoseRow> =
        if (loads.incrementAndGet() == 1) {
            LoadResult.Error(IllegalStateException("boom"))
        } else {
            LoadResult.Page(data = page, prevKey = null, nextKey = null)
        }
}
