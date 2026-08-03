package io.github.trevarj.motd.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.bouncer.BouncerKind
import io.github.trevarj.motd.bouncer.SojuLoginForm
import io.github.trevarj.motd.bouncer.ZncLoginForm
import io.github.trevarj.motd.backend.ConnectionState
import io.github.trevarj.motd.ui.settings.BouncerLoginFields
import io.github.trevarj.motd.ui.settings.NetworkForm
import io.github.trevarj.motd.ui.settings.PasswordField
import io.github.trevarj.motd.ui.settings.addnetwork.NetworkPresetId
import io.github.trevarj.motd.ui.settings.addnetwork.NetworkPresetPicker
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdTheme

/** Stateful entry: wires the ViewModel; the wizard is a single route with an internal pager. */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit = {},
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OnboardingContent(
        state = state,
        onNext = viewModel::next,
        onBack = viewModel::back,
        onSkip = { viewModel.skip(onDone) },
        onChoose = viewModel::chooseConnection,
        onChooseBouncerKind = viewModel::chooseBouncerKind,
        onSelectPreset = viewModel::selectPreset,
        onServerChange = viewModel::editServer,
        onAuthChange = viewModel::editAuth,
        onSojuLoginChange = viewModel::editSojuLogin,
        onZncLoginChange = viewModel::editZncLogin,
        onRetry = viewModel::retryConnect,
        onRetryBouncerDiscovery = viewModel::retryBouncerDiscovery,
        onToggleBouncer = viewModel::toggleBouncerNetwork,
        onBouncerAddDraftChange = viewModel::editBouncerAddDraft,
        onAddBouncer = viewModel::addBouncerNetwork,
        onFinish = { viewModel.finish(onDone) },
        onConfirmPlaintext = viewModel::confirmPlaintext,
        onDismissPlaintext = viewModel::dismissPlaintextWarning,
    )
}

@Composable
fun OnboardingContent(
    state: OnboardingState,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onChoose: (ConnectionChoice) -> Unit,
    onChooseBouncerKind: (BouncerKind) -> Unit,
    onSelectPreset: (NetworkPresetId) -> Unit,
    onServerChange: (ServerForm) -> Unit,
    onAuthChange: (AuthForm) -> Unit,
    onSojuLoginChange: (SojuLoginForm) -> Unit,
    onZncLoginChange: (ZncLoginForm) -> Unit,
    onRetry: () -> Unit,
    onRetryBouncerDiscovery: () -> Unit,
    onToggleBouncer: (String) -> Unit,
    onBouncerAddDraftChange: (BouncerAddDraft) -> Unit,
    onAddBouncer: () -> Unit,
    onFinish: () -> Unit,
    onConfirmPlaintext: () -> Unit,
    onDismissPlaintext: () -> Unit,
) {
    val steps = OnboardingStep.entries
    val pagerState = rememberPagerState(pageCount = { steps.size })

    // Keep the pager synced to the reducer-driven step.
    LaunchedEffect(state.step) {
        pagerState.animateScrollToPage(steps.indexOf(state.step))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            userScrollEnabled = false,
        ) { page ->
            when (steps[page]) {
                OnboardingStep.WELCOME -> WelcomePage()
                OnboardingStep.CHOICE ->
                    ChoicePage(state, onChoose, onChooseBouncerKind, onSelectPreset)
                OnboardingStep.SERVER -> ServerPage(state, onServerChange, onAuthChange, authOnly = false)
                OnboardingStep.AUTH ->
                    if (state.isBouncer) {
                        BouncerAuthPage(state, onSojuLoginChange, onZncLoginChange)
                    } else {
                        // Direct path AUTH step: mechanism picker only (server fields live on step 3).
                        ServerPage(state, onServerChange, onAuthChange, authOnly = true)
                    }
                OnboardingStep.CONNECT -> ConnectPage(
                    state,
                    onRetry,
                    onRetryBouncerDiscovery,
                    onToggleBouncer,
                    onBouncerAddDraftChange,
                    onAddBouncer,
                )
                OnboardingStep.FINISH -> FinishPage()
            }
        }
        WizardBar(
            state = state,
            onNext = onNext,
            onBack = onBack,
            onSkip = onSkip,
            onFinish = onFinish,
        )
    }

    if (state.showPlaintextWarning) {
        AlertDialog(
            modifier = Modifier.testTag("plaintext_network_warning"),
            onDismissRequest = onDismissPlaintext,
            title = { Text(stringResource(R.string.add_network_plaintext_title)) },
            text = { Text(stringResource(R.string.add_network_plaintext_message)) },
            confirmButton = {
                Button(onClick = onConfirmPlaintext) {
                    Text(stringResource(R.string.add_network_plaintext_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissPlaintext) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

@Composable
private fun WizardBar(
    state: OnboardingState,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
) {
    Row(
        // The activity draws edge-to-edge, so keep the actual touch targets above gesture and
        // three-button navigation. Semantics clicks do not reveal this class of overlap.
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.step != OnboardingStep.WELCOME) {
            // Stable handle: Back is icon-agnostic across steps.
            TextButton(onClick = onBack, modifier = Modifier.testTag("onboarding_back_button")) {
                Text(stringResource(R.string.onboarding_back))
            }
        } else {
            TextButton(onClick = onSkip, modifier = Modifier.testTag("onboarding_skip_button")) {
                Text(stringResource(R.string.onboarding_skip))
            }
        }
        // Single stable handle for the forward button whose label varies (Get started/Next/Finish).
        val forwardTag = Modifier.testTag("onboarding_forward_button")
        when (state.step) {
            OnboardingStep.WELCOME -> Button(onClick = onNext, modifier = forwardTag) {
                Text(stringResource(R.string.onboarding_get_started))
            }
            OnboardingStep.FINISH -> Button(onClick = onFinish, modifier = forwardTag) {
                Text(stringResource(R.string.onboarding_finish))
            }
            else -> Button(onClick = onNext, enabled = state.canAdvance, modifier = forwardTag) {
                Text(stringResource(R.string.onboarding_next))
            }
        }
    }
}

@Composable
private fun WelcomePage() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Brand hero above the welcome copy.
        Image(
            painter = painterResource(R.drawable.motd_onboarding_hero),
            contentDescription = null,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.width(220.dp).height(144.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.onboarding_welcome_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun ChoicePage(
    state: OnboardingState,
    onChoose: (ConnectionChoice) -> Unit,
    onChooseBouncerKind: (BouncerKind) -> Unit,
    onSelectPreset: (NetworkPresetId) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.onboarding_choice_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        ChoiceCard(
            title = stringResource(R.string.onboarding_choice_bouncer_title),
            desc = stringResource(R.string.onboarding_choice_bouncer_desc),
            selected = state.choice == ConnectionChoice.BOUNCER,
            onClick = { onChoose(ConnectionChoice.BOUNCER) },
            modifier = Modifier.testTag("onboarding_choice_bouncer"),
        )
        if (state.isBouncer) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChoiceCard(
                    title = stringResource(R.string.bouncer_kind_soju),
                    desc = stringResource(R.string.bouncer_kind_soju_desc),
                    selected = state.bouncerKind == BouncerKind.SOJU,
                    onClick = { onChooseBouncerKind(BouncerKind.SOJU) },
                    modifier = Modifier.weight(1f).testTag("onboarding_choice_soju"),
                )
                ChoiceCard(
                    title = stringResource(R.string.bouncer_kind_znc),
                    desc = stringResource(R.string.bouncer_kind_znc_desc),
                    selected = state.bouncerKind == BouncerKind.ZNC,
                    onClick = { onChooseBouncerKind(BouncerKind.ZNC) },
                    modifier = Modifier.weight(1f).testTag("onboarding_choice_znc"),
                )
            }
        }
        ChoiceCard(
            title = stringResource(R.string.onboarding_choice_network_title),
            desc = stringResource(R.string.onboarding_choice_network_desc),
            selected = state.choice == ConnectionChoice.NETWORK,
            onClick = { onChoose(ConnectionChoice.NETWORK) },
            modifier = Modifier.testTag("onboarding_choice_network"),
        )
        if (state.choice == ConnectionChoice.NETWORK) {
            NetworkPresetPicker(
                selected = state.presetId,
                onSelect = onSelectPreset,
            )
        }
    }
}

@Composable
private fun ChoiceCard(
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(
                if (selected) Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(12.dp),
                ) else Modifier,
            ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                desc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * Steps 3 (server) and 4 (auth) share the [NetworkForm], but render disjoint sections: the SERVER
 * step shows server fields only, the direct-path AUTH step shows the mechanism picker only.
 */
@Composable
private fun ServerPage(
    state: OnboardingState,
    onServerChange: (ServerForm) -> Unit,
    onAuthChange: (AuthForm) -> Unit,
    authOnly: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
    ) {
        Text(
            stringResource(if (authOnly) R.string.onboarding_auth_title else R.string.onboarding_server_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        NetworkForm(
            server = state.server,
            auth = state.auth,
            onServerChange = onServerChange,
            onAuthChange = onAuthChange,
            showServer = !authOnly,
            showAuth = authOnly,
            // Direct path shows the full identity (nick/username/realname); the soju root shows
            // only the nick here (its bouncer SASL user/password live on the AUTH step).
            showIdentity = !state.isBouncer,
            showNick = state.isBouncer,
        )
    }
}

/**
 * Simplified AUTH page for the soju bouncer path: only username + password, always SASL PLAIN.
 * No mechanism picker (NONE/EXTERNAL are meaningless for soju login).
 */
@Composable
private fun BouncerAuthPage(
    state: OnboardingState,
    onSojuLoginChange: (SojuLoginForm) -> Unit,
    onZncLoginChange: (ZncLoginForm) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.onboarding_auth_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        BouncerLoginFields(
            kind = state.bouncerKind,
            server = state.server,
            sojuLogin = state.sojuLogin,
            zncLogin = state.zncLogin,
            onServerChange = {},
            onSojuLoginChange = onSojuLoginChange,
            onZncLoginChange = onZncLoginChange,
            showEndpoint = false,
        )
    }
}

@Composable
private fun ConnectPage(
    state: OnboardingState,
    onRetry: () -> Unit,
    onRetryBouncerDiscovery: () -> Unit,
    onToggleBouncer: (String) -> Unit,
    onBouncerAddDraftChange: (BouncerAddDraft) -> Unit,
    onAddBouncer: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.onboarding_connect_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        StateIndicator(state.connState)
        state.stateLog.forEach { s ->
            // Annotate a Failed entry with its own reason so a diagnosis is possible even mid-loop.
            // Authenticating is rendered as "Registering": this diagnostic log predates the
            // backend-neutral ConnectionState rename and keeps its original IRC-flow wording.
            val label = when {
                s is ConnectionState.Failed -> "Failed: ${s.reason}"
                s is ConnectionState.Authenticating -> "Registering"
                else -> s::class.simpleName.orEmpty()
            }
            Text(
                "• $label",
                style = MaterialTheme.typography.bodySmall,
                color = if (s is ConnectionState.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        // The connection retries, so `connState` may already be back to Connecting after a failure;
        // surface the latest captured failure reason (reducer's `error`) so it stays visible (#43).
        val failureReason = (state.connState as? ConnectionState.Failed)?.reason ?: state.error
        if (failureReason != null) {
            Text(
                failureReason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.onboarding_connect_retry)) }
        }

        // Retain discovery/add recovery controls after the root loses its connection. A failed
        // refresh is actionable only if its retry affordance stays visible while reconnecting.
        if (state.isSoju && state.bouncerDiscovery != null) {
            BouncerNetworksSection(
                state,
                onRetryBouncerDiscovery,
                onToggleBouncer,
                onBouncerAddDraftChange,
                onAddBouncer,
            )
        }
    }
}

@Composable
private fun StateIndicator(connState: ConnectionState?) {
    Row(
        // Stable handle for the connect-step status line (label varies: Connecting…/Connected as …).
        modifier = Modifier.testTag("onboarding_state_indicator"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedContent(targetState = connState, label = "connState") { cs ->
            when (cs) {
                is ConnectionState.Ready ->
                    Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                is ConnectionState.Failed ->
                    Icon(Icons.Filled.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                null -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        AnimatedContent(
            targetState = when (connState) {
                is ConnectionState.Ready -> "Connected as ${connState.selfHandle}"
                is ConnectionState.Failed -> "Failed"
                ConnectionState.Authenticating -> "Registering…"
                ConnectionState.Connecting -> "Connecting…"
                else -> "Starting…"
            },
            transitionSpec = {
                fadeIn(MotdMotion.microFadeIn) togetherWith fadeOut(MotdMotion.microFadeOut)
            },
            label = "onboarding_connection_label",
        ) { label -> Text(label) }
    }
}

@Composable
private fun BouncerNetworksSection(
    state: OnboardingState,
    onRetryDiscovery: () -> Unit,
    onToggleBouncer: (String) -> Unit,
    onDraftChange: (BouncerAddDraft) -> Unit,
    onAddBouncer: () -> Unit,
) {
    Text(
        stringResource(R.string.onboarding_connect_networks_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp),
    )
    Text(
        stringResource(R.string.onboarding_connect_import_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    when (val discovery = state.bouncerDiscovery) {
        is BouncerDiscoveryState.Loading -> Row(
            modifier = Modifier.testTag("onboarding_bouncer_discovery_loading"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp).testTag("onboarding_bouncer_discovery_progress"),
                strokeWidth = 2.dp,
            )
            Text(stringResource(R.string.onboarding_bouncer_discovery_loading), style = MaterialTheme.typography.bodySmall)
        }
        is BouncerDiscoveryState.Failed -> {
            Text(
                bouncerErrorMessage(discovery.error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("onboarding_bouncer_discovery_error"),
            )
            OutlinedButton(
                onClick = onRetryDiscovery,
                modifier = Modifier.testTag("onboarding_bouncer_discovery_retry"),
            ) { Text(stringResource(R.string.onboarding_bouncer_discovery_retry)) }
        }
        is BouncerDiscoveryState.Loaded -> OutlinedButton(
            onClick = onRetryDiscovery,
            modifier = Modifier.testTag("onboarding_bouncer_discovery_refresh"),
        ) { Text(stringResource(R.string.onboarding_bouncer_discovery_refresh)) }
        null -> Unit
    }
    Column(Modifier.selectableGroup()) {
        state.bouncerNetworks.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding_bouncer_row_${row.netId}")
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(row.name, modifier = Modifier.weight(1f))
                Switch(
                    checked = row.selected,
                    onCheckedChange = { onToggleBouncer(row.netId) },
                    modifier = Modifier.testTag("onboarding_bouncer_switch_${row.netId}"),
                )
            }
        }
    }

    Text(
        stringResource(R.string.onboarding_connect_add_network),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp),
    )
    OutlinedTextField(
        value = state.bouncerAddDraft.name,
        onValueChange = { onDraftChange(state.bouncerAddDraft.copy(name = it)) },
        label = { Text(stringResource(R.string.onboarding_connect_add_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("onboarding_bouncer_add_name"),
    )
    OutlinedTextField(
        value = state.bouncerAddDraft.host,
        onValueChange = { onDraftChange(state.bouncerAddDraft.copy(host = it)) },
        label = { Text(stringResource(R.string.onboarding_connect_add_host)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("onboarding_bouncer_add_host"),
    )
    val adding = state.bouncerAdd is BouncerAddState.Submitting
    if (state.bouncerAdd is BouncerAddState.Failed) {
        Text(
            bouncerErrorMessage(state.bouncerAdd.error),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("onboarding_bouncer_add_error"),
        )
    }
    OutlinedButton(
        onClick = onAddBouncer,
        enabled = state.bouncerAddDraft.isValid && !adding,
        modifier = Modifier.testTag("onboarding_bouncer_add_submit"),
    ) {
        if (adding) CircularProgressIndicator(
            modifier = Modifier.size(18.dp).testTag("onboarding_bouncer_add_progress"),
            strokeWidth = 2.dp,
        )
        else Text(stringResource(R.string.onboarding_connect_add_network))
    }
}

@Composable
private fun bouncerErrorMessage(error: BouncerOperationError): String = when (error) {
    BouncerOperationError.ConnectionLost -> stringResource(R.string.onboarding_bouncer_connection_lost)
    is BouncerOperationError.ServerRejected -> if (error.detail.isBlank()) {
        stringResource(R.string.onboarding_bouncer_server_rejected_generic)
    } else {
        stringResource(R.string.onboarding_bouncer_server_rejected, error.detail)
    }
    is BouncerOperationError.Unexpected -> if (error.detail.isBlank()) {
        stringResource(R.string.onboarding_bouncer_request_failed)
    } else {
        error.detail
    }
}

@Composable
private fun FinishPage() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(72.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp),
            )
        }
        Text(
            stringResource(R.string.onboarding_finish),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Preview
@Composable
private fun OnboardingChoicePreview() {
    MotdTheme {
        // Surface so the preview reflects the runtime themed background under the wizard.
        Surface {
            OnboardingContent(
                state = OnboardingState(step = OnboardingStep.CHOICE, choice = ConnectionChoice.NETWORK),
                onNext = {}, onBack = {}, onSkip = {}, onChoose = {}, onChooseBouncerKind = {}, onSelectPreset = {},
                onServerChange = {}, onAuthChange = {}, onSojuLoginChange = {}, onZncLoginChange = {}, onRetry = {},
                onRetryBouncerDiscovery = {}, onToggleBouncer = {}, onBouncerAddDraftChange = {}, onAddBouncer = {}, onFinish = {},
                onConfirmPlaintext = {}, onDismissPlaintext = {},
            )
        }
    }
}

@Preview
@Composable
private fun OnboardingConnectPreview() {
    MotdTheme {
        Surface {
            OnboardingContent(
                state = OnboardingState(
                    step = OnboardingStep.CONNECT,
                    choice = ConnectionChoice.BOUNCER,
                    connState = ConnectionState.Ready("me"),
                    stateLog = listOf(ConnectionState.Connecting, ConnectionState.Authenticating),
                    bouncerDiscovery = BouncerDiscoveryState.Loaded(
                        listOf(
                            BouncerNetworkRow("1", "Libera", selected = true),
                            BouncerNetworkRow("2", "OFTC", selected = false),
                        ),
                    ),
                ),
                onNext = {}, onBack = {}, onSkip = {}, onChoose = {}, onChooseBouncerKind = {}, onSelectPreset = {},
                onServerChange = {}, onAuthChange = {}, onSojuLoginChange = {}, onZncLoginChange = {}, onRetry = {},
                onRetryBouncerDiscovery = {}, onToggleBouncer = {}, onBouncerAddDraftChange = {}, onAddBouncer = {}, onFinish = {},
                onConfirmPlaintext = {}, onDismissPlaintext = {},
            )
        }
    }
}
