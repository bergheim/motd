package io.github.trevarj.motd.ui.onboarding

import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcCommandException
import io.github.trevarj.motd.irc.client.IrcDisconnectedException
import io.github.trevarj.motd.irc.client.BouncerNetwork
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ircbackend.IrcSessions
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Narrow onboarding seam for the bouncer protocol. Keeping it separate from the wizard makes
 * LIST/ADD outcomes controllable in tests without weakening the connection-manager boundary.
 */
interface OnboardingBouncerOperations {
    fun snapshots(rootNetworkId: Long): StateFlow<Map<String, Map<String, String>>>?
    suspend fun list(rootNetworkId: Long): List<BouncerNetwork>
    suspend fun add(rootNetworkId: Long, name: String, host: String): String
}

class ConnectionManagerOnboardingBouncerOperations @Inject constructor(
    private val ircSessions: IrcSessions,
) : OnboardingBouncerOperations {
    override fun snapshots(rootNetworkId: Long) = ircSessions.sessionFor(rootNetworkId)?.bouncerNetworks

    override suspend fun list(rootNetworkId: Long): List<BouncerNetwork> =
        client(rootNetworkId).bouncerListNetworks()

    override suspend fun add(rootNetworkId: Long, name: String, host: String): String =
        client(rootNetworkId).bouncerAddNetwork(mapOf("name" to name, "host" to host))

    private fun client(rootNetworkId: Long): IrcClient =
        ircSessions.sessionFor(rootNetworkId)
            ?.takeIf { it.state.value is IrcClientState.Ready }
            ?: throw IrcDisconnectedException("BOUNCER", "connection is no longer ready")
}

/** Retryable operation error safe to render in onboarding. */
sealed interface BouncerOperationError {
    data object ConnectionLost : BouncerOperationError
    data class ServerRejected(val detail: String) : BouncerOperationError
    data class Unexpected(val detail: String) : BouncerOperationError
}

internal fun bouncerOperationError(error: Throwable): BouncerOperationError = when (error) {
    is IrcDisconnectedException -> BouncerOperationError.ConnectionLost
    is IrcCommandException -> BouncerOperationError.ServerRejected(
        error.text.replace(Regex("[\\r\\n]+"), " ").trim(),
    )
    else -> BouncerOperationError.Unexpected(
        error.message.orEmpty().replace(Regex("[\\r\\n]+"), " ").trim(),
    )
}
