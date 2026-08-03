package io.github.trevarj.motd.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.trevarj.motd.irc.transport.OkioLineTransport
import io.github.trevarj.motd.irc.transport.TransportFactory
import io.github.trevarj.motd.ircbackend.IrcSessions
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.ConnectionManagerImpl
import javax.inject.Singleton

/**
 * IRC adapter wiring. The shared [ConnectionManager] binding lives in [BackendModule] (the
 * registry-dispatching composite); the IRC session manager reaches it via [IrcChatBackend]. The
 * [IrcEventSink] binding lives in [AppModule] (EventProcessor).
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class IrcModule {
    /** IRC-owned session accessor for IRC feature surfaces (docs/backend-neutral-xmpp-rollout.md). */
    @Binds @Singleton
    abstract fun ircSessions(impl: ConnectionManagerImpl): IrcSessions

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
    }
}
