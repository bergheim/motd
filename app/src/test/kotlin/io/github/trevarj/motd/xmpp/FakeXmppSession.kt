package io.github.trevarj.motd.xmpp

import kotlinx.coroutines.channels.Channel

class FakeXmppSession : XmppSession {
    private val channel = Channel<XmppEvent>(Channel.UNLIMITED)
    override val events = channel
    val sentChats = mutableListOf<Triple<String, String, String>>()   // to, text, originId
    val sentMuc = mutableListOf<Triple<String, String, String>>()     // room, text, originId
    val joinedRooms = mutableListOf<String>()
    var connectCalls = 0; var closed = false
    var failLoginWith: Exception? = null

    /** Room JIDs whose [joinMuc] should throw, to exercise per-room rejoin degradation. */
    var failJoinFor: Set<String> = emptySet()

    /** Canned [listRooms] result; channel-browser MUC discovery tests configure this directly. */
    var roomListings: List<MucRoomListing> = emptyList()

    /** Canned [listIrcGateways] result; IRC-gateway discovery tests configure this directly. */
    var ircGateways: List<String> = emptyList()

    suspend fun emit(event: XmppEvent) = channel.send(event)
    override suspend fun connectAndLogin() { connectCalls++; failLoginWith?.let { throw it } }
    override suspend fun joinMuc(roomJid: String, nick: String) {
        if (roomJid in failJoinFor) throw RuntimeException("simulated MUC join failure for $roomJid")
        joinedRooms += roomJid
    }
    override suspend fun leaveMuc(roomJid: String) { joinedRooms -= roomJid }
    override suspend fun sendChat(toBareJid: String, text: String, originId: String) {
        sentChats += Triple(toBareJid, text, originId)
    }
    override suspend fun sendMuc(roomJid: String, text: String, originId: String) {
        sentMuc += Triple(roomJid, text, originId)
    }
    override suspend fun sendChatState(toBareJid: String, composing: Boolean) = Unit
    override suspend fun listRooms(): List<MucRoomListing> = roomListings
    override suspend fun listIrcGateways(): List<String> = ircGateways
    override suspend fun close() { closed = true; channel.close() }
}
