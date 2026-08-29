package io.github.trevarj.motd.service

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.prefs.PushPrefs
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.push.PushHealthStore
import io.github.trevarj.motd.push.socketFallbackNetworkIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Whether boot delivery is enabled. An unset component state uses manifest's enabled default. */
internal fun isBootReceiverEnabled(context: Context): Boolean =
    when (
        context.packageManager.getComponentEnabledSetting(
            ComponentName(context, BootReceiver::class.java),
        )
    ) {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
        -> false

        else -> true
    }

/** Enables or disables boot delivery without killing running app process. */
internal fun setBootReceiverEnabled(
    context: Context,
    enabled: Boolean,
) {
    if (isBootReceiverEnabled(context) == enabled) return
    context.packageManager.setComponentEnabledSetting(
        ComponentName(context, BootReceiver::class.java),
        if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP,
    )
}

/**
 * Starts the foreground service on BOOT_COMPLETED when delivery mode is PERSISTENT_SOCKET and at
 * least one network exists. API 34+ permits FGS start from BOOT_COMPLETED for
 * specialUse; a [ForegroundServiceStartNotAllowedException] is swallowed (app connects on open).
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var db: MotdDatabase

    @Inject lateinit var pushPrefs: PushPrefs

    @Inject lateinit var pushHealthStore: PushHealthStore

    @Inject @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        launchAsync(applicationScope, TAG) {
            val mode = settings.settings.first().deliveryMode
            val networks = db.networkDao().connectable()
            val shouldRun =
                if (mode == DeliveryMode.PERSISTENT_SOCKET) {
                    networks.isNotEmpty()
                } else {
                    socketFallbackNetworkIds(
                        networks,
                        pushPrefs.endpoints(),
                        pushHealthStore.snapshot(),
                    ).isNotEmpty()
                }
            if (shouldRun) startService(context)
        }
    }

    private fun startService(context: Context) {
        val svc = Intent(context, IrcForegroundService::class.java)
        try {
            ContextCompat.startForegroundService(context, svc)
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                // Swallow: the app will connect on next open.
            } else {
                throw e
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
