package io.github.trevarj.motd.xmppbackend

import io.github.trevarj.motd.data.db.XmppAccountEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Test double for [XmppSession] (mirrors the fork/xmpp-support prototype's `FakeXmppSession`,
 * reshaped for the new state-flow-based seam). [connect] suspends on [completeConnect] so tests can
 * observe the intermediate [XmppSessionState.Connecting] phase before deciding the outcome, exactly
 * like driving a real handshake under a virtual-time test dispatcher.
 */
internal class FakeXmppSession : XmppSession {
    private val _state = MutableStateFlow<XmppSessionState>(XmppSessionState.Disconnected)
    override val state: StateFlow<XmppSessionState> = _state.asStateFlow()

    // Buffered like the real SmackXmppSession (see its incomingMessages KDoc): emit() is a plain,
    // non-suspending call so tests can fire it without racing the actor's collector subscription.
    private val _incomingMessages = MutableSharedFlow<XmppIncomingMessage>(extraBufferCapacity = 64)
    override val incomingMessages: Flow<XmppIncomingMessage> = _incomingMessages.asSharedFlow()

    var connectCalls = 0
        private set
    var disconnectCalls = 0
        private set

    private var connectGate = CompletableDeferred<XmppSessionState>()

    override suspend fun connect() {
        connectCalls++
        _state.value = XmppSessionState.Connecting
        _state.value = connectGate.await()
    }

    override suspend fun disconnect() {
        disconnectCalls++
        _state.value = XmppSessionState.Disconnected
    }

    /** Resolve the in-flight (or next) [connect] call with [outcome] (typically Ready or Failed). */
    fun completeConnect(outcome: XmppSessionState) {
        connectGate.complete(outcome)
    }

    /** Publish a state change without going through [connect] — simulates an async transport event
     *  arriving after Ready (server close, socket error). */
    fun publish(state: XmppSessionState) {
        _state.value = state
    }

    /** Simulate a stanza arriving on this live session — the incoming-message counterpart to
     *  [publish]. Buffered, so it is safe to call as soon as a test has driven this session to
     *  Ready, without an extra `advanceUntilIdle()` first to make sure the collector is attached. */
    fun emit(message: XmppIncomingMessage) {
        check(_incomingMessages.tryEmit(message)) { "incomingMessages buffer full in test" }
    }
}

/** Hands out a pre-queued sequence of [FakeXmppSession]s and records every creation. */
internal class FakeXmppSessionFactory(sessions: List<FakeXmppSession> = emptyList()) : XmppSessionFactory {
    private val queued = ArrayDeque(sessions)
    val created = mutableListOf<FakeXmppSession>()
    val accountsUsed = mutableListOf<XmppAccountEntity>()

    override fun create(account: XmppAccountEntity): XmppSession {
        val session = queued.removeFirstOrNull() ?: FakeXmppSession()
        created += session
        accountsUsed += account
        return session
    }
}
