package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.Protocol
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.push.NetworkPushHealth
import io.github.trevarj.motd.push.PushRegistrationState
import io.github.trevarj.motd.push.fingerprintEndpoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the pure [shouldStartOnBoot] predicate extracted from [BootReceiver.onReceive]. */
class BootReceiverTest {

    private fun ircRow(id: Long, autoConnect: Boolean = true) =
        network("irc-$id").copy(id = id, autoConnect = autoConnect)

    private fun xmppRow(id: Long, autoConnect: Boolean = true) =
        network("xmpp-$id").copy(id = id, protocol = Protocol.XMPP, autoConnect = autoConnect)

    @Test
    fun persistentSocket_withAnyNetwork_starts() {
        val result = shouldStartOnBoot(
            mode = DeliveryMode.PERSISTENT_SOCKET,
            networks = listOf(ircRow(1)),
            endpoints = emptyMap(),
            health = emptyMap(),
        )
        assertTrue(result)
    }

    @Test
    fun persistentSocket_noNetworks_doesNotStart() {
        val result = shouldStartOnBoot(
            mode = DeliveryMode.PERSISTENT_SOCKET,
            networks = emptyList(),
            endpoints = emptyMap(),
            health = emptyMap(),
        )
        assertFalse(result)
    }

    @Test
    fun unifiedPush_ircNetworkAlreadyProtected_noXmpp_doesNotStart() {
        val endpoint = "https://push.example/endpoint"
        val health = NetworkPushHealth(
            registrationState = PushRegistrationState.ACTIVE,
            endpointFingerprint = fingerprintEndpoint(endpoint),
        )
        val result = shouldStartOnBoot(
            mode = DeliveryMode.UNIFIED_PUSH,
            networks = listOf(ircRow(1)),
            endpoints = mapOf(1L to endpoint),
            health = mapOf(1L to health),
        )
        assertFalse(result)
    }

    @Test
    fun unifiedPush_ircNetworkNeedsFallback_starts() {
        val result = shouldStartOnBoot(
            mode = DeliveryMode.UNIFIED_PUSH,
            networks = listOf(ircRow(1)),
            endpoints = emptyMap(),
            health = emptyMap(),
        )
        assertTrue(result)
    }

    @Test
    fun unifiedPush_bouncerRootIrcRowExcludedFromFallback_noXmpp_doesNotStart() {
        val row = ircRow(1).copy(role = NetworkRole.BOUNCER_ROOT)
        val result = shouldStartOnBoot(
            mode = DeliveryMode.UNIFIED_PUSH,
            networks = listOf(row),
            endpoints = emptyMap(),
            health = emptyMap(),
        )
        assertFalse(result)
    }

    @Test
    fun unifiedPush_xmppNetworkPresent_alwaysStarts() {
        // Even in a hypothetical world where push-fallback bookkeeping considered the XMPP row
        // already "protected", XMPP has no push-mode fallback of its own (xmpp-support): the
        // persistent socket must still be started whenever an autoConnect XMPP row exists.
        val endpoint = "https://push.example/endpoint"
        val health = NetworkPushHealth(
            registrationState = PushRegistrationState.ACTIVE,
            endpointFingerprint = fingerprintEndpoint(endpoint),
        )
        val result = shouldStartOnBoot(
            mode = DeliveryMode.UNIFIED_PUSH,
            networks = listOf(xmppRow(1)),
            endpoints = mapOf(1L to endpoint),
            health = mapOf(1L to health),
        )
        assertTrue(result)
    }

    @Test
    fun persistentSocket_onlyXmppNetwork_starts() {
        val result = shouldStartOnBoot(
            mode = DeliveryMode.PERSISTENT_SOCKET,
            networks = listOf(xmppRow(1)),
            endpoints = emptyMap(),
            health = emptyMap(),
        )
        assertTrue(result)
    }
}
