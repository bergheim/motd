package io.github.trevarj.motd.service

import io.github.trevarj.motd.backend.ConnectionState
import io.github.trevarj.motd.backend.ReactionCapability
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.TimelineEventId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

enum class DeliveryMode { PERSISTENT_SOCKET, UNIFIED_PUSH }
enum class SendRejectionReason {
    BUFFER_NOT_FOUND,
    INVALID_CONTENT,
    UNSUPPORTED_BUFFER,
    EVENT_NOT_RETRYABLE,
    PERSISTENCE_FAILED,
    NOT_IN_CHANNEL,
}

enum class ImmediateWireAcceptance {
    ACCEPTED,
    DISCONNECTED,
    FAILED,
}

sealed interface SendAcceptance {
    data class Accepted(
        val eventIds: List<TimelineEventId>,
        val immediateWireAcceptance: ImmediateWireAcceptance = ImmediateWireAcceptance.ACCEPTED,
    ) : SendAcceptance
    data class Rejected(val reason: SendRejectionReason) : SendAcceptance
}
enum class RosterLoadState { NOT_LOADED, LOADING, LOADED, FAILED }
enum class PresenceState { UNKNOWN, ONLINE, OFFLINE }
data class PresenceKey(val networkId: Long, val normalizedNick: String)

/** Ephemeral, target-keyed server rejection for a browser-initiated JOIN. */
sealed interface ChannelJoinOutcome {
    data class Rejected(
        val networkId: Long,
        val channel: String,
        val reason: String,
    ) : ChannelJoinOutcome
}

internal fun rosterStateAfterNames(explicitRefreshInFlight: Boolean): RosterLoadState =
    if (explicitRefreshInFlight) RosterLoadState.LOADING else RosterLoadState.LOADED

internal fun rosterStateAfterExplicitRefresh(completed: Boolean): RosterLoadState =
    if (completed) RosterLoadState.LOADED else RosterLoadState.FAILED

private val EMPTY_MEMBER_LOAD_STATES: StateFlow<Map<Long, RosterLoadState>> = MutableStateFlow(emptyMap())
private val EMPTY_PRESENCE_STATES: StateFlow<Map<PresenceKey, PresenceState>> = MutableStateFlow(emptyMap())
private val EMPTY_LAG_STATES: StateFlow<Map<Long, Long?>> = MutableStateFlow(emptyMap())
data class ConnectionActivitySnapshot(
    val states: Map<Long, ConnectionState> = emptyMap(),
    val progressing: Map<Long, Boolean> = emptyMap(),
    val initializationComplete: Boolean = true,
    val historyCatchUpPending: Set<Long> = emptySet(),
)

private val EMPTY_CONNECTION_ACTIVITY = MutableStateFlow(ConnectionActivitySnapshot())
private val EMPTY_SERVER_PUSH: StateFlow<Boolean> = MutableStateFlow(false)
private val EMPTY_ATTACHMENT_ENDPOINTS: StateFlow<Map<Long, String>> = MutableStateFlow(emptyMap())
private val EMPTY_REACTION_CAPABILITIES: StateFlow<Map<Long, ReactionCapability>> = MutableStateFlow(emptyMap())

/**
 * A pending TOFU cert-trust decision surfaced to the UI (plans/12). Published when a TLS handshake
 * hit an untrusted (self-signed / bare-IP / changed) leaf certificate. [changed] = true means a
 * previously-pinned cert now differs (possible MITM or rotation) and warrants a warning.
 */
data class CertPrompt(
    val networkId: Long,
    val host: String,
    val port: Int,
    val sha256: String,            // lowercase hex of the presented leaf cert
    val subject: String,
    val issuer: String,
    val notBefore: Long,           // epoch ms
    val notAfter: Long,            // epoch ms
    val changed: Boolean,
)

interface ConnectionManager {
    /** Connection state per network row id, in the backend-neutral lifecycle vocabulary. */
    val connectionStates: StateFlow<Map<Long, ConnectionState>>
    /** Atomically published connection state, actor liveness, and initial-reconcile readiness. */
    val connectionActivity: StateFlow<ConnectionActivitySnapshot> get() = EMPTY_CONNECTION_ACTIVITY

    /**
     * Member-list load state per BUFFER row id — IRC channel NAMES, XMPP MUC occupants. Never
     * keyed by network id: the map's keyspace is buffer ids across every backend, unioned by the
     * composite (pinned after Branch-2 feedback, docs/backend-neutral-xmpp-rollout.md).
     */
    val memberLoadStates: StateFlow<Map<Long, RosterLoadState>> get() = EMPTY_MEMBER_LOAD_STATES
    val presenceStates: StateFlow<Map<PresenceKey, PresenceState>> get() = EMPTY_PRESENCE_STATES
    /** Latest PING/PONG round-trip latency (ms) per network id; null = unknown/disconnected (#34). */
    val lagStates: StateFlow<Map<Long, Long?>> get() = EMPTY_LAG_STATES
    val channelJoinOutcomes: Flow<ChannelJoinOutcome> get() = emptyFlow()

    /** True while any connected network accepts server-side push registration. */
    val serverPushAvailable: StateFlow<Boolean> get() = EMPTY_SERVER_PUSH

    /** networkId -> attachment upload endpoint, present only while the network offers one. */
    val attachmentUploadEndpoints: StateFlow<Map<Long, String>> get() = EMPTY_ATTACHMENT_ENDPOINTS

    /** networkId -> reaction sendability; absent means reactions are unavailable right now. */
    val reactionCapabilities: StateFlow<Map<Long, ReactionCapability>> get() = EMPTY_REACTION_CAPABILITIES

    /**
     * Live negotiated identity rules for a network, null when no live session exists. Callers keep
     * their persisted fallback so offline normalization behavior never changes
     * (docs/backend-neutral-xmpp-rollout.md). The value type stays [IrcIdentityRules] until the
     * canonical participant-identity model is neutralized.
     */
    fun liveIdentityRules(networkId: Long): IrcIdentityRules? = null

    /**
     * Live server-history availability for a network, null when no live session exists. The
     * :irc [HistoryAvailability] shape (reference types, page limit) stays on this contract only
     * until the neutral history boundary lands (docs/backend-neutral-xmpp-rollout.md).
     */
    fun historyAvailability(networkId: Long): HistoryAvailability? = null

    /** Start/stop the whole subsystem (invoked by service / delivery-mode changes). */
    suspend fun startAll()
    suspend fun stopAll()
    suspend fun connect(networkId: Long)
    suspend fun disconnect(networkId: Long)

    /**
     * Re-drive the wanted set and revive any actor that died/parked in the background (Doze/network
     * drop leaves it terminally Failed with a completed job). Canonical app-foreground reconnect,
     * invoked from ProcessLifecycleOwner's onStart. Ready actors receive one watchdog-style
     * liveness probe and are only restarted when the probe times out; healthy/connecting/retrying/
     * cert-parked actors otherwise remain untouched. Requests are conflated, so repeated lifecycle
     * callbacks cannot storm reconnects.
     */
    suspend fun reconnectStale()

    /** Accepted means every chunk is durably represented, not necessarily written to the wire. */
    suspend fun sendMessage(
        bufferId: Long,
        text: String,
        replyToEventId: TimelineEventId? = null,
    ): SendAcceptance

    /** Retry the same durable row with a new attempt label. */
    suspend fun retryMessage(eventId: TimelineEventId): SendAcceptance =
        SendAcceptance.Rejected(SendRejectionReason.EVENT_NOT_RETRYABLE)
    suspend fun sendTyping(bufferId: Long, state: String)
    suspend fun sendReact(bufferId: Long, msgid: String, emoji: String)
    suspend fun joinChannel(networkId: Long, channel: String)

    /** Atomically claim a persisted invitation, connect if needed, then send exactly one JOIN. */
    suspend fun acceptInvite(messageId: Long) = Unit

    /** Resolve a persisted invitation without joining. */
    suspend fun dismissInvite(messageId: Long) = Unit

    /** Explicit lazy roster refresh; duplicate callers share the same in-flight request. */
    suspend fun requestMembers(bufferId: Long, force: Boolean = false) = Unit

    /** Part the buffer's channel; [reason] (from `/part <reason>`) becomes the PART trailing param. */
    suspend fun partChannel(bufferId: Long, reason: String? = null)

    /**
     * PART seam used by durable channel-close requests. Returns true only when the connection
     * boundary confirms that the write reached its live transport. The default keeps existing
     * test/fake implementations source-compatible; the real manager overrides it with a strict
     * Ready/transport check.
     */
    suspend fun partChannelForClose(bufferId: Long, reason: String? = null): Boolean = try {
        partChannel(bufferId, reason)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    /**
     * Write a channel TOPIC command. True means the live transport accepted the write; it does
     * not mean the server authorized or echoed the change. Room is updated only by the IRC echo.
     * The default is deliberately conservative so lightweight fakes remain disconnected unless
     * they opt into an accepted write.
     */
    suspend fun setChannelTopic(bufferId: Long, topic: String): Boolean = false

    /** Find-or-create a QUERY buffer for a DM (name Isupport-normalized); returns bufferId. */
    suspend fun ensureQueryBuffer(networkId: Long, nick: String): Long

    /** Find-or-create the per-network SERVER buffer (name "*", displayName = network name);
     *  returns bufferId. UI entry for the server-messages timeline (plans/16). */
    suspend fun ensureServerBuffer(networkId: Long): Long

    /** Advance the exact local anchor; wire MARKREAD uses an authoritative boundary at/before it. */
    suspend fun markRead(bufferId: Long, anchor: TimelineAnchor)

    /** Re-evaluate push-mode socket teardown after per-network endpoint changes.
     *  No-op unless deliveryMode == UNIFIED_PUSH. Called by MotdPushReceiver.onNewEndpoint. */
    suspend fun evaluatePushMode()

    // -- TOFU cert trust (plans/12) --

    /** Pending cert-trust prompts (deduped by networkId). Observed by the global dialog host. */
    val certPrompts: StateFlow<List<CertPrompt>>

    /** Trust: pin the leaf SHA-256, drop the prompt, and reconnect that network. */
    suspend fun trustCert(prompt: CertPrompt)

    /** Dismiss: drop the prompt; the network stays disconnected until manually reconnected. */
    fun dismissCertPrompt(prompt: CertPrompt)
}

/**
 * In-memory typing state. Read by ChatViewModel; written by each backend's processor through the
 * neutral write contract (pinned after Branch-2 feedback, docs/backend-neutral-xmpp-rollout.md).
 */
interface TypingTracker {
    fun typingNicks(bufferId: Long): StateFlow<List<String>>

    /** Apply a typing state ("active" | "paused" | "done") for [actor] in [bufferId]. */
    fun onTyping(bufferId: Long, actor: String, state: String)
}

/** Buffer currently visible in the foreground UI. Set by ChatViewModel (WP7), read by the
 *  notification suppression logic (WP5). WP1 provides the trivial impl (a MutableStateFlow). */
interface ForegroundBufferTracker {
    val foregroundBufferId: StateFlow<Long?>
    fun set(bufferId: Long?)
}
