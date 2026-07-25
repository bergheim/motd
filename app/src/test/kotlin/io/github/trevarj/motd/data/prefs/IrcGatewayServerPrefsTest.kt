package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IrcGatewayServerPrefsTest {
    @Test
    fun `prependRecentServer puts newest first, dedupes case-insensitively, and caps`() {
        assertEquals(listOf("b", "a"), prependRecentServer(listOf("a"), "b"))
        // Re-using an existing server (case-insensitively) moves it to the front without duplicating.
        assertEquals(listOf("A", "b", "c"), prependRecentServer(listOf("b", "a", "c"), "A"))
        // Blank input leaves the list untouched.
        assertEquals(listOf("a"), prependRecentServer(listOf("a"), "  "))
        // Cap keeps only the most-recent [cap] entries.
        assertEquals(listOf("x", "1", "2"), prependRecentServer(listOf("1", "2", "3"), "x", cap = 3))
    }

    @Test
    fun `recents survive a fresh repository instance and stay most-recent-first`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val networkId = System.nanoTime()
        val prefs: IrcGatewayServerPrefs = IrcGatewayServerPrefsImpl(context)

        prefs.remember(networkId, "irc.libera.chat")
        prefs.remember(networkId, "irc.oftc.net")

        assertEquals(
            listOf("irc.oftc.net", "irc.libera.chat"),
            IrcGatewayServerPrefsImpl(context).recentServers(networkId).first(),
        )
    }
}
