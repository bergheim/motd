package io.github.trevarj.motd.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.transport.OkioLineTransport
import io.github.trevarj.motd.irc.transport.TransportFactory
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.ConnectionManagerImpl
import io.github.trevarj.motd.service.IrcConnectionManager
import io.github.trevarj.motd.service.RoutingConnectionManager
import io.github.trevarj.motd.service.SendAcceptance
import io.github.trevarj.motd.service.XmppConnectionSurface
import io.github.trevarj.motd.xmpp.MucRoomListing
import io.github.trevarj.motd.xmpp.XmppConnectionManager
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow

/**
 * IRC/service seam wiring (xmpp-support Task 7). [ConnectionManager] → [RoutingConnectionManager],
 * which routes each call by the target network's protocol to either the real IRC
 * [ConnectionManagerImpl] (bound separately behind [IrcConnectionManager] so the router's own
 * dependency on it cannot form a self-cycle with the unqualified [ConnectionManager] binding it
 * provides) or `XmppConnectionManager` (Task 6, adapted to [XmppConnectionSurface] below). The
 * [IrcEventSink] binding lives in [AppModule] (EventProcessor).
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class IrcModule {
    @Binds @Singleton
    abstract fun connectionManager(impl: RoutingConnectionManager): ConnectionManager

    @Binds @Singleton @IrcConnectionManager
    abstract fun ircConnectionManager(impl: ConnectionManagerImpl): ConnectionManager

    companion object {
        /**
         * Base JVM transport factory (plain okio-over-Socket/SSLSocket). ConnectionManagerImpl
         * builds a per-network TLS/client-cert-aware AppTransportFactory itself; this binding
         * satisfies its injected fallback factory.
         */
        @Provides
        @Singleton
        fun transportFactory(): TransportFactory =
            // wsUrl/proxy are ignored by the pure-JVM fallback; the app builds a WSS- and
            // proxy-aware AppTransportFactory per network (plans/19 §3.3/§3.4, plans/20 Phase 1).
            TransportFactory { host, port, tls, _, _ -> OkioLineTransport(host, port, tls) }

        /**
         * Adapts the concrete `XmppConnectionManager` (Task 6) to the narrower
         * [XmppConnectionSurface] the router depends on, so `XmppConnectionManager` itself never
         * needs to know about the routing seam.
         */
        @Provides
        @Singleton
        fun xmppConnectionSurface(impl: XmppConnectionManager): XmppConnectionSurface =
            object : XmppConnectionSurface {
                override val connectionStates: StateFlow<Map<Long, IrcClientState>>
                    get() = impl.connectionStates
                override suspend fun startAll() = impl.startAll()
                override suspend fun stopAll() = impl.stopAll()
                override suspend fun connect(networkId: Long) = impl.connect(networkId)
                override suspend fun disconnect(networkId: Long) = impl.disconnect(networkId)
                override suspend fun reconnectStale() = impl.reconnectStale()
                override suspend fun sendMessage(bufferId: Long, text: String): SendAcceptance =
                    impl.sendMessage(bufferId, text)
                override suspend fun sendTyping(bufferId: Long, state: String) =
                    impl.sendTyping(bufferId, state)
                override suspend fun joinChannel(networkId: Long, roomJid: String) =
                    impl.joinChannel(networkId, roomJid)
                override suspend fun listRooms(networkId: Long): List<MucRoomListing> =
                    impl.listRooms(networkId)
                override suspend fun partChannel(bufferId: Long, reason: String?) =
                    impl.partChannel(bufferId, reason)
                override suspend fun ensureQueryBuffer(networkId: Long, bareJid: String): Long =
                    impl.ensureQueryBuffer(networkId, bareJid)
                override suspend fun ensureServerBuffer(networkId: Long): Long =
                    impl.ensureServerBuffer(networkId)
            }
    }
}
