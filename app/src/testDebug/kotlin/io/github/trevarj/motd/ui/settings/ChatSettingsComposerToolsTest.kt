package io.github.trevarj.motd.ui.settings

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.trevarj.motd.audio.VoiceConfig
import io.github.trevarj.motd.avatar.AvatarConfig
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import io.github.trevarj.motd.data.prefs.ReplyConfig
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatSettingsComposerToolsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun composerToolSwitchesRenderAndDispatchIndependently() {
        var emoji: Boolean? = null
        var formatting: Boolean? = null
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatSettingsContent(
                    settings = Settings(showComposerEmoji = false, showComposerFormattingTools = true),
                    reply = ReplyConfig(),
                    contentPreviews = ContentPreviewConfig(),
                    voice = VoiceConfig(),
                    avatars = AvatarConfig(),
                    onBack = {},
                    onOpenFriends = {},
                    onOpenFools = {},
                    onOpenDirectConnections = {},
                    onPresenceMode = {},
                    onShowRedactedMessages = {},
                    onAutoAwayEnabled = {},
                    onAutoAwayMinutes = {},
                    onAutoAwayMessage = {},
                    onFoolsMode = {},
                    onShowComposerEmoji = { emoji = it },
                    onShowComposerFormattingTools = { formatting = it },
                    onChatSoundsEnabled = {},
                    onVisibleReplyPrefix = {},
                    onShowImages = {},
                    onShowLinkPreviews = {},
                    onDirectMediaOnProxiedNetworks = {},
                    onShowSharedAvatars = {},
                    onVoiceEncryptionDefault = {},
                    onVoiceQuality = {},
                    onVoiceNoiseReduction = {},
                    onClearAudioCache = {},
                )
            }
        }

        compose
            .onNodeWithText("Emoji tool")
            .performScrollTo()
            .assertIsOff()
            .performClick()
        compose
            .onNodeWithText("Formatting tools")
            .performScrollTo()
            .assertIsOn()
            .performClick()

        compose.runOnIdle {
            assertEquals(true, emoji)
            assertEquals(false, formatting)
        }
    }
}
