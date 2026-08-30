package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FolderDisplayModeSettingsTest {
    private val repository: SettingsRepository =
        DataStoreSettingsRepository(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun freshAndOldSettingsDefaultInline() =
        runTest {
            repository.setFolderDisplayMode(FolderDisplayMode.INLINE)

            assertEquals(FolderDisplayMode.INLINE, Settings().folderDisplayMode)
            assertEquals(FolderDisplayMode.INLINE, repository.settings.first().folderDisplayMode)
            assertEquals(FolderDisplayMode.INLINE, Json.decodeFromString<Settings>("{}").folderDisplayMode)
        }

    @Test
    fun tabsPreferenceRoundTrips() =
        runTest {
            try {
                repository.setFolderDisplayMode(FolderDisplayMode.TABS)
                assertEquals(FolderDisplayMode.TABS, repository.settings.first().folderDisplayMode)
            } finally {
                repository.setFolderDisplayMode(FolderDisplayMode.INLINE)
            }
        }

    @Test
    fun invalidPreferenceDefaultsInline() {
        assertEquals(FolderDisplayMode.INLINE, folderDisplayModeFromPreference("GRID"))
        assertEquals(FolderDisplayMode.INLINE, folderDisplayModeFromPreference(null))
    }
}
