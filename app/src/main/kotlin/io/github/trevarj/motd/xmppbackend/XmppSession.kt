package io.github.trevarj.motd.xmppbackend

import io.github.trevarj.motd.data.db.XmppAccountEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Session-local connection lifecycle for one [XmppSession] attempt (docs/backend-neutral-xmpp-rollout.md
 * "PR 2"). Deliberately local to `xmppbackend` rather than reusing an `:irc` type — [XmppAccountActor]
 * and [XmppConnectionManager] map this onto [io.github.trevarj.motd.backend.ConnectionState] without
 * ever seeing a wire/session library type. Shaped after that neutral contract's phases, but without
 * [io.github.trevarj.motd.backend.ConnectionState.Ready]'s `generation`/`negotiationRevision`: those
 * are manager-assigned session identity, not something a bare session knows about itself.
 */
sealed interface XmppSessionState {
    data object Disconnected : XmppSessionState

    /** Transport/TLS being established. */
    data object Connecting : XmppSessionState

    /** Transport up; SASL negotiation in progress. */
    data object Authenticating : XmppSessionState

    /** Authenticated. [bareJid] is the server-confirmed own address (user@domain). */
    data class Ready(val bareJid: String) : XmppSessionState

    /** [fatal] = do not auto-retry (e.g. rejected credentials). */
    data class Failed(val reason: String, val fatal: Boolean) : XmppSessionState
}

/**
 * One incoming direct-message stanza observed on a live [XmppSession] (docs/backend-neutral-xmpp-rollout.md
 * "PR 2", slice X4). Deliberately minimal: only what a 1:1 DM needs. MUC occupant/subject/message
 * events (slice X5; see [XmppIncomingMucMessage], [XmppMucSubject], [XmppMucOccupantEvent]) and
 * XEP-0280 carbons (a later slice) extend this seam with their own event shapes rather than
 * reshaping this one.
 */
data class XmppIncomingMessage(
    /** Bare JID (user@domain) of the sender, already resource-stripped by the session layer. */
    val fromBareJid: String,
    val body: String,
    /** Sender-supplied stanza `id` attribute (RFC 6120), not a server/archive-assigned identifier.
     *  Absent for some clients/servers — never invented when missing. */
    val stanzaId: String?,
    /** Wall-clock ms from an XEP-0203 (or legacy XEP-0091) delay stamp, when the stanza carried one
     *  (e.g. offline-storage redelivery); null means "observed at receipt time". */
    val delayStampMillis: Long?,
    /**
     * True when this stanza is a reflection of this account's own message (e.g. an XEP-0280 carbon)
     * rather than a peer's DM. Carbons are not wired until a later slice, so every emission from
     * [SmackXmppSession] sets this false today; the field exists now so that slice reshapes the
     * processor's dispatch, not this model.
     */
    val isCarbonOrSelf: Boolean = false,
)

/**
 * One MUC (groupchat) message observed on a live [XmppSession] (docs/backend-neutral-xmpp-rollout.md
 * "PR 2", slice X5). Mirrors [XmppIncomingMessage]'s shape but keyed by the room's bare JID plus the
 * sending occupant's in-room nickname rather than a peer's bare JID: real JIDs are not visible in a
 * semi-anonymous room, so the nickname is the only identity a MUC message carries.
 */
data class XmppIncomingMucMessage(
    /** Bare JID (room@service) of the room. */
    val roomBareJid: String,
    /** The sending occupant's in-room nickname (an XMPP resourcepart) — never a real JID. */
    val occupantNick: String,
    val body: String,
    /** Sender-supplied stanza `id`, exactly like [XmppIncomingMessage.stanzaId] — never invented
     *  when the stanza carries none. */
    val stanzaId: String?,
    /** Wall-clock ms from a delay stamp, exactly like [XmppIncomingMessage.delayStampMillis]. */
    val delayStampMillis: Long?,
    /**
     * True when [occupantNick] is this session's own nickname in this room. A MUC reflects every
     * accepted message back to its sender with the same occupant nick, so this — not a JID
     * comparison, which a semi-anonymous room cannot support — is how "did I send this" is known.
     * Computed by the session (the only party that knows what nick it joined each room with; see
     * [XmppSession.joinRoom]), never re-derived by the processor.
     */
    val isSelf: Boolean,
)

/**
 * A MUC subject (topic) observation (slice X5). [byNick] is the occupant who set it; null when the
 * room supplies a subject with no attributable occupant (e.g. the informational subject some
 * servers replay on join).
 */
data class XmppMucSubject(
    val roomBareJid: String,
    val subject: String,
    val byNick: String?,
)

/**
 * Occupant roster deltas/snapshots for one joined MUC (slice X5) — the MUC counterpart of an IRC
 * NAMES reply plus live JOIN/PART. [Snapshot] arrives exactly once per successful
 * [XmppSession.joinRoom] (and again on [XmppSession.refreshOccupants]), listing every occupant
 * present at that moment, including this session's own nickname; [Joined]/[Left] arrive only for
 * occupants who arrive/depart afterwards. The initial roster is never reported through
 * [Joined]: the session defers registering its live occupant listeners until after the join
 * snapshot is captured (carried over from the fork/xmpp-support prototype's join-listener-ordering
 * fix, which otherwise floods the timeline with a false JOIN per pre-existing member on a busy room).
 */
sealed interface XmppMucOccupantEvent {
    val roomBareJid: String

    data class Snapshot(override val roomBareJid: String, val nicks: List<String>) : XmppMucOccupantEvent
    data class Joined(override val roomBareJid: String, val nick: String) : XmppMucOccupantEvent
    data class Left(override val roomBareJid: String, val nick: String) : XmppMucOccupantEvent
}

/** One roster (buddy-list) contact, as surfaced by [XmppSession.rosterLoad]. */
data class XmppRosterContact(val bareJid: String, val name: String?)

/**
 * Roster (buddy-list) load outcome for one connection attempt (slice X5). Surfaced exactly once per
 * session, some time after [XmppSessionState.Ready] — not a live presence/subscription-change
 * stream: this baseline narrows the fork/xmpp-support prototype's continuous `RosterListener`-driven
 * updates down to a single load-outcome signal per connection (docs/backend-neutral-xmpp-rollout.md
 * "PR 2" baseline scope names "roster loading", not live roster sync). A live-updates feature can
 * extend this seam later without reshaping it.
 */
sealed interface XmppRosterLoad {
    data class Loaded(val contacts: List<XmppRosterContact>) : XmppRosterLoad
    data class Failed(val reason: String) : XmppRosterLoad
}

/**
 * Which wire stanza shape [XmppSession.sendMessage] must use for one send (review fix, P1 finding).
 * Decided by the CALLER from the buffer's own type — [XmppConnectionManager.writeAndTrack] passes
 * [GROUPCHAT] for a CHANNEL buffer and [CHAT] for a QUERY buffer — never inferred by the session
 * from its own live join bookkeeping, which is empty on every fresh session and briefly stale mid
 * reconnect. See [XmppSession.sendMessage]'s KDoc for the full rationale.
 */
enum class XmppSendKind { CHAT, GROUPCHAT }

/**
 * Protocol seam over one XMPP connection attempt. Models the fork/xmpp-support prototype's
 * `XmppSession` abstraction (connect/login, per-room/message operations, teardown), reshaped to this
 * package's event/state vocabulary: transport/TLS/SASL lifecycle, the incoming-DM stream (slice X4),
 * and MUC join/leave/occupants/subjects/messages plus one-shot roster loading (slice X5).
 *
 * One instance = one connection attempt; [XmppAccountActor] creates a fresh [XmppSession] per
 * (re)connect, exactly like the prototype and like `:irc`'s `ManagedConnection`/`IrcClient` pairing.
 */
interface XmppSession {
    /** Current lifecycle phase; a fresh session starts at [XmppSessionState.Disconnected]. */
    val state: StateFlow<XmppSessionState>

    /**
     * Incoming direct-message stream for this connection attempt. A hot, buffered flow rather than
     * a suspending channel: [SmackXmppSession] feeds it from Smack's synchronous listener callback,
     * which cannot suspend, so delivery is best-effort past the buffer (acceptable for this baseline
     * slice — durable, gap-free delivery arrives with the MAM/carbons/stream-resumption follow-ups in
     * docs/backend-neutral-xmpp-rollout.md). [XmppAccountActor] attaches its collector before calling
     * [connect], so nothing arriving right after Ready races the subscription.
     */
    val incomingMessages: Flow<XmppIncomingMessage>

    /**
     * Incoming 1:1 XEP-0085 chat-state stream for this connection attempt (slice X6); see
     * [XmppIncomingChatState]. Same hot/buffered-flow rationale as [incomingMessages] — fed from the
     * same synchronous Smack listener callback that feeds it, since a chat-state-only stanza (no
     * `<body/>`) is exactly the kind [incomingMessages]' listener already has to see and skip.
     */
    val incomingChatStates: Flow<XmppIncomingChatState>

    /** MUC messages across every room this session has joined (slice X5); see
     *  [XmppIncomingMucMessage]. Same hot/buffered-flow rationale as [incomingMessages]. */
    val incomingMucMessages: Flow<XmppIncomingMucMessage>

    /** MUC subject changes across every joined room (slice X5); see [XmppMucSubject]. */
    val mucSubjects: Flow<XmppMucSubject>

    /** MUC occupant snapshots/joins/leaves across every joined room (slice X5); see
     *  [XmppMucOccupantEvent]. */
    val mucOccupants: Flow<XmppMucOccupantEvent>

    /**
     * This connection attempt's roster load outcome (slice X5); see [XmppRosterLoad]. Emits at most
     * once — a session that never reaches [XmppSessionState.Ready] never emits at all.
     */
    val rosterLoad: Flow<XmppRosterLoad>

    /**
     * Establish the transport, negotiate TLS, and SASL-authenticate, publishing [state] transitions
     * (Connecting -> Authenticating -> Ready/Failed) along the way. Never throws except
     * [kotlinx.coroutines.CancellationException]: every transport/TLS/SASL failure is translated to
     * [XmppSessionState.Failed] on [state] so callers branch only on the sealed state, never on a
     * session-library exception type.
     *
     * Returns once the attempt reaches a stable outcome (Ready or Failed). A later drop out of Ready
     * (server-initiated close, socket error) is published to [state] asynchronously, without a
     * further [connect] call — callers observe it by continuing to watch [state].
     */
    suspend fun connect()

    /** Tear down the connection and release resources. Safe to call from any state, more than once. */
    suspend fun disconnect()

    /**
     * Join the MUC at [bareRoomJid] presenting [nick]. On success, publishes exactly one
     * [XmppMucOccupantEvent.Snapshot] on [mucOccupants]. Never throws except
     * [kotlinx.coroutines.CancellationException]: a rejected/timed-out join (bad JID, banned,
     * nickname conflict, gateway timeout) is logged and produces no snapshot rather than surfacing a
     * wire exception. This baseline has no dedicated join-failure signal — callers can only infer
     * success by whether a [XmppMucOccupantEvent.Snapshot] for that room ever arrives; a later slice
     * can add an explicit failure event without reshaping this contract.
     */
    suspend fun joinRoom(bareRoomJid: String, nick: String)

    /** Leave a previously joined room and stop delivering its events. Safe to call even if never
     *  joined, and more than once. */
    suspend fun leaveRoom(bareRoomJid: String)

    /** Re-publish the current occupant list for an already-joined room as a fresh
     *  [XmppMucOccupantEvent.Snapshot]. A no-op if [bareRoomJid] is not currently joined. */
    suspend fun refreshOccupants(bareRoomJid: String)

    /**
     * Send one chat message (docs/backend-neutral-xmpp-rollout.md, slice X6) to [to] — a contact's
     * bare JID for a 1:1 message, or a joined room's bare JID for a MUC message. [kind] says which
     * stanza shape to use (review fix — P1: this used to be inferred from whether [to] was a room
     * this *same session* currently has joined, via the implementation's own `roomListeners`/MUC
     * bookkeeping. That bookkeeping is empty on every fresh session and briefly stale during a
     * reconnect's rejoin, so a CHANNEL send in that window silently went out as a one-to-one `chat`
     * stanza addressed at the room JID instead of failing loudly. The CALLER — [XmppConnectionManager],
     * which already resolved the buffer and therefore its type — decides instead, so a CHANNEL buffer
     * never produces a `chat` stanza: a send to a room this session has not actually joined now
     * either succeeds as a real groupchat send or throws (Smack's `MucNotJoinedException` for a room
     * with no live join), which the caller's existing wire-write catch already turns into a durable
     * failure rather than a silent misrouting.
     *
     * [messageId] becomes the outgoing stanza's `id` attribute verbatim: the caller (a fresh label
     * from [XmppProcessor.persistOutgoingSend]/[XmppProcessor.beginRetry]) uses it to correlate this
     * send with its durable pending row — for a DM, only to log/diagnose, since a DM's own send is
     * never echoed back in this baseline (no carbons yet); for a MUC, this is load-bearing: the room
     * reflects the accepted message back to every occupant including the sender, and that reflection
     * is expected to carry the same id back as [XmppIncomingMucMessage.stanzaId], which is exactly
     * how [XmppConnectionManager] recognizes "this is the echo of my own pending send" and reconciles
     * it (see that class's send pipeline).
     *
     * Unlike every other method on this interface, this one is NOT documented as swallowing
     * failures: a transport/stanza-write exception (other than
     * [kotlinx.coroutines.CancellationException], which always propagates) is allowed to propagate
     * to the caller, which needs to know the wire write did not go out so it can fail the durable
     * row immediately instead of leaving it pending with nothing left to resolve it.
     */
    suspend fun sendMessage(to: String, body: String, messageId: String, kind: XmppSendKind)

    /**
     * Publish a XEP-0085 chat-state notification to [toBareJid] (docs/backend-neutral-xmpp-rollout.md,
     * slice X6) — 1:1 only; MUC typing is out of this baseline's scope (see
     * [XmppConnectionManager.sendTyping]'s QUERY-only guard, its only caller). Best-effort exactly
     * like [joinRoom]/[refreshOccupants]: a transport failure (other than
     * [kotlinx.coroutines.CancellationException]) is logged and swallowed rather than thrown — there
     * is no durable row backing a typing ping, so there is nothing for a caller to retry or report.
     */
    suspend fun sendChatState(toBareJid: String, state: XmppChatState)
}

/**
 * The full XEP-0085 chat-state vocabulary (docs/backend-neutral-xmpp-rollout.md, slice X6: "one-to-one
 * typing where supported"). Bidirectional: [XmppConnectionManager.sendTyping] only ever *sends*
 * COMPOSING/PAUSED/ACTIVE (the seam's own typing vocabulary — mirrored from `:irc`'s `+typing` client
 * tag — is only "active"/"paused"/"done", which map onto exactly those three), but an incoming
 * notification (see [XmppIncomingChatState]) can legitimately carry any of the five, so this type
 * covers the whole XEP rather than only the outgoing subset.
 */
enum class XmppChatState { COMPOSING, PAUSED, ACTIVE, INACTIVE, GONE }

/**
 * One incoming XEP-0085 chat-state notification observed on a live [XmppSession] (docs/backend-neutral-xmpp-rollout.md,
 * slice X6) — the incoming half of one-to-one typing; [XmppSession.sendChatState] is the outgoing
 * half. Deliberately shaped like [XmppIncomingMessage]: a bare-JID-keyed 1:1 signal, with the same
 * [isCarbonOrSelf] escape hatch and the same "not wired until carbons land" rationale.
 */
data class XmppIncomingChatState(
    /** Bare JID (user@domain) of the sender, already resource-stripped by the session layer. */
    val fromBareJid: String,
    val state: XmppChatState,
    /** True when this stanza is a reflection of this account's own chat state rather than a peer's.
     *  Carbons are not wired until a later slice, so every emission from [SmackXmppSession] sets this
     *  false today; the field exists now so that slice reshapes the processor's dispatch, not this
     *  model — exactly like [XmppIncomingMessage.isCarbonOrSelf]. */
    val isCarbonOrSelf: Boolean = false,
)

/** Builds one [XmppSession] attempt from a persisted XMPP account row. */
fun interface XmppSessionFactory {
    fun create(account: XmppAccountEntity): XmppSession
}
