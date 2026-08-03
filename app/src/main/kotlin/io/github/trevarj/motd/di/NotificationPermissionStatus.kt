package io.github.trevarj.motd.di

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Durable record that the one automatic notification request has been launched. */
interface NotificationPermissionRequestHistory {
    fun wasLaunched(): Boolean
    fun markLaunched()
}

private class SharedPreferencesNotificationPermissionRequestHistory(context: Context) :
    NotificationPermissionRequestHistory {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun wasLaunched(): Boolean = preferences.getBoolean(REQUEST_LAUNCHED, false)

    override fun markLaunched() {
        // The request can display over a process recreation, so make the history durable first.
        preferences.edit(commit = true) { putBoolean(REQUEST_LAUNCHED, true) }
    }

    private companion object {
        const val PREFERENCES = "notification_permission"
        const val REQUEST_LAUNCHED = "request_launched_v1"
    }
}

/**
 * App-wide notification permission state, independent of the selected delivery provider.
 *
 * Android has no permission-state callback for a return from system settings; callers explicitly
 * invoke [refresh] on resume. Request history is deliberately separate from the OS grant so a
 * denial does not cause a prompt loop after process recreation.
 */
@Singleton
class NotificationPermissionStatus(
    private val apiLevel: () -> Int,
    private val isGrantedBySystem: () -> Boolean,
    private val requestHistory: NotificationPermissionRequestHistory,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        apiLevel = { Build.VERSION.SDK_INT },
        isGrantedBySystem = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionGranted(context)
            } else {
                true
            }
        },
        requestHistory = SharedPreferencesNotificationPermissionRequestHistory(context),
    )

    private val _granted = MutableStateFlow(readGranted())
    val granted: StateFlow<Boolean> = _granted.asStateFlow()

    /** Re-read the current platform setting, including after a return from system settings. */
    fun refresh() {
        _granted.value = readGranted()
    }

    /** Accept the runtime request callback without waiting for an unrelated delivery emission. */
    fun onPermissionResult(granted: Boolean) {
        _granted.value = if (apiLevel() < Build.VERSION_CODES.TIRAMISU) true else granted
    }

    /**
     * Whether MainActivity may make the one automatic runtime request.
     *
     * A rationale means Android has already denied once, while a previous history with no
     * rationale is the permanently-denied shape. Both route users to Settings remediation.
     */
    fun shouldRequestAutomatically(rationaleAvailable: Boolean): Boolean =
        apiLevel() >= Build.VERSION_CODES.TIRAMISU && !readGranted() &&
            !requestHistory.wasLaunched() && !rationaleAvailable

    /** Must run immediately before launching the platform request. */
    fun markRequestLaunched() {
        requestHistory.markLaunched()
    }

    private fun readGranted(): Boolean =
        apiLevel() < Build.VERSION_CODES.TIRAMISU || isGrantedBySystem()
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun notificationPermissionGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
