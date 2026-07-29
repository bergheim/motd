package io.github.trevarj.motd.ui.settings.xmpp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.settings.PasswordField
import io.github.trevarj.motd.ui.settings.SettingsGroup
import io.github.trevarj.motd.ui.settings.SettingsScaffold
import io.github.trevarj.motd.ui.settings.SwitchRow
import io.github.trevarj.motd.ui.theme.MotdTheme

/**
 * Stateful entry: wires [XmppAccountViewModel] and drives navigation. [networkId] null creates a
 * new account; non-null edits the existing one (docs/backend-neutral-xmpp-rollout.md baseline
 * "account creation and edits").
 */
@Composable
fun XmppAccountScreen(
    networkId: Long?,
    onBack: () -> Unit = {},
    viewModel: XmppAccountViewModel = hiltViewModel(),
) {
    LaunchedEffect(networkId) { viewModel.init(networkId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    XmppAccountContent(
        state = state,
        onBack = onBack,
        onDisplayNameChange = viewModel::editDisplayName,
        onJidChange = viewModel::editJid,
        onPasswordChange = viewModel::editPassword,
        onResourceChange = viewModel::editResource,
        onAutoConnectChange = viewModel::setAutoConnect,
        onSave = { viewModel.save(onBack) },
        onDelete = { viewModel.delete(onBack) },
    )
}

@Composable
fun XmppAccountContent(
    state: XmppAccountUiState,
    onBack: () -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onJidChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onResourceChange: (String) -> Unit,
    onAutoConnectChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val title = state.displayName.ifBlank {
        stringResource(if (state.isEdit) R.string.xmpp_account_edit_title else R.string.xmpp_account_create_title)
    }

    SettingsScaffold(title = title, onBack = onBack) {
        SettingsGroup(title = stringResource(R.string.xmpp_account_details_section)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = state.displayName,
                    onValueChange = onDisplayNameChange,
                    label = { Text(stringResource(R.string.xmpp_account_display_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().testTag("xmpp_account_display_name"),
                )
                OutlinedTextField(
                    value = state.jid,
                    onValueChange = onJidChange,
                    label = { Text(stringResource(R.string.xmpp_account_jid)) },
                    placeholder = { Text("user@example.org") },
                    singleLine = true,
                    isError = state.jid.isNotEmpty() && !state.jidValid,
                    supportingText = {
                        if (state.jid.isNotEmpty() && !state.jidValid) {
                            Text(stringResource(R.string.xmpp_account_jid_error))
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("xmpp_account_jid"),
                )
                PasswordField(
                    value = state.password,
                    onValueChange = onPasswordChange,
                    label = stringResource(R.string.xmpp_account_password),
                    modifier = Modifier.testTag("xmpp_account_password"),
                )
                OutlinedTextField(
                    value = state.resource,
                    onValueChange = onResourceChange,
                    label = { Text(stringResource(R.string.xmpp_account_resource)) },
                    supportingText = { Text(stringResource(R.string.xmpp_account_resource_desc)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("xmpp_account_resource"),
                )
            }
        }

        if (state.isEdit) {
            SettingsGroup {
                SwitchRow(
                    title = stringResource(R.string.network_settings_autoconnect),
                    subtitle = stringResource(R.string.network_settings_autoconnect_desc),
                    checked = state.autoConnect,
                    onCheckedChange = onAutoConnectChange,
                    switchTag = "xmpp_account_autoconnect",
                )
            }
        }

        Button(
            onClick = onSave,
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth().testTag("xmpp_account_save"),
        ) {
            Text(
                stringResource(
                    if (state.isEdit) R.string.network_settings_save else R.string.add_network_connect_save,
                ),
            )
        }

        if (state.isEdit) {
            SettingsGroup(title = stringResource(R.string.network_settings_danger_section)) {
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("xmpp_account_delete"),
                ) {
                    Text(
                        stringResource(R.string.network_settings_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.network_settings_delete_confirm_title)) },
            text = { Text(stringResource(R.string.network_settings_delete_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = { showDeleteConfirm = false; onDelete() },
                    modifier = Modifier.testTag("xmpp_account_delete_confirm"),
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Preview
@Composable
private fun XmppAccountCreatePreview() {
    MotdTheme {
        XmppAccountContent(
            state = XmppAccountUiState(loaded = true),
            onBack = {}, onDisplayNameChange = {}, onJidChange = {}, onPasswordChange = {},
            onResourceChange = {}, onAutoConnectChange = {}, onSave = {}, onDelete = {},
        )
    }
}

@Preview
@Composable
private fun XmppAccountEditPreview() {
    MotdTheme {
        XmppAccountContent(
            state = XmppAccountUiState(
                loaded = true, isEdit = true, networkId = 1,
                displayName = "Home", jid = "alice@example.org", password = "hunter2",
            ),
            onBack = {}, onDisplayNameChange = {}, onJidChange = {}, onPasswordChange = {},
            onResourceChange = {}, onAutoConnectChange = {}, onSave = {}, onDelete = {},
        )
    }
}
