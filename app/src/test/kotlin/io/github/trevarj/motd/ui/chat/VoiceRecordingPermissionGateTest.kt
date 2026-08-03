package io.github.trevarj.motd.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRecordingPermissionGateTest {
    @Test
    fun deniedLockedStart_neverStartsAndClearsThePendingRequest() {
        var starts = 0
        var denials = 0
        val gate = VoiceRecordingPermissionGate(
            permissionGranted = { false },
            onStart = { starts++ },
            onDenied = { denials++ },
        )

        assertTrue(gate.start(locked = true))
        gate.onPermissionResult(granted = false)
        gate.onPermissionResult(granted = true)

        assertEquals(0, starts)
        assertEquals(1, denials)
    }

    @Test
    fun repeatedPendingSemanticStart_requestsOnceAndStartsLockedOnce() {
        var starts = 0
        var locked: Boolean? = null
        val gate = VoiceRecordingPermissionGate(
            permissionGranted = { false },
            onStart = { value ->
                starts++
                locked = value
            },
            onDenied = {},
        )

        assertTrue(gate.start(locked = true))
        assertFalse(gate.start(locked = true))
        gate.onPermissionResult(granted = true)

        assertEquals(1, starts)
        assertEquals(true, locked)
    }
}
