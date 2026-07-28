package io.github.trevarj.motd.backend

/**
 * Neutral per-network connection lifecycle exposed by the ConnectionManager seam
 * (docs/backend-neutral-xmpp-rollout.md). Backends map their protocol states onto these phases;
 * shared code never sees wire-level connection types.
 */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState

    /** Transport being established: socket, TLS, proxy. */
    data object Connecting : ConnectionState

    /** Transport up; protocol negotiation and authentication in progress (IRC registration, SASL). */
    data object Authenticating : ConnectionState

    /** Session established. [selfHandle] is the network-assigned own identity (the IRC nick). */
    data class Ready(val selfHandle: String) : ConnectionState

    /** [fatal] = do not auto-retry (e.g. failed authentication). */
    data class Failed(val reason: String, val fatal: Boolean) : ConnectionState
}

/** Composer reaction sendability for one network, derived by its backend. */
data class ReactionCapability(val canAdd: Boolean, val canRemoveOwn: Boolean)
