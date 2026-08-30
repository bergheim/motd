package io.github.trevarj.motd

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.audio.AudioAttachment
import io.github.trevarj.motd.audio.AudioCacheStatus
import io.github.trevarj.motd.audio.AudioPlaybackState
import io.github.trevarj.motd.ui.components.AudioAttachmentPlayers
import io.github.trevarj.motd.ui.components.AudioMiniPlayer
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class AudioPlayerUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun uncached_audio_uses_download_action() {
        val attachment = audio()
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AudioAttachmentPlayers(
                    attachments = listOf(attachment),
                    playbackState = AudioPlaybackState(),
                    cacheStatuses = mapOf(attachment.playbackId to AudioCacheStatus.NOT_CACHED),
                    networkId = null,
                    isSelf = false,
                    onToggle = { _, _ -> },
                    onSeek = { _, _ -> },
                )
            }
        }

        compose.onNodeWithContentDescription("Download audio").assertIsDisplayed()
    }

    @Test fun cached_audio_uses_play_action() {
        val attachment = audio()
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AudioAttachmentPlayers(
                    attachments = listOf(attachment),
                    playbackState = AudioPlaybackState(),
                    cacheStatuses = mapOf(attachment.playbackId to AudioCacheStatus.CACHED),
                    networkId = null,
                    isSelf = false,
                    onToggle = { _, _ -> },
                    onSeek = { _, _ -> },
                )
            }
        }

        compose.onNodeWithContentDescription("Play audio").assertIsDisplayed()
    }

    @Test fun encrypted_audio_uses_lock_icon_and_keeps_full_timestamp() {
        val attachment = audio().copy(encrypted = true)
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AudioAttachmentPlayers(
                    attachments = listOf(attachment),
                    playbackState = AudioPlaybackState(),
                    cacheStatuses = mapOf(attachment.playbackId to AudioCacheStatus.CACHED),
                    networkId = null,
                    isSelf = false,
                    formattedTime = "12:34 PM",
                    onToggle = { _, _ -> },
                    onSeek = { _, _ -> },
                )
            }
        }

        compose.onNodeWithContentDescription("Encrypted audio").assertIsDisplayed()
        compose.onNodeWithText("12:34 PM").assertIsDisplayed()
    }

    @Test fun mini_player_scrubber_uses_the_full_player_height() {
        val attachment = audio()
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AudioMiniPlayer(
                    state =
                        AudioPlaybackState(
                            activeId = attachment.playbackId,
                            attachment = attachment,
                            durationMs = 60_000,
                        ),
                    onToggle = {},
                    onCancelLoading = {},
                    onRetry = {},
                    onDismiss = {},
                    onSeek = {},
                    onOpenOrigin = {},
                )
            }
        }

        val bounds = compose.onNodeWithTag("audio_mini_scrubber").getUnclippedBoundsInRoot()
        val height = bounds.bottom - bounds.top
        assertTrue("scrubber touch height was $height", height >= 32.dp)
    }

    @Test fun voice_speed_is_only_available_in_the_mini_player() {
        val attachment = audio().copy(voice = true)
        var requestedSpeed: Float? = null
        val state =
            AudioPlaybackState(
                activeId = attachment.playbackId,
                attachment = attachment,
                durationMs = 60_000,
            )
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AudioAttachmentPlayers(
                    attachments = listOf(attachment),
                    playbackState = state,
                    networkId = null,
                    isSelf = false,
                    onToggle = { _, _ -> },
                    onSeek = { _, _ -> },
                )
                AudioMiniPlayer(
                    state = state,
                    onToggle = {},
                    onCancelLoading = {},
                    onRetry = {},
                    onDismiss = {},
                    onSeek = {},
                    onOpenOrigin = {},
                    onSpeed = { requestedSpeed = it },
                )
            }
        }

        compose.onAllNodesWithTag("audio_speed").assertCountEquals(0)
        compose.onNodeWithTag("audio_mini_speed").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(requestedSpeed == 1.5f) }
    }

    private fun audio() =
        AudioAttachment(
            url = "https://files.example/song.ogg",
            title = "song.ogg",
            mimeType = "audio/ogg",
        )
}
