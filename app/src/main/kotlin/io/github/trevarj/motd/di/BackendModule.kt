package io.github.trevarj.motd.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.trevarj.motd.service.CompositeConnectionManager
import io.github.trevarj.motd.service.ConnectionManager
import javax.inject.Singleton

/**
 * Shared seam wiring: [ConnectionManager] resolves to the registry-dispatching composite
 * (docs/backend-neutral-xmpp-rollout.md). Backends contribute their session managers through
 * their own adapter modules.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class BackendModule {
    @Binds @Singleton
    abstract fun connectionManager(impl: CompositeConnectionManager): ConnectionManager
}
