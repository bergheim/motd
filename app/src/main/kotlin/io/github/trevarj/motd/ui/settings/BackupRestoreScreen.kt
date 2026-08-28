package io.github.trevarj.motd.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.backup.BackupExportMode
import io.github.trevarj.motd.data.backup.BackupImportMode
import io.github.trevarj.motd.data.backup.ConfigurationBackupRepository
import io.github.trevarj.motd.data.backup.ConfigurationImportPreview
import io.github.trevarj.motd.ui.theme.MotdMotion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import javax.inject.Inject

@Composable
fun BackupRestoreScreen(
    onBack: () -> Unit,
    viewModel: BackupRestoreViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var includeSecrets by rememberSaveable { mutableStateOf(false) }
    var exportPassword by rememberSaveable { mutableStateOf("") }
    var importPassword by rememberSaveable { mutableStateOf("") }

    val createDocument =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri -> if (uri != null) viewModel.writePreparedExport(uri) else viewModel.cancelPreparedExport() }
    val openDocument =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri -> if (uri != null) viewModel.loadImport(uri) }

    LaunchedEffect(state.exportRequestToken) {
        val token = state.exportRequestToken ?: return@LaunchedEffect
        createDocument.launch("motd-$token.motdconfig")
    }

    SettingsScaffold(
        title = stringResource(R.string.settings_backup_restore),
        onBack = onBack,
        modifier = Modifier.testTag("screen_backup_restore"),
    ) {
        SettingsGroup("Export configuration") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Messages, chat history, drafts, generated push keys, cached previews, upload history, certificate pins, pending account setup, channel keys, and runtime state are not exported.")
                // The switch row and the password field share one Column child so the collapsed
                // field sits outside the spacedBy flow; its 12dp gap lives inside the animation.
                Column {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Switch) { includeSecrets = !includeSecrets }
                                .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Include credentials", fontWeight = FontWeight.Medium)
                            Text(
                                "Creates a password-encrypted export. Client certificate selections still stay device-local.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = includeSecrets,
                            onCheckedChange = null,
                            modifier = Modifier.testTag("backup_export_include_credentials"),
                        )
                    }
                    AnimatedVisibility(
                        visible = includeSecrets,
                        enter = fadeIn(MotdMotion.microFadeIn) + expandVertically(animationSpec = MotdMotion.contentSize),
                        exit = fadeOut(MotdMotion.microFadeOut) + shrinkVertically(animationSpec = MotdMotion.contentSize),
                    ) {
                        PasswordField(
                            value = exportPassword,
                            onValueChange = { exportPassword = it },
                            modifier = Modifier.padding(top = 12.dp).fillMaxWidth().testTag("backup_export_password"),
                            label = "Export password",
                            supportingText = "Use 12 to 128 characters.",
                        )
                    }
                }
                Button(
                    onClick = {
                        viewModel.prepareExport(
                            mode =
                                if (includeSecrets) {
                                    BackupExportMode.ENCRYPTED_WITH_CREDENTIALS
                                } else {
                                    BackupExportMode.CREDENTIALS_EXCLUDED
                                },
                            password = exportPassword,
                        )
                    },
                    enabled = !state.busy && (!includeSecrets || exportPassword.length in 12..128),
                    modifier = Modifier.testTag("backup_export"),
                ) {
                    Text("Export")
                }
            }
        }

        SettingsGroup("Import configuration") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Same grouping trick as the export block: each collapsed section stays outside
                // the spacedBy flow and carries its own 12dp gap inside the animation.
                Column {
                    OutlinedButton(
                        onClick = { openDocument.launch(arrayOf("application/json", "text/*", "*/*")) },
                        enabled = !state.busy,
                        modifier = Modifier.testTag("backup_import_choose"),
                    ) {
                        Text("Choose backup")
                    }
                    AnimatedVisibility(
                        visible = state.importNeedsPassword,
                        enter = fadeIn(MotdMotion.microFadeIn) + expandVertically(animationSpec = MotdMotion.contentSize),
                        exit = fadeOut(MotdMotion.microFadeOut) + shrinkVertically(animationSpec = MotdMotion.contentSize),
                    ) {
                        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            PasswordField(
                                value = importPassword,
                                onValueChange = { importPassword = it },
                                modifier = Modifier.fillMaxWidth().testTag("backup_import_password"),
                                label = "Backup password",
                            )
                            Button(
                                onClick = { viewModel.previewImport(importPassword) },
                                enabled = !state.busy && importPassword.isNotBlank(),
                                modifier = Modifier.testTag("backup_import_preview_encrypted"),
                            ) {
                                Text("Preview")
                            }
                        }
                    }
                }
                Column {
                    ImportModeRow(
                        selected = state.importMode,
                        onSelected = viewModel::setImportMode,
                    )
                    // Exit latch: the preview nulls when the import applies, so the outgoing block
                    // holds the last real preview while it collapses.
                    var lastPreview by remember { mutableStateOf<ConfigurationImportPreview?>(null) }
                    state.preview?.let { lastPreview = it }
                    AnimatedVisibility(
                        visible = state.preview != null,
                        enter = fadeIn(MotdMotion.microFadeIn) + expandVertically(animationSpec = MotdMotion.contentSize),
                        exit = fadeOut(MotdMotion.microFadeOut) + shrinkVertically(animationSpec = MotdMotion.contentSize),
                    ) {
                        lastPreview?.let { preview ->
                            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                ImportPreview(preview)
                                Button(
                                    onClick = { viewModel.applyImport(importPassword) },
                                    // Also disabled while the block is exiting (preview nulled):
                                    // the latched content stays tappable for the collapse frames,
                                    // and a second tap there would re-run the import.
                                    enabled = !state.busy && state.preview != null,
                                    modifier = Modifier.testTag("backup_import_apply"),
                                ) {
                                    Text(if (state.importMode == BackupImportMode.REPLACE && preview.removedNetworks > 0) "Replace configuration" else "Apply import")
                                }
                            }
                        }
                    }
                }
            }
        }

        if (state.busy) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Text("Working...")
            }
        }
        state.message?.let {
            Text(
                it,
                modifier = Modifier.testTag("backup_restore_message"),
                color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
internal fun ImportModeRow(
    selected: BackupImportMode,
    onSelected: (BackupImportMode) -> Unit,
) {
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Import mode", fontWeight = FontWeight.Medium)
        ImportModeOption("Merge", "Update matching networks and keep local-only networks.", BackupImportMode.MERGE, selected, onSelected)
        ImportModeOption("Replace", "Remove local-only networks and their local history.", BackupImportMode.REPLACE, selected, onSelected)
    }
}

@Composable
private fun ImportModeOption(
    label: String,
    description: String,
    mode: BackupImportMode,
    selected: BackupImportMode,
    onSelected: (BackupImportMode) -> Unit,
) {
    val isSelected = selected == mode
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = isSelected, role = Role.RadioButton) { onSelected(mode) }
                .testTag("backup_import_mode_${mode.name.lowercase()}")
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = isSelected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(label)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ImportPreview(preview: ConfigurationImportPreview) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("backup_import_preview")) {
        Text("Preview", fontWeight = FontWeight.Medium)
        Text("${preview.networkCount} networks from motd ${preview.appVersion}")
        Text("Add ${preview.addedNetworks}, update ${preview.updatedNetworks}, remove ${preview.removedNetworks}")
        Text("${preview.folderCount} folders, ${preview.folderAssignmentCount} chat assignments")
        Text("Settings: ${preview.settingGroups.joinToString().ifBlank { "none" }}")
        if (preview.containsSecrets) Text("Credentials are included in this encrypted backup.")
        if (preview.retainedLocalCredentials > 0) Text("${preview.retainedLocalCredentials} networks will retain local credentials.")
        if (preview.missingCredentialNetworks > 0) Text("${preview.missingCredentialNetworks} networks need credentials before connecting.")
    }
}

@HiltViewModel
class BackupRestoreViewModel
    @Inject
    constructor(
        private val repository: ConfigurationBackupRepository,
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        private val _state = MutableStateFlow(BackupRestoreUiState())
        val state: StateFlow<BackupRestoreUiState> = _state.asStateFlow()
        private var preparedExport: String? = null
        private var importedDocument: String? = null

        fun prepareExport(
            mode: BackupExportMode,
            password: String,
        ) {
            viewModelScope.launch {
                runBusy {
                    preparedExport = repository.exportToString(mode, password.takeIf(String::isNotBlank))
                    _state.update {
                        it.copy(
                            exportRequestToken = System.currentTimeMillis(),
                            message = null,
                            error = false,
                        )
                    }
                }
            }
        }

        fun writePreparedExport(uri: Uri) {
            viewModelScope.launch {
                runBusy {
                    val content = preparedExport ?: error("No export is ready.")
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(content.encodeToByteArray())
                    } ?: error("Could not open export destination.")
                    preparedExport = null
                    _state.update { it.copy(exportRequestToken = null, message = "Configuration exported.", error = false) }
                }
            }
        }

        fun cancelPreparedExport() {
            preparedExport = null
            _state.update { it.copy(exportRequestToken = null) }
        }

        fun loadImport(uri: Uri) {
            viewModelScope.launch {
                runBusy {
                    importedDocument = readText(uri)
                    val encrypted = repository.isEncrypted(importedDocument.orEmpty())
                    _state.update {
                        it.copy(
                            importNeedsPassword = encrypted,
                            preview = null,
                            message = if (encrypted) "Enter the backup password to preview." else null,
                            error = false,
                        )
                    }
                    if (!encrypted) previewImport("")
                }
            }
        }

        fun setImportMode(mode: BackupImportMode) {
            _state.update { it.copy(importMode = mode) }
            if (importedDocument != null && !_state.value.importNeedsPassword) previewImport("")
        }

        fun previewImport(password: String) {
            viewModelScope.launch {
                runBusy {
                    val raw = importedDocument ?: error("Choose a backup first.")
                    val preview = repository.preview(raw, password.takeIf(String::isNotBlank), _state.value.importMode)
                    _state.update {
                        it.copy(
                            preview = preview,
                            message = null,
                            error = false,
                        )
                    }
                }
            }
        }

        fun applyImport(password: String) {
            viewModelScope.launch {
                runBusy {
                    val raw = importedDocument ?: error("Choose a backup first.")
                    val result = repository.import(raw, password.takeIf(String::isNotBlank), _state.value.importMode)
                    _state.update {
                        it.copy(
                            preview = null,
                            message = "Imported ${result.addedNetworks} added, ${result.updatedNetworks} updated, ${result.removedNetworks} removed. ${result.missingCredentialNetworks} need credentials.",
                            error = false,
                        )
                    }
                }
            }
        }

        private suspend fun runBusy(block: suspend () -> Unit) {
            _state.update { it.copy(busy = true, message = null, error = false) }
            runCatching { block() }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            message = failure.message ?: "Backup operation failed.",
                            error = true,
                        )
                    }
                }
            _state.update { it.copy(busy = false) }
        }

        private fun readText(uri: Uri): String {
            val output = ByteArrayOutputStream()
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_IMPORT_BYTES) { "Backup file is too large." }
                    output.write(buffer, 0, read)
                }
            } ?: error("Could not open backup file.")
            return output.toString(Charsets.UTF_8.name())
        }

        private companion object {
            const val MAX_IMPORT_BYTES = 4 * 1024 * 1024
        }
    }

data class BackupRestoreUiState(
    val busy: Boolean = false,
    val exportRequestToken: Long? = null,
    val importMode: BackupImportMode = BackupImportMode.MERGE,
    val importNeedsPassword: Boolean = false,
    val preview: ConfigurationImportPreview? = null,
    val message: String? = null,
    val error: Boolean = false,
)
