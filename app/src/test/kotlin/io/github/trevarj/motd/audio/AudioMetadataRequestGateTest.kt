package io.github.trevarj.motd.audio

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudioMetadataRequestGateTest {
    @Test
    fun missingNetworkIdentityOrRouteFailsClosed() =
        runTest {
            var routeLookups = 0
            val repository =
                AudioMetadataRepositoryImpl(
                    routeProvider =
                        MediaRouteResolver {
                            routeLookups++
                            null
                        },
                    applicationScope = backgroundScope,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            assertNull(repository.metadata("https://example.test/audio", null))
            assertEquals(0, routeLookups)
            assertNull(repository.metadata("https://example.test/other-audio", 7))
            assertEquals(1, routeLookups)
        }
}
