package io.github.trevarj.motd.xmppbackend

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.github.trevarj.motd.backend.ChatBackend

/** Registers the XMPP adapter with the backend registry; lives with the adapter it binds. */
@Module
@InstallIn(SingletonComponent::class)
abstract class XmppBackendModule {
    @Binds
    @IntoSet
    abstract fun bindXmppChatBackend(impl: XmppChatBackend): ChatBackend
}
