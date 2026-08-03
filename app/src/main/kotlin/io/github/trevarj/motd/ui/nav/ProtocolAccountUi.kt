package io.github.trevarj.motd.ui.nav

import androidx.annotation.StringRes
import io.github.trevarj.motd.backend.ChatBackend
import io.github.trevarj.motd.backend.ProtocolId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Protocol-owned account UI entry point (docs/backend-neutral-xmpp-rollout.md: "Protocol-aware UI
 * is confined to protocol-owned surfaces such as account setup ... reached through the registry and
 * capabilities"). Each protocol's UI package contributes exactly one binding into the [Set] Hilt
 * multibinds into below (e.g. `ui/settings/addnetwork/IrcProtocolAccountUi.kt`,
 * `ui/settings/xmpp/XmppProtocolAccountUi.kt`); [ProtocolAccountUiRegistry] is the single place that
 * resolves protocol -> route, so shared navigation never branches on a protocol switch.
 */
interface ProtocolAccountUi {
    val protocol: ProtocolId

    /** Picker-row label for this protocol's create flow; owned by the protocol's own UI copy. */
    @get:StringRes
    val labelRes: Int

    /**
     * Route object for this protocol's create-account flow, passed straight to
     * `NavController.navigate(Any)` — resolved by the route's runtime type, so a plain `Any` here
     * loses no type safety at the call site.
     */
    val createRoute: Any

    /** Route object for this protocol's edit-account flow for an existing network row. */
    fun editRoute(networkId: Long): Any
}

/**
 * Resolves a persisted [ProtocolId] to its registered [ProtocolAccountUi]. [entries] is derived
 * from the same [ChatBackend] multibinding [io.github.trevarj.motd.backend.BackendRegistry] uses
 * for its own protocol -> backend lookup, so the add-account picker always lists exactly the
 * registered backends — never a superset or subset the UI side could drift out of sync with — and a
 * backend that forgets to register its UI fails fast at startup instead of silently vanishing from
 * the picker.
 */
@Singleton
class ProtocolAccountUiRegistry @Inject constructor(
    backends: Set<@JvmSuppressWildcards ChatBackend>,
    uis: Set<@JvmSuppressWildcards ProtocolAccountUi>,
) {
    private val uiByProtocol: Map<ProtocolId, ProtocolAccountUi> = uis.associateBy { it.protocol }

    /** One entry per registered backend, in a stable (protocol id) order for the picker. */
    val entries: List<ProtocolAccountUi> = backends
        .map { backend ->
            requireNotNull(uiByProtocol[backend.protocol]) {
                "No ProtocolAccountUi registered for protocol '${backend.protocol.value}'"
            }
        }
        .sortedBy { it.protocol.value }

    fun uiFor(protocol: ProtocolId): ProtocolAccountUi? = uiByProtocol[protocol]
}
