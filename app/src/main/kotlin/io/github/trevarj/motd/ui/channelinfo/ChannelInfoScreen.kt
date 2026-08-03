package io.github.trevarj.motd.ui.channelinfo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.prefs.matchesConfiguredNick
import io.github.trevarj.motd.ui.chat.LagTone
import io.github.trevarj.motd.ui.chat.NickActionSheet
import io.github.trevarj.motd.ui.chat.lagTone
import io.github.trevarj.motd.ui.components.Avatar
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.service.RosterLoadState

/** Stateful entry: wires the ViewModel and drives navigation/leave. */
@Composable
fun ChannelInfoScreen(
    bufferId: Long,
    onBack: () -> Unit = {},
    onOpenBuffer: (Long) -> Unit = {},
    viewModel: ChannelInfoViewModel = hiltViewModel(),
) {
    LaunchedEffect(bufferId) { viewModel.init(bufferId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val nickSheet by viewModel.nickSheet.collectAsStateWithLifecycle()
    val topicMutation by viewModel.topicMutation.collectAsStateWithLifecycle()
    val leaveMutation by viewModel.leaveMutation.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel, onBack) {
        viewModel.operationEvents.collect { event ->
            if (event is ChannelInfoOperationEvent.LeaveAccepted) onBack()
        }
    }

    ChannelInfoContent(
        state = state,
        onBack = onBack,
        onSetPinned = viewModel::setPinned,
        onSetMuted = viewModel::setMuted,
        onLeave = viewModel::part,
        leaveMutation = leaveMutation,
        onBeginLeave = viewModel::beginLeave,
        onMemberClick = viewModel::openNickSheet,
        onSetTopic = viewModel::setTopic,
        topicMutation = topicMutation,
        onBeginTopicEdit = viewModel::beginTopicEdit,
        onInvite = viewModel::invite,
        onSetBanMask = viewModel::setBanMask,
        onSetChannelMode = viewModel::setChannelMode,
        onRetryMembers = viewModel::retryMembers,
        onQueryChange = viewModel::setQuery,
    )

    // Nick sheet (plans/16 §5.8): shared with the chat timeline. Moderation shown only when op.
    nickSheet?.let { sheet ->
        NickActionSheet(
            nick = sheet.nick,
            networkId = state.buffer?.networkId,
            isSelf = false,
            isFriend = state.identityRules.matchesConfiguredNick(sheet.nick, state.friends),
            isFool = state.identityRules.matchesConfiguredNick(sheet.nick, state.fools),
            canModerate = state.canModerate,
            whois = sheet.details,
            presence = sheet.presence,
            onDismiss = viewModel::dismissNickSheet,
            onMessage = { viewModel.dismissNickSheet(); viewModel.messageMember(sheet.nick, onOpenBuffer) },
            onMention = { viewModel.dismissNickSheet(); viewModel.mentionMember(sheet.nick, onDone = onBack) },
            onToggleFriend = { viewModel.toggleFriend(sheet.nick) },
            onToggleFool = { viewModel.toggleFool(sheet.nick) },
            onIgnoreNetwork = { viewModel.ignoreNickOnNetwork(sheet.nick) },
            onOp = { grant -> viewModel.setMemberMode(sheet.nick, 'o', grant) },
            onVoice = { grant -> viewModel.setMemberMode(sheet.nick, 'v', grant) },
            onKick = { reason -> viewModel.dismissNickSheet(); viewModel.kick(sheet.nick, reason) },
            onBan = { viewModel.dismissNickSheet(); viewModel.ban(sheet.nick) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelInfoContent(
    state: ChannelInfoUiState,
    onBack: () -> Unit,
    onSetPinned: (Boolean) -> Unit,
    onSetMuted: (Boolean) -> Unit,
    onLeave: () -> Unit,
    leaveMutation: LeaveMutationState = LeaveMutationState.Idle,
    onBeginLeave: () -> Unit = {},
    onMemberClick: (String) -> Unit = {},
    onSetTopic: (String) -> Unit = {},
    topicMutation: TopicMutationState = TopicMutationState.Idle,
    onBeginTopicEdit: () -> Unit = {},
    onInvite: (String) -> Unit = {},
    onSetBanMask: (String, Boolean) -> Unit = { _, _ -> },
    onSetChannelMode: (String, String) -> Unit = { _, _ -> },
    onRetryMembers: () -> Unit = {},
    onQueryChange: (String) -> Unit = {},
) {
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var showTopicEdit by remember { mutableStateOf(false) }
    // Fools section is collapsed by default; state is local to the screen (plans/13 §3.6).
    var foolsExpanded by remember { mutableStateOf(false) }
    val buffer = state.buffer
    // Visible query lives in local IME state so keystrokes aren't dropped and the cursor is
    // preserved; the ViewModel query drives the filter only. Seeded once from incoming state.
    var queryText by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(state.query))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.channelinfo_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.onboarding_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item(key = "header") {
                ChannelHeader(
                    buffer = buffer,
                    memberCount = state.memberCount,
                    rosterState = state.rosterState,
                    hasStaleMembers = state.hasStaleMembers,
                    lagMs = state.lagMs,
                    connected = state.connected,
                    onRetryMembers = onRetryMembers,
                    onEditTopic = {
                        onBeginTopicEdit()
                        showTopicEdit = true
                    },
                )
            }
            item(key = "actions") {
                ActionsRow(
                    buffer = buffer,
                    onSetPinned = onSetPinned,
                    onSetMuted = onSetMuted,
                    onLeave = {
                        onBeginLeave()
                        showLeaveConfirm = true
                    },
                )
            }
            if (state.canModerate && buffer?.type == BufferType.CHANNEL) {
                item(key = "channel-tools") {
                    ChannelManagementTools(
                        onInvite = onInvite,
                        onSetBanMask = onSetBanMask,
                        onSetChannelMode = onSetChannelMode,
                    )
                }
            }
            item(key = "search-field") {
                OutlinedTextField(
                    value = queryText,
                    onValueChange = { queryText = it; onQueryChange(it.text) },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.channelinfo_member_search_hint)) },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = null,
                        )
                    },
                    trailingIcon = {
                        if (queryText.text.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    queryText = TextFieldValue("")
                                    onQueryChange("")
                                },
                            ) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = stringResource(R.string.channelinfo_member_search_clear),
                                )
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { /* in-memory filter; nothing to fetch */ }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("channelinfo_member_search_field"),
                )
            }
            val searchResults = state.searchResults
            if (searchResults != null) {
                if (searchResults.isEmpty()) {
                    item(key = "search-empty") {
                        Text(
                            text = stringResource(R.string.channelinfo_member_search_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                } else {
                    items(searchResults, key = { "search-${it.nick}" }) { member ->
                        MemberRow(
                            member = member,
                            networkId = buffer?.networkId,
                            isFriend = state.identityRules.matchesConfiguredNick(member.nick, state.friends),
                            onClick = { onMemberClick(member.nick) },
                        )
                    }
                }
            } else {
                state.sections.forEach { section ->
                    item(key = "sec-${section.prefix ?: "regular"}") {
                        Text(
                            text = section.prefix?.let { "$it" } ?: stringResource(R.string.channelinfo_section_regular),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(section.members, key = { "${section.prefix}-${it.nick}" }) { member ->
                        MemberRow(
                            member = member,
                            networkId = buffer?.networkId,
                            isFriend = state.identityRules.matchesConfiguredNick(member.nick, state.friends),
                            onClick = { onMemberClick(member.nick) },
                        )
                    }
                }
            }
            if (state.searchResults == null && state.foolMembers.isNotEmpty()) {
                item(key = "fools-header") {
                    FoolsSectionHeader(
                        count = state.foolMembers.size,
                        expanded = foolsExpanded,
                        onToggle = { foolsExpanded = !foolsExpanded },
                    )
                }
                if (foolsExpanded) {
                    items(state.foolMembers, key = { "fool-${it.nick}" }) { member ->
                        Box(modifier = Modifier.alpha(0.55f)) {
                            MemberRow(
                                member = member,
                                networkId = buffer?.networkId,
                                isFriend = false,
                                onClick = { onMemberClick(member.nick) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = {
                if (leaveMutation !is LeaveMutationState.Submitting) showLeaveConfirm = false
            },
            modifier = Modifier.testTag("channelinfo_leave_dialog"),
            title = { Text(stringResource(R.string.channelinfo_leave_confirm_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.channelinfo_leave_confirm_message))
                    if (leaveMutation is LeaveMutationState.Failed) {
                        Text(
                            text = stringResource(R.string.channelinfo_leave_failed),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .testTag("channelinfo_leave_error"),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onLeave,
                    enabled = leaveMutation !is LeaveMutationState.Submitting,
                    modifier = Modifier.testTag("channelinfo_leave_confirm"),
                ) {
                    Text(stringResource(R.string.channelinfo_leave))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showLeaveConfirm = false },
                    enabled = leaveMutation !is LeaveMutationState.Submitting,
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // Topic edit (plans/16 §5.8): a multiline dialog prefilled with the current topic. Always
    // offered for CHANNEL buffers; a 482 (no privileges) lands in the server buffer.
    if (showTopicEdit && buffer != null) {
        TopicEditDialog(
            initial = buffer.topic.orEmpty(),
            mutation = topicMutation,
            onDismiss = { showTopicEdit = false },
            onAccepted = { showTopicEdit = false },
            onSave = onSetTopic,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TopicEditDialog(
    initial: String,
    mutation: TopicMutationState,
    onDismiss: () -> Unit,
    onAccepted: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val submitting = mutation is TopicMutationState.Submitting
    LaunchedEffect(mutation) {
        if (mutation is TopicMutationState.Accepted) onAccepted()
    }
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        modifier = Modifier.testTag("channelinfo_topic_edit_dialog"),
        title = { Text(stringResource(R.string.channelinfo_topic_edit_title)) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.channelinfo_topic_edit_hint)) },
                    minLines = 2,
                    maxLines = 6,
                    enabled = !submitting,
                    modifier = Modifier.testTag("channelinfo_topic_edit_text"),
                )
                if (mutation is TopicMutationState.Failed) {
                    Text(
                        text = stringResource(R.string.channelinfo_topic_edit_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .testTag("channelinfo_topic_edit_error"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text) },
                enabled = !submitting,
                modifier = Modifier.testTag("channelinfo_topic_edit_save"),
            ) {
                Text(stringResource(R.string.channelinfo_topic_edit_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun FoolsSectionHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.channelinfo_fools_section, count),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ChannelHeader(
    buffer: BufferEntity?,
    memberCount: Int?,
    rosterState: RosterLoadState,
    hasStaleMembers: Boolean,
    lagMs: Long?,
    connected: Boolean,
    onEditTopic: () -> Unit = {},
    onRetryMembers: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val name = buffer?.displayName ?: ""
        Avatar(
            name = name,
            size = 88.dp,
            isChannel = buffer?.type == BufferType.CHANNEL,
            networkId = buffer?.networkId,
        )
        Text(
            text = name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 12.dp),
        )
        // Topic + edit affordance (CHANNEL buffers only). Shown even when the topic is blank so an
        // op can set an initial topic (plans/16 §5.8).
        if (buffer?.type == BufferType.CHANNEL) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                val topic = buffer.topic?.takeIf { it.isNotBlank() }
                if (topic != null) {
                    Text(
                        text = topic,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false).testTag("channelinfo_topic"),
                    )
                }
                IconButton(onClick = onEditTopic) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.channelinfo_topic_edit_action),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        val rosterText = when {
            memberCount != null -> pluralStringResource(R.plurals.channelinfo_members, memberCount, memberCount)
            hasStaleMembers -> stringResource(R.string.channelinfo_members_stale)
            rosterState == RosterLoadState.FAILED -> stringResource(R.string.channelinfo_members_failed)
            else -> stringResource(R.string.channelinfo_members_loading)
        }
        Text(
            text = rosterText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp).testTag("channelinfo_roster_state"),
        )
        // Subtle network latency readout (#34). Only shown once a PONG round-trip has completed on
        // a Ready connection, so an offline/loading channel info page stays uncluttered.
        val resolvedLag = lagMs?.takeIf { connected && it >= 0 }
        if (resolvedLag != null) {
            LagReadout(
                lagMs = resolvedLag,
                modifier = Modifier.padding(top = 6.dp).testTag("channelinfo_lag"),
            )
        }
        if (rosterState == RosterLoadState.FAILED) {
            TextButton(
                onClick = onRetryMembers,
                modifier = Modifier.testTag("channelinfo_roster_retry"),
            ) {
                Text(stringResource(R.string.channelinfo_members_retry))
            }
        }
    }
}

@Composable
private fun ActionsRow(
    buffer: BufferEntity?,
    onSetPinned: (Boolean) -> Unit,
    onSetMuted: (Boolean) -> Unit,
    onLeave: () -> Unit,
) {
    val pinned = buffer?.pinned == true
    val muted = buffer?.muted == true
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        ActionItem(
            icon = if (muted) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff,
            label = stringResource(if (muted) R.string.channelinfo_unmute else R.string.channelinfo_mute),
            onClick = { onSetMuted(!muted) },
        )
        ActionItem(
            icon = Icons.Outlined.PushPin,
            label = stringResource(if (pinned) R.string.channelinfo_unpin else R.string.channelinfo_pin),
            onClick = { onSetPinned(!pinned) },
        )
        if (buffer?.type == BufferType.CHANNEL) {
            ActionItem(
                icon = Icons.AutoMirrored.Outlined.Logout,
                label = stringResource(R.string.channelinfo_leave),
                onClick = onLeave,
            )
        }
    }
}

@Composable
private fun ActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.clickable(onClick = onClick).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ChannelManagementTools(
    onInvite: (String) -> Unit,
    onSetBanMask: (String, Boolean) -> Unit,
    onSetChannelMode: (String, String) -> Unit,
) {
    var inviteNick by remember { mutableStateOf("") }
    var banMask by remember { mutableStateOf("") }
    var modes by remember { mutableStateOf("") }
    var modeArgs by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.channelinfo_operator_tools),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = inviteNick,
            onValueChange = { inviteNick = it },
            label = { Text(stringResource(R.string.channelinfo_invite_nick)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("channelinfo_invite_nick"),
        )
        Button(
            onClick = { onInvite(inviteNick); inviteNick = "" },
            enabled = inviteNick.isNotBlank(),
        ) { Text(stringResource(R.string.channelinfo_invite)) }
        OutlinedTextField(
            value = banMask,
            onValueChange = { banMask = it },
            label = { Text(stringResource(R.string.channelinfo_ban_mask)) },
            supportingText = { Text(stringResource(R.string.network_tools_ignore_help)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("channelinfo_ban_mask"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSetBanMask(banMask, true) },
                enabled = banMask.isNotBlank(),
            ) { Text(stringResource(R.string.channelinfo_set_ban)) }
            OutlinedButton(
                onClick = { onSetBanMask(banMask, false); banMask = "" },
                enabled = banMask.isNotBlank(),
            ) { Text(stringResource(R.string.channelinfo_remove_ban)) }
        }
        OutlinedTextField(
            value = modes,
            onValueChange = { modes = it },
            label = { Text(stringResource(R.string.network_tools_modes)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("channelinfo_modes"),
        )
        OutlinedTextField(
            value = modeArgs,
            onValueChange = { modeArgs = it },
            label = { Text(stringResource(R.string.network_tools_mode_args)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = { onSetChannelMode(modes, modeArgs); modes = ""; modeArgs = "" },
            enabled = modes.isNotBlank(),
        ) { Text(stringResource(R.string.network_tools_send_mode)) }
    }
}

@Composable
private fun MemberRow(member: MemberEntity, networkId: Long?, isFriend: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(member.prefixes.take(1) + member.nick) },
        leadingContent = { Avatar(name = member.nick, size = 36.dp, networkId = networkId) },
        trailingContent = if (isFriend) {
            {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            null
        },
        // Per-member handle so the harness selects a specific member row.
        modifier = Modifier.testTag("channelinfo_member_${member.nick}").clickable(onClick = onClick),
    )
}

@Preview
@Composable
private fun ChannelInfoContentPreview() {
    MotdTheme {
        ChannelInfoContent(
            state = ChannelInfoUiState(
                buffer = BufferEntity(
                    id = 1, networkId = 1, name = "#kotlin", displayName = "#kotlin",
                    type = BufferType.CHANNEL, topic = "Kotlin discussion — be nice",
                    pinned = true, muted = false,
                ),
                sections = sectionMembers(
                    listOf(
                        MemberEntity(1, "owner", "~"),
                        MemberEntity(1, "op", "@"),
                        MemberEntity(1, "voiced", "+"),
                        MemberEntity(1, "alice", ""),
                        MemberEntity(1, "bob", ""),
                    ),
                ),
                memberCount = 5,
                rosterState = RosterLoadState.LOADED,
            ),
            onBack = {}, onSetPinned = {}, onSetMuted = {}, onLeave = {},
            onQueryChange = {},
        )
    }
}

/**
 * Subtle latency readout for the Channel Info header (#34): a small status dot whose tone follows
 * [lagTone] alongside the millisecond value. Inline (not a pill) so it reads as supporting metadata
 * under the channel name rather than a prominent status banner.
 */
@Composable
private fun LagReadout(
    lagMs: Long,
    modifier: Modifier = Modifier,
) {
    val tone = lagTone(lagMs)
    val dotColor = when (tone) {
        LagTone.GOOD -> MaterialTheme.colorScheme.primary
        LagTone.DEGRADED -> MaterialTheme.colorScheme.tertiary
        LagTone.BAD -> MaterialTheme.colorScheme.error
    }
    val description = stringResource(R.string.chat_lag_content_description, lagMs)
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = description
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(dotColor),
        )
        Text(
            text = stringResource(R.string.chat_lag_ms, lagMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
