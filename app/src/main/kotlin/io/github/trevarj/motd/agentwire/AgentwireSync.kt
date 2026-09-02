package io.github.trevarj.motd.agentwire

import io.github.trevarj.motd.di.AppClock
import kotlinx.coroutines.delay
import java.util.UUID

internal const val AGENTWIRE_SYNC_RETRY_INITIAL_MS = 1_000L
internal const val AGENTWIRE_SYNC_RETRY_MAX_MS = 10_000L

/**
 * Hard ceiling on one user-visible handshake. Nothing inside the session may extend it, so the
 * "syncing" spinner is reachable for at most this long before a named failure replaces it.
 */
internal const val AGENTWIRE_SYNC_BUDGET_MS = 30_000L

/** Floor between consecutive `sync.request` sends, whatever triggered them. */
internal const val AGENTWIRE_SYNC_MIN_INTERVAL_MS = 500L

/** Consecutive failed writes that end the handshake instead of waiting out the budget. */
internal const val AGENTWIRE_SYNC_SEND_FAILURE_LIMIT = 3

/** What the channel's Agentwire handshake is currently doing, or why it stopped. */
sealed interface AgentwireSyncState {
    /** The gate is not ACTIVE, so no handshake is owed. */
    data object Idle : AgentwireSyncState

    /** The gate is ACTIVE but this device is not in the channel, so no session is started. */
    data object NotJoined : AgentwireSyncState

    data class Syncing(
        val attempt: Int,
        val startedAtMs: Long,
    ) : AgentwireSyncState

    data object Ready : AgentwireSyncState

    data class Failed(
        val failure: AgentwireSyncFailure,
    ) : AgentwireSyncState
}

/** The five distinguishable ways a handshake can end without state. */
sealed interface AgentwireSyncFailure {
    /** The budget expired with no correlated reply at all. */
    data class Timeout(
        val attempts: Int,
        val counters: IgnoreCounters,
    ) : AgentwireSyncFailure

    /** The bridge answered our sync id with `action.failed`: a definitive wire-level no. */
    data class Rejected(
        val detail: String,
    ) : AgentwireSyncFailure

    /** Repeated envelope validation failures from the trusted account within one handshake. */
    data class ProtocolMismatch(
        val detail: String,
    ) : AgentwireSyncFailure

    /** The request never reached the wire. */
    data class SendFailed(
        val detail: String,
    ) : AgentwireSyncFailure
}

/** Diagnostic journal component for everything in this package. */
internal const val AGENTWIRE_DIAGNOSTIC_COMPONENT = "agentwire"

internal fun AgentwireSyncFailure.endReason(): String =
    when (this) {
        is AgentwireSyncFailure.Timeout -> "timeout"
        is AgentwireSyncFailure.Rejected -> "rejected"
        is AgentwireSyncFailure.ProtocolMismatch -> "protocol"
        is AgentwireSyncFailure.SendFailed -> "send"
    }

/** Human phrasing for a send stage, used in the failure card. Classification only, no user data. */
internal fun sendFailureDetail(stage: String): String =
    when (stage) {
        "not_ready" -> "the connection is not ready"
        "caps" -> "the connection is missing a required capability"
        "client_tag" -> "the server does not allow this client tag"
        else -> "the write did not reach the server"
    }

/** Why a delivered Agentwire event did not advance state. Every silent path names itself. */
enum class IgnoreReason {
    PLAYBACK,
    NOT_PROTOCOL,
    TARGET_MISMATCH,
    MISSING_ACCOUNT,
    NO_TRUST_ANCHOR,
    CONTROLLER_EVENT,
    UNTRUSTED_ACCOUNT,
    FRAGMENT_PENDING,
    NOT_EVENT,

    /** Arrived before any correlated hello established the backend identity for this epoch. */
    UNCORRELATED_HELLO,

    /** A hello replying to a superseded sync id, i.e. the reply lost the race with the next retry. */
    STALE_REPLY,
    EPOCH_MISMATCH,
    FILTERED,
}

/** Per-attempt tally of [IgnoreReason]s, carried into the failure state as evidence. */
data class IgnoreCounters(
    val counts: Map<IgnoreReason, Int> = emptyMap(),
) {
    val total: Int get() = counts.values.sum()

    /** Diagnostic fields, one per non-zero counter, using the redaction-safe `ignored_*` prefix. */
    fun diagnosticFields(): Map<String, Int> = counts.filterValues { it > 0 }.mapKeys { (reason, _) -> "ignored_${reason.name.lowercase()}" }
}

/** What made the client enter or re-enter a handshake; recorded verbatim as a diagnostic field. */
enum class AgentwireSyncTrigger(
    val wireName: String,
) {
    OPEN("open"),
    RETRY("retry"),
    REJOIN("rejoin"),
    IDENTITY("identity"),
    RESYNC_GAP("resync_gap"),
    RESYNC_FRAGMENT("resync_fragment"),
    RESYNC_EPOCH("resync_epoch"),
}

/** Why the coordinator abandoned the current epoch. */
enum class AgentwireResyncCause(
    val wireName: String,
) {
    GAP("gap"),
    FRAGMENT_EXPIRY("fragment_expiry"),
    EPOCH("epoch"),
}

/**
 * Deadline and send pacing for one user-visible handshake.
 *
 * [anchor] is called only when the user visibly (re-)enters sync: the screen opens, the user taps
 * retry, the channel is rejoined, or the topic identity changes. Internal resynchronisations
 * restart the retry job against the *same* deadline, so a bridge that keeps forcing resyncs cannot
 * walk the budget forward and reopen the unbounded spinner through the back door.
 */
internal class AgentwireSyncBudget(
    private val clock: AppClock,
    private val budgetMs: Long = AGENTWIRE_SYNC_BUDGET_MS,
) {
    private var deadlineAtMs = Long.MIN_VALUE
    private var lastSendAtMs: Long? = null

    var startedAtMs: Long = 0L
        private set

    /** Sends issued since the last [anchor], across every retry-job restart in this window. */
    var attempts: Int = 0
        private set

    fun anchor() {
        startedAtMs = clock.nowMillis()
        deadlineAtMs = startedAtMs + budgetMs
        attempts = 0
    }

    fun expired(): Boolean = clock.nowMillis() >= deadlineAtMs

    fun remainingMs(): Long = (deadlineAtMs - clock.nowMillis()).coerceAtLeast(0L)

    fun elapsedMs(): Long = (clock.nowMillis() - startedAtMs).coerceAtLeast(0L)

    fun sendFloorMs(): Long =
        lastSendAtMs
            ?.let { (AGENTWIRE_SYNC_MIN_INTERVAL_MS - (clock.nowMillis() - it)).coerceAtLeast(0L) }
            ?: 0L

    fun recordSend(): Int {
        lastSendAtMs = clock.nowMillis()
        attempts += 1
        return attempts
    }
}

/**
 * Issues `sync.request` until the session reports itself synchronised, the budget expires, or the
 * writes stop reaching the wire. Every exit is terminal and named; the loop cannot run forever.
 */
internal suspend fun retryAgentwireSync(
    budget: AgentwireSyncBudget,
    isReady: () -> Boolean,
    issue: suspend (String) -> Boolean,
    onAttempt: (Int) -> Unit = {},
    onTimeout: suspend () -> Unit = {},
    onSendFailed: suspend (Int) -> Unit = {},
    nextId: () -> String = { UUID.randomUUID().toString() },
    pause: suspend (Long) -> Unit = { delay(it) },
) {
    var retryDelay = AGENTWIRE_SYNC_RETRY_INITIAL_MS
    var consecutiveFailures = 0
    while (!isReady()) {
        val floor = budget.sendFloorMs()
        if (floor > 0L) {
            if (!waitWithinBudget(budget, floor, isReady, onTimeout, pause)) return
        } else if (budget.expired()) {
            onTimeout()
            return
        }
        onAttempt(budget.recordSend())
        if (issue(nextId())) {
            consecutiveFailures = 0
        } else {
            consecutiveFailures += 1
            if (consecutiveFailures >= AGENTWIRE_SYNC_SEND_FAILURE_LIMIT) {
                onSendFailed(consecutiveFailures)
                return
            }
        }
        if (isReady()) return
        if (!waitWithinBudget(budget, retryDelay, isReady, onTimeout, pause)) return
        retryDelay = (retryDelay * 2).coerceAtMost(AGENTWIRE_SYNC_RETRY_MAX_MS)
    }
}

/**
 * Sleeps at most [requested] ms and never past the deadline, so the timeout fires exactly at the
 * budget rather than one backoff step late. False means the caller must stop looping.
 */
private suspend fun waitWithinBudget(
    budget: AgentwireSyncBudget,
    requested: Long,
    isReady: () -> Boolean,
    onTimeout: suspend () -> Unit,
    pause: suspend (Long) -> Unit,
): Boolean {
    val remaining = budget.remainingMs()
    if (remaining <= 0L) {
        onTimeout()
        return false
    }
    pause(minOf(requested, remaining))
    if (isReady()) return false
    if (budget.expired()) {
        onTimeout()
        return false
    }
    return true
}
