package io.github.trevarj.motd.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.audio.VoiceConfig
import io.github.trevarj.motd.audio.VoiceRecordingQuality
import io.github.trevarj.motd.avatar.AvatarConfig
import io.github.trevarj.motd.data.prefs.AUTO_AWAY_MINUTE_CHOICES
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.prefs.ReplyConfig
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.service.autoAwayText
import io.github.trevarj.motd.ui.chat.presenceModeDescription
import io.github.trevarj.motd.ui.chat.presenceModeLabel
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.SheetSystemBars

/** Chat category: presence-event visibility, friends/fools management, and fools' message handling. */
@Composable
fun ChatSettingsScreen(
    onBack: () -> Unit = {},
    onOpenFriends: () -> Unit = {},
    onOpenFools: () -> Unit = {},
    onOpenDirectConnections: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val audioCacheCleared = stringResource(R.string.settings_audio_cache_cleared)
    val audioCacheClearFailed = stringResource(R.string.settings_audio_cache_clear_failed)
    LaunchedEffect(viewModel, context, audioCacheCleared, audioCacheClearFailed) {
        viewModel.audioCacheClearEvents.collect { event ->
            val message =
                when (event) {
                    AudioCacheClearEvent.CLEARED -> audioCacheCleared
                    AudioCacheClearEvent.FAILED -> audioCacheClearFailed
                }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    ChatSettingsContent(
        settings = state.settings,
        reply = state.reply,
        contentPreviews = state.contentPreviews,
        voice = state.voice,
        avatars = state.avatars,
        onBack = onBack,
        onOpenFriends = onOpenFriends,
        onOpenFools = onOpenFools,
        onOpenDirectConnections = onOpenDirectConnections,
        onPresenceMode = viewModel::setPresenceMode,
        onAutoAwayEnabled = viewModel::setAutoAwayEnabled,
        onAutoAwayMinutes = viewModel::setAutoAwayMinutes,
        onAutoAwayMessage = viewModel::setAutoAwayMessage,
        onFoolsMode = viewModel::setFoolsMode,
        onShowComposerEmoji = viewModel::setShowComposerEmoji,
        onShowComposerFormattingTools = viewModel::setShowComposerFormattingTools,
        onChatSoundsEnabled = viewModel::setChatSoundsEnabled,
        onVisibleReplyPrefix = viewModel::setVisibleReplyPrefix,
        onShowImages = viewModel::setShowImages,
        onShowLinkPreviews = viewModel::setShowLinkPreviews,
        onDirectMediaOnProxiedNetworks = viewModel::setDirectMediaOnProxiedNetworks,
        onShowSharedAvatars = viewModel::setShowSharedAvatars,
        onVoiceEncryptionDefault = viewModel::setVoiceEncryptionDefault,
        onVoiceQuality = viewModel::setVoiceQuality,
        onVoiceNoiseReduction = viewModel::setVoiceNoiseReduction,
        onClearAudioCache = viewModel::clearAudioCache,
    )
}

@Composable
fun ChatSettingsContent(
    settings: Settings,
    reply: ReplyConfig,
    contentPreviews: ContentPreviewConfig,
    voice: VoiceConfig,
    avatars: AvatarConfig,
    onBack: () -> Unit,
    onOpenFriends: () -> Unit,
    onOpenFools: () -> Unit,
    onOpenDirectConnections: () -> Unit,
    onPresenceMode: (PresenceMode) -> Unit,
    onAutoAwayEnabled: (Boolean) -> Unit,
    onAutoAwayMinutes: (Int) -> Unit,
    onAutoAwayMessage: (String) -> Unit,
    onFoolsMode: (FoolsMode) -> Unit,
    onShowComposerEmoji: (Boolean) -> Unit,
    onShowComposerFormattingTools: (Boolean) -> Unit,
    onChatSoundsEnabled: (Boolean) -> Unit,
    onVisibleReplyPrefix: (Boolean) -> Unit,
    onShowImages: (Boolean) -> Unit,
    onShowLinkPreviews: (Boolean) -> Unit,
    onDirectMediaOnProxiedNetworks: (Boolean) -> Unit,
    onShowSharedAvatars: (Boolean) -> Unit,
    onVoiceEncryptionDefault: (Boolean) -> Unit,
    onVoiceQuality: (VoiceRecordingQuality) -> Unit,
    onVoiceNoiseReduction: (Boolean) -> Unit,
    onClearAudioCache: () -> Unit,
) {
    var qualitySheetOpen by remember { mutableStateOf(false) }
    var awayMessageDialogOpen by remember { mutableStateOf(false) }
    val defaultAwayMessage = stringResource(R.string.auto_away_default_message)
    SettingsScaffold(title = stringResource(R.string.settings_chat), onBack = onBack) {
        SettingsGroup(title = stringResource(R.string.settings_messages_section)) {
            SubLabel(stringResource(R.string.settings_presence_title))
            PresenceModeGroup(current = settings.presenceMode, onSelect = onPresenceMode)
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchRow(
                title = stringResource(R.string.settings_show_images),
                subtitle = stringResource(R.string.settings_show_images_desc),
                checked = contentPreviews.showImages,
                onCheckedChange = onShowImages,
                switchTag = "settings_switch_show_images",
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchRow(
                title = stringResource(R.string.settings_direct_media_proxied),
                subtitle = stringResource(R.string.settings_direct_media_proxied_desc),
                checked = contentPreviews.directMediaOnProxiedNetworks,
                onCheckedChange = onDirectMediaOnProxiedNetworks,
                switchTag = "settings_switch_direct_media_proxied",
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchRow(
                title = stringResource(R.string.settings_show_link_previews),
                subtitle = stringResource(R.string.settings_show_link_previews_desc),
                checked = contentPreviews.showLinkPreviews,
                onCheckedChange = onShowLinkPreviews,
                switchTag = "settings_switch_show_link_previews",
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchRow(
                title = stringResource(R.string.settings_show_shared_avatars),
                subtitle = stringResource(R.string.settings_show_shared_avatars_desc),
                checked = avatars.showSharedAvatars,
                onCheckedChange = onShowSharedAvatars,
                switchTag = "settings_switch_show_shared_avatars",
            )
        }
        // Deliberately after the message-display group: the presence radios stay the first thing on
        // this screen, which is what the required E2E journey asserts without scrolling.
        SettingsGroup(title = stringResource(R.string.settings_auto_away_section)) {
            SwitchRow(
                title = stringResource(R.string.settings_auto_away),
                subtitle = stringResource(R.string.settings_auto_away_desc),
                checked = settings.autoAwayEnabled,
                onCheckedChange = onAutoAwayEnabled,
                switchTag = "settings_auto_away_switch",
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SubLabel(stringResource(R.string.settings_auto_away_delay))
            AutoAwayDelayGroup(
                current = settings.autoAwayMinutes,
                enabled = settings.autoAwayEnabled,
                onSelect = onAutoAwayMinutes,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_auto_away_message_title)) },
                supportingContent = { Text(autoAwayText(settings.autoAwayMessage, defaultAwayMessage)) },
                modifier =
                    Modifier
                        .clickable(enabled = settings.autoAwayEnabled) { awayMessageDialogOpen = true }
                        .testTag("settings_auto_away_message"),
            )
        }
        SettingsGroup(title = stringResource(R.string.settings_composer_section)) {
            SwitchRow(
                title = stringResource(R.string.settings_chat_sounds),
                subtitle = stringResource(R.string.settings_chat_sounds_desc),
                checked = settings.chatSoundsEnabled,
                onCheckedChange = onChatSoundsEnabled,
                switchTag = "settings_switch_chat_sounds",
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchRow(
                title = stringResource(R.string.settings_composer_emoji),
                subtitle = stringResource(R.string.settings_composer_emoji_desc),
                checked = settings.showComposerEmoji,
                onCheckedChange = onShowComposerEmoji,
                switchTag = "settings_switch_composer_emoji",
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchRow(
                title = stringResource(R.string.settings_composer_formatting_tools),
                subtitle = stringResource(R.string.settings_composer_formatting_tools_desc),
                checked = settings.showComposerFormattingTools,
                onCheckedChange = onShowComposerFormattingTools,
                switchTag = "settings_switch_composer_formatting_tools",
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchRow(
                title = stringResource(R.string.settings_reply_prefix),
                subtitle = stringResource(R.string.settings_reply_prefix_desc),
                checked = reply.visibleChannelPrefix,
                onCheckedChange = onVisibleReplyPrefix,
                switchTag = "settings_switch_reply_prefix",
            )
        }
        SettingsGroup(title = stringResource(R.string.settings_direct_connections)) {
            SettingsNavigationRow(
                icon = Icons.Outlined.AttachFile,
                title = stringResource(R.string.settings_direct_connections),
                summary = stringResource(R.string.settings_direct_connections_summary),
                modifier = Modifier.testTag("settings_direct_connections"),
                onClick = onOpenDirectConnections,
            )
        }
        SettingsGroup(title = stringResource(R.string.settings_voice_section)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_voice_quality)) },
                supportingContent = {
                    Text(
                        "${voiceQualityLabel(voice.quality)} · ${voiceQualityDescription(voice.quality)}",
                    )
                },
                modifier =
                    Modifier
                        .clickable { qualitySheetOpen = true }
                        .testTag("settings_voice_quality"),
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchRow(
                title = stringResource(R.string.settings_voice_noise_reduction),
                subtitle = stringResource(R.string.settings_voice_noise_reduction_desc),
                checked = voice.noiseReduction,
                onCheckedChange = onVoiceNoiseReduction,
                switchTag = "settings_switch_voice_noise_reduction",
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchRow(
                title = stringResource(R.string.settings_voice_encryption),
                subtitle = stringResource(R.string.settings_voice_encryption_desc),
                checked = voice.encryptionDefault,
                onCheckedChange = onVoiceEncryptionDefault,
                switchTag = "settings_switch_voice_encryption",
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_clear_audio_cache)) },
                supportingContent = { Text(stringResource(R.string.settings_clear_audio_cache_desc)) },
                modifier =
                    Modifier
                        .clickable(onClick = onClearAudioCache)
                        .testTag("settings_clear_audio_cache"),
            )
        }
        SettingsGroup(title = stringResource(R.string.settings_people)) {
            SettingsNavigationRow(
                icon = Icons.Outlined.PersonOutline,
                title = stringResource(R.string.settings_friends),
                value = pluralStringResource(R.plurals.settings_nick_count, settings.friends.size, settings.friends.size),
                modifier = Modifier.testTag("settings_friends"),
                onClick = onOpenFriends,
            )
            SettingsNavigationRow(
                icon = Icons.Outlined.Block,
                title = stringResource(R.string.settings_fools),
                value = pluralStringResource(R.plurals.settings_nick_count, settings.fools.size, settings.fools.size),
                modifier = Modifier.testTag("settings_fools"),
                onClick = onOpenFools,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SubLabel(stringResource(R.string.settings_fools_mode))
            FoolsModeGroup(current = settings.foolsMode, onSelect = onFoolsMode)
        }
    }
    if (awayMessageDialogOpen) {
        AutoAwayMessageDialog(
            initial = settings.autoAwayMessage,
            placeholder = defaultAwayMessage,
            onSave = {
                onAutoAwayMessage(it)
                awayMessageDialogOpen = false
            },
            onDismiss = { awayMessageDialogOpen = false },
        )
    }
    if (qualitySheetOpen) {
        VoiceQualitySheet(
            selected = voice.quality,
            onSelect = {
                onVoiceQuality(it)
                qualitySheetOpen = false
            },
            onDismiss = { qualitySheetOpen = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceQualitySheet(
    selected: VoiceRecordingQuality,
    onSelect: (VoiceRecordingQuality) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("settings_voice_quality_sheet")) {
        SheetSystemBars()
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            SubLabel(stringResource(R.string.settings_voice_quality))
            VoiceRecordingQuality.entries.forEach { quality ->
                RadioRow(
                    label = voiceQualityLabel(quality),
                    subtitle = voiceQualityDescription(quality),
                    selected = selected == quality,
                    enabled = true,
                    onClick = { onSelect(quality) },
                    modifier = Modifier.testTag("settings_voice_quality_${quality.name.lowercase()}"),
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun voiceQualityLabel(quality: VoiceRecordingQuality): String =
    when (quality) {
        VoiceRecordingQuality.DATA_SAVER -> "Data saver"
        VoiceRecordingQuality.BALANCED -> "Balanced"
        VoiceRecordingQuality.HIGH -> "High"
    }

private fun voiceQualityDescription(quality: VoiceRecordingQuality): String =
    when (quality) {
        VoiceRecordingQuality.DATA_SAVER -> "Opus 24 kbps · AAC 32 kbps"
        VoiceRecordingQuality.BALANCED -> "Opus 48 kbps · AAC 64 kbps"
        VoiceRecordingQuality.HIGH -> "Opus 64 kbps · AAC 96 kbps"
    }

/** Background delay before auto-away fires. Disabled (but visible) while the feature is off. */
@Composable
private fun AutoAwayDelayGroup(
    current: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.selectableGroup()) {
        AUTO_AWAY_MINUTE_CHOICES.forEach { minutes ->
            RadioRow(
                label = pluralStringResource(R.plurals.settings_auto_away_minutes, minutes, minutes),
                selected = current == minutes,
                enabled = enabled,
                onClick = { onSelect(minutes) },
                modifier = Modifier.testTag("settings_auto_away_minutes_$minutes"),
            )
        }
    }
}

/** Blank keeps the localized default, which the field advertises as its placeholder. */
@Composable
private fun AutoAwayMessageDialog(
    initial: String,
    placeholder: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_auto_away_message_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text(placeholder) },
                supportingText = { Text(stringResource(R.string.settings_auto_away_message_hint)) },
                modifier = Modifier.fillMaxWidth().testTag("settings_auto_away_message_field"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text) },
                modifier = Modifier.testTag("settings_auto_away_message_save"),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        modifier = Modifier.testTag("settings_auto_away_message_dialog"),
    )
}

/** Global presence-event choice. A conversation can override it from its own overflow menu. */
@Composable
private fun PresenceModeGroup(
    current: PresenceMode,
    onSelect: (PresenceMode) -> Unit,
) {
    Column(Modifier.selectableGroup()) {
        PresenceMode.entries.forEach { mode ->
            RadioRow(
                label = stringResource(presenceModeLabel(mode)),
                subtitle = stringResource(presenceModeDescription(mode)),
                selected = current == mode,
                enabled = true,
                onClick = { onSelect(mode) },
                modifier = Modifier.testTag("settings_presence_mode_${mode.name.lowercase()}"),
            )
        }
    }
}

@Composable
private fun FoolsModeGroup(
    current: FoolsMode,
    onSelect: (FoolsMode) -> Unit,
) {
    Column(Modifier.selectableGroup()) {
        RadioRow(
            label = stringResource(R.string.settings_fools_collapse),
            subtitle = stringResource(R.string.settings_fools_collapse_desc),
            selected = current == FoolsMode.COLLAPSE,
            enabled = true,
            onClick = { onSelect(FoolsMode.COLLAPSE) },
            modifier = Modifier.testTag("settings_fools_mode_collapse"),
        )
        RadioRow(
            label = stringResource(R.string.settings_fools_hide),
            subtitle = stringResource(R.string.settings_fools_hide_desc),
            selected = current == FoolsMode.HIDE,
            enabled = true,
            onClick = { onSelect(FoolsMode.HIDE) },
            modifier = Modifier.testTag("settings_fools_mode_hide"),
        )
    }
}

@Preview
@Composable
private fun ChatSettingsPreview() {
    MotdTheme {
        ChatSettingsContent(
            settings = Settings(friends = setOf("alice"), fools = setOf("bob", "carol")),
            reply = ReplyConfig(),
            contentPreviews = ContentPreviewConfig(),
            voice = VoiceConfig(),
            avatars = AvatarConfig(),
            onBack = {},
            onOpenFriends = {},
            onOpenFools = {},
            onOpenDirectConnections = {},
            onPresenceMode = {},
            onAutoAwayEnabled = {},
            onAutoAwayMinutes = {},
            onAutoAwayMessage = {},
            onFoolsMode = {},
            onShowComposerEmoji = {},
            onShowComposerFormattingTools = {},
            onChatSoundsEnabled = {},
            onVisibleReplyPrefix = {},
            onShowImages = {},
            onShowLinkPreviews = {},
            onDirectMediaOnProxiedNetworks = {},
            onShowSharedAvatars = {},
            onVoiceEncryptionDefault = {},
            onClearAudioCache = {},
            onVoiceQuality = {},
            onVoiceNoiseReduction = {},
        )
    }
}
