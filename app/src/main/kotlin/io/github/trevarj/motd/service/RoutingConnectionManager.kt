package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.Protocol
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.TimelineEventId
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.xmpp.MucRoomListing
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Disambiguates the IRC-only [ConnectionManager] binding (`ConnectionManagerImpl`) from the
 * protocol-routing [ConnectionManager] binding ([RoutingConnectionManager]) that the rest of the
 * app injects. Without a qualifier, [RoutingConnectionManager]'s own constructor asking for a plain
 * `ConnectionManager` would be a self-referential Dagger cycle, since it IS the unqualified binding.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IrcConnectionManager

/**
 * The XMPP-side surface [RoutingConnectionManager] needs. Mirrors `XmppConnectionManager`'s actual
 * public API exactly (xmpp-support Task 6); kept as a separate interface — rather than depending on
 * the concrete class directly — so tests can substitute a lightweight recording fake instead of
 * constructing the real Smack-backed manager (which pulls in Android Smack initialization and a
 * live `MotdDatabase`). [IrcModule] provides the real instance by delegating to the concrete
 * `XmppConnectionManager`.
 */
interface XmppConnectionSurface {
    val connectionStates: StateFlow<Map<Long, IrcClientState>>
    suspend fun startAll()
    suspend fun stopAll()
    suspend fun connect(networkId: Long)
    suspend fun disconnect(networkId: Long)
    suspend fun reconnectStale()
    suspend fun sendMessage(bufferId: Long, text: String): SendAcceptance
    suspend fun sendTyping(bufferId: Long, state: String)
    suspend fun joinChannel(networkId: Long, roomJid: String)
    /** Channel-browser MUC discovery for an XMPP network id (xmpp-support room-browse). */
    suspend fun listRooms(networkId: Long): List<MucRoomListing>
    /** IRC-gateway discovery for an XMPP network id (component JIDs whose disco identity is IRC). */
    suspend fun listIrcGateways(networkId: Long): List<String>
    suspend fun partChannel(bufferId: Long, reason: String?)
    suspend fun ensureQueryBuffer(networkId: Long, bareJid: String): Long
    suspend fun ensureServerBuffer(networkId: Long): Long
}

/** Inert [XmppConnectionSurface] for components (e.g. ViewModels under test) constructed without XMPP. */
object NoopXmppConnectionSurface : XmppConnectionSurface {
    override val connectionStates: StateFlow<Map<Long, IrcClientState>> = MutableStateFlow(emptyMap())
    override suspend fun startAll() = Unit
    override suspend fun stopAll() = Unit
    override suspend fun connect(networkId: Long) = Unit
    override suspend fun disconnect(networkId: Long) = Unit
    override suspend fun reconnectStale() = Unit
    override suspend fun sendMessage(bufferId: Long, text: String): SendAcceptance =
        SendAcceptance.Rejected(SendRejectionReason.BUFFER_NOT_FOUND)
    override suspend fun sendTyping(bufferId: Long, state: String) = Unit
    override suspend fun joinChannel(networkId: Long, roomJid: String) = Unit
    override suspend fun listRooms(networkId: Long): List<MucRoomListing> = emptyList()
    override suspend fun listIrcGateways(networkId: Long): List<String> = emptyList()
    override suspend fun partChannel(bufferId: Long, reason: String?) = Unit
    override suspend fun ensureQueryBuffer(networkId: Long, bareJid: String): Long = 0L
    override suspend fun ensureServerBuffer(networkId: Long): Long = 0L
}

/**
 * Routes every [ConnectionManager] call by the target network's [Protocol] (xmpp-support Task 7).
 * Bound as THE app-wide [ConnectionManager] ([io.github.trevarj.motd.di.IrcModule]); IRC-protocol
 * rows fall through to [irc] (the real `ConnectionManagerImpl`, injected behind the
 * [IrcConnectionManager]-qualified binding to avoid a self-cycle on the unqualified binding this
 * class itself satisfies), XMPP-protocol rows to [xmpp].
 *
 * Buffer-scoped calls resolve the owning network through [db] first. A buffer row that cannot be
 * resolved (deleted/never existed) makes a send return [SendAcceptance.Rejected] with
 * [SendRejectionReason.BUFFER_NOT_FOUND]; every other buffer-scoped call silently no-ops — the same
 * "vanished row" behavior `ConnectionManagerImpl` already had before routing existed.
 *
 * Members with no XMPP equivalent yet (reactions, roster refresh, wire read-marker sync, retries,
 * invites, cert trust, push-mode re-evaluation) delegate to [irc] unconditionally or no-op for XMPP
 * buffers, per member; see the routing rules on each override below.
 */
@Singleton
class RoutingConnectionManager @Inject constructor(
    @IrcConnectionManager private val irc: ConnectionManager,
    private val xmpp: XmppConnectionSurface,
    private val db: MotdDatabase,
    @ApplicationScope scope: CoroutineScope,
) : ConnectionManager {

    private suspend fun protocolOf(networkId: Long): Protocol =
        db.networkDao().byId(networkId)?.protocol ?: Protocol.IRC

    /** Null only when the buffer row itself is unresolvable (deleted/never existed). */
    private suspend fun bufferProtocol(bufferId: Long): Protocol? {
        val networkId = db.bufferDao().rawById(bufferId)?.networkId ?: return null
        return protocolOf(networkId)
    }

    override val connectionStates: StateFlow<Map<Long, IrcClientState>> =
        combine(irc.connectionStates, xmpp.connectionStates) { ircStates, xmppStates ->
            // Disjoint network-id spaces (one `networks` table, one protocol per row): a plain union
            // can never collide.
            ircStates + xmppStates
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    // -- IRC-only observable surface: no XMPP equivalent exists yet, so delegate unconditionally. --

    override val rosterStates: StateFlow<Map<Long, RosterLoadState>> get() = irc.rosterStates
    override val presenceStates: StateFlow<Map<PresenceKey, PresenceState>> get() = irc.presenceStates
    override val channelJoinOutcomes: Flow<ChannelJoinOutcome> get() = irc.channelJoinOutcomes
    override val certPrompts: StateFlow<List<CertPrompt>> get() = irc.certPrompts

    /** IRC-only: an XMPP network id never appears in `irc`'s own actor/registry maps, so this
     *  naturally returns null for one without needing an explicit protocol check. */
    override fun clientFor(networkId: Long): IrcClient? = irc.clientFor(networkId)

    override suspend fun startAll() {
        irc.startAll()
        xmpp.startAll()
    }

    override suspend fun stopAll() {
        irc.stopAll()
        xmpp.stopAll()
    }

    override suspend fun reconnectStale() {
        irc.reconnectStale()
        xmpp.reconnectStale()
    }

    override suspend fun connect(networkId: Long) {
        when (protocolOf(networkId)) {
            Protocol.IRC -> irc.connect(networkId)
            Protocol.XMPP -> xmpp.connect(networkId)
        }
    }

    override suspend fun disconnect(networkId: Long) {
        when (protocolOf(networkId)) {
            Protocol.IRC -> irc.disconnect(networkId)
            Protocol.XMPP -> xmpp.disconnect(networkId)
        }
    }

    override suspend fun joinChannel(networkId: Long, channel: String) {
        when (protocolOf(networkId)) {
            Protocol.IRC -> irc.joinChannel(networkId, channel)
            Protocol.XMPP -> xmpp.joinChannel(networkId, channel)
        }
    }

    override suspend fun ensureQueryBuffer(networkId: Long, nick: String): Long =
        // Route BEFORE calling either impl: XmppConnectionManager.ensureQueryBuffer throws
        // IllegalArgumentException for a non-XMPP network id, so it must never see an IRC row.
        when (protocolOf(networkId)) {
            Protocol.IRC -> irc.ensureQueryBuffer(networkId, nick)
            Protocol.XMPP -> xmpp.ensureQueryBuffer(networkId, nick)
        }

    override suspend fun ensureServerBuffer(networkId: Long): Long =
        // Same IllegalArgumentException hazard as ensureQueryBuffer: route before delegating.
        when (protocolOf(networkId)) {
            Protocol.IRC -> irc.ensureServerBuffer(networkId)
            Protocol.XMPP -> xmpp.ensureServerBuffer(networkId)
        }

    override suspend fun sendMessage(
        bufferId: Long,
        text: String,
        replyToEventId: TimelineEventId?,
    ): SendAcceptance {
        val protocol = bufferProtocol(bufferId)
            ?: return SendAcceptance.Rejected(SendRejectionReason.BUFFER_NOT_FOUND)
        return when (protocol) {
            Protocol.IRC -> irc.sendMessage(bufferId, text, replyToEventId)
            // v1: XMPP has no reply support yet (XmppConnectionManager.sendMessage takes no
            // replyToEventId param); drop the reply target rather than rejecting the whole send.
            Protocol.XMPP -> xmpp.sendMessage(bufferId, text)
        }
    }

    override suspend fun sendTyping(bufferId: Long, state: String) {
        when (bufferProtocol(bufferId)) {
            Protocol.IRC -> irc.sendTyping(bufferId, state)
            Protocol.XMPP -> xmpp.sendTyping(bufferId, state)
            null -> Unit
        }
    }

    override suspend fun partChannel(bufferId: Long, reason: String?) {
        when (bufferProtocol(bufferId)) {
            Protocol.IRC -> irc.partChannel(bufferId, reason)
            Protocol.XMPP -> xmpp.partChannel(bufferId, reason)
            null -> Unit
        }
    }

    override suspend fun partChannelForClose(bufferId: Long, reason: String?): Boolean =
        when (bufferProtocol(bufferId)) {
            Protocol.IRC -> irc.partChannelForClose(bufferId, reason)
            // The durable-close retry/confirmation pipeline (PendingChannelCloseCoordinator et al.)
            // is IRC-only machinery. XMPP v1 has no equivalent durable-close concept, so a plain MUC
            // leave that completes without throwing is reported as a confirmed close.
            Protocol.XMPP -> {
                xmpp.partChannel(bufferId, reason)
                true
            }
            null -> false
        }

    // sendReact/requestMembers/markRead are IRC-only features (reactions, roster refresh, wire
    // read-marker sync) with nothing to route to on the XMPP side; XMPP buffers silently no-op.

    override suspend fun sendReact(bufferId: Long, msgid: String, emoji: String) {
        if (bufferProtocol(bufferId) == Protocol.IRC) irc.sendReact(bufferId, msgid, emoji)
    }

    override suspend fun requestMembers(bufferId: Long, force: Boolean) {
        if (bufferProtocol(bufferId) == Protocol.IRC) irc.requestMembers(bufferId, force)
    }

    override suspend fun markRead(bufferId: Long, anchor: TimelineAnchor) {
        // XMPP read anchors are local-only in v1 (no wire MARKREAD-equivalent wired up yet).
        if (bufferProtocol(bufferId) == Protocol.IRC) irc.markRead(bufferId, anchor)
    }

    // -- Unconditional IRC delegation: no XMPP counterpart exists for these yet. --

    override suspend fun retryMessage(eventId: TimelineEventId): SendAcceptance =
        // retryMessage takes an event id, not a buffer id, and `irc`'s byCanonicalId lookup DOES
        // resolve XMPP rows too (both protocols share one timeline table — the id spaces are not
        // disjoint). What actually makes unconditional delegation safe is the eligibility gate:
        // isGenericRetryEligible requires msgid == null, and every XMPP row always carries a msgid
        // (originId while pending, stanzaId once received), so an XMPP event is never retry-eligible
        // and `irc` returns EVENT_NOT_RETRYABLE for it. XMPP v1 has no retry path of its own.
        irc.retryMessage(eventId)

    override suspend fun acceptInvite(messageId: Long) = irc.acceptInvite(messageId)

    override suspend fun dismissInvite(messageId: Long) = irc.dismissInvite(messageId)

    override suspend fun evaluatePushMode() = irc.evaluatePushMode()

    override suspend fun trustCert(prompt: CertPrompt) = irc.trustCert(prompt)

    override fun dismissCertPrompt(prompt: CertPrompt) = irc.dismissCertPrompt(prompt)
}
