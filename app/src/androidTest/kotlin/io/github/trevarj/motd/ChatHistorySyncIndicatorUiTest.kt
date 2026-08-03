package io.github.trevarj.motd

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.service.HistorySyncStatus
import io.github.trevarj.motd.ui.chat.CHAT_HISTORY_SYNC_INDICATOR_TAG
import io.github.trevarj.motd.ui.chat.CHAT_HISTORY_SYNC_RETRY_TAG
import io.github.trevarj.motd.ui.chat.EMPTY_HISTORY_LOADING_INDICATOR_DELAY_MS
import io.github.trevarj.motd.ui.chat.HISTORY_SYNC_INDICATOR_DELAY_MS
import io.github.trevarj.motd.ui.chat.TimelineHistorySyncIndicator
import io.github.trevarj.motd.ui.chat.TimelineTopOverlays
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatHistorySyncIndicatorUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun activeSyncAppearsOnlyAfterDelay() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MotdTheme {
                TimelineHistorySyncIndicator(
                    status = HistorySyncStatus.Syncing,
                    timelineEmpty = false,
                    retryEnabled = true,
                    onRetry = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onAllNodesWithTag(CHAT_HISTORY_SYNC_INDICATOR_TAG).assertCountEquals(0)
        compose.mainClock.advanceTimeBy(HISTORY_SYNC_INDICATOR_DELAY_MS - 1)
        compose.onAllNodesWithTag(CHAT_HISTORY_SYNC_INDICATOR_TAG).assertCountEquals(0)
        compose.mainClock.advanceTimeBy(1)
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag(CHAT_HISTORY_SYNC_INDICATOR_TAG).assertIsDisplayed()
        compose.onNodeWithText("Finding first unread…").assertExists()
    }

    @Test
    fun emptyTimelineUsesLoadingCopy() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MotdTheme {
                TimelineHistorySyncIndicator(
                    status = HistorySyncStatus.Checking,
                    timelineEmpty = true,
                    retryEnabled = true,
                    onRetry = {},
                )
            }
        }
        compose.waitForIdle()

        compose.mainClock.advanceTimeBy(EMPTY_HISTORY_LOADING_INDICATOR_DELAY_MS)
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag(CHAT_HISTORY_SYNC_INDICATOR_TAG).assertIsDisplayed()
        compose.onNodeWithText("Loading messages…").assertExists()
    }

    @Test
    fun partialStateDoesNotCoverCachedMessages() {
        compose.setContent {
            MotdTheme {
                TimelineHistorySyncIndicator(
                    status = HistorySyncStatus.Partial("fixture"),
                    timelineEmpty = false,
                    retryEnabled = true,
                    onRetry = {},
                )
            }
        }

        compose.onAllNodesWithTag(CHAT_HISTORY_SYNC_INDICATOR_TAG).assertCountEquals(0)
    }

    @Test
    fun failedStateKeepsAccessibleManualRetry() {
        var retries = 0
        compose.setContent {
            MotdTheme {
                TimelineHistorySyncIndicator(
                    status = HistorySyncStatus.Failed("fixture"),
                    timelineEmpty = false,
                    retryEnabled = true,
                    onRetry = { retries++ },
                )
            }
        }

        compose.onNodeWithText("Couldn't sync messages").assertIsDisplayed()
        compose.onNodeWithTag(CHAT_HISTORY_SYNC_RETRY_TAG)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, retries)
    }

    @Test
    fun topOverlaysKeepSyncBelowAudioWithoutOverlap() {
        compose.setContent {
            Box(Modifier.fillMaxWidth().height(160.dp)) {
                TimelineTopOverlays(
                    audioPlayer = {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                                .testTag("fixture_audio_player"),
                        )
                    },
                    historyIndicator = {
                        Box(Modifier.size(24.dp).testTag("fixture_history_sync"))
                    },
                )
            }
        }

        val audioBounds = compose.onNodeWithTag("fixture_audio_player").getUnclippedBoundsInRoot()
        val syncBounds = compose.onNodeWithTag("fixture_history_sync").getUnclippedBoundsInRoot()
        assertTrue("audio player was not pinned to the timeline top", audioBounds.top <= 1.dp)
        assertTrue("history sync overlapped the audio player", syncBounds.top >= audioBounds.bottom)
    }
}
