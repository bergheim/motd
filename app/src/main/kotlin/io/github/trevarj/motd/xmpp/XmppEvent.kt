package io.github.trevarj.motd.xmpp

data class RosterContact(val bareJid: String, val name: String?)

sealed interface XmppEvent {
    /** Authenticated and initial roster loaded — the account is Ready. */
    data class Ready(val selfBareJid: String) : XmppEvent
    data class RosterUpdated(val contacts: List<RosterContact>) : XmppEvent
    data class ChatMessage(
        val fromBareJid: String, val text: String, val stanzaId: String?, val delayedAtMs: Long?,
    ) : XmppEvent
    data class ChatState(val fromBareJid: String, val composing: Boolean) : XmppEvent
    data class MucMessage(
        val roomJid: String, val occupantNick: String, val text: String, val stanzaId: String?,
        val delayedAtMs: Long?,
    ) : XmppEvent
    data class MucSubject(val roomJid: String, val subject: String, val byNick: String?) : XmppEvent
    data class MucOccupantJoined(val roomJid: String, val nick: String) : XmppEvent
    data class MucOccupantLeft(val roomJid: String, val nick: String) : XmppEvent
    data class MucSelfJoined(val roomJid: String, val occupants: List<String>) : XmppEvent
    data class MucJoinFailed(val roomJid: String, val reason: String) : XmppEvent
    data class MucKicked(val roomJid: String, val reason: String?) : XmppEvent
    /** XEP-0198 server ack for an outbound stanza we sent with [originId]. */
    data class SendConfirmed(val originId: String) : XmppEvent
    data class Disconnected(val reason: String?, val fatal: Boolean) : XmppEvent
}
