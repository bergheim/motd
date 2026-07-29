package io.github.trevarj.motd.xmppbackend

import io.github.trevarj.motd.data.db.XmppAccountEntity
import kotlinx.coroutines.flow.StateFlow

/**
 * Session-local connection lifecycle for one [XmppSession] attempt (docs/backend-neutral-xmpp-rollout.md
 * "PR 2"). Deliberately local to `xmppbackend` rather than reusing an `:irc` type — [XmppAccountActor]
 * and [XmppConnectionManager] map this onto [io.github.trevarj.motd.backend.ConnectionState] without
 * ever seeing a wire/session library type. Shaped after that neutral contract's phases, but without
 * [io.github.trevarj.motd.backend.ConnectionState.Ready]'s `generation`/`negotiationRevision`: those
 * are manager-assigned session identity, not something a bare session knows about itself.
 */
sealed interface XmppSessionState {
    data object Disconnected : XmppSessionState

    /** Transport/TLS being established. */
    data object Connecting : XmppSessionState

    /** Transport up; SASL negotiation in progress. */
    data object Authenticating : XmppSessionState

    /** Authenticated. [bareJid] is the server-confirmed own address (user@domain). */
    data class Ready(val bareJid: String) : XmppSessionState

    /** [fatal] = do not auto-retry (e.g. rejected credentials). */
    data class Failed(val reason: String, val fatal: Boolean) : XmppSessionState
}

/**
 * Protocol seam over one XMPP connection attempt. Models the fork/xmpp-support prototype's
 * `XmppSession` abstraction (connect/login, per-room/message operations, teardown) but narrowed to
 * this slice's scope: transport/TLS/SASL lifecycle only. Roster, chat, and MUC operations arrive
 * with later slices (X4/X6) as additions to this same seam.
 *
 * One instance = one connection attempt; [XmppAccountActor] creates a fresh [XmppSession] per
 * (re)connect, exactly like the prototype and like `:irc`'s `ManagedConnection`/`IrcClient` pairing.
 */
interface XmppSession {
    /** Current lifecycle phase; a fresh session starts at [XmppSessionState.Disconnected]. */
    val state: StateFlow<XmppSessionState>

    /**
     * Establish the transport, negotiate TLS, and SASL-authenticate, publishing [state] transitions
     * (Connecting -> Authenticating -> Ready/Failed) along the way. Never throws except
     * [kotlinx.coroutines.CancellationException]: every transport/TLS/SASL failure is translated to
     * [XmppSessionState.Failed] on [state] so callers branch only on the sealed state, never on a
     * session-library exception type.
     *
     * Returns once the attempt reaches a stable outcome (Ready or Failed). A later drop out of Ready
     * (server-initiated close, socket error) is published to [state] asynchronously, without a
     * further [connect] call — callers observe it by continuing to watch [state].
     */
    suspend fun connect()

    /** Tear down the connection and release resources. Safe to call from any state, more than once. */
    suspend fun disconnect()
}

/** Builds one [XmppSession] attempt from a persisted XMPP account row. */
fun interface XmppSessionFactory {
    fun create(account: XmppAccountEntity): XmppSession
}
