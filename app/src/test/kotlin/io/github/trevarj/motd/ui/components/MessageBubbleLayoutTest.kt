package io.github.trevarj.motd.ui.components

import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.TimeFormat
import io.github.trevarj.motd.data.prefs.isDark
import io.github.trevarj.motd.ui.theme.MotdDarkScheme
import io.github.trevarj.motd.ui.theme.MotdLightScheme
import io.github.trevarj.motd.ui.theme.contrastRatio
import io.github.trevarj.motd.ui.theme.fixedThemeScheme
import io.github.trevarj.motd.ui.theme.semanticColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageBubbleLayoutTest {
    @Test fun bubbleWidth_usesItsPaneAndCapsWideLayouts() {
        assertEquals(344, bubbleMaxWidthPx(420, 560))
        assertEquals(560, bubbleMaxWidthPx(1_000, 560))
        assertEquals(0, bubbleMaxWidthPx(0, 560))
    }

    @Test fun resolveIs24Hour_autoFollowsTheDeviceSetting() {
        assertTrue(resolveIs24Hour(TimeFormat.AUTO, deviceIs24 = true))
        assertFalse(resolveIs24Hour(TimeFormat.AUTO, deviceIs24 = false))
    }

    @Test fun resolveIs24Hour_h12AlwaysResolvesToFalse() {
        assertFalse(resolveIs24Hour(TimeFormat.H12, deviceIs24 = true))
        assertFalse(resolveIs24Hour(TimeFormat.H12, deviceIs24 = false))
    }

    @Test fun resolveIs24Hour_h24AlwaysResolvesToTrue() {
        assertTrue(resolveIs24Hour(TimeFormat.H24, deviceIs24 = true))
        assertTrue(resolveIs24Hour(TimeFormat.H24, deviceIs24 = false))
    }

    @Test fun bubbleRoles_areDistinctAndReadable() {
        // Every palette: the own-bubble container is blended, so its ink is re-fitted per scheme.
        val schemes =
            listOf(MotdLightScheme to false, MotdDarkScheme to true) +
                ColorThemePreset.entries.mapNotNull { preset -> fixedThemeScheme(preset)?.let { it to preset.isDark } }
        schemes.forEach { (scheme, dark) ->
            val semantic = semanticColors(scheme, dark)
            val roles =
                listOf(
                    messageBubbleRoleColors(scheme, isSelf = false, mentionHighlighted = false, MessageKind.PRIVMSG, semantic),
                    messageBubbleRoleColors(scheme, isSelf = true, mentionHighlighted = false, MessageKind.PRIVMSG, semantic),
                    messageBubbleRoleColors(scheme, isSelf = false, mentionHighlighted = true, MessageKind.PRIVMSG, semantic),
                    messageBubbleRoleColors(scheme, isSelf = false, mentionHighlighted = false, MessageKind.NOTICE, semantic),
                )

            assertEquals(4, roles.map { it.container }.distinct().size)
            roles.forEach { role ->
                assertTrue(contrastRatio(role.content, role.container) >= 4.5)
            }
        }
    }
}
