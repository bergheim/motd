package io.github.trevarj.motd.ui.onboarding

import io.github.trevarj.motd.bouncer.BouncerKind
import io.github.trevarj.motd.bouncer.SojuLoginForm
import io.github.trevarj.motd.bouncer.ZncLoginForm
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.backend.ConnectionState
import io.github.trevarj.motd.ui.settings.addnetwork.NetworkPresetId
import io.github.trevarj.motd.ui.settings.addnetwork.applyNetworkPreset
import io.github.trevarj.motd.ui.settings.addnetwork.networkPreset

/**
 * Pure state machine for the onboarding wizard. No Android/Compose/coroutine dependencies so the
 * whole flow is unit-testable via [onboardingReducer]. The ViewModel owns the side effects
 * (creating the NetworkEntity, connecting, listing bouncer networks) and folds their results back
 * in through actions.
 */

/** The wizard pages, in pager order. */
enum class OnboardingStep {
    WELCOME,
    CHOICE,
    SERVER,
    AUTH,
    CONNECT,
    FINISH,
}

/** Top-level path chosen on the CHOICE page. */
enum class ConnectionChoice { BOUNCER, NETWORK }

/** Auth mechanism selected on the AUTH page. Mirrors SaslMechanism names for persistence. */
enum class AuthMode { NONE, PLAIN, EXTERNAL }

/** Default IRC ports: 6697 for TLS, 6667 for plaintext. */
const val PORT_TLS = "6697"
const val PORT_PLAIN = "6667"

/** Editable server-form fields (step 3). */
data class ServerForm(
    val host: String = "",
    val port: String = PORT_TLS,
    val tls: Boolean = true,
    val nick: String = "",
    val username: String = "",
    val realname: String = "",
) {
    /** Effective username: explicit value, else falls back to nick (spec default). */
    val effectiveUsername: String get() = username.ifBlank { nick }

    /** True when [port] holds the default value for either TLS state (so a toggle may re-default it). */
    val portIsDefault: Boolean get() = port.isBlank() || port == PORT_TLS || port == PORT_PLAIN

    /**
     * Toggle TLS and re-default the port when the user hasn't typed a custom one, so 6697/6667
     * track the switch without clobbering an explicit port.
     */
    fun withTls(enabled: Boolean): ServerForm = copy(
        tls = enabled,
        port = if (portIsDefault) (if (enabled) PORT_TLS else PORT_PLAIN) else port,
    )

    /**
     * SERVER-step validity for both paths: host, a valid port, and a nick. The soju root now
     * collects a nick too (it is the IRC NICK the bouncer registers with); its bouncer SASL
     * username/password are gathered on the AUTH step.
     */
    val isValid: Boolean
        get() = hostAndPortValid && nick.isNotBlank()

    /** Transport-only validity (host + valid port), independent of identity. */
    val hostAndPortValid: Boolean
        get() = host.isNotBlank() &&
            port.toIntOrNull()?.let { it in 1..65535 } == true
}

/** Auth-form fields (step 4). */
data class AuthForm(
    val mode: AuthMode = AuthMode.NONE,
    val saslUser: String = "",
    val saslPassword: String = "",
    val certAlias: String? = null,
    val serverPassword: String = "",
) {
    val serverPasswordValid: Boolean
        get() = serverPassword.none { it == '\r' || it == '\n' } &&
            serverPassword.toByteArray(Charsets.UTF_8).size <= MAX_SERVER_PASSWORD_BYTES

    val isValid: Boolean
        get() = serverPasswordValid && when (mode) {
            AuthMode.NONE -> true
            AuthMode.PLAIN -> saslUser.isNotBlank() && saslPassword.isNotBlank()
            AuthMode.EXTERNAL -> certAlias != null
        }
}

/** Leaves room for `PASS :`, CRLF, and the IRC 512-byte non-tag message limit. */
private const val MAX_SERVER_PASSWORD_BYTES = 504

/** A bouncer network row on the CONNECT page (soju import list / add form results). */
data class BouncerNetworkRow(
    val netId: String,
    val name: String,
    val selected: Boolean,
)

/** LISTNETWORKS request state. Rows remain available while a refresh or retry is in flight. */
sealed interface BouncerDiscoveryState {
    data class Loading(val rows: List<BouncerNetworkRow> = emptyList()) : BouncerDiscoveryState
    data class Loaded(val rows: List<BouncerNetworkRow>) : BouncerDiscoveryState
    data class Failed(
        val error: BouncerOperationError,
        val rows: List<BouncerNetworkRow> = emptyList(),
    ) : BouncerDiscoveryState
}

/** ADDNETWORK is deliberately independent from discovery: a passive LIST must not hide its error. */
sealed interface BouncerAddState {
    data object Idle : BouncerAddState
    data object Submitting : BouncerAddState
    data class Failed(val error: BouncerOperationError) : BouncerAddState
    data object Success : BouncerAddState
}

data class BouncerAddDraft(
    val name: String = "",
    val host: String = "",
) {
    val isValid: Boolean get() = name.isNotBlank() && host.isNotBlank()
}

/** Full wizard state. */
data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val choice: ConnectionChoice? = null,
    val bouncerKind: BouncerKind = BouncerKind.SOJU,
    val server: ServerForm = ServerForm(),
    /** Direct-network authentication draft; bouncer credentials are kept separately. */
    val auth: AuthForm = AuthForm(),
    val presetId: NetworkPresetId = NetworkPresetId.CUSTOM,
    val showPlaintextWarning: Boolean = false,
    val plaintextConfirmed: Boolean = false,
    val sojuLogin: SojuLoginForm = SojuLoginForm(),
    val zncLogin: ZncLoginForm = ZncLoginForm(),
    // Connect-test progress.
    val networkId: Long? = null,
    val connState: ConnectionState? = null,
    val stateLog: List<ConnectionState> = emptyList(),
    val bouncerDiscovery: BouncerDiscoveryState? = null,
    /** Monotonic root-session identity; changes only when the onboarding root is replaced. */
    val bouncerSessionGeneration: Long = 0L,
    /** Token for the current LIST attempt; independent ADD work remains valid across refresh. */
    val bouncerListAttempt: Long = 0L,
    val bouncerAdd: BouncerAddState = BouncerAddState.Idle,
    val bouncerAddDraft: BouncerAddDraft = BouncerAddDraft(),
    val error: String? = null,
) {
    val isBouncer: Boolean get() = choice == ConnectionChoice.BOUNCER
    val isSoju: Boolean get() = isBouncer && bouncerKind == BouncerKind.SOJU
    val isZnc: Boolean get() = isBouncer && bouncerKind == BouncerKind.ZNC

    /** Network role implied by the choice (soju root vs. direct network). */
    val role: NetworkRole
        get() = if (isSoju) NetworkRole.BOUNCER_ROOT else NetworkRole.DIRECT

    val activeAuth: AuthForm
        get() = when {
            isSoju -> sojuLogin.toAuthForm()
            isZnc -> zncLogin.toAuthForm()
            else -> auth
        }

    /** True once the connect test reached a Ready state. */
    val isReady: Boolean get() = connState is ConnectionState.Ready

    val bouncerNetworks: List<BouncerNetworkRow>
        get() = when (val discovery = bouncerDiscovery) {
            is BouncerDiscoveryState.Loading -> discovery.rows
            is BouncerDiscoveryState.Loaded -> discovery.rows
            is BouncerDiscoveryState.Failed -> discovery.rows
            null -> emptyList()
        }

    /** Whether the "next" affordance should be enabled on the current step. */
    val canAdvance: Boolean
        get() = when (step) {
            OnboardingStep.WELCOME -> true
            OnboardingStep.CHOICE -> choice != null
            // Every path collects host/port/nick; the active login form gates AUTH.
            OnboardingStep.SERVER -> server.isValid
            OnboardingStep.AUTH -> when {
                isSoju -> sojuLogin.isValid
                isZnc -> zncLogin.isValid
                else -> auth.isValid
            }
            OnboardingStep.CONNECT -> isReady
            OnboardingStep.FINISH -> true
        }
}

/** All actions that can mutate wizard state. Pure — no side effects here. */
sealed interface OnboardingAction {
    data object Next : OnboardingAction
    data object Back : OnboardingAction
    data class GoTo(val step: OnboardingStep) : OnboardingAction

    data class ChooseConnection(val choice: ConnectionChoice) : OnboardingAction
    data class ChooseBouncerKind(val kind: BouncerKind) : OnboardingAction
    data class SelectPreset(val id: NetworkPresetId) : OnboardingAction
    data object ShowPlaintextWarning : OnboardingAction
    data object ConfirmPlaintext : OnboardingAction
    data object DismissPlaintextWarning : OnboardingAction

    data class EditServer(val server: ServerForm) : OnboardingAction
    data class EditAuth(val auth: AuthForm) : OnboardingAction
    data class EditSojuLogin(val login: SojuLoginForm) : OnboardingAction
    data class EditZncLogin(val login: ZncLoginForm) : OnboardingAction

    // Connect-test lifecycle (folded back from ViewModel side effects).
    data class NetworkCreated(val networkId: Long) : OnboardingAction
    data class ConnStateChanged(val state: ConnectionState) : OnboardingAction
    data class BouncerListLoading(
        val networkId: Long,
        val sessionGeneration: Long,
        val attempt: Long,
    ) : OnboardingAction
    data class BouncerListed(
        val networkId: Long,
        val sessionGeneration: Long,
        val attempt: Long,
        val rows: List<BouncerNetworkRow>,
    ) : OnboardingAction
    data class BouncerListFailed(
        val networkId: Long,
        val sessionGeneration: Long,
        val attempt: Long,
        val error: BouncerOperationError,
    ) : OnboardingAction
    data class BouncerSnapshot(
        val networkId: Long,
        val sessionGeneration: Long,
        val rows: List<BouncerNetworkRow>,
    ) : OnboardingAction
    data class ToggleBouncerNetwork(val netId: String) : OnboardingAction
    data class EditBouncerAddDraft(val draft: BouncerAddDraft) : OnboardingAction
    data class BouncerAddSubmitting(val networkId: Long, val sessionGeneration: Long) : OnboardingAction
    data class BouncerAdded(
        val networkId: Long,
        val sessionGeneration: Long,
        val row: BouncerNetworkRow,
    ) : OnboardingAction
    data class BouncerAddFailed(
        val networkId: Long,
        val sessionGeneration: Long,
        val error: BouncerOperationError,
    ) : OnboardingAction
    data class Error(val message: String?) : OnboardingAction
}

/** Steps in order; used for Next/Back traversal. */
private val STEP_ORDER = OnboardingStep.entries

private fun nextStep(step: OnboardingStep): OnboardingStep {
    val idx = STEP_ORDER.indexOf(step)
    return STEP_ORDER.getOrElse(idx + 1) { step }
}

private fun prevStep(step: OnboardingStep): OnboardingStep {
    val idx = STEP_ORDER.indexOf(step)
    return STEP_ORDER.getOrElse(idx - 1) { step }
}

/** Pure reducer: (state, action) -> state. */
fun onboardingReducer(state: OnboardingState, action: OnboardingAction): OnboardingState =
    when (action) {
        is OnboardingAction.Next ->
            if (state.canAdvance) state.copy(step = nextStep(state.step)) else state

        is OnboardingAction.Back -> state.copy(step = prevStep(state.step))

        is OnboardingAction.GoTo -> state.copy(step = action.step)

        is OnboardingAction.ChooseConnection -> state.copy(
            choice = action.choice,
            presetId = if (action.choice == ConnectionChoice.NETWORK) {
                state.presetId
            } else {
                NetworkPresetId.CUSTOM
            },
            showPlaintextWarning = false,
            plaintextConfirmed = false,
        )

        is OnboardingAction.ChooseBouncerKind -> state.copy(
            bouncerKind = action.kind,
            showPlaintextWarning = false,
            plaintextConfirmed = false,
        )

        is OnboardingAction.SelectPreset -> {
            val preset = networkPreset(action.id)
            if (preset == null) {
                state.copy(
                    presetId = NetworkPresetId.CUSTOM,
                    showPlaintextWarning = false,
                    plaintextConfirmed = false,
                )
            } else {
                val (server, auth) = applyNetworkPreset(preset, state.server)
                state.copy(
                    choice = ConnectionChoice.NETWORK,
                    server = server,
                    auth = auth,
                    presetId = action.id,
                    showPlaintextWarning = false,
                    plaintextConfirmed = false,
                )
            }
        }

        is OnboardingAction.ShowPlaintextWarning ->
            state.copy(showPlaintextWarning = true)

        is OnboardingAction.ConfirmPlaintext ->
            state.copy(showPlaintextWarning = false, plaintextConfirmed = true)

        is OnboardingAction.DismissPlaintextWarning ->
            state.copy(showPlaintextWarning = false)

        is OnboardingAction.EditServer -> {
            val selected = networkPreset(state.presetId)
            state.copy(
                server = action.server,
                presetId = if (selected?.matches(action.server) == true) {
                    state.presetId
                } else {
                    NetworkPresetId.CUSTOM
                },
                showPlaintextWarning = false,
                plaintextConfirmed = false,
            )
        }

        is OnboardingAction.EditAuth -> state.copy(auth = action.auth)

        is OnboardingAction.EditSojuLogin -> state.copy(sojuLogin = action.login)

        is OnboardingAction.EditZncLogin -> state.copy(zncLogin = action.login)

        is OnboardingAction.NetworkCreated -> state.copy(networkId = action.networkId)

        is OnboardingAction.ConnStateChanged ->
            state.copy(
                connState = action.state,
                stateLog = state.stateLog + action.state,
                error = (action.state as? ConnectionState.Failed)?.reason ?: state.error,
            )

        is OnboardingAction.BouncerListLoading ->
            if (state.networkId != action.networkId) state else state.copy(
                bouncerSessionGeneration = action.sessionGeneration,
                bouncerListAttempt = action.attempt,
                bouncerDiscovery = BouncerDiscoveryState.Loading(state.bouncerNetworks),
            )

        is OnboardingAction.BouncerListed ->
            if (!state.matchesBouncerList(action.networkId, action.sessionGeneration, action.attempt)) state else state.copy(
                bouncerDiscovery = BouncerDiscoveryState.Loaded(
                    mergeBouncerNetworkRows(state.bouncerNetworks, action.rows),
                ),
            )

        is OnboardingAction.BouncerListFailed ->
            if (!state.matchesBouncerList(action.networkId, action.sessionGeneration, action.attempt)) state else state.copy(
                bouncerDiscovery = BouncerDiscoveryState.Failed(action.error, state.bouncerNetworks),
            )

        is OnboardingAction.BouncerSnapshot ->
            if (!state.matchesBouncerSession(action.networkId, action.sessionGeneration)) state else state.copy(
                // A passive bouncer notification reconciles the import rows but deliberately
                // retains Loading/Failed. Only the matching explicit LIST result resolves it.
                bouncerDiscovery = when (val discovery = state.bouncerDiscovery) {
                    is BouncerDiscoveryState.Loading -> discovery.copy(
                        rows = mergeBouncerNetworkRows(discovery.rows, action.rows),
                    )
                    is BouncerDiscoveryState.Failed -> discovery.copy(
                        rows = mergeBouncerNetworkRows(discovery.rows, action.rows),
                    )
                    is BouncerDiscoveryState.Loaded -> discovery.copy(
                        rows = mergeBouncerNetworkRows(discovery.rows, action.rows),
                    )
                    null -> null
                },
            )

        is OnboardingAction.ToggleBouncerNetwork ->
            state.withBouncerRows(
                state.bouncerNetworks.map {
                    if (it.netId == action.netId) it.copy(selected = !it.selected) else it
                },
            )

        is OnboardingAction.EditBouncerAddDraft -> state.copy(
            bouncerAddDraft = action.draft,
            bouncerAdd = if (state.bouncerAdd is BouncerAddState.Success) BouncerAddState.Idle else state.bouncerAdd,
        )

        is OnboardingAction.BouncerAddSubmitting ->
            if (!state.matchesBouncerSession(action.networkId, action.sessionGeneration) ||
                state.bouncerAdd is BouncerAddState.Submitting
            ) state
            else state.copy(bouncerAdd = BouncerAddState.Submitting)

        is OnboardingAction.BouncerAdded ->
            if (!state.matchesBouncerSession(action.networkId, action.sessionGeneration)) state else state.withBouncerRows(
                rows = mergeBouncerNetworkRows(state.bouncerNetworks, listOf(action.row)),
                addState = BouncerAddState.Success,
                // Clearing is exclusively part of the accepted ADD transition, so recomposition
                // and passive snapshots cannot erase a failed draft or clear success twice.
                draft = BouncerAddDraft(),
            )

        is OnboardingAction.BouncerAddFailed ->
            if (!state.matchesBouncerSession(action.networkId, action.sessionGeneration)) state
            else state.copy(bouncerAdd = BouncerAddState.Failed(action.error))

        is OnboardingAction.Error -> state.copy(error = action.message)
    }

private fun mergeBouncerNetworkRows(
    existing: List<BouncerNetworkRow>,
    incoming: List<BouncerNetworkRow>,
): List<BouncerNetworkRow> {
    val selected = existing.filter { it.selected }.associateBy { it.netId }
    val incomingIds = incoming.mapTo(mutableSetOf()) { it.netId }
    return incoming.map { row -> row.copy(selected = row.selected || row.netId in selected) } +
        selected.values.filter { it.netId !in incomingIds }
}

private fun OnboardingState.withBouncerRows(
    rows: List<BouncerNetworkRow>,
    addState: BouncerAddState = bouncerAdd,
    draft: BouncerAddDraft = bouncerAddDraft,
): OnboardingState = copy(
    bouncerDiscovery = when (val discovery = bouncerDiscovery) {
        is BouncerDiscoveryState.Loading -> discovery.copy(rows = rows)
        is BouncerDiscoveryState.Loaded -> discovery.copy(rows = rows)
        is BouncerDiscoveryState.Failed -> discovery.copy(rows = rows)
        null -> null
    },
    bouncerAdd = addState,
    bouncerAddDraft = draft,
)

private fun OnboardingState.matchesBouncerSession(networkId: Long, sessionGeneration: Long): Boolean =
    networkId == this.networkId && sessionGeneration == bouncerSessionGeneration

private fun OnboardingState.matchesBouncerList(
    networkId: Long,
    sessionGeneration: Long,
    attempt: Long,
): Boolean = matchesBouncerSession(networkId, sessionGeneration) && attempt == bouncerListAttempt
