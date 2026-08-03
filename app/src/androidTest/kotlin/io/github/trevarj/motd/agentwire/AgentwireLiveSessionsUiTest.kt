package io.github.trevarj.motd.agentwire

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class AgentwireLiveSessionsUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun desktopTui_isVisibleAndRemainsManualToAttach() {
        var attached: Pair<String, String?>? = null
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AgentwireLiveSessions(
                    sessions = listOf(
                        AgentwireListItem(
                            id = "thread-desktop",
                            title = "Desktop TUI",
                            subtitle = "/work/motd",
                            raw = buildJsonObject {
                                put("busy", true)
                                put("tuiAttached", true)
                            },
                        ),
                    ),
                    activeSid = null,
                    actions = emptySet(),
                    onAttach = { sid, cwd -> attached = sid to cwd },
                )
            }
        }

        compose.onNodeWithTag("agentwire_live_sessions").assertIsDisplayed()
        compose.onNodeWithText("Desktop TUI").assertIsDisplayed()
        compose.onNodeWithText("TUI").assertIsDisplayed()
        compose.onNodeWithText("Running").assertIsDisplayed()
        compose.runOnIdle { assertNull(attached) }

        compose.onNodeWithText("Attach").performClick()
        compose.runOnIdle {
            assertEquals("thread-desktop" to "/work/motd", attached)
        }
    }
}
