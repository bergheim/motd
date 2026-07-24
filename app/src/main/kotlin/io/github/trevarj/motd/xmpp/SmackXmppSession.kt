package io.github.trevarj.motd.xmpp

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jivesoftware.smack.ConnectionConfiguration
import org.jivesoftware.smack.ConnectionListener
import org.jivesoftware.smack.MessageListener
import org.jivesoftware.smack.ReconnectionManager
import org.jivesoftware.smack.StanzaListener
import org.jivesoftware.smack.sm.StreamManagementException
import org.jivesoftware.smack.XMPPException
import org.jivesoftware.smack.chat2.ChatManager
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.MessageBuilder
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.packet.Stanza
import org.jivesoftware.smack.roster.Roster
import org.jivesoftware.smack.roster.RosterListener
import org.jivesoftware.smack.sasl.SASLErrorException
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smackx.chatstates.ChatState
import org.jivesoftware.smackx.chatstates.packet.ChatStateExtension
import org.jivesoftware.smackx.delay.packet.DelayInformation
import org.jivesoftware.smackx.muc.MultiUserChat
import org.jivesoftware.smackx.muc.MultiUserChatException
import org.jivesoftware.smackx.muc.MultiUserChatManager
import org.jivesoftware.smackx.muc.ParticipantStatusListener
import org.jivesoftware.smackx.muc.SubjectUpdatedListener
import org.jivesoftware.smackx.muc.UserStatusListener
import org.jivesoftware.smackx.sid.element.OriginIdElement
import org.jxmpp.jid.EntityFullJid
import org.jxmpp.jid.Jid
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Resourcepart
import java.net.InetAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class SmackXmppSession(private val config: XmppAccountConfig) : XmppSession {
    private val channel = Channel<XmppEvent>(Channel.UNLIMITED)
    override val events = channel

    /** Listener refs registered per room, so leaveMuc/rejoin can remove exactly what joinMuc added. */
    private data class RoomListeners(
        val messageListener: MessageListener,
        val subjectUpdatedListener: SubjectUpdatedListener,
        val participantStatusListener: ParticipantStatusListener,
        val userStatusListener: UserStatusListener,
    )

    // Accessed from suspend methods dispatched on Dispatchers.IO without caller-side serialization.
    private val roomListeners = ConcurrentHashMap<String, RoomListeners>()

    /**
     * Per-session XMPP resource. Two devices signed into one account must NOT present an identical
     * resource: a server's conflict policy (RFC 6120 §7.7.2.2) typically kills the older session
     * when a newer one binds the same resource, so a fixed "motd" resource would make two devices
     * repeatedly kick each other in a reconnect loop. A random per-instance suffix (UUID-derived,
     * no wall-clock) keeps each session's resource distinct. Declared before [connection] so it is
     * initialized by the time the builder reads it.
     */
    private val resource: Resourcepart =
        Resourcepart.from("motd-" + UUID.randomUUID().toString().take(4))

    private val connection: XMPPTCPConnection = XMPPTCPConnection(
        XMPPTCPConnectionConfiguration.builder()
            .setXmppAddressAndPassword(config.bareJid, config.password)
            .setHost(config.host).setPort(config.port)
            .setResource(resource)
            // Smack's default hostname verifier comes from legacy Apache HTTP classes that are
            // absent on modern Android, and the JVM's HttpsURLConnection default is deny-all;
            // neither works in both environments, so verify SAN dNSNames directly.
            .setHostnameVerifier(SanHostnameVerifier)
            .apply {
                if (config.directTls) {
                    // Verify the peer against the XMPP service domain — the bare JID's domainpart,
                    // NOT config.host (which may be a routing override). See
                    // EndpointIdentifyingSSLSocketFactory for why SanHostnameVerifier alone is not
                    // enough on the direct-TLS path.
                    setSocketFactory(EndpointIdentifyingSSLSocketFactory(config.bareJid.substringAfter('@')))
                    setSecurityMode(ConnectionConfiguration.SecurityMode.disabled) // TLS already on the socket
                } else {
                    setSecurityMode(ConnectionConfiguration.SecurityMode.required) // STARTTLS mandatory
                }
            }
            .build(),
    ).apply {
        setUseStreamManagement(true)
        setUseStreamManagementResumption(false)
        // Smack's default callback disconnects on any stanza it cannot parse (e.g. ejabberd's
        // HTTP-upload disco form uses a field type unknown to Smack 4.4). Drop the stanza and
        // keep the stream alive instead — but log it at WARN so silent drops are observable.
        setParsingExceptionCallback { unparseable ->
            val content = unparseable.content?.toString().orEmpty()
            val truncated = if (content.length > MAX_LOGGED_STANZA_CHARS) {
                content.take(MAX_LOGGED_STANZA_CHARS) + "…(${content.length} chars total)"
            } else {
                content
            }
            LOGGER.log(Level.WARNING, "Dropping unparsable XMPP stanza: $truncated", unparseable.parsingException)
        }
    }

    override suspend fun connectAndLogin() {
        withContext(Dispatchers.IO) {
            ReconnectionManager.getInstanceFor(connection).disableAutomaticReconnection()
            registerAccountListeners()   // BEFORE connect/login — spec invariant
            connection.connect()
            connection.login()
            val roster = Roster.getInstanceFor(connection)
            if (!roster.isLoaded) roster.reloadAndWait()
            channel.trySend(XmppEvent.RosterUpdated(roster.entries.map {
                RosterContact(it.jid.asBareJid().toString(), it.name)
            }))
            channel.trySend(XmppEvent.Ready(connection.user.asBareJid().toString()))
        }
    }

    private fun registerAccountListeners() {
        ChatManager.getInstanceFor(connection).addIncomingListener { from, message, _ ->
            val chatStateExtension = message.extensions.filterIsInstance<ChatStateExtension>().firstOrNull()
            if (chatStateExtension != null) {
                channel.trySend(
                    XmppEvent.ChatState(
                        fromBareJid = from.asBareJid().toString(),
                        composing = chatStateExtension.chatState == ChatState.composing,
                    ),
                )
            }
            val body = message.body
            if (body != null) {
                channel.trySend(
                    XmppEvent.ChatMessage(
                        fromBareJid = from.asBareJid().toString(),
                        text = body,
                        stanzaId = extractStanzaId(message),
                        delayedAtMs = extractDelayMs(message),
                    ),
                )
            }
        }

        val roster = Roster.getInstanceFor(connection)
        roster.addRosterListener(object : RosterListener {
            override fun entriesAdded(addresses: Collection<Jid>) = emitRosterSnapshot(roster)
            override fun entriesUpdated(addresses: Collection<Jid>) = emitRosterSnapshot(roster)
            override fun entriesDeleted(addresses: Collection<Jid>) = emitRosterSnapshot(roster)
            override fun presenceChanged(presence: Presence) = emitRosterSnapshot(roster)
        })

        connection.addConnectionListener(object : ConnectionListener {
            override fun connectionClosed() {
                channel.trySend(XmppEvent.Disconnected(reason = null, fatal = false))
            }

            override fun connectionClosedOnError(e: Exception) {
                channel.trySend(XmppEvent.Disconnected(reason = e.message, fatal = e is SASLErrorException))
            }
        })
    }

    private fun emitRosterSnapshot(roster: Roster) {
        channel.trySend(
            XmppEvent.RosterUpdated(
                roster.entries.map { RosterContact(it.jid.asBareJid().toString(), it.name) },
            ),
        )
    }

    private fun extractStanzaId(message: Message): String? =
        OriginIdElement.getOriginId(message)?.id ?: message.stanzaId

    private fun extractDelayMs(message: Message): Long? =
        DelayInformation.from(message)?.stamp?.time

    override suspend fun joinMuc(roomJid: String, nick: String) {
        withContext(Dispatchers.IO) {
            val muc =
                MultiUserChatManager.getInstanceFor(connection).getMultiUserChat(JidCreate.entityBareFrom(roomJid))

            // getMultiUserChat(jid) returns a cached per-room instance — drop any listeners left
            // over from a previous join so a rejoin doesn't double every room event.
            removeRoomListeners(muc, roomJid)

            val messageListener = MessageListener { message ->
                val occupantNick = (message.from as? EntityFullJid)?.resourceOrEmpty?.toString()
                val body = message.body
                if (!occupantNick.isNullOrEmpty() && body != null) {
                    channel.trySend(
                        XmppEvent.MucMessage(
                            roomJid = roomJid,
                            occupantNick = occupantNick,
                            text = body,
                            stanzaId = extractStanzaId(message),
                            delayedAtMs = extractDelayMs(message),
                        ),
                    )
                }
            }

            val subjectUpdatedListener = SubjectUpdatedListener { subject, from ->
                channel.trySend(XmppEvent.MucSubject(roomJid, subject, from?.resourceOrEmpty?.toString()))
            }

            val participantStatusListener = object : ParticipantStatusListener {
                override fun joined(participant: EntityFullJid) {
                    channel.trySend(XmppEvent.MucOccupantJoined(roomJid, participant.resourceOrEmpty.toString()))
                }

                override fun left(participant: EntityFullJid) {
                    channel.trySend(XmppEvent.MucOccupantLeft(roomJid, participant.resourceOrEmpty.toString()))
                }

                override fun kicked(participant: EntityFullJid, actor: Jid?, reason: String?) {
                    // A kick of another occupant is just a departure from our model's perspective.
                    channel.trySend(XmppEvent.MucOccupantLeft(roomJid, participant.resourceOrEmpty.toString()))
                }
            }

            val userStatusListener = object : UserStatusListener {
                override fun kicked(actor: Jid?, reason: String?) {
                    channel.trySend(XmppEvent.MucKicked(roomJid, reason))
                }
            }

            muc.addMessageListener(messageListener)
            muc.addSubjectUpdatedListener(subjectUpdatedListener)
            muc.addParticipantStatusListener(participantStatusListener)
            muc.addUserStatusListener(userStatusListener)
            roomListeners[roomJid] = RoomListeners(
                messageListener, subjectUpdatedListener, participantStatusListener, userStatusListener,
            )

            try {
                muc.join(Resourcepart.from(nick))
                channel.trySend(
                    XmppEvent.MucSelfJoined(roomJid, muc.occupants.map { it.resourceOrEmpty.toString() }),
                )
            } catch (e: XMPPException.XMPPErrorException) {
                val reason = e.stanzaError?.condition?.toString() ?: e.message.orEmpty()
                channel.trySend(XmppEvent.MucJoinFailed(roomJid, reason))
            } catch (e: MultiUserChatException) {
                channel.trySend(XmppEvent.MucJoinFailed(roomJid, e.message ?: "MUC join failed"))
            }
        }
    }

    override suspend fun leaveMuc(roomJid: String) {
        withContext(Dispatchers.IO) {
            val muc =
                MultiUserChatManager.getInstanceFor(connection).getMultiUserChat(JidCreate.entityBareFrom(roomJid))
            removeRoomListeners(muc, roomJid)
            muc.leave()
        }
    }

    private fun removeRoomListeners(muc: MultiUserChat, roomJid: String) {
        roomListeners.remove(roomJid)?.let { listeners ->
            muc.removeMessageListener(listeners.messageListener)
            muc.removeSubjectUpdatedListener(listeners.subjectUpdatedListener)
            muc.removeParticipantStatusListener(listeners.participantStatusListener)
            muc.removeUserStatusListener(listeners.userStatusListener)
        }
    }

    override suspend fun sendChat(toBareJid: String, text: String, originId: String) {
        withContext(Dispatchers.IO) {
            val chat = ChatManager.getInstanceFor(connection).chatWith(JidCreate.entityBareFrom(toBareJid))
            // Chat.send(...) only accepts a built Message, not a MessageBuilder.
            val message = MessageBuilder.buildMessage(originId)
                .ofType(Message.Type.chat)
                .addExtension(OriginIdElement(originId))
                .setBody(text)
                .build()
            val ackRegistered = registerAckListener(originId)
            try {
                chat.send(message)
            } catch (t: Throwable) {
                if (ackRegistered) connection.removeStanzaIdAcknowledgedListener(originId)
                throw t
            }
        }
    }

    override suspend fun sendMuc(roomJid: String, text: String, originId: String) {
        withContext(Dispatchers.IO) {
            val muc =
                MultiUserChatManager.getInstanceFor(connection).getMultiUserChat(JidCreate.entityBareFrom(roomJid))
            // MultiUserChat.sendMessage(...) takes the MessageBuilder itself — it fills in
            // room "to"/type=groupchat internally, so we must NOT .build() this one.
            val messageBuilder = MessageBuilder.buildMessage(originId)
                .addExtension(OriginIdElement(originId))
                .setBody(text)
            val ackRegistered = registerAckListener(originId)
            try {
                muc.sendMessage(messageBuilder)
            } catch (t: Throwable) {
                if (ackRegistered) connection.removeStanzaIdAcknowledgedListener(originId)
                throw t
            }
        }
    }

    /**
     * Registered BEFORE the send call so an ack that races in between isn't lost. Returns whether
     * the listener was actually registered — if stream management isn't enabled this throws;
     * swallow it and proceed without ack tracking. Callers must remove the listener themselves if
     * the send subsequently fails (Smack only ever removes it on a matching ack).
     */
    private fun registerAckListener(originId: String): Boolean {
        return try {
            connection.addStanzaIdAcknowledgedListener(
                originId,
                object : StanzaListener {
                    override fun processStanza(packet: Stanza) {
                        channel.trySend(XmppEvent.SendConfirmed(originId))
                    }
                },
            )
            true
        } catch (e: StreamManagementException.StreamManagementNotEnabledException) {
            // No ack tracking possible for this send; SendConfirmed simply never fires.
            false
        }
    }

    override suspend fun sendChatState(toBareJid: String, composing: Boolean) {
        withContext(Dispatchers.IO) {
            val chat = ChatManager.getInstanceFor(connection).chatWith(JidCreate.entityBareFrom(toBareJid))
            val state = if (composing) ChatState.composing else ChatState.active
            // Same overload constraint as sendChat: Chat.send(...) needs a built Message.
            val message = MessageBuilder.buildMessage()
                .ofType(Message.Type.chat)
                .addExtension(ChatStateExtension(state))
                .build()
            chat.send(message)
        }
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) {
            connection.disconnect(Presence(Presence.Type.unavailable))
            channel.close()
        }
    }

    private companion object {
        private val LOGGER = Logger.getLogger(SmackXmppSession::class.java.name)
        private const val MAX_LOGGED_STANZA_CHARS = 500
    }
}

object SmackXmppSessionFactory : XmppSessionFactory {
    override fun create(config: XmppAccountConfig): XmppSession = SmackXmppSession(config)
}

/**
 * Wraps the platform default [SSLSocketFactory] so every [SSLSocket] it hands back to Smack has
 * HTTPS endpoint identification and SNI pinned to the XMPP service domain BEFORE the handshake.
 *
 * Why this is necessary: Smack only invokes the configured [HostnameVerifier] on the STARTTLS path
 * (`XMPPTCPConnection.proceedTLSReceived`). The direct-TLS (port 5223) path just wraps the socket
 * produced by the configured [SSLSocketFactory] and never calls the verifier, so a plain
 * `SSLSocketFactory.getDefault()` there would accept ANY chain-valid certificate regardless of
 * which host it authenticates. Setting `endpointIdentificationAlgorithm = "HTTPS"` makes the
 * JDK/Android TLS stack itself verify the peer certificate against [xmppDomain] during the
 * handshake, and the matching SNI server name both advertises the expected host and is used by
 * JSSE as the reference identity for that check.
 *
 * [xmppDomain] must be the XMPP service domain (the bare JID's domainpart), NOT the connect-host
 * override: the certificate has to authenticate the service identity the user configured, not
 * whatever host the connection happened to be routed through.
 *
 * Every `createSocket` overload applies the parameters, including the no-arg one: Smack's non-proxy
 * path builds the socket via `SmackFuture.SocketFuture`, which calls `createSocket()` unconnected
 * and connects it afterwards, so an overload that returned a bare socket would skip verification.
 */
internal class EndpointIdentifyingSSLSocketFactory(private val xmppDomain: String) : SSLSocketFactory() {
    private val delegate = SSLSocketFactory.getDefault() as SSLSocketFactory

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(): Socket = identify(delegate.createSocket())
    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
        identify(delegate.createSocket(s, host, port, autoClose))
    override fun createSocket(host: String?, port: Int): Socket =
        identify(delegate.createSocket(host, port))
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        identify(delegate.createSocket(host, port, localHost, localPort))
    override fun createSocket(host: InetAddress?, port: Int): Socket =
        identify(delegate.createSocket(host, port))
    override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): Socket = identify(delegate.createSocket(address, port, localAddress, localPort))

    private fun identify(socket: Socket): Socket {
        if (socket is SSLSocket) {
            socket.sslParameters = socket.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
                serverNames = listOf(SNIHostName(xmppDomain))
            }
        }
        return socket
    }
}

/**
 * RFC 6125-style verification of the peer certificate's subjectAltName dNSName entries against
 * the XMPP domain. Deliberately strict: no CN fallback (deprecated; SAN-less certs fail), and a
 * wildcard only matches a single leftmost label ("*.example.net" matches "a.example.net", never
 * "example.net" or "a.b.example.net"). TLS chain trust is already enforced by the socket layer;
 * this only binds the validated chain to the expected host.
 */
internal object SanHostnameVerifier : HostnameVerifier {
    private const val SAN_DNS_NAME = 2

    override fun verify(hostname: String, session: SSLSession): Boolean {
        val cert = session.peerCertificates.firstOrNull() as? X509Certificate ?: return false
        val host = hostname.lowercase()
        val sans = try {
            cert.subjectAlternativeNames ?: return false
        } catch (_: java.security.cert.CertificateParsingException) {
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
