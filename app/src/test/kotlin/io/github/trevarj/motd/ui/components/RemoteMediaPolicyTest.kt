package io.github.trevarj.motd.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil.request.CachePolicy
import coil.request.ImageRequest
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteMediaPolicyTest {
    @Test
    fun automatic_policy_matrix_fails_closed_when_unavailable() {
        val both = ContentPreviewConfig()
        val neither = ContentPreviewConfig(autoLoadOnUnmetered = false, autoLoadOnMetered = false)
        val unmeteredOnly = ContentPreviewConfig(autoLoadOnMetered = false)
        val meteredOnly = ContentPreviewConfig(autoLoadOnUnmetered = false)

        assertTrue(automaticRemoteMediaAllowed(RemoteMediaNetwork.UNMETERED, both))
        assertTrue(automaticRemoteMediaAllowed(RemoteMediaNetwork.METERED, both))
        assertFalse(automaticRemoteMediaAllowed(RemoteMediaNetwork.UNAVAILABLE, both))
        assertFalse(automaticRemoteMediaAllowed(RemoteMediaNetwork.UNMETERED, neither))
        assertFalse(automaticRemoteMediaAllowed(RemoteMediaNetwork.METERED, neither))
        assertTrue(automaticRemoteMediaAllowed(RemoteMediaNetwork.UNMETERED, unmeteredOnly))
        assertFalse(automaticRemoteMediaAllowed(RemoteMediaNetwork.METERED, unmeteredOnly))
        assertFalse(automaticRemoteMediaAllowed(RemoteMediaNetwork.UNMETERED, meteredOnly))
        assertTrue(automaticRemoteMediaAllowed(RemoteMediaNetwork.METERED, meteredOnly))
    }

    @Test
    fun classification_requires_validated_internet_and_then_uses_metering() {
        assertEquals(
            RemoteMediaNetwork.UNAVAILABLE,
            classifyRemoteMediaNetwork(validatedInternet = false, unmetered = true),
        )
        assertEquals(
            RemoteMediaNetwork.METERED,
            classifyRemoteMediaNetwork(validatedInternet = true, unmetered = false),
        )
        assertEquals(
            RemoteMediaNetwork.UNMETERED,
            classifyRemoteMediaNetwork(validatedInternet = true, unmetered = true),
        )
    }

    @Test
    fun remote_image_request_disables_only_network_cache_policy() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val cacheOnly = ImageRequest.Builder(context).remoteMediaData("https://example.test/a.png", false).build()
        val automatic = ImageRequest.Builder(context).remoteMediaData("https://example.test/a.png", true).build()

        assertEquals(CachePolicy.DISABLED, cacheOnly.networkCachePolicy)
        assertEquals(CachePolicy.ENABLED, cacheOnly.memoryCachePolicy)
        assertEquals(CachePolicy.ENABLED, cacheOnly.diskCachePolicy)
        assertEquals(CachePolicy.ENABLED, automatic.networkCachePolicy)
    }
}
