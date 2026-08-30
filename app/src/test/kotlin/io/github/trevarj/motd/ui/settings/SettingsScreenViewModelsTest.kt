package io.github.trevarj.motd.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.attachment.AttachmentPrefsImpl
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.prefs.AppearancePrefsImpl
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.repo.NetworkRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsScreenViewModelsTest {
    @Test
    fun `home view model keeps search query while reflecting cheap root values`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val db = inMemoryDb()
            try {
                db.networkDao().insert(
                    NetworkEntity(
                        name = "Libera",
                        role = NetworkRole.DIRECT,
                        host = "irc.libera.chat",
                        port = 6697,
                        nick = "me",
                        username = "me",
                        realname = "Me",
                    ),
                )
                val context = ApplicationProvider.getApplicationContext<Context>()
                val vm =
                    SettingsHomeViewModel(
                        DataStoreSettingsRepository(context),
                        NetworkRepositoryImpl(db.networkDao()),
                        AppearancePrefsImpl(context),
                        AttachmentPrefsImpl(context),
                    )
                vm.setQuery("presence")
                val state = vm.state.first { it.query == "presence" && it.networks.isNotEmpty() }

                assertEquals("presence", state.query)
                assertEquals(listOf("Libera"), state.networks.map { it.name })
            } finally {
                db.close()
                Dispatchers.resetMain()
            }
        }
}
