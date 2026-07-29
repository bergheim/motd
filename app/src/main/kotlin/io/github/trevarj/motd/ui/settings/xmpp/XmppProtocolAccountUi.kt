package io.github.trevarj.motd.ui.settings.xmpp

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.github.trevarj.motd.R
import io.github.trevarj.motd.backend.ProtocolId
import io.github.trevarj.motd.ui.nav.ProtocolAccountUi
import io.github.trevarj.motd.ui.nav.XmppAccountRoute
import io.github.trevarj.motd.xmppbackend.XmppChatBackend
import javax.inject.Inject

/**
 * XMPP's registered entry into [io.github.trevarj.motd.ui.nav.ProtocolAccountUiRegistry]
 * (docs/backend-neutral-xmpp-rollout.md "the minimum protocol-aware conversation and account UI"):
 * routes both the add-account picker and existing xmpp-protocol network rows to
 * [XmppAccountScreen] via [XmppAccountRoute].
 */
class XmppProtocolAccountUi @Inject constructor() : ProtocolAccountUi {
    override val protocol: ProtocolId = XmppChatBackend.XMPP_PROTOCOL
    override val labelRes: Int = R.string.protocol_xmpp_label
    override val createRoute: Any = XmppAccountRoute()
    override fun editRoute(networkId: Long): Any = XmppAccountRoute(networkId)
}

/** Registers [XmppProtocolAccountUi]; lives with the UI surface it binds, mirroring [XmppBackendModule]. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class XmppProtocolAccountUiModule {
    @Binds
    @IntoSet
    abstract fun xmppProtocolAccountUi(impl: XmppProtocolAccountUi): ProtocolAccountUi
}
