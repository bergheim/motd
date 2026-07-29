package io.github.trevarj.motd.service

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.github.trevarj.motd.backend.ConnectionState
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground-service keeper for the connection subsystem (plans/05). Thin [LifecycleService]:
 * onStartCommand → startForeground(status) + connectionManager.startAll(); onDestroy → stopAll().
 * START_STICKY so Android restarts it after a kill while PERSISTENT_SOCKET is in effect.
 */
@AndroidEntryPoint
class IrcForegroundService : LifecycleService() {

    @Inject lateinit var connectionManager: ConnectionManager
    @Inject lateinit var notifications: MotdNotifications

    override fun onCreate() {
        super.onCreate()
        // Reflect live connection state in the status notification. Read through the neutral seam:
        // a downcast to the IRC manager silently yielded null once the registry-dispatching
        // composite became the bound implementation, which stopped the status ever updating.
        lifecycleScope.launch {
            connectionManager.connectionStates.collect { states -> updateStatus(states) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            lifecycleScope.launch(Dispatchers.Default) {
                connectionManager.stopAll()
                withContext(Dispatchers.Main) { stopSelf() }
            }
            return START_NOT_STICKY
        }
        startAsForeground()
        // Off the main thread: a lifecycle broadcast resolves every registered backend's session
        // manager, and a backend's construction can do real work (protocol library initialization).
        // Blocking the main thread here stalls the freshly-foregrounded service.
        lifecycleScope.launch(Dispatchers.Default) { connectionManager.startAll() }
        return START_STICKY
    }

    // The merged manifest for every flavor declares specialUse. AGP lint loses that declaration
    // when analyzing the shared service against the Google flavor's manifest overlay.
    @SuppressLint("ForegroundServiceType")
    private fun startAsForeground() {
        val notification = notifications.statusNotification(
            connectedCount = 0,
            reconnecting = false,
            starting = true,
        )
        // FOREGROUND_SERVICE_TYPE_SPECIAL_USE is an API 34 constant; only pass the type on 34+.
        // On 29-33 use the 2-arg overload (the manifest still declares foregroundServiceType).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(STATUS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(STATUS_ID, notification)
        }
    }

    private fun updateStatus(states: Map<Long, ConnectionState>) {
        val connected = states.values.count { it is ConnectionState.Ready }
        val reconnecting = states.values.any {
            it is ConnectionState.Connecting || it is ConnectionState.Authenticating
        }
        val notification = notifications.statusNotification(
            connectedCount = connected,
            reconnecting = reconnecting && connected == 0,
            starting = states.isEmpty(),
        )
        // POST_NOTIFICATIONS is only a runtime permission on API 33+; guard so lint's flow
        // analysis is satisfied and we don't attempt to post the status update without it.
        val canPost = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (canPost) {
            runCatching {
                androidx.core.app.NotificationManagerCompat.from(this).notify(STATUS_ID, notification)
            }
        }
    }

    // Service removal during a fully verified push hand-off must not disable the singleton
    // connection subsystem. Explicit ACTION_STOP performs stopAll above; process death naturally
    // tears down both service and manager together.

    companion object {
        const val STATUS_ID = 1
        const val ACTION_STOP = "io.github.trevarj.motd.service.STOP"
    }
}
