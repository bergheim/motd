package io.github.trevarj.motd.backend

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persisted protocol discriminator for a network/account row
 * (docs/backend-neutral-xmpp-rollout.md). The value set is open: shared code never enumerates
 * protocols and resolves behavior through the one allowed [BackendRegistry] lookup. Values are
 * stable lowercase identifiers; each backend defines its own.
 */
@JvmInline
value class ProtocolId(val value: String)

/**
 * One chat protocol backend. The contract grows session lifecycle, commands, event streams, and
 * capabilities slice by slice; shared code resolves a backend through [BackendRegistry] and must
 * never downcast to a concrete implementation or switch on [protocol].
 */
interface ChatBackend {
    val protocol: ProtocolId
}

/**
 * Resolves a persisted [ProtocolId] to its registered [ChatBackend]. Backends self-register via a
 * Hilt set multibinding from their own adapter package, so this shared type names no protocol.
 */
@Singleton
class BackendRegistry @Inject constructor(
    backends: Set<@JvmSuppressWildcards ChatBackend>,
) {
    private val byProtocol: Map<ProtocolId, ChatBackend> = backends
        .groupBy(ChatBackend::protocol)
        .also { grouped ->
            val duplicates = grouped.filterValues { it.size > 1 }.keys
            require(duplicates.isEmpty()) {
                "Multiple backends registered for ${duplicates.map(ProtocolId::value)}"
            }
        }
        .mapValues { (_, single) -> single.single() }

    /** Null when the protocol has no registered backend (e.g. a database from a newer build). */
    fun backendFor(protocol: ProtocolId): ChatBackend? = byProtocol[protocol]
}
