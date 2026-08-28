package io.github.trevarj.motd.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.attachment.AVAILABLE_ATTACHMENT_BACKENDS
import io.github.trevarj.motd.attachment.AttachmentBackend
import io.github.trevarj.motd.attachment.AttachmentPrefs
import io.github.trevarj.motd.attachment.LITTERBOX_EXPIRIES
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.attachment.backendMaxBytes
import io.github.trevarj.motd.attachment.forBackend
import io.github.trevarj.motd.attachment.validateEndpoint
import io.github.trevarj.motd.ui.theme.MotdMotion
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AttachmentSettingsViewModel
    @Inject
    constructor(
        private val prefs: AttachmentPrefs,
    ) : ViewModel() {
        val config = prefs.config.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PasteBackendConfig())

        fun update(transform: (PasteBackendConfig) -> PasteBackendConfig) = viewModelScope.launch { prefs.updateConfig(transform) }
    }

@Composable
fun UploadsSettingsContent(viewModel: AttachmentSettingsViewModel = hiltViewModel()) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    var customEndpoint by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(config.customEndpoint) {
        customEndpoint = config.customEndpoint
    }

    SettingsGroup(title = stringResource(io.github.trevarj.motd.R.string.settings_upload_destination)) {
        Column(Modifier.selectableGroup()) {
            AVAILABLE_ATTACHMENT_BACKENDS.forEach { backend ->
                RadioRow(
                    label = backend.label,
                    subtitle = backendDescription(backend),
                    selected = config.backend == backend,
                    enabled = true,
                    onClick = { viewModel.update { it.forBackend(backend) } },
                )
            }
        }
    }

    // One animated region hosts every backend-specific block (warning, endpoint, options) so a
    // radio pick crossfades and eases the height once instead of snapping three conditionals.
    AnimatedContent(
        targetState = config.backend,
        transitionSpec = {
            (fadeIn(MotdMotion.microFadeIn) togetherWith fadeOut(MotdMotion.microFadeOut))
                .using(SizeTransform(sizeAnimationSpec = { _, _ -> MotdMotion.contentSize }))
        },
        label = "upload_backend_options",
    ) { backend ->
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (backend == AttachmentBackend.TERMBIN) {
                UploadWarning(stringResource(io.github.trevarj.motd.R.string.settings_upload_termbin_warning))
            }
            if (backend == AttachmentBackend.CUSTOM_0X0) {
                SettingsGroup(title = stringResource(io.github.trevarj.motd.R.string.settings_upload_endpoint)) {
                    var usernameDraft by remember { mutableStateOf<String?>(null) }
                    var passwordDraft by remember { mutableStateOf<String?>(null) }
                    val endpointError = customEndpoint.isNotBlank() && validateEndpoint(customEndpoint) == null
                    OutlinedTextField(
                        value = customEndpoint,
                        onValueChange = { value ->
                            customEndpoint = value
                            if (value.isBlank()) {
                                usernameDraft = ""
                                passwordDraft = ""
                                viewModel.update {
                                    it.copy(endpoint = "", customEndpoint = "", username = "", password = "")
                                }
                            } else {
                                validateEndpoint(value)?.let { endpoint ->
                                    val authorityChanged =
                                        config.endpoint.isNotBlank() &&
                                            !sameUploadAuthority(config.endpoint, endpoint)
                                    if (authorityChanged) {
                                        usernameDraft = ""
                                        passwordDraft = ""
                                    }
                                    viewModel.update {
                                        it.copy(
                                            endpoint = endpoint,
                                            customEndpoint = endpoint,
                                            username = if (authorityChanged) "" else it.username,
                                            password = if (authorityChanged) "" else it.password,
                                        )
                                    }
                                }
                            }
                        },
                        label = { Text(stringResource(io.github.trevarj.motd.R.string.settings_upload_custom_url)) },
                        isError = endpointError,
                        supportingText = {
                            Text(
                                stringResource(
                                    if (endpointError) {
                                        io.github.trevarj.motd.R.string.settings_upload_custom_error
                                    } else {
                                        io.github.trevarj.motd.R.string.settings_upload_custom_desc
                                    },
                                ),
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .testTag("settings_upload_custom_endpoint"),
                    )
                    OutlinedTextField(
                        value = usernameDraft ?: config.username,
                        onValueChange = { value ->
                            usernameDraft = value
                            viewModel.update { it.copy(username = value) }
                        },
                        label = { Text(stringResource(io.github.trevarj.motd.R.string.settings_upload_custom_username)) },
                        singleLine = true,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .testTag("settings_upload_custom_username"),
                    )
                    OutlinedTextField(
                        value = passwordDraft ?: config.password,
                        onValueChange = { value ->
                            passwordDraft = value
                            viewModel.update { it.copy(password = value) }
                        },
                        label = { Text(stringResource(io.github.trevarj.motd.R.string.settings_upload_custom_password)) },
                        visualTransformation =
                            androidx.compose.ui.text.input
                                .PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .testTag("settings_upload_custom_password"),
                    )
                }
            }

            when (backend) {
                AttachmentBackend.CRAFTERBIN, AttachmentBackend.ZERO_X_ZERO, AttachmentBackend.CUSTOM_0X0 -> {
                    SettingsGroup(title = stringResource(io.github.trevarj.motd.R.string.settings_upload_privacy)) {
                        SwitchRow(
                            title = stringResource(io.github.trevarj.motd.R.string.settings_upload_secret),
                            subtitle = stringResource(io.github.trevarj.motd.R.string.settings_upload_secret_desc),
                            checked = config.secretUrl,
                            onCheckedChange = { value -> viewModel.update { it.copy(secretUrl = value) } },
                            switchTag = "settings_upload_secret",
                        )
                        OutlinedTextField(
                            value = config.expiry.orEmpty(),
                            onValueChange = { value -> viewModel.update { it.copy(expiry = value.ifBlank { null }) } },
                            label = { Text(stringResource(io.github.trevarj.motd.R.string.settings_upload_expiry)) },
                            supportingText = { Text(stringResource(io.github.trevarj.motd.R.string.settings_upload_expiry_desc)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }

                AttachmentBackend.LITTERBOX -> {
                    SettingsGroup(
                        title = stringResource(io.github.trevarj.motd.R.string.settings_upload_privacy),
                    ) {
                        Column(Modifier.selectableGroup()) {
                            LITTERBOX_EXPIRIES.forEach { expiry ->
                                RadioRow(
                                    label = litterboxExpiryLabel(expiry),
                                    subtitle = stringResource(io.github.trevarj.motd.R.string.settings_upload_litterbox_expiry_desc),
                                    selected = config.litterboxExpiry == expiry,
                                    enabled = true,
                                    onClick = { viewModel.update { it.copy(litterboxExpiry = expiry) } },
                                )
                            }
                        }
                    }
                }

                AttachmentBackend.UGUU -> {
                    UploadWarning(
                        stringResource(io.github.trevarj.motd.R.string.settings_upload_uguu_warning),
                        caution = false,
                    )
                }

                AttachmentBackend.X0_AT -> {
                    UploadWarning(
                        stringResource(io.github.trevarj.motd.R.string.settings_upload_x0at_warning),
                        caution = false,
                    )
                }

                AttachmentBackend.CNET -> {
                    UploadWarning(
                        stringResource(io.github.trevarj.motd.R.string.settings_upload_cnet_warning),
                        caution = false,
                    )
                }

                AttachmentBackend.CATBOX -> {
                    UploadWarning(stringResource(io.github.trevarj.motd.R.string.settings_upload_catbox_warning))
                }

                AttachmentBackend.SOJU_FILEHOST -> {
                    UploadWarning(
                        stringResource(io.github.trevarj.motd.R.string.settings_upload_soju_warning),
                        caution = false,
                    )
                }

                AttachmentBackend.TERMBIN -> {}
            }
        }
    }

    SettingsGroup(title = stringResource(io.github.trevarj.motd.R.string.settings_upload_limits)) {
        val maximumMiB = uploadLimitMaximumMiB(config.backend)
        OutlinedTextField(
            value = (config.sizeLimitBytes / MIB).toString(),
            onValueChange = { value ->
                value.toLongOrNull()?.coerceIn(1, maximumMiB)?.let { mib ->
                    viewModel.update { it.copy(sizeLimitBytes = mib * MIB) }
                }
            },
            label = { Text(stringResource(io.github.trevarj.motd.R.string.settings_upload_limit)) },
            supportingText = { Text(stringResource(io.github.trevarj.motd.R.string.settings_upload_limit_desc, maximumMiB)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}

@Composable
private fun UploadWarning(
    message: String,
    caution: Boolean = true,
) {
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    androidx.compose.material3.Surface(
        color = if (caution) colors.errorContainer else colors.surfaceContainerHigh,
        shape =
            androidx.compose.foundation.shape
                .RoundedCornerShape(16.dp),
    ) {
        Text(
            message,
            color = if (caution) colors.onErrorContainer else colors.onSurface,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}

internal fun sameUploadAuthority(
    first: String,
    second: String,
): Boolean =
    runCatching {
        val a = java.net.URI(first)
        val b = java.net.URI(second)
        val aPort = if (a.port >= 0) a.port else 443
        val bPort = if (b.port >= 0) b.port else 443
        a.scheme.equals("https", ignoreCase = true) &&
            b.scheme.equals("https", ignoreCase = true) &&
            a.host.equals(b.host, ignoreCase = true) &&
            aPort == bPort
    }.getOrDefault(false)

internal fun uploadLimitMaximumMiB(backend: AttachmentBackend): Long = backendMaxBytes(backend) / MIB

internal fun backendDescription(backend: AttachmentBackend): String =
    when (backend) {
        AttachmentBackend.CRAFTERBIN -> "Files, photos, and text • configurable expiry"
        AttachmentBackend.ZERO_X_ZERO -> "Files, photos, and text • public service"
        AttachmentBackend.X0_AT -> "Files, photos, and text • 3–100 days by size • no deletion"
        AttachmentBackend.CUSTOM_0X0 -> "HTTPS URL, optional username and password"
        AttachmentBackend.CNET -> "Files, photos, and text • rolling 180 days • deletable"
        AttachmentBackend.UGUU -> "Files, photos, and text • 3 hours"
        AttachmentBackend.LITTERBOX -> "Files, photos, and text • 1–72 hours"
        AttachmentBackend.CATBOX -> "Files, photos, and text • long-lived"
        AttachmentBackend.SOJU_FILEHOST -> "Files, photos, and text • current chat's bouncer"
        AttachmentBackend.TERMBIN -> "Text only • unencrypted TCP"
    }

internal fun litterboxExpiryLabel(expiry: String): String =
    when (expiry) {
        "1h" -> "1 hour"
        "12h" -> "12 hours"
        "24h" -> "24 hours"
        "72h" -> "72 hours"
        else -> expiry
    }

private const val MIB = 1024L * 1024L
