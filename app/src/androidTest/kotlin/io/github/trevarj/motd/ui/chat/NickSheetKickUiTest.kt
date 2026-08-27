package io.github.trevarj.motd.ui.chat

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NickSheetKickUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun inviteToChannelAction_isExposedForOtherUsers() {
        var invites = 0
        compose.setContent {
            MotdTheme {
                NickActionSheet(
                    nick = "bob",
                    isSelf = false,
                    isFriend = false,
                    isFool = false,
                    canModerate = false,
                    whois = null,
                    onDismiss = {},
                    onMessage = {},
                    onMention = {},
                    onToggleFriend = {},
                    onToggleFool = {},
                    onInviteToChannel = { invites++ },
                    onOp = {},
                    onVoice = {},
                    onKick = {},
                    onBan = { _, _ -> },
                )
            }
        }

        compose.onNodeWithTag("nick_sheet_invite_to_channel").performClick()
        compose.runOnIdle { assertEquals(1, invites) }
    }

    @Test
    fun inviteToChannelAction_isHiddenForSelf() {
        compose.setContent {
            MotdTheme {
                NickActionSheet(
                    nick = "me",
                    isSelf = true,
                    isFriend = false,
                    isFool = false,
                    canModerate = false,
                    whois = null,
                    onDismiss = {},
                    onMessage = {},
                    onMention = {},
                    onToggleFriend = {},
                    onToggleFool = {},
                    onInviteToChannel = {},
                    onOp = {},
                    onVoice = {},
                    onKick = {},
                    onBan = { _, _ -> },
                )
            }
        }

        compose.onNodeWithTag("nick_sheet_invite_to_channel").assertDoesNotExist()
    }

    @Test
    fun kickDialog_presetChipFillsTheReasonAndConfirmPassesIt() {
        var reason: String? = null
        var kicks = 0
        compose.setContent {
            MotdTheme {
                NickActionSheet(
                    nick = "bob",
                    isSelf = false,
                    isFriend = false,
                    isFool = false,
                    canModerate = true,
                    whois = null,
                    onDismiss = {},
                    onMessage = {},
                    onMention = {},
                    onToggleFriend = {},
                    onToggleFool = {},
                    onOp = {},
                    onVoice = {},
                    onKick = {
                        kicks += 1
                        reason = it
                    },
                    onBan = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText("Kick").performClick()
        compose.onNodeWithTag("nick_sheet_kick_chip_flooding").performClick()
        compose.onNodeWithTag("nick_sheet_kick_reason").assertTextContains("Flooding")
        compose.onNodeWithTag("nick_sheet_kick_confirm").performClick()

        compose.runOnIdle {
            assertEquals(1, kicks)
            assertEquals("Flooding", reason)
        }
    }
}
