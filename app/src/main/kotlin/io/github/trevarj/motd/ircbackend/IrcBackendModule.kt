package io.github.trevarj.motd.ircbackend

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.github.trevarj.motd.backend.ChatBackend

/** Registers the IRC adapter with the backend registry; lives with the adapter it binds. */
@Module
@InstallIn(SingletonComponent::class)
abstract class IrcBackendModule {
    @Binds
    @IntoSet
    abstract fun bindIrcChatBackend(impl: IrcChatBackend): ChatBackend
}
