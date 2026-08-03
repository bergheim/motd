package io.github.trevarj.motd.ui.settings.addnetwork

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.github.trevarj.motd.R
import io.github.trevarj.motd.backend.ProtocolId
import io.github.trevarj.motd.ircbackend.IrcChatBackend
import io.github.trevarj.motd.ui.nav.AddNetworkRoute
import io.github.trevarj.motd.ui.nav.NetworkSettingsRoute
import io.github.trevarj.motd.ui.nav.ProtocolAccountUi
import javax.inject.Inject

/**
 * IRC's registered entry into [io.github.trevarj.motd.ui.nav.ProtocolAccountUiRegistry]
 * (docs/backend-neutral-xmpp-rollout.md): points at IRC's existing
 * [AddNetworkRoute]/[NetworkSettingsRoute] flows, unchanged — the add-account picker and the
 * existing-row routing both now reach them through the registry instead of a hardcoded default.
 */
class IrcProtocolAccountUi @Inject constructor() : ProtocolAccountUi {
    override val protocol: ProtocolId = IrcChatBackend.IRC_PROTOCOL
    override val labelRes: Int = R.string.add_network_kind_network
    override val createRoute: Any = AddNetworkRoute
    override fun editRoute(networkId: Long): Any = NetworkSettingsRoute(networkId)
}

/** Registers [IrcProtocolAccountUi]; lives with the UI surface it binds, mirroring [IrcBackendModule]. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class IrcProtocolAccountUiModule {
    @Binds
    @IntoSet
    abstract fun ircProtocolAccountUi(impl: IrcProtocolAccountUi): ProtocolAccountUi
}
