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

    /**
     * Session established. [selfHandle] is the network-assigned own identity (the IRC nick).
     * [generation] identifies this session instance: it changes whenever a new session is
     * established, so callers can detect reconnects without holding protocol session objects
     * (docs/backend-neutral-xmpp-rollout.md connection-generation boundary).
     * [negotiationRevision] is an opaque value that changes whenever the session's negotiated
     * feature set changes after Ready (late IRC CAP/ISUPPORT updates); observers of deduplicated
     * seam flows re-pull capability contracts when it moves.
     */
    data class Ready(
        val selfHandle: String,
        val generation: Long = 0,
        val negotiationRevision: Int = 0,
    ) : ConnectionState

    /** [fatal] = do not auto-retry (e.g. failed authentication). */
    data class Failed(val reason: String, val fatal: Boolean) : ConnectionState
}

/** Composer reaction sendability for one network, derived by its backend. */
data class ReactionCapability(val canAdd: Boolean, val canRemoveOwn: Boolean)
