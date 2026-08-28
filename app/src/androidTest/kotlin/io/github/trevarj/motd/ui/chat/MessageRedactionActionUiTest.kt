package io.github.trevarj.motd.ui.chat

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageRedactionActionUiTest {
    @get:Rule val compose = createComposeRule()

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun negotiatedRedactionActionInvokesConfirmationRequest() {
        var requested = false
        compose.setContent {
            MotdTheme {
                MessageActionSheet(
                    sheetState = rememberModalBottomSheetState(),
                    onDismiss = {},
                    onReply = {},
                    onReact = {},
                    onCopy = {},
                    onQuote = {},
                    onShare = {},
                    canRedact = true,
                    onRedact = { requested = true },
                )
            }
        }

        compose.onNodeWithTag("message_redact").performClick()
        compose.runOnIdle { assertTrue(requested) }
    }
}
