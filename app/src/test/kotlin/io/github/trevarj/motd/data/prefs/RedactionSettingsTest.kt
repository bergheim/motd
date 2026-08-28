package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RedactionSettingsTest {
    private val repository: SettingsRepository =
        DataStoreSettingsRepository(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun redaction_tombstones_defaultToVisible_andRoundTrip() =
        runTest {
            assertTrue(Settings().showRedactedMessages)

            repository.setShowRedactedMessages(false)
            assertFalse(repository.settings.first().showRedactedMessages)

            repository.setShowRedactedMessages(true)
            assertTrue(repository.settings.first().showRedactedMessages)
        }
}
