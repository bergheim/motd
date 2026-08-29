package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.audio.MediaRouteResolver
import io.github.trevarj.motd.audio.NetworkMediaRoute
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class LinkPreviewRequestGateTest {
    private lateinit var server: MockWebServer
    private lateinit var prefs: FakeContentPreviewPrefs

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        prefs = FakeContentPreviewPrefs()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // MockWebServer serves cleartext on loopback, which production destination policy forbids;
    // these tests cover the request pipeline, so the policy alone is relaxed.
    private fun repository(
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
    ) = LinkPreviewRepositoryImpl(
        prefs,
        directResolver,
        LinkPreviewFetchPolicy(enforceDestinationPolicy = false),
        scope,
        dispatcher,
    )

    @Test
    fun disabled_gate_skips_network_and_cached_metadata() =
        runTest {
            val repository = repository(this, StandardTestDispatcher(testScheduler))
            val url = server.url("/article").toString()
            prefs.setShowLinkPreviews(false)

            assertNull(repository.preview(url, NETWORK_ID))
            assertEquals(0, server.requestCount)

            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/html")
                    .setBody("<meta property=\"og:title\" content=\"Example\">"),
            )
            prefs.setShowLinkPreviews(true)
            assertNotNull(repository.preview(url, NETWORK_ID))
            assertNotNull(repository.cachedPreview(url, NETWORK_ID)?.preview)
            assertEquals(1, server.requestCount)
            assertEquals(
                "motd-Android (https://github.com/trevarj/motd)",
                server.takeRequest().getHeader("User-Agent"),
            )

            prefs.setShowLinkPreviews(false)
            assertNull(repository.preview(url, NETWORK_ID))
            assertEquals(1, server.requestCount)
        }

    @Test
    fun completed_file_result_is_distinct_from_a_cache_miss() =
        runTest {
            val repository = repository(this, StandardTestDispatcher(testScheduler))
            val url = server.url("/binary").toString()
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/octet-stream")
                    .setBody("not html"),
            )

            assertNull(repository.cachedPreview(url, NETWORK_ID))
            assertEquals(LinkPreviewKind.FILE, repository.preview(url, NETWORK_ID)?.kind)
            assertNotNull(repository.cachedPreview(url, NETWORK_ID))
            assertEquals(LinkPreviewKind.FILE, repository.cachedPreview(url, NETWORK_ID)?.preview?.kind)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun cancellation_interrupts_an_active_http_request() =
        runBlocking {
            val repository = repository(this, Dispatchers.IO)
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/html")
                    .setBody("<title>Slow</title>xx")
                    .throttleBody(1, 200, TimeUnit.MILLISECONDS),
            )

            val request = launch { repository.preview(server.url("/slow").toString(), NETWORK_ID) }
            withTimeout(2_000) {
                while (server.requestCount == 0) delay(10)
            }

            withTimeout(2_000) {
                request.cancel()
                request.join()
            }
        }

    @Test
    fun text_preview_honors_declared_charset_and_16kib_cap() =
        runTest {
            val repository = repository(this, StandardTestDispatcher(testScheduler))
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/plain; charset=ISO-8859-1")
                    .setBody(Buffer().write("caf\u00e9".toByteArray(Charsets.ISO_8859_1))),
            )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/plain")
                    .setBody("a".repeat(16 * 1024) + "ignored"),
            )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/plain")
                    .setBody("\u0000".repeat(16 * 1024) + "must-not-be-read"),
            )

            assertEquals("caf\u00e9", repository.preview(server.url("/latin1").toString(), NETWORK_ID)?.description)
            assertEquals(2_048, repository.preview(server.url("/large").toString(), NETWORK_ID)?.description?.length)
            assertNull(repository.preview(server.url("/beyond-cap").toString(), NETWORK_ID))
        }

    // A resolver whose routes are direct (no proxy, no proxy error) for any requested network.
    private val directResolver =
        MediaRouteResolver { networkId ->
            NetworkMediaRoute(
                networkId = networkId,
                endpoint = testNetworkEntity(networkId),
                proxy = null,
                proxyError = null,
                authorizationHeader = null,
            )
        }

    private companion object {
        const val NETWORK_ID = 7L

        fun testNetworkEntity(networkId: Long) =
            NetworkEntity(
                id = networkId,
                name = "test",
                role = NetworkRole.DIRECT,
                host = "irc.example.test",
                port = 6697,
                nick = "nick",
                username = "user",
                realname = "real",
            )
    }

    private class FakeContentPreviewPrefs : ContentPreviewPrefs {
        private val state = MutableStateFlow(ContentPreviewConfig())
        override val config: Flow<ContentPreviewConfig> = state

        override suspend fun setShowImages(show: Boolean) {
            state.value = state.value.copy(showImages = show)
        }

        override suspend fun setShowLinkPreviews(show: Boolean) {
            state.value = state.value.copy(showLinkPreviews = show)
        }

        override suspend fun setAutoLoadOnUnmetered(enabled: Boolean) = Unit

        override suspend fun setAutoLoadOnMetered(enabled: Boolean) = Unit

        override suspend fun setDirectMediaOnProxiedNetworks(enabled: Boolean) {
            state.value = state.value.copy(directMediaOnProxiedNetworks = enabled)
        }
    }
}
