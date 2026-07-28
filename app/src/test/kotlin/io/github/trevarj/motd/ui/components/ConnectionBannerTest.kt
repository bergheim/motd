package io.github.trevarj.motd.ui.components

import io.github.trevarj.motd.backend.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionBannerTest {

    @Test
    fun connectingStatusIsTransientAndUsesGraceWindow() {
        val status = bannerStatus(mapOf(1L to ConnectionState.Connecting)) { "Libera" }

        assertEquals("Connecting to Libera…", status?.text)
        assertTrue(status?.transient == true)
        assertEquals(3_000L, CONNECTION_BANNER_GRACE_MS)
    }

    @Test
    fun fatalFailureIsImmediateWhileRetryFailureIsTransient() {
        val fatal = bannerStatus(mapOf(1L to ConnectionState.Failed("bad cert", fatal = true))) { "Libera" }
        val retry = bannerStatus(mapOf(1L to ConnectionState.Failed("timeout", fatal = false))) { "Libera" }

        assertFalse(fatal?.transient == true)
        assertTrue(retry?.transient == true)
    }

    @Test
    fun readyStateClearsBannerAfterFailureOrConnecting() {
        assertNull(
            bannerStatus(
                mapOf(1L to ConnectionState.Ready("neo")),
            ) { "Libera" },
        )
    }

    @Test
    fun dismissedStatusStaysHiddenUntilConnectionStatusChanges() {
        val accountRequired = bannerStatus(
            mapOf(1L to ConnectionState.Failed("ACCOUNT_REQUIRED", fatal = true)),
        ) { "Libera" }
        val reconnecting = bannerStatus(
            mapOf(1L to ConnectionState.Connecting),
        ) { "Libera" }

        assertNull(
            visibleBannerStatus(
                accountRequired,
                accountRequired?.dismissalKey,
                transientGraceElapsed = true,
            ),
        )
        assertEquals(
            reconnecting,
            visibleBannerStatus(reconnecting, accountRequired?.dismissalKey, transientGraceElapsed = true),
        )
    }

    @Test
    fun transientStatusWaitsForGraceBeforeAppearing() {
        val connecting = bannerStatus(mapOf(1L to ConnectionState.Connecting)) { "Libera" }

        assertNull(visibleBannerStatus(connecting, dismissedStatusKey = null, transientGraceElapsed = false))
        assertEquals(
            connecting,
            visibleBannerStatus(connecting, dismissedStatusKey = null, transientGraceElapsed = true),
        )
    }
}
