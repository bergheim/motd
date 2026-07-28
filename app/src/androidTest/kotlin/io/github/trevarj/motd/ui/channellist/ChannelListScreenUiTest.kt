package io.github.trevarj.motd.ui.channellist

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import io.github.trevarj.motd.backend.ConnectionState
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChannelListScreenUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun keyboardSearch_submitsVisibleTextWhenExternalQueryIsStale() {
        var submittedQuery: String? = null
        compose.setContent {
            MotdTheme {
                ChannelListContent(
                    state = ChannelListUiState(
                        networkId = 2,
                        initialized = true,
                        connState = ConnectionState.Ready("trev"),
                    ),
                    onBack = {},
                    onQueryChange = {},
                    onSearch = { submittedQuery = it },
                    onJoin = {},
                )
            }
        }

        val search = compose.onNodeWithTag("channel_list_search_field")
        search.performTextInput("bitcoin")
        search.performImeAction()

        compose.runOnIdle { assertEquals("bitcoin", submittedQuery) }
    }
}
