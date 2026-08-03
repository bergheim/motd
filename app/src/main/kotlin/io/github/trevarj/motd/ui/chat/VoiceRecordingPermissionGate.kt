package io.github.trevarj.motd.ui.chat

/** Keeps microphone permission results on the same recording-start path as pointer and semantic input. */
internal class VoiceRecordingPermissionGate(
    private val permissionGranted: () -> Boolean,
    private val onStart: (locked: Boolean) -> Unit,
    private val onDenied: () -> Unit,
) {
    private var pendingLocked: Boolean? = null

    /** Returns whether the caller must launch Android's permission request. */
    fun start(locked: Boolean): Boolean {
        if (permissionGranted()) {
            onStart(locked)
            return false
        }
        if (pendingLocked != null) return false
        pendingLocked = locked
        return true
    }

    fun onPermissionResult(granted: Boolean) {
        val locked = pendingLocked ?: return
        pendingLocked = null
        if (granted) onStart(locked) else onDenied()
    }
}
