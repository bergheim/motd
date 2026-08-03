package io.github.trevarj.motd.ui.nav

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.backend.ProtocolId
import io.github.trevarj.motd.data.repo.NetworkRepository
import javax.inject.Inject

/**
 * Registry-driven navigation dispatch for account creation/editing
 * (docs/backend-neutral-xmpp-rollout.md). [MotdNavGraph] resolves through this instead of
 * hardcoding [AddNetworkRoute]/[NetworkSettingsRoute]:
 *  - [createDestination] is the add-account entry point's target: the lone registered backend's
 *    create route directly, or [AccountPickerRoute] when more than one backend is registered.
 *  - [editRouteFor] is where "open network settings" resolves an existing row's persisted protocol
 *    to its owning edit route.
 *
 * Neither branches on a protocol switch — both are single lookups through
 * [ProtocolAccountUiRegistry], which is itself built from the registered [ChatBackend][io.github.trevarj.motd.backend.ChatBackend]
 * set.
 */
@HiltViewModel
class AccountRoutingViewModel @Inject constructor(
    private val registry: ProtocolAccountUiRegistry,
    private val networkRepository: NetworkRepository,
) : ViewModel() {

    /** Registered protocols' create entries, for [io.github.trevarj.motd.ui.settings.addnetwork.ProtocolPickerScreen]. */
    val createChoices: List<ProtocolAccountUi> get() = registry.entries

    /**
     * The lone registered backend's create route directly (today's single-IRC-backend behavior
     * when only one backend is registered), or [AccountPickerRoute] when more than one is
     * registered. Zero registered backends is a defensive fallback for a broken build/test config;
     * real builds always register at least one.
     */
    fun createDestination(): Any = registry.entries.singleOrNull()?.createRoute ?: AccountPickerRoute

    /**
     * [networkId]'s persisted protocol resolved to its owning edit route. Falls back to the IRC
     * edit screen for a missing row or a protocol with no registered UI (e.g. a row written by a
     * newer build) so opening network settings never dead-ends — mirroring how
     * [io.github.trevarj.motd.backend.BackendRegistry.backendFor] itself degrades to null rather
     * than failing for an unrecognized protocol.
     */
    suspend fun editRouteFor(networkId: Long): Any {
        val protocol = networkRepository.networkById(networkId)?.protocol?.let(::ProtocolId)
        return protocol?.let(registry::uiFor)?.editRoute(networkId) ?: NetworkSettingsRoute(networkId)
    }
}
