package io.github.trevarj.motd.xmppbackend

import io.github.trevarj.motd.data.db.XmppAccountEntity
import java.util.concurrent.ConcurrentHashMap
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

    private val _incomingChatStates = MutableSharedFlow<XmppIncomingChatState>(extraBufferCapacity = 64)
    override val incomingChatStates: Flow<XmppIncomingChatState> = _incomingChatStates.asSharedFlow()

    private val _incomingMucMessages = MutableSharedFlow<XmppIncomingMucMessage>(extraBufferCapacity = 64)
    override val incomingMucMessages: Flow<XmppIncomingMucMessage> = _incomingMucMessages.asSharedFlow()

    private val _mucSubjects = MutableSharedFlow<XmppMucSubject>(extraBufferCapacity = 64)
    override val mucSubjects: Flow<XmppMucSubject> = _mucSubjects.asSharedFlow()

    private val _mucOccupants = MutableSharedFlow<XmppMucOccupantEvent>(extraBufferCapacity = 64)
    override val mucOccupants: Flow<XmppMucOccupantEvent> = _mucOccupants.asSharedFlow()

    private val _rosterLoad = MutableSharedFlow<XmppRosterLoad>(extraBufferCapacity = 8)
    override val rosterLoad: Flow<XmppRosterLoad> = _rosterLoad.asSharedFlow()

    /** roomJid -> nick this fake is "known as" in that room, mirroring what a real
     *  [SmackXmppSession] tracks per joined `MultiUserChat` (its `nickname` property) so self-echo
     *  can be derived the same way; see [emitMucMessage]. Absence means "not currently joined", which
     *  every MUC emit helper below treats as "no listener registered" — the same observable contract
     *  [XmppSession.leaveRoom] documents for the real session (stops delivering that room's events). */
    private val joinedRooms = ConcurrentHashMap<String, String>()

    var connectCalls = 0
        private set
    var disconnectCalls = 0
        private set

    val joinRoomCalls = mutableListOf<Pair<String, String>>()
    val leaveRoomCalls = mutableListOf<String>()
    val refreshOccupantsCalls = mutableListOf<String>()

    /** One recorded [XmppSession.sendMessage] call (slice X6): [to] a bare JID or joined room, the
     *  [body], the [messageId] the caller asked to be set as the outgoing stanza id — a test reads
     *  this back and feeds it to [emitMucMessage]'s `stanzaId` to simulate a MUC's delivery echo of
     *  this exact send — and [kind], the caller-decided stanza shape (review fix: this used to be
     *  inferred here from [joinedRooms], which a test double could get away with but a real session
     *  could not once a rejoin lagged the send; see [XmppSession.sendMessage]'s KDoc). */
    data class SentMessage(val to: String, val body: String, val messageId: String, val kind: XmppSendKind)

    val sentMessages = mutableListOf<SentMessage>()
    val sentChatStates = mutableListOf<Pair<String, XmppChatState>>()

    /** One-shot: when set, the next [sendMessage] call throws this instead of recording/succeeding,
     *  then clears itself — simulates a transport failure on an otherwise-live session (e.g. a
     *  write that races a server-initiated close), the counterpart to leaving [connectGate]
     *  unresolved for "no session at all". */
    var sendMessageFailure: Throwable? = null

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

    override suspend fun joinRoom(bareRoomJid: String, nick: String) {
        joinRoomCalls += bareRoomJid to nick
        joinedRooms[bareRoomJid] = nick
    }

    override suspend fun leaveRoom(bareRoomJid: String) {
        leaveRoomCalls += bareRoomJid
        joinedRooms.remove(bareRoomJid)
    }

    override suspend fun refreshOccupants(bareRoomJid: String) {
        refreshOccupantsCalls += bareRoomJid
    }

    override suspend fun sendMessage(to: String, body: String, messageId: String, kind: XmppSendKind) {
        sendMessageFailure?.let { failure ->
            sendMessageFailure = null
            throw failure
        }
        sentMessages += SentMessage(to, body, messageId, kind)
    }

    override suspend fun sendChatState(toBareJid: String, state: XmppChatState) {
        sentChatStates += toBareJid to state
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

    /** Simulate a XEP-0085 chat-state notification arriving from [fromBareJid] (slice X6) — the
     *  incoming-chat-state counterpart to [emit]. Same buffered, no-extra-advance rationale. */
    fun emitChatState(fromBareJid: String, state: XmppChatState, isCarbonOrSelf: Boolean = false) {
        check(
            _incomingChatStates.tryEmit(XmppIncomingChatState(fromBareJid, state, isCarbonOrSelf)),
        ) { "incomingChatStates buffer full in test" }
    }

    /**
     * Simulate a MUC (groupchat) message arriving in [roomJid]. A no-op — nothing is emitted — when
     * [roomJid] is not currently joined (see [joinedRooms]'s KDoc): a real session has no listener
     * left to deliver it either, once [leaveRoom] has run. [isSelf] is derived from the nick this
     * fake was joined with, exactly like [SmackXmppSession] derives it from `MultiUserChat.getNickname()`.
     */
    fun emitMucMessage(
        roomJid: String,
        occupantNick: String,
        body: String,
        stanzaId: String?,
        delayStampMillis: Long? = null,
    ) {
        val ownNick = joinedRooms[roomJid] ?: return
        check(
            _incomingMucMessages.tryEmit(
                XmppIncomingMucMessage(
                    roomBareJid = roomJid,
                    occupantNick = occupantNick,
                    body = body,
                    stanzaId = stanzaId,
                    delayStampMillis = delayStampMillis,
                    isSelf = occupantNick == ownNick,
                ),
            ),
        ) { "incomingMucMessages buffer full in test" }
    }

    /** Simulate a MUC subject change in [roomJid]; a no-op if not currently joined (see
     *  [emitMucMessage]'s KDoc). */
    fun emitMucSubject(roomJid: String, subject: String, byNick: String? = null) {
        if (!joinedRooms.containsKey(roomJid)) return
        check(_mucSubjects.tryEmit(XmppMucSubject(roomJid, subject, byNick))) {
            "mucSubjects buffer full in test"
        }
    }

    /** Simulate the occupant snapshot a real [XmppSession.joinRoom]/[XmppSession.refreshOccupants]
     *  publishes; a no-op if not currently joined (see [emitMucMessage]'s KDoc). */
    fun emitOccupantSnapshot(roomJid: String, nicks: List<String>) {
        if (!joinedRooms.containsKey(roomJid)) return
        check(_mucOccupants.tryEmit(XmppMucOccupantEvent.Snapshot(roomJid, nicks))) {
            "mucOccupants buffer full in test"
        }
    }

    /** Simulate another occupant joining [roomJid] after this session's own join; a no-op if not
     *  currently joined (see [emitMucMessage]'s KDoc). */
    fun emitOccupantJoined(roomJid: String, nick: String) {
        if (!joinedRooms.containsKey(roomJid)) return
        check(_mucOccupants.tryEmit(XmppMucOccupantEvent.Joined(roomJid, nick))) {
            "mucOccupants buffer full in test"
        }
    }

    /** Simulate another occupant leaving [roomJid]; a no-op if not currently joined (see
     *  [emitMucMessage]'s KDoc). */
    fun emitOccupantLeft(roomJid: String, nick: String) {
        if (!joinedRooms.containsKey(roomJid)) return
        check(_mucOccupants.tryEmit(XmppMucOccupantEvent.Left(roomJid, nick))) {
            "mucOccupants buffer full in test"
        }
    }

    /** Simulate this session's one-shot roster-load outcome (see [XmppSession.rosterLoad]'s KDoc). */
    fun emitRosterLoad(load: XmppRosterLoad) {
        check(_rosterLoad.tryEmit(load)) { "rosterLoad buffer full in test" }
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
