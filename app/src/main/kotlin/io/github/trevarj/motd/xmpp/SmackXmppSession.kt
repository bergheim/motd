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
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
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

    private val connection: XMPPTCPConnection = XMPPTCPConnection(
        XMPPTCPConnectionConfiguration.builder()
            .setXmppAddressAndPassword(config.bareJid, config.password)
            .setHost(config.host).setPort(config.port)
            .setResource(Resourcepart.from("motd"))
            // Smack's default hostname verifier comes from legacy Apache HTTP classes that are
            // absent on modern Android (and stubbed on the unit-test JVM); the platform default
            // is correct in both environments.
            .setHostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier())
            .apply {
                if (config.directTls) {
                    setSocketFactory(SSLSocketFactory.getDefault())
                    setSecurityMode(ConnectionConfiguration.SecurityMode.disabled) // TLS already on the socket
                } else {
                    setSecurityMode(ConnectionConfiguration.SecurityMode.required) // STARTTLS mandatory
                }
            }
            .build(),
    ).apply {
        setUseStreamManagement(true)
        setUseStreamManagementResumption(false)
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
}

object SmackXmppSessionFactory : XmppSessionFactory {
    override fun create(config: XmppAccountConfig): XmppSession = SmackXmppSession(config)
}
