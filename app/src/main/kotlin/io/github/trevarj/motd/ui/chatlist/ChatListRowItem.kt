package io.github.trevarj.motd.ui.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.audio.displayTextForAudioMessage
import io.github.trevarj.motd.audio.formatAudioDuration
import io.github.trevarj.motd.audio.parseAudioAttachments
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.service.PresenceState
import io.github.trevarj.motd.ui.components.Avatar
import io.github.trevarj.motd.ui.components.MentionBadge
import io.github.trevarj.motd.ui.components.MutedActivityBadge
import io.github.trevarj.motd.ui.components.NetworkChip
import io.github.trevarj.motd.ui.components.UnreadBadge
import io.github.trevarj.motd.ui.theme.LocalMotdSemanticColors
import io.github.trevarj.motd.ui.theme.LocalNickColors
import io.github.trevarj.motd.ui.theme.LocalSpacing
import io.github.trevarj.motd.ui.theme.MotdShapes
import io.github.trevarj.motd.ui.theme.MotdTheme

internal data class ChatListBadgeState(
    val mutedActivity: Int? = null,
    val mentions: Int? = null,
    val unread: Int? = null,
    val mutedActivityIncomplete: Boolean = false,
    val mentionsIncomplete: Boolean = false,
    val unreadIncomplete: Boolean = false,
)

internal enum class ChatListRowVisualState { DEFAULT, UNREAD, ACTIVE, SELECTED }

internal fun chatListRowVisualState(
    selected: Boolean,
    active: Boolean,
    unread: Boolean,
): ChatListRowVisualState = when {
    selected -> ChatListRowVisualState.SELECTED
    active -> ChatListRowVisualState.ACTIVE
    unread -> ChatListRowVisualState.UNREAD
    else -> ChatListRowVisualState.DEFAULT
}

internal fun chatListRowContainer(
    state: ChatListRowVisualState,
    scheme: ColorScheme,
): Color = when (state) {
    ChatListRowVisualState.SELECTED -> scheme.secondaryContainer
    ChatListRowVisualState.ACTIVE -> scheme.primaryContainer
    ChatListRowVisualState.UNREAD -> lerp(scheme.surface, scheme.primaryContainer, 0.48f)
    ChatListRowVisualState.DEFAULT -> Color.Transparent
}

internal fun chatListBadgeState(row: ChatListRow): ChatListBadgeState =
    if (row.muted) {
        ChatListBadgeState(
            mutedActivity = row.unreadCount.takeIf { it > 0 },
            mutedActivityIncomplete = row.unreadCountIncomplete,
        )
    } else {
        ChatListBadgeState(
            mentions = row.mentionCount.takeIf { it > 0 },
            unread = row.unreadCount.takeIf { it > 0 },
            mentionsIncomplete = row.mentionCountIncomplete,
            unreadIncomplete = row.unreadCountIncomplete,
        )
    }

internal sealed interface ChatListMessagePreview {
    data class Text(val value: String) : ChatListMessagePreview
    data class Voice(val durationMs: Long?) : ChatListMessagePreview
}

internal fun chatListMessagePreview(text: String?): ChatListMessagePreview {
    val value = text.orEmpty()
    val attachments = parseAudioAttachments(value)
    val voice = attachments.singleOrNull()?.takeIf { attachment ->
        attachment.voice && displayTextForAudioMessage(value, attachments).isBlank()
    }
    return voice?.let { ChatListMessagePreview.Voice(it.durationMs) }
        ?: ChatListMessagePreview.Text(value)
}

internal fun chatListPreviewSender(
    type: BufferType,
    messageText: String?,
    sender: String?,
): String? = sender?.takeIf {
    // System events use an empty sender, which must not leave an empty label chip.
    type == BufferType.CHANNEL && messageText != null && it.isNotBlank()
}

/**
 * One chat-list row: avatar, display name, supporting network/last-message line, relative time, and
 * unread/mention badges. Muted rows use subdued semantic colors with a bell-off glyph.
 * Pinned rows carry a small inline [Icons.Outlined.PushPin] beside the name (there is no separate
 * "Pinned" section; pinning gives the row global list priority).
 *
 * Round 4 (plans/13 §3.5, Confirmed decision #4): a friend row gets a trailing [Icons.Filled.Star]
 * plus a quiet raised-surface background behind the display name, layered under the nick color.
 */
@Composable
fun ChatListRowItem(
    row: ChatListRow,
    showNetworkChip: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    isFriend: Boolean = false,
    presence: PresenceState? = null,
    selected: Boolean = false,
    active: Boolean = false,
) {
    // Resolved per-nick color (also used to tint the friend star), matching sender coloring.
    val nickColor = LocalNickColors.current.nick(row.displayName, MaterialTheme.colorScheme.onSurfaceVariant)
    val spacing = LocalSpacing.current
    val queryPresence = presence.takeIf { row.type == BufferType.QUERY }
    val badges = chatListBadgeState(row)
    val isUnread = !row.muted && row.unreadCount > 0
    val visualState = chatListRowVisualState(selected, active, isUnread)
    val rowContainer = chatListRowContainer(visualState, MaterialTheme.colorScheme)
    val activeIndicator = MaterialTheme.colorScheme.primary
    val presenceDescription = queryPresence?.let {
        stringResource(
            when (it) {
                PresenceState.ONLINE -> R.string.presence_online
                PresenceState.OFFLINE -> R.string.presence_offline
                PresenceState.UNKNOWN -> R.string.presence_unknown
            },
        )
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(MotdShapes.card)
            .background(rowContainer)
            .drawBehind {
                if (active) {
                    val width = 3.dp.toPx()
                    val height = size.height * 0.58f
                    drawRoundRect(
                        color = activeIndicator,
                        topLeft = Offset(0f, (size.height - height) / 2f),
                        size = Size(width, height),
                        cornerRadius = CornerRadius(width / 2f),
                    )
                }
            }
            // Per-buffer handle so the harness selects a specific row (display names collide).
            .testTag("chatlist_row_${row.bufferId}")
            .semantics { this.selected = selected || active }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .defaultMinSize(minHeight = 48.dp)
            .then(
                if (presenceDescription != null) {
                    Modifier.semantics { stateDescription = presenceDescription }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 12.dp, vertical = spacing.chatListVPad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PresenceAvatar(
            name = row.displayName,
            isChannel = row.type == BufferType.CHANNEL,
            networkId = row.networkId,
            presence = queryPresence,
            size = spacing.chatListAvatar,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Quiet raised surface behind a non-muted friend's name.
                val nameModifier = if (isFriend && !row.muted) {
                    Modifier
                        .weight(1f, fill = false)
                        .clip(MotdShapes.tag)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                } else {
                    Modifier.weight(1f, fill = false)
                }
                Text(
                    text = row.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isUnread) FontWeight.Bold else FontWeight.Medium,
                    color = when {
                        row.muted -> MaterialTheme.colorScheme.onSurfaceVariant
                        isFriend -> nickColor
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = nameModifier,
                )
                if (isFriend) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(14.dp),
                        tint = if (row.muted) MaterialTheme.colorScheme.onSurfaceVariant else nickColor,
                    )
                }
                if (row.pinned) {
                    // Inline pin marker (replaces the former "Pinned" section); subtle, unread-neutral.
                    Icon(
                        imageVector = Icons.Outlined.PushPin,
                        contentDescription = stringResource(R.string.chatlist_pinned),
                        modifier = Modifier
                            .testTag("chatlist_row_pin")
                            .padding(start = 4.dp)
                            .size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (row.muted) {
                    Icon(
                        imageVector = Icons.Filled.NotificationsOff,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Network belongs to buffer identity, so it rides the title line (trailing the
                // status icons); keeping it off the preview line avoids a two-chip collision
                // with the sender label.
                if (showNetworkChip) {
                    Spacer(Modifier.width(6.dp))
                    NetworkChip(
                        name = row.networkName,
                        dimmed = true,
                        emphasized = isUnread,
                    )
                }
            }
            Spacer(Modifier.size(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Channel previews lead with a sender label chip (no colon, which collided
                // with nick mentions in the message text); queries read cleaner without it.
                val lastMessage = row.lastMessageText
                val preview = chatListMessagePreview(lastMessage)
                val sender = chatListPreviewSender(row.type, lastMessage, row.lastMessageSender)
                if (sender != null) {
                    SenderLabel(
                        sender = sender,
                        color = LocalNickColors.current.nick(
                            sender,
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        unread = isUnread,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                if (preview is ChatListMessagePreview.Voice) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    text = when (preview) {
                        is ChatListMessagePreview.Text -> preview.value
                        is ChatListMessagePreview.Voice -> buildString {
                            append(stringResource(R.string.chatlist_voice_message))
                            preview.durationMs?.let { append(" · ${formatAudioDuration(it)}") }
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isUnread) FontWeight.Medium else FontWeight.Normal,
                    color = if (isUnread) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            row.lastMessageTime?.let { time ->
                Text(
                    text = relativeChatTime(time),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                badges.mutedActivity?.let { count ->
                    MutedActivityBadge(
                        count = count,
                        lowerBound = badges.mutedActivityIncomplete,
                        modifier = Modifier.testTag("chatlist_row_muted_activity_badge"),
                    )
                }
                badges.mentions?.let { count ->
                    MentionBadge(
                        count = count,
                        lowerBound = badges.mentionsIncomplete,
                        modifier = Modifier.testTag("chatlist_row_mention_badge"),
                    )
                }
                badges.unread?.let { count ->
                    UnreadBadge(
                        count = count,
                        lowerBound = badges.unreadIncomplete,
                        modifier = Modifier.testTag("chatlist_row_unread_badge"),
                    )
                }
            }
        }
    }
}

internal enum class PresenceBadgeVisual { FILLED, HOLLOW, UNKNOWN }

internal fun presenceBadgeVisual(presence: PresenceState): PresenceBadgeVisual = when (presence) {
    PresenceState.ONLINE -> PresenceBadgeVisual.FILLED
    PresenceState.OFFLINE -> PresenceBadgeVisual.HOLLOW
    PresenceState.UNKNOWN -> PresenceBadgeVisual.UNKNOWN
}

@Composable
private fun PresenceAvatar(
    name: String,
    isChannel: Boolean,
    networkId: Long,
    presence: PresenceState?,
    size: Dp,
) {
    Box(modifier = Modifier.size(size)) {
        Avatar(
            name = name,
            isChannel = isChannel,
            networkId = networkId,
            size = size,
            modifier = Modifier.align(Alignment.Center),
        )
        presence?.let { state ->
            PresenceBadge(
                visual = presenceBadgeVisual(state),
                tag = "chatlist_presence_${state.name.lowercase()}",
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

@Composable
private fun PresenceBadge(
    visual: PresenceBadgeVisual,
    tag: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val online = LocalMotdSemanticColors.current.success
    Box(
        modifier = modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(scheme.background)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        when (visual) {
            PresenceBadgeVisual.FILLED -> Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(online)
                    .clearAndSetSemantics {},
            )
            PresenceBadgeVisual.HOLLOW -> Box(
                Modifier
                    .size(10.dp)
                    .border(2.dp, scheme.onSurfaceVariant, CircleShape)
                    .clearAndSetSemantics {},
            )
            PresenceBadgeVisual.UNKNOWN -> Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(scheme.surfaceContainerHighest)
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "?",
                    color = scheme.onSurfaceVariant,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 8.sp,
                )
            }
        }
    }
}

/**
 * Sender label chip for channel previews: nick-tinted rounded chip (mirrors
 * [NetworkChip] metrics), no colon so a nick mention in the text no longer reads
 * as a double `nick: nick:`. Dimmed to match the message-preview prominence:
 * the nick hue stays opaque while weight tracks the row's unread state, so
 * the label reads as part of the preview line rather than a louder element.
 */
@Composable
private fun SenderLabel(
    sender: String,
    color: Color,
    unread: Boolean,
    modifier: Modifier = Modifier,
) {
    val container = if (unread) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Box(
        modifier = modifier
            .background(container, MotdShapes.tag)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text = sender,
            color = color,
            fontWeight = if (unread) FontWeight.Medium else FontWeight.Normal,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@PreviewLightDark
@Composable
private fun ChatListPresencePreview() {
    MotdTheme {
        Column {
            ChatListRowItem(
                row = ChatListRow(
                    bufferId = 1, networkId = 1, networkName = "Libera",
                    displayName = "alice", type = BufferType.QUERY,
                    pinned = true, muted = false,
                    lastMessageText = "I am around", lastMessageSender = "alice",
                    lastMessageTime = System.currentTimeMillis() - 120_000,
                    unreadCount = 12, mentionCount = 2,
                ),
                showNetworkChip = true,
                onClick = {}, onLongClick = {},
                isFriend = true,
                presence = PresenceState.ONLINE,
            )
            ChatListRowItem(
                row = ChatListRow(
                    bufferId = 2, networkId = 1, networkName = "Libera",
                    displayName = "bob", type = BufferType.QUERY,
                    pinned = false, muted = false,
                    lastMessageText = "see you later", lastMessageSender = "bob",
                    lastMessageTime = System.currentTimeMillis() - 3_600_000 * 26,
                    unreadCount = 0, mentionCount = 0,
                ),
                showNetworkChip = false,
                onClick = {}, onLongClick = {},
                presence = PresenceState.OFFLINE,
            )
            ChatListRowItem(
                row = ChatListRow(
                    bufferId = 3, networkId = 1, networkName = "Libera",
                    displayName = "carol", type = BufferType.QUERY,
                    pinned = false, muted = true,
                    lastMessageText = "reconnecting", lastMessageSender = "carol",
                    lastMessageTime = System.currentTimeMillis() - 60_000,
                    unreadCount = 7, mentionCount = 1,
                ),
                showNetworkChip = true,
                onClick = {}, onLongClick = {},
                presence = PresenceState.UNKNOWN,
            )
            ChatListRowItem(
                row = ChatListRow(
                    bufferId = 4, networkId = 1, networkName = "Libera",
                    displayName = "#motd", type = BufferType.CHANNEL,
                    pinned = false, muted = false,
                    lastMessageText = "alice: welcome @bob", lastMessageSender = "alice",
                    lastMessageTime = System.currentTimeMillis() - 30_000,
                    unreadCount = 3, mentionCount = 1,
                ),
                showNetworkChip = true,
                onClick = {}, onLongClick = {},
            )
        }
    }
}
