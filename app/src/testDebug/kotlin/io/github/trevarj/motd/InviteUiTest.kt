package io.github.trevarj.motd

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.trevarj.motd.data.prefs.AccountEnrollmentProvider
import io.github.trevarj.motd.invite.JoinInviteV1
import io.github.trevarj.motd.ui.invite.AccountSetupContent
import io.github.trevarj.motd.ui.invite.AccountSetupPhase
import io.github.trevarj.motd.ui.invite.AccountSetupUiState
import io.github.trevarj.motd.ui.invite.CreateInviteContent
import io.github.trevarj.motd.ui.invite.CreateInviteUiState
import io.github.trevarj.motd.ui.invite.JoinInviteContent
import io.github.trevarj.motd.ui.invite.JoinInvitePhase
import io.github.trevarj.motd.ui.invite.JoinInviteUiState
import io.github.trevarj.motd.ui.invite.QrInviteScannerScreen
import io.github.trevarj.motd.ui.onboarding.OnboardingContent
import io.github.trevarj.motd.ui.onboarding.OnboardingState
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
class InviteUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun onboardingBottomBarScansInvite() {
        var scanned = false
        compose.setContent {
            MotdTheme {
                OnboardingContent(
                    state = OnboardingState(),
                    onNext = {},
                    onBack = {},
                    onSkip = {},
                    onChoose = {},
                    onChooseBouncerKind = {},
                    onSelectPreset = {},
                    onServerChange = {},
                    onAuthChange = {},
                    onSojuLoginChange = {},
                    onZncLoginChange = {},
                    onRetry = {},
                    onRetryBouncerDiscovery = {},
                    onToggleBouncer = {},
                    onBouncerAddDraftChange = {},
                    onAddBouncer = {},
                    onSelectHistoryDepth = {},
                    onFinish = {},
                    onConfirmPlaintext = {},
                    onDismissPlaintext = {},
                    onScanInvite = { scanned = true },
                )
            }
        }

        compose.onNodeWithTag("onboarding_scan_invite").assertExists().performClick()
        assertTrue(scanned)
    }

    @Test
    fun scannerOffersPermissionAndPasteFallback() {
        compose.setContent { MotdTheme { QrInviteScannerScreen({}, {}, cameraAvailable = true) } }

        compose.onNodeWithTag("invite_camera_permission").assertExists()
        compose.onNodeWithTag("invite_paste").assertDoesNotExist()
        compose.onNodeWithTag("invite_paste_toggle").performClick()
        compose.onNodeWithTag("invite_paste").assertExists()
    }

    @Test
    fun cameraLessScannerKeepsPasteFallback() {
        compose.setContent { MotdTheme { QrInviteScannerScreen({}, {}, cameraAvailable = false) } }

        compose.onNodeWithTag("invite_paste").assertExists()
        compose.onNodeWithText("No camera is available. Paste a motd invite link below.").assertExists()
    }

    @Test
    fun recipientReviewsBeforeIdentity() {
        val invite = JoinInviteV1(networkName = "Ergo", host = "irc.example", port = 6697, channel = "#friends", channelKey = "secret")
        compose.setContent {
            MotdTheme {
                JoinInviteContent(
                    state = JoinInviteUiState(invite = invite, phase = JoinInvitePhase.REVIEW),
                    onBack = {},
                    onContinue = {},
                    onNickChange = {},
                    onConnect = {},
                    onRetry = {},
                    onSetupAccount = {},
                    onConfirmPlaintext = {},
                    onDismissPlaintext = {},
                )
            }
        }

        compose.onNodeWithText("#friends").assertExists()
        compose.onNodeWithText("Private-channel access is included in this invitation.").assertExists()
        compose.onNodeWithText("irc.example").assertDoesNotExist()
        compose.onNodeWithTag("invite_details_toggle").performClick()
        compose.onNodeWithText("irc.example").assertExists()
        compose.onNodeWithTag("invite_review_continue").assertExists()
    }

    @Test
    fun senderExplainsInstallAndRescan() {
        val invite = JoinInviteV1(networkName = "Ergo", host = "irc.example", port = 6697, channel = "#friends")
        compose.setContent {
            MotdTheme {
                CreateInviteContent(
                    state =
                        CreateInviteUiState(
                            loading = false,
                            invite = invite,
                            includeKeyConfirmed = true,
                            qrText = "https://github.com/trevarj/motd/releases/latest#motd-invite=x",
                        ),
                    onBack = {},
                    onKeyChange = {},
                    onConfirmKey = {},
                    onRemoveKey = {},
                )
            }
        }

        compose.onNodeWithText("1.").assertExists()
        compose.onNodeWithText("Have your friend scan this code to open the motd download page.").assertExists()
        compose.onNodeWithText("2.").assertExists()
        compose.onNodeWithText("Install or open motd, then scan the same code inside the app.").assertExists()
        compose.onNodeWithTag("invite_share").assertExists()
    }

    @Test
    fun oftcVerificationUsesCaptchaLinkInsteadOfCode() {
        compose.setContent {
            MotdTheme {
                AccountSetupContent(
                    state =
                        AccountSetupUiState(
                            phase = AccountSetupPhase.VERIFY,
                            provider = AccountEnrollmentProvider.OFTC,
                            account = "alice",
                            verificationUrl = "https://verify.oftc.net/account/token",
                        ),
                    onBack = {},
                    onAccountChange = {},
                    onEmailChange = {},
                    onVerificationChange = {},
                    onSubmit = {},
                    onVerify = {},
                    onRetry = {},
                )
            }
        }

        compose.onNodeWithTag("account_setup_open_verification").assertExists()
        compose.onNodeWithTag("account_setup_new_verification").assertExists()
        compose.onNodeWithTag("account_setup_code").assertDoesNotExist()
    }

    @Test
    fun accountVerificationUsesDedicatedCodeField() {
        compose.setContent {
            MotdTheme {
                AccountSetupContent(
                    state =
                        AccountSetupUiState(
                            phase = AccountSetupPhase.VERIFY,
                            provider = AccountEnrollmentProvider.LIBERA,
                            account = "alice",
                        ),
                    onBack = {},
                    onAccountChange = {},
                    onEmailChange = {},
                    onVerificationChange = {},
                    onSubmit = {},
                    onVerify = {},
                    onRetry = {},
                )
            }
        }

        compose.onNodeWithTag("account_setup_code").assertExists()
        compose.onNodeWithTag("account_setup_verify").assertExists()
    }
}
