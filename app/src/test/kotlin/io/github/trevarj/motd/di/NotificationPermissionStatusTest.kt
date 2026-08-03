package io.github.trevarj.motd.di

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionStatusTest {
    private class FakeHistory : NotificationPermissionRequestHistory {
        var launched = false
        override fun wasLaunched(): Boolean = launched
        override fun markLaunched() {
            launched = true
        }
    }

    @Test fun denied_runtime_result_updates_state_immediately() {
        val status = status(granted = true)

        status.onPermissionResult(false)

        assertFalse(status.granted.value)
    }

    @Test fun refresh_observes_grant_after_returning_from_system_settings() {
        var granted = false
        val status = status(granted = { granted })
        assertFalse(status.granted.value)

        granted = true
        status.refresh()

        assertTrue(status.granted.value)
    }

    @Test fun pre_tiramisu_is_granted_and_never_requests() {
        val status = NotificationPermissionStatus(
            apiLevel = { Build.VERSION_CODES.TIRAMISU - 1 },
            isGrantedBySystem = { false },
            requestHistory = FakeHistory(),
        )

        assertTrue(status.granted.value)
        assertFalse(status.shouldRequestAutomatically(rationaleAvailable = false))
    }

    @Test fun only_first_request_without_rationale_is_automatic() {
        val history = FakeHistory()
        val status = status(granted = false, history = history)

        assertTrue(status.shouldRequestAutomatically(rationaleAvailable = false))
        status.markRequestLaunched()
        assertFalse(status.shouldRequestAutomatically(rationaleAvailable = false))
    }

    @Test fun rationale_and_permanent_denial_shapes_route_to_remediation() {
        assertFalse(status(granted = false).shouldRequestAutomatically(rationaleAvailable = true))

        val permanentlyDeniedHistory = FakeHistory().also { it.markLaunched() }
        assertFalse(
            status(granted = false, history = permanentlyDeniedHistory)
                .shouldRequestAutomatically(rationaleAvailable = false),
        )
    }

    @Test fun process_recreation_keeps_denial_remediation_when_request_history_is_shared() {
        val history = FakeHistory()
        val firstProcess = status(granted = false, history = history)
        firstProcess.markRequestLaunched()

        val recreatedProcess = status(granted = false, history = history)

        assertFalse(recreatedProcess.shouldRequestAutomatically(rationaleAvailable = false))
    }

    @Test fun process_recreation_rechecks_the_current_platform_grant() {
        val recreatedProcess = status(granted = true)

        assertTrue(recreatedProcess.granted.value)
    }

    private fun status(
        granted: Boolean,
        history: NotificationPermissionRequestHistory = FakeHistory(),
    ): NotificationPermissionStatus = status(granted = { granted }, history = history)

    private fun status(
        granted: () -> Boolean,
        history: NotificationPermissionRequestHistory = FakeHistory(),
    ) = NotificationPermissionStatus(
        apiLevel = { Build.VERSION_CODES.TIRAMISU },
        isGrantedBySystem = granted,
        requestHistory = history,
    )
}
