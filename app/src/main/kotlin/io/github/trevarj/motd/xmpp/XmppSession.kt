package io.github.trevarj.motd.xmpp

import kotlinx.coroutines.channels.ReceiveChannel

/** Config subset the session needs; derived from NetworkEntity by the actor. */
data class XmppAccountConfig(
    val bareJid: String, val password: String, val host: String, val port: Int,
    val directTls: Boolean, val mucNick: String,
)

/** One MUC room discovered via service discovery; [name] is the room's disco#items name, if any. */
data class MucRoomListing(val roomJid: String, val name: String?)

/**
 * Protocol seam over one Smack connection. Implementations MUST register all account-level
 * listeners before login and room listeners before join; every callback is surfaced only
 * through [events]. One instance = one connection attempt; create a fresh session per (re)connect.
 */
interface XmppSession {
    val events: ReceiveChannel<XmppEvent>
    suspend fun connectAndLogin()
    suspend fun joinMuc(roomJid: String, nick: String)
    suspend fun leaveMuc(roomJid: String)
    suspend fun sendChat(toBareJid: String, text: String, originId: String)
    suspend fun sendMuc(roomJid: String, text: String, originId: String)
    suspend fun sendChatState(toBareJid: String, composing: Boolean)
    /** Discover MUC rooms across every MUC service domain this connection can see (channel-browser
     *  support). A server with no reachable MUC service is not an error worth surfacing. */
    suspend fun listRooms(): List<MucRoomListing>
    /** Discover component JIDs that are IRC gateways (disco#info identity `type == "irc"`, e.g. a
     *  Biboumi component). Same best-effort contract as [listRooms]: a discovery failure yields an
     *  empty list rather than surfacing an error. */
    suspend fun listIrcGateways(): List<String>
    suspend fun close()
}

fun interface XmppSessionFactory {
    fun create(config: XmppAccountConfig): XmppSession
}

/**
 * Thrown by [XmppSession.connectAndLogin] when the server rejected the credentials (SASL
 * failure). Kept at the seam so [XmppAccountActor] can park the account with a clear message
 * instead of retrying a password that will never work; the message is shown verbatim in the
 * onboarding/add-network error UI.
 */
class XmppAuthException(cause: Throwable? = null) :
    Exception("Wrong address or password", cause)
