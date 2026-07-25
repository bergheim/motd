package io.github.trevarj.motd.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.trevarj.motd.xmpp.SmackXmppSessionFactory
import io.github.trevarj.motd.xmpp.XmppSessionFactory
import javax.inject.Singleton

/**
 * Binds the production [XmppSessionFactory]. Kept as its own module so tests can swap in a fake
 * factory without touching shared DI, and so this task does not edit modules owned by Task 7.
 */
@Module
@InstallIn(SingletonComponent::class)
object XmppModule {
    @Provides
    @Singleton
    fun xmppSessionFactory(): XmppSessionFactory = SmackXmppSessionFactory
}
