package io.github.trevarj.motd.service

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BootReceiverSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val component = ComponentName(context, BootReceiver::class.java)

    @After
    fun resetComponent() {
        context.packageManager.setComponentEnabledSetting(
            component,
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP,
        )
    }

    @Test
    fun bootReceiverDefaultsOnAndCanBeDisabledAndReenabled() {
        resetComponent()
        assertTrue(isBootReceiverEnabled(context))

        setBootReceiverEnabled(context, false)
        assertFalse(isBootReceiverEnabled(context))

        setBootReceiverEnabled(context, true)
        assertTrue(isBootReceiverEnabled(context))
    }
}
