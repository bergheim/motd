package io.github.trevarj.motd.xmppbackend

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.data.db.XmppAccountEntity
import java.security.cert.CertificateParsingException
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.jivesoftware.smack.ConnectionConfiguration
import org.jivesoftware.smack.ConnectionListener
import org.jivesoftware.smack.MessageListener
import org.jivesoftware.smack.ReconnectionManager
import org.jivesoftware.smack.android.AndroidSmackInitializer
import org.jivesoftware.smack.chat2.ChatManager
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.MessageBuilder
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.roster.Roster
import org.jivesoftware.smack.sasl.SASLErrorException
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smackx.chatstates.ChatState
import org.jivesoftware.smackx.chatstates.packet.ChatStateExtension
import org.jivesoftware.smackx.delay.DelayInformationManager
import org.jivesoftware.smackx.muc.MultiUserChat
import org.jivesoftware.smackx.muc.MultiUserChatManager
import org.jivesoftware.smackx.muc.ParticipantStatusListener
import org.jivesoftware.smackx.muc.SubjectUpdatedListener
import org.jivesoftware.smackx.muc.UserStatusListener
import org.jxmpp.jid.EntityFullJid
import org.jxmpp.jid.Jid
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Resourcepart

/** Verbatim credential-failure message, shown by the account UI (carried over from fork/xmpp-support). */
internal const val XMPP_AUTH_FAILURE_REASON = "Wrong address or password"

/**
 * Smack-backed [XmppSession] (docs/backend-neutral-xmpp-rollout.md "PR 2"). Carries over the
 * fork/xmpp-support prototype's TLS/SASL connect mechanics, its [SanHostnameVerifier] fix for the
 * STARTTLS path, its parsing-exception/clean-teardown reconnect-stability fixes, and (slice X5) its
 * MUC join-listener-ordering fix and roster-load-at-login handling. Reimplemented against the new
 * seam: state is published as this package's [XmppSessionState], never a wire type, and every Smack
 * import stays inside `xmppbackend/` (enforced by `ImportBoundaryTest`).
 *
 * Only [XmppAccountEntity.jid]/[XmppAccountEntity.password]/[XmppAccountEntity.resource] are read.
 * Host/port are intentionally left unset so Smack resolves them via the standard XMPP SRV lookup
 * against the JID's domain: the `networks` row's host/port columns are IRC-shaped and grandfathered
 * to the IRC adapter (docs/backend-neutral-xmpp-rollout.md "Persistence and writer ownership"), so
 * the XMPP adapter must not read them.
 *
 * One instance = one connection attempt, per the [XmppSession] contract; [XmppAccountActor] creates
 * a fresh instance for every (re)connect.
 */
internal class SmackXmppSession(private val account: XmppAccountEntity) : XmppSession {
    private val _state = MutableStateFlow<XmppSessionState>(XmppSessionState.Disconnected)
    override val state: StateFlow<XmppSessionState> = _state.asStateFlow()

    // Buffered rather than rendezvous: Smack's chat listener callback (registerIncomingMessageListener)
    // is a plain synchronous callback and cannot suspend to wait for a slow collector. A dropped
    // emission past the buffer is an accepted v1 tradeoff (see [XmppSession.incomingMessages]).
    private val _incomingMessages = MutableSharedFlow<XmppIncomingMessage>(extraBufferCapacity = INCOMING_MESSAGE_BUFFER)
    override val incomingMessages: Flow<XmppIncomingMessage> = _incomingMessages.asSharedFlow()

    // Fed from the exact same registerIncomingMessageListener callback as _incomingMessages (slice
    // X6) — a chat-state-only stanza has no body, so it would otherwise be silently dropped there.
    private val _incomingChatStates =
        MutableSharedFlow<XmppIncomingChatState>(extraBufferCapacity = INCOMING_MESSAGE_BUFFER)
    override val incomingChatStates: Flow<XmppIncomingChatState> = _incomingChatStates.asSharedFlow()

    // Same buffered-flow rationale as _incomingMessages above; all four are fed from Smack's
    // synchronous MUC/roster listener callbacks (slice X5).
    private val _incomingMucMessages =
        MutableSharedFlow<XmppIncomingMucMessage>(extraBufferCapacity = INCOMING_MESSAGE_BUFFER)
    override val incomingMucMessages: Flow<XmppIncomingMucMessage> = _incomingMucMessages.asSharedFlow()

    private val _mucSubjects = MutableSharedFlow<XmppMucSubject>(extraBufferCapacity = MUC_EVENT_BUFFER)
    override val mucSubjects: Flow<XmppMucSubject> = _mucSubjects.asSharedFlow()

    private val _mucOccupants = MutableSharedFlow<XmppMucOccupantEvent>(extraBufferCapacity = MUC_EVENT_BUFFER)
    override val mucOccupants: Flow<XmppMucOccupantEvent> = _mucOccupants.asSharedFlow()

    // Roster loads at most once per session (see XmppSession.rosterLoad), so one slot is enough.
    private val _rosterLoad = MutableSharedFlow<XmppRosterLoad>(extraBufferCapacity = 1)
    override val rosterLoad: Flow<XmppRosterLoad> = _rosterLoad.asSharedFlow()

    /**
     * Listener refs registered per joined room, keyed by the canonical (Smack-normalized) room bare
     * JID, so [leaveRoom]/a rejoin removes exactly what [joinRoom] added — carried over from the
     * fork/xmpp-support prototype's `roomListeners` map. Accessed only from suspend functions
     * dispatched on [Dispatchers.IO] without further caller-side serialization, exactly like the
     * prototype; [ConcurrentHashMap] is defensive rather than load-bearing.
     */
    private data class RoomListeners(
        val messageListener: MessageListener,
        val subjectUpdatedListener: SubjectUpdatedListener,
        val participantStatusListener: ParticipantStatusListener,
        val userStatusListener: UserStatusListener,
    )

    private val roomListeners = ConcurrentHashMap<String, RoomListeners>()

    /**
     * Per-session XMPP resource. Two devices signed into one account must NOT present an identical
     * resource: a server's conflict policy (RFC 6120 §7.7.2.2) typically kills the older session
     * when a newer one binds the same resource, so a fixed fallback resource would make two devices
     * repeatedly kick each other in a reconnect loop. When the account has no configured resource,
     * fall back to a random per-attempt suffix (carried over from the prototype's fix).
     */
    private val resource: Resourcepart = account.resource?.takeIf(String::isNotBlank)
        ?.let(Resourcepart::from)
        ?: Resourcepart.from("motd-" + UUID.randomUUID().toString().take(4))

    private val connection: XMPPTCPConnection = XMPPTCPConnection(
        XMPPTCPConnectionConfiguration.builder()
            .setXmppAddressAndPassword(account.jid, account.password)
            .setResource(resource)
            .setSecurityMode(ConnectionConfiguration.SecurityMode.required) // STARTTLS mandatory
            // Smack's default hostname verifier comes from legacy Apache HTTP classes that are
            // absent on modern Android, and the JVM/Android TLS stack's own default is deny-all;
            // neither works here, so verify SAN dNSNames directly (carried over from the prototype).
            .setHostnameVerifier(SanHostnameVerifier)
            .build(),
    ).apply {
        // Smack's default callback disconnects the whole stream on any stanza it cannot parse (e.g.
        // a disco form field type unknown to this Smack version). Drop just that stanza and keep the
        // stream alive instead — carried over from the prototype as a reconnect-stability fix — but
        // log it at WARN so a silent drop stays observable. Never log stanza content: it can hold
        // private message bodies that must not leak into logcat.
        setParsingExceptionCallback { unparseable ->
            val length = unparseable.content?.length ?: 0
            LOGGER.log(
                Level.WARNING,
                "Dropping unparsable XMPP stanza ($length chars)",
                unparseable.parsingException,
            )
        }
    }

    override suspend fun connect() {
        withContext(Dispatchers.IO) {
            _state.value = XmppSessionState.Connecting
            // XmppAccountActor owns the reconnect loop; Smack's own reconnection manager staying on
            // would race it, so it must be disabled before connecting (carried over from the
            // prototype, same invariant as :irc's actor-owned reconnect).
            ReconnectionManager.getInstanceFor(connection).disableAutomaticReconnection()
            registerConnectionListener() // BEFORE connect/login — spec invariant, per the prototype.
            registerIncomingMessageListener() // Same ordering invariant: attach before stanzas can flow.
            try {
                connection.connect()
                _state.value = XmppSessionState.Authenticating
                connection.login()
                _state.value = XmppSessionState.Ready(connection.user.asBareJid().toString())
                loadRoster() // never throws; a roster hiccup must not fail an otherwise-good login.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (auth: SASLErrorException) {
                // Rejected credentials never fix themselves: fatal, no auto-retry.
                _state.value = XmppSessionState.Failed(XMPP_AUTH_FAILURE_REASON, fatal = true)
            } catch (e: Exception) {
                // Transport/TLS/timeout/etc: retryable.
                _state.value = XmppSessionState.Failed(e.message ?: "connection failed", fatal = false)
            }
        }
    }

    private fun registerConnectionListener() {
        connection.addConnectionListener(object : ConnectionListener {
            override fun connectionClosed() {
                _state.value = XmppSessionState.Disconnected
            }

            override fun connectionClosedOnError(e: Exception) {
                val auth = e is SASLErrorException
                _state.value = XmppSessionState.Failed(
                    reason = if (auth) XMPP_AUTH_FAILURE_REASON else (e.message ?: "connection closed"),
                    fatal = auth,
                )
            }
        })
    }

    /**
     * Bridge Smack's one-to-one chat callback onto [incomingMessages] and (slice X6)
     * [incomingChatStates]. `ChatManager`'s listener already narrows delivery to 1:1 DM-shaped
     * stanzas addressed to us (not MUC, not our own sends — carbons are not enabled in this slice, so
     * there is no reflection to filter here). A XEP-0085 [ChatStateExtension], when present, is
     * routed to [incomingChatStates] regardless of whether the stanza also carries a body (many
     * clients send `<active/>` alongside the body of a just-sent message, signaling "done composing";
     * both listeners legitimately fire for that one stanza). A stanza with no `<body/>` (e.g. a bare
     * chat-state notification) carries no message to persist and is dropped from [incomingMessages]
     * rather than landing as a blank row — this is the "floor" a chat-state-only stanza used to fall
     * through entirely before this slice routed it to [incomingChatStates] instead. Never logs stanza
     * content (same privacy invariant as the parsing-exception callback above).
     */
    private fun registerIncomingMessageListener() {
        ChatManager.getInstanceFor(connection).addIncomingListener { from, message, _ ->
            message.extensions.filterIsInstance<ChatStateExtension>().firstOrNull()?.let { extension ->
                _incomingChatStates.tryEmit(
                    XmppIncomingChatState(
                        fromBareJid = from.toString(),
                        state = extension.chatState.toXmppChatState(),
                    ),
                )
            }
            val body = message.body ?: return@addIncomingListener
            _incomingMessages.tryEmit(
                XmppIncomingMessage(
                    fromBareJid = from.toString(),
                    body = body,
                    stanzaId = message.stanzaId,
                    delayStampMillis = DelayInformationManager.getDelayTimestamp(message)?.time,
                ),
            )
        }
    }

    private fun ChatState.toXmppChatState(): XmppChatState = when (this) {
        ChatState.composing -> XmppChatState.COMPOSING
        ChatState.paused -> XmppChatState.PAUSED
        ChatState.active -> XmppChatState.ACTIVE
        ChatState.inactive -> XmppChatState.INACTIVE
        ChatState.gone -> XmppChatState.GONE
    }

    /**
     * Load the roster once, right after [connect] reaches Ready, and publish its one-shot outcome on
     * [rosterLoad] (never on [state]: a roster hiccup is not a connection failure). Smack requests
     * the roster as part of login by default (`Roster.isRosterLoadedAtLogin`), so [Roster.isLoaded]
     * is normally already true here; `reloadAndWait()` is a defensive fallback for the case where it
     * is not (carried over from the fork/xmpp-support prototype). Never throws.
     */
    private fun loadRoster() {
        try {
            val roster = Roster.getInstanceFor(connection)
            if (!roster.isLoaded) roster.reloadAndWait()
            val contacts = roster.entries.map { XmppRosterContact(it.jid.asBareJid().toString(), it.name) }
            _rosterLoad.tryEmit(XmppRosterLoad.Loaded(contacts))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            LOGGER.log(Level.WARNING, "XMPP roster load failed", e)
            _rosterLoad.tryEmit(XmppRosterLoad.Failed(e.message ?: "roster load failed"))
        }
    }

    /**
     * Smack caches one [MultiUserChat] instance per room JID (`getMultiUserChat` returns the same
     * instance on every call for a given room) — dropping any listeners left over from an earlier
     * join on this same instance, so a rejoin never double-delivers every room event. Carried over
     * from the fork/xmpp-support prototype's `joinMuc`/`removeRoomListeners` pairing.
     */
    override suspend fun joinRoom(bareRoomJid: String, nick: String) {
        withContext(Dispatchers.IO) {
            var joinedMuc: MultiUserChat? = null
            var canonicalRoomJid: String? = null
            try {
                val roomJid = JidCreate.entityBareFrom(bareRoomJid)
                val jidString = roomJid.toString()
                canonicalRoomJid = jidString
                val muc = MultiUserChatManager.getInstanceFor(connection).getMultiUserChat(roomJid)
                joinedMuc = muc
                removeRoomListeners(muc, jidString)

                val messageListener = MessageListener { message ->
                    val occupantNick = (message.from as? EntityFullJid)?.resourceOrEmpty?.toString()
                    val body = message.body
                    if (!occupantNick.isNullOrEmpty() && body != null) {
                        _incomingMucMessages.tryEmit(
                            XmppIncomingMucMessage(
                                roomBareJid = jidString,
                                occupantNick = occupantNick,
                                body = body,
                                stanzaId = message.stanzaId,
                                delayStampMillis = DelayInformationManager.getDelayTimestamp(message)?.time,
                                // muc.nickname reflects our CURRENT in-room nick (live, tracks a
                                // server-forced rename on nickname conflict), not just the nick this
                                // joinRoom call requested — see XmppIncomingMucMessage.isSelf.
                                isSelf = occupantNick == muc.nickname?.toString(),
                            ),
                        )
                    }
                }
                val subjectUpdatedListener = SubjectUpdatedListener { subject, from ->
                    _mucSubjects.tryEmit(XmppMucSubject(jidString, subject, from?.resourceOrEmpty?.toString()))
                }
                val participantStatusListener = object : ParticipantStatusListener {
                    override fun joined(participant: EntityFullJid) {
                        _mucOccupants.tryEmit(
                            XmppMucOccupantEvent.Joined(jidString, participant.resourceOrEmpty.toString()),
                        )
                    }

                    override fun left(participant: EntityFullJid) {
                        _mucOccupants.tryEmit(
                            XmppMucOccupantEvent.Left(jidString, participant.resourceOrEmpty.toString()),
                        )
                    }

                    override fun kicked(participant: EntityFullJid, actor: Jid?, reason: String?) {
                        // A kick of another occupant is just a departure from this baseline's model.
                        _mucOccupants.tryEmit(
                            XmppMucOccupantEvent.Left(jidString, participant.resourceOrEmpty.toString()),
                        )
                    }
                }
                // Our own kick/ban/room-destroyed handling is out of this baseline's scope (no
                // UserStatusListener callback is overridden); the listener is still registered so
                // removeRoomListeners has a stable, always-non-null ref to remove on leave/rejoin.
                val userStatusListener = object : UserStatusListener {}

                // Message/subject listeners must be attached before join() — both can fire mid-join.
                // Occupant listeners are attached AFTER a successful join so Smack's own replay of
                // every pre-existing member during join is never reported as a fresh Joined (carried
                // over fix from the fork/xmpp-support prototype; see XmppMucOccupantEvent's KDoc).
                muc.addMessageListener(messageListener)
                muc.addSubjectUpdatedListener(subjectUpdatedListener)
                roomListeners[jidString] = RoomListeners(
                    messageListener,
                    subjectUpdatedListener,
                    participantStatusListener,
                    userStatusListener,
                )

                // A gateway (e.g. a Biboumi-style IRC bridge) may cold-connect to the far network
                // before it can even reflect our presence, which routinely exceeds Smack's default
                // ~5s reply timeout; allow a long join window (carried over from the prototype).
                val enter = muc.getEnterConfigurationBuilder(Resourcepart.from(nick))
                    .timeoutAfter(MUC_JOIN_TIMEOUT_MS)
                    .requestMaxStanzasHistory(MUC_JOIN_HISTORY_MAX)
                    .build()
                muc.join(enter)
                muc.addParticipantStatusListener(participantStatusListener)
                muc.addUserStatusListener(userStatusListener)
                _mucOccupants.tryEmit(
                    XmppMucOccupantEvent.Snapshot(jidString, muc.occupants.map { it.resourceOrEmpty.toString() }),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                // Never logs bareRoomJid's original (un-parsed) form or any stanza content — only
                // the already-canonical room JID, which carries no message/roster content.
                LOGGER.log(Level.WARNING, "MUC join failed for ${canonicalRoomJid ?: "unparsed room JID"}", e)
                val muc = joinedMuc
                val jidString = canonicalRoomJid
                if (muc != null && jidString != null) removeRoomListeners(muc, jidString)
            }
        }
    }

    override suspend fun leaveRoom(bareRoomJid: String) {
        withContext(Dispatchers.IO) {
            try {
                val roomJid = JidCreate.entityBareFrom(bareRoomJid)
                val muc = MultiUserChatManager.getInstanceFor(connection).getMultiUserChat(roomJid)
                removeRoomListeners(muc, roomJid.toString())
                muc.leave()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                // Safe to call even if never joined (XmppSession.leaveRoom contract) — MucNotJoinedException
                // and friends are expected, not exceptional.
                LOGGER.log(Level.FINE, "MUC leave was a no-op or failed for $bareRoomJid", e)
            }
        }
    }

    override suspend fun refreshOccupants(bareRoomJid: String) {
        withContext(Dispatchers.IO) {
            try {
                val roomJid = JidCreate.entityBareFrom(bareRoomJid)
                val muc = MultiUserChatManager.getInstanceFor(connection).getMultiUserChat(roomJid)
                if (!muc.isJoined) return@withContext
                _mucOccupants.tryEmit(
                    XmppMucOccupantEvent.Snapshot(
                        roomJid.toString(),
                        muc.occupants.map { it.resourceOrEmpty.toString() },
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                LOGGER.log(Level.WARNING, "MUC occupant refresh failed for $bareRoomJid", e)
            }
        }
    }

    /**
     * [kind] — supplied by the caller, never inferred from [roomListeners] (review fix; see
     * [XmppSession.sendMessage]'s KDoc) — is the sole decider of stanza shape. MUC send uses
     * [MultiUserChat.sendMessage], which fills in `to`/`type=groupchat` itself, so the
     * [MessageBuilder] passed to it must stay unbuilt (per Smack's API contract); a 1:1 send instead
     * needs a fully built [Message] for [org.jivesoftware.smack.chat2.Chat.send]. A [XmppSendKind.GROUPCHAT]
     * send to a room this session has not actually joined throws (Smack's `MucNotJoinedException`)
     * rather than silently falling back to a one-to-one stanza — [XmppConnectionManager]'s existing
     * wire-write catch already turns that into a durable failure.
     */
    override suspend fun sendMessage(to: String, body: String, messageId: String, kind: XmppSendKind) {
        withContext(Dispatchers.IO) {
            val bareTo = JidCreate.entityBareFrom(to)
            when (kind) {
                XmppSendKind.GROUPCHAT -> {
                    val muc = MultiUserChatManager.getInstanceFor(connection).getMultiUserChat(bareTo)
                    muc.sendMessage(MessageBuilder.buildMessage(messageId).setBody(body))
                }
                XmppSendKind.CHAT -> {
                    val chat = ChatManager.getInstanceFor(connection).chatWith(bareTo)
                    val message = MessageBuilder.buildMessage(messageId)
                        .ofType(Message.Type.chat)
                        .setBody(body)
                        .build()
                    chat.send(message)
                }
            }
        }
    }

    /** Best-effort per [XmppSession.sendChatState]'s KDoc: logged and swallowed, never thrown. */
    override suspend fun sendChatState(toBareJid: String, state: XmppChatState) {
        withContext(Dispatchers.IO) {
            try {
                val chat = ChatManager.getInstanceFor(connection).chatWith(JidCreate.entityBareFrom(toBareJid))
                val message = MessageBuilder.buildMessage()
                    .ofType(Message.Type.chat)
                    .addExtension(ChatStateExtension(state.toSmackChatState()))
                    .build()
                chat.send(message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                LOGGER.log(Level.FINE, "XMPP chat-state send failed", e)
            }
        }
    }

    /** [XmppConnectionManager.sendTyping] never actually produces INACTIVE/GONE (the seam it maps
     *  from has no outgoing vocabulary for them; see [XmppChatState]'s KDoc), but the mapping must
     *  still be exhaustive over the full incoming-capable enum. */
    private fun XmppChatState.toSmackChatState(): ChatState = when (this) {
        XmppChatState.COMPOSING -> ChatState.composing
        XmppChatState.PAUSED -> ChatState.paused
        XmppChatState.ACTIVE -> ChatState.active
        XmppChatState.INACTIVE -> ChatState.inactive
        XmppChatState.GONE -> ChatState.gone
    }

    /** Carried over from the fork/xmpp-support prototype's `removeRoomListeners`: removing an
     *  unregistered listener is a harmless no-op, so this is safe to call defensively. */
    private fun removeRoomListeners(muc: MultiUserChat, roomJid: String) {
        roomListeners.remove(roomJid)?.let { listeners ->
            muc.removeMessageListener(listeners.messageListener)
            muc.removeSubjectUpdatedListener(listeners.subjectUpdatedListener)
            muc.removeParticipantStatusListener(listeners.participantStatusListener)
            muc.removeUserStatusListener(listeners.userStatusListener)
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            runCatching { connection.disconnect(Presence(Presence.Type.unavailable)) }
        }
        _state.value = XmppSessionState.Disconnected
    }

    private companion object {
        val LOGGER: Logger = Logger.getLogger(SmackXmppSession::class.java.name)

        /** Generous headroom for a burst of offline-storage redelivery; see [incomingMessages]. */
        const val INCOMING_MESSAGE_BUFFER = 64

        /** Headroom for a burst of MUC subject/occupant events (e.g. a busy room's join snapshot
         *  landing alongside a flurry of live joins); see [mucSubjects]/[mucOccupants]. */
        const val MUC_EVENT_BUFFER = 64

        /** A gateway cold-connecting to a far network can take tens of seconds before it can reflect
         *  our MUC self-presence; wait well past Smack's ~5s default (carried over from the
         *  fork/xmpp-support prototype's `GATEWAY_JOIN_TIMEOUT_MS`). */
        const val MUC_JOIN_TIMEOUT_MS = 60_000L

        /** Modest backlog on join: ignored by rooms with no history, recent context otherwise
         *  (carried over from the prototype's `MUC_JOIN_HISTORY_MAX`). */
        const val MUC_JOIN_HISTORY_MAX = 50
    }
}

/**
 * Real [XmppSessionFactory] (docs/backend-neutral-xmpp-rollout.md "PR 2"). Registers Smack's
 * Android providers/DNS resolver once per process — required for Smack to work on Android,
 * no-op-safe under Robolectric — carried over from the prototype's manager-level init, relocated
 * here so [XmppConnectionManager] stays Smack-agnostic.
 *
 * That registration parses provider descriptors and is far too slow for a constructor: Dagger
 * builds this factory while resolving the backend registry, which a lifecycle broadcast can
 * trigger from any thread, and doing it eagerly once stalled the foreground service at startup.
 * It runs lazily on first [create] instead, off the caller's critical path.
 *
 * Not `internal`: [XmppBackendModule]'s `@Binds` function takes this as a parameter type, and a
 * public Dagger binding cannot expose an internal type (Kotlin visibility rule) — same reason
 * [SmackXmppSession] itself stays unexposed by never appearing in a public signature.
 */
class SmackXmppSessionFactory @Inject constructor(
    @ApplicationContext private val context: Context,
) : XmppSessionFactory {
    private val smackInitialized = AtomicBoolean(false)

    override fun create(account: XmppAccountEntity): XmppSession {
        if (smackInitialized.compareAndSet(false, true)) {
            runCatching { AndroidSmackInitializer.initialize(context) }
        }
        return SmackXmppSession(account)
    }
}

/**
 * RFC 6125-style verification of the peer certificate's subjectAltName dNSName entries against the
 * connect hostname. Carried over verbatim from the fork/xmpp-support prototype: deliberately strict
 * (no CN fallback — SAN-less certs fail; a wildcard matches only a single leftmost label, so
 * `*.example.net` matches `a.example.net` but never `example.net` or `a.b.example.net`). TLS chain
 * trust is already enforced by the socket layer; this only binds the validated chain to the
 * expected host, which Smack's own default verifier does not reliably do on Android.
 */
internal object SanHostnameVerifier : HostnameVerifier {
    private const val SAN_DNS_NAME = 2

    override fun verify(hostname: String, session: SSLSession): Boolean {
        val cert = session.peerCertificates.firstOrNull() as? X509Certificate ?: return false
        val host = hostname.lowercase()
        val sans = try {
            cert.subjectAlternativeNames ?: return false
        } catch (_: CertificateParsingException) {
            return false
        }
        return sans.any { entry ->
            entry != null && entry.size >= 2 && entry[0] == SAN_DNS_NAME &&
                (entry[1] as? String)?.lowercase()?.let { matches(host, it) } == true
        }
    }

    internal fun matches(host: String, pattern: String): Boolean {
        if (!pattern.startsWith("*.")) return host == pattern
        val suffix = pattern.substring(1) // ".example.net"
        if (!host.endsWith(suffix)) return false
        val label = host.dropLast(suffix.length)
        return label.isNotEmpty() && !label.contains('.')
    }
}
