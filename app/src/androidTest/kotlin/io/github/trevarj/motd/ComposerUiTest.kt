package io.github.trevarj.motd

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertIsDisplayed
import io.github.trevarj.motd.ui.components.AutocompletePanel
import io.github.trevarj.motd.ui.components.Composer
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ComposerUiTest {
    @get:Rule
    val compose: ComposeContentTestRule = createComposeRule()

    @Test
    fun emojiPicker_opensAlongsideTheComposerInput() {
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("draft"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("chat_composer_input_row").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsDisplayed()
    }

    @Test
    fun emojiPicker_toggle_keepsTheComposerInputAvailable() {
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("draft"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_input_row").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsDisplayed()

        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("chat_composer_input_row").assertIsDisplayed()
        assertEquals(
            0,
            compose.onAllNodesWithTag("chat_composer_emoji_picker").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun autocompletePopup_rowIsClickableOutsideComposerBounds() {
        var picked: String? = null
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("ali"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                    autocomplete = {
                        AutocompletePanel(
                            candidates = listOf("alice"),
                            onPick = { picked = it },
                        )
                    },
                )
            }
        }

        compose.onNodeWithText("alice").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("alice", picked)
        }
    }

    @Test
    fun semanticVoiceActivation_startsOneLockedRecordingAndStopsWhenActive() {
        var starts = 0
        var stops = 0
        val recording = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        val voiceEnabled = mutableStateOf(true)
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue(),
                    onValueChange = {},
                    onSend = {},
                    enabled = enabled.value,
                    voiceEnabled = voiceEnabled.value,
                    voiceRecording = recording.value,
                    onVoiceAccessibilityStart = {
                        starts++
                        recording.value = true
                    },
                    onVoiceHoldStop = {
                        stops++
                        recording.value = false
                    },
                )
            }
        }
        compose.onNodeWithTag("chat_composer_voice").performTouchInput { click() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, starts) }

        compose.onNodeWithTag("chat_composer_voice")
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, starts) }

        compose.onNodeWithTag("chat_composer_voice").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, starts)
            assertEquals(1, stops)
        }

        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithTag("chat_composer_voice")
            .assertIsNotEnabled()

        compose.runOnIdle {
            enabled.value = true
            voiceEnabled.value = false
        }
        compose.onAllNodesWithTag("chat_composer_voice").assertCountEquals(0)
        compose.runOnIdle { assertEquals(1, starts) }
    }
}
