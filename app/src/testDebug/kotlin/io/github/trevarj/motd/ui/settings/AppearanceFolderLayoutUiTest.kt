package io.github.trevarj.motd.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.data.prefs.FolderDisplayMode
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppearanceFolderLayoutUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun folderLayoutSheetInvokesTabsCallback() {
        var selected: FolderDisplayMode? = null
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AppearanceSettingsContent(
                    settings = Settings(),
                    appearance = AppearanceConfig(),
                    onBack = {},
                    onOpenNickColors = {},
                    onThemePreset = {},
                    onTrueBlack = {},
                    onFollowSystem = {},
                    onDynamicColor = {},
                    onLayoutDensity = {},
                    onFolderDisplayMode = { selected = it },
                    onAvatarStyle = {},
                    onNickColorsEnabled = {},
                    onNickColorPalette = {},
                    onWallpaper = {},
                    onUiFontScale = {},
                    onConversationFontScale = {},
                    onFontChoice = {},
                    onShowTimestamps = {},
                    onTimeFormat = {},
                    onCustomTimeFormatPattern = {},
                    onMessageSpacing = {},
                    onBubbleCornerStyle = {},
                    onLauncherIcon = {},
                )
            }
        }

        compose.onNodeWithTag("settings_folder_layout_picker").performScrollTo().performClick()
        compose.onNodeWithTag("settings_folder_layout_sheet").assertIsDisplayed()
        compose.onNodeWithTag("settings_folder_layout_tabs").performClick()

        assertEquals(FolderDisplayMode.TABS, selected)
    }
}
