package io.github.trevarj.motd.ui.channelinfo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChannelInfoTopicEditUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun offlineSave_keepsExactDraftAndDialogWithRetryError() {
        var mutation by mutableStateOf<TopicMutationState>(TopicMutationState.Idle)
        compose.setContent {
            TopicContent(mutation = mutation, onSetTopic = { mutation = TopicMutationState.Failed })
        }

        openEditorAndEnter("exact offline topic")
        compose.onNodeWithTag("channelinfo_topic_edit_save").performClick()

        compose.onNodeWithTag("channelinfo_topic_edit_dialog").assertExists()
        compose.onNodeWithTag("channelinfo_topic_edit_text").assertTextContains("exact offline topic")
        compose.onNodeWithTag("channelinfo_topic_edit_error").assertExists()
    }

    @Test
    fun pendingSave_disablesDuplicateSubmission() {
        var mutation by mutableStateOf<TopicMutationState>(TopicMutationState.Idle)
        var calls = 0
        compose.setContent {
            TopicContent(
                mutation = mutation,
                onSetTopic = {
                    calls += 1
                    mutation = TopicMutationState.Submitting
                },
            )
        }

        openEditorAndEnter("one write only")
        compose.onNodeWithTag("channelinfo_topic_edit_save").performClick()

        compose.onNodeWithTag("channelinfo_topic_edit_save").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test
    fun acceptedSave_dismissesTheDialog() {
        var mutation by mutableStateOf<TopicMutationState>(TopicMutationState.Idle)
        compose.setContent {
            TopicContent(mutation = mutation, onSetTopic = { mutation = TopicMutationState.Accepted })
        }

        openEditorAndEnter("accepted write")
        compose.onNodeWithTag("channelinfo_topic_edit_save").performClick()

        compose.waitForIdle()
        compose.onAllNodesWithTag("channelinfo_topic_edit_dialog").assertCountEquals(0)
    }

    @Test
    fun confirmedOfflineLeave_keepsChannelInfoConfirmationOpenWithRetryFeedback() {
        var mutation by mutableStateOf<LeaveMutationState>(LeaveMutationState.Idle)
        var leaveCalls = 0
        compose.setContent {
            MotdTheme {
                ChannelInfoContent(
                    state = ChannelInfoUiState(
                        buffer = BufferEntity(1, 1, "#internal-alias", "#room", BufferType.CHANNEL),
                    ),
                    onBack = {},
                    onSetPinned = {},
                    onSetMuted = {},
                    onLeave = {
                        leaveCalls += 1
                        mutation = LeaveMutationState.Failed
                    },
                    leaveMutation = mutation,
                )
            }
        }

        compose.onNodeWithContentDescription("Leave").performClick()
        compose.onNodeWithTag("channelinfo_leave_confirm").performClick()

        compose.onAllNodesWithText("Leave channel?").assertCountEquals(1)
        compose.onAllNodesWithTag("channelinfo_leave_error").assertCountEquals(1)
        compose.runOnIdle { assertEquals(1, leaveCalls) }
    }

    private fun openEditorAndEnter(text: String) {
        compose.onNodeWithContentDescription("Edit topic").performClick()
        val field = compose.onNodeWithTag("channelinfo_topic_edit_text")
        field.performTextClearance()
        field.performTextInput(text)
    }

    @androidx.compose.runtime.Composable
    private fun TopicContent(mutation: TopicMutationState, onSetTopic: (String) -> Unit) {
        MotdTheme {
            ChannelInfoContent(
                state = ChannelInfoUiState(
                    buffer = BufferEntity(
                        id = 1,
                        networkId = 1,
                        name = "#internal-alias",
                        displayName = "#room",
                        type = BufferType.CHANNEL,
                        topic = "original topic",
                    ),
                ),
                onBack = {},
                onSetPinned = {},
                onSetMuted = {},
                onLeave = {},
                onSetTopic = onSetTopic,
                topicMutation = mutation,
            )
        }
    }
}
