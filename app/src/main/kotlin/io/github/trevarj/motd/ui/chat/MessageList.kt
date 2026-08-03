package io.github.trevarj.motd.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag as semanticsTestTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import io.github.trevarj.motd.R
import io.github.trevarj.motd.audio.AudioAttachment
import io.github.trevarj.motd.audio.AudioMetadata
import io.github.trevarj.motd.audio.AudioCacheStatus
import io.github.trevarj.motd.audio.AudioPlaybackState
import io.github.trevarj.motd.audio.AudioPlaybackOrigin
import io.github.trevarj.motd.audio.AudioPlaybackRequest
import io.github.trevarj.motd.audio.AudioWaveform
import io.github.trevarj.motd.audio.CachedAudioMetadata
import io.github.trevarj.motd.audio.displayTextForAudioMessage
import io.github.trevarj.motd.audio.extensionlessAudioCandidates
import io.github.trevarj.motd.audio.toAttachment
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.DccDirection
import io.github.trevarj.motd.data.db.DccTransferEntity
import io.github.trevarj.motd.data.db.DccTransferProtocol
import io.github.trevarj.motd.data.db.DccTransferState
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.InviteState
import io.github.trevarj.motd.data.sync.InvitePayloadV1
import io.github.trevarj.motd.data.sync.NetworkBatchPayloadV1
import io.github.trevarj.motd.dcc.DccEndpointRisk
import io.github.trevarj.motd.dcc.dccEndpointRisk
import io.github.trevarj.motd.dcc.resolveDccAddress
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.repo.CachedLinkPreview
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.ui.components.MessageBubble
import io.github.trevarj.motd.ui.components.AudioAttachmentPlayers
import io.github.trevarj.motd.ui.components.NewMessagesDivider
import io.github.trevarj.motd.ui.components.ReactionChip
import io.github.trevarj.motd.ui.components.ReplyPreviewData
import io.github.trevarj.motd.ui.components.SystemEventPill
import io.github.trevarj.motd.ui.components.SwipeToReplyContainer
import io.github.trevarj.motd.ui.components.DaySeparator
import io.github.trevarj.motd.ui.components.dayStart
import io.github.trevarj.motd.ui.components.rememberMessageTimeFormatter
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.LocalSpacing
import io.github.trevarj.motd.ui.theme.MotdSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Limit collapsed system-event work per composed row during high-velocity history traversal. */
internal const val MAX_COLLAPSED_SYSTEM_EVENTS = 24

/** Refresh identity for expanded line content; changes when Paging extends a tail chunk. */
internal data class SystemRunContentKey(val newestId: Long, val oldestId: Long, val count: Int)

/** An expanded run stays expanded when synchronization reshapes its bounded Paging chunk. */
internal fun systemRunExpanded(runIds: Collection<Long>, expandedEventIds: Set<Long>): Boolean =
    runIds.any(expandedEventIds::contains)

internal fun updateExpandedSystemEvents(
    current: Set<Long>,
    runIds: Collection<Long>,
    expanded: Boolean,
): Set<Long> = if (expanded) current + runIds else current - runIds.toSet()

/** Reuse lazy compositions only across rows with the same structural layout. */
internal enum class MessageContentType {
    SYSTEM,
    NETWORK_BATCH,
    INVITE,
    DCC_TRANSFER,
    ACTION,
    ACTION_FAILED,
    SELF,
    SELF_FAILED,
    OTHER,
    OTHER_FAILED,
}

fun isSystemKind(kind: MessageKind): Boolean = when (kind) {
    MessageKind.JOIN,
    MessageKind.PART,
    MessageKind.QUIT,
    MessageKind.KICK,
    MessageKind.NICK,
    MessageKind.MODE,
    MessageKind.TOPIC,
    MessageKind.SERVER_INFO,
    MessageKind.ERROR,
    -> true
    else -> false
}

internal fun messageContentType(message: MessageEntity): MessageContentType = when {
    message.kind == MessageKind.INVITE -> MessageContentType.INVITE
    message.kind == MessageKind.DCC_TRANSFER -> MessageContentType.DCC_TRANSFER
    message.kind == MessageKind.NETSPLIT || message.kind == MessageKind.NETJOIN -> MessageContentType.NETWORK_BATCH
    isSystemKind(message.kind) -> MessageContentType.SYSTEM
    message.kind == MessageKind.ACTION && message.failed -> MessageContentType.ACTION_FAILED
    message.kind == MessageKind.ACTION -> MessageContentType.ACTION
    message.isSelf && message.failed -> MessageContentType.SELF_FAILED
    message.isSelf -> MessageContentType.SELF
    message.failed -> MessageContentType.OTHER_FAILED
    else -> MessageContentType.OTHER
}

/** Stable per-message testTag id: server msgid when present, else the local entity id (pending). */
/** Stable UIAutomator/Compose address: server identity wins once an echo has promoted the row. */
internal fun timelineMessageTag(msgid: String?, eventId: Long): String =
    "chat_message_${msgid ?: eventId}"

private fun messageTag(msg: MessageEntity): String = timelineMessageTag(msg.msgid, msg.id)

internal fun foolCollapseTag(msgid: String?, eventId: Long): String =
    "chat_fool_collapse_${msgid ?: eventId}"

private fun MessageEntity.timelineAnchor(): TimelineAnchor = TimelineAnchor(serverTime, id, timelineOrder)

/**
 * True when [current] should show its sender header: it opens a new same-sender ≤3-min group.
 * [olderNeighbor] is the message immediately older in time (index+1 in a reversed list).
 */
fun showsSender(current: MessageEntity, olderNeighbor: MessageEntity?): Boolean {
    if (olderNeighbor == null) return true
    val sameActor = if (current.senderAccount != null && olderNeighbor.senderAccount != null) {
        current.senderAccount == olderNeighbor.senderAccount
    } else {
        current.normalizedActor == olderNeighbor.normalizedActor
    }
    if (!sameActor || olderNeighbor.isSelf != current.isSelf) return true
    // An ACTION (/me) is its own utterance: it always opens a new group on either side of the
    // boundary, so a regular message following an ACTION shows its nick again instead of reading
    // as a continuation of the emote.
    if (current.kind == MessageKind.ACTION || olderNeighbor.kind == MessageKind.ACTION) return true
    if (isSystemKind(olderNeighbor.kind) != isSystemKind(current.kind)) return true
    return current.serverTime - olderNeighbor.serverTime > GROUP_WINDOW_MS
}

/**
 * Vertical gap to render before a bubble row. Reuses [showsSender] so the gap tracks the same-sender
 * grouping window: a burst gap while a group continues, a break gap when a new group opens (sender,
 * direction, or system-kind change, an ACTION boundary, or >[GROUP_WINDOW_MS]). Zero when there is
 * no older neighbor. Non-COMFORTABLE densities get 0 for both tokens, so this is a no-op there.
 */
fun bubbleGap(showSender: Boolean, hasOlder: Boolean, spacing: MotdSpacing): Dp {
    if (!hasOlder) return 0.dp
    return if (showSender) spacing.bubbleBreakGap else spacing.bubbleBurstGap
}

/**
 * Reverse-layout message list. Index 0 is the newest message (bottom). For each row we peek the
 * next (older) item to compute grouping, day separators, and the read-marker divider.
 */
@Composable
fun MessageList(
    items: LazyPagingItems<MessageEntity>,
    listState: LazyListState,
    networkId: Long?,
    readMarkerTime: TimelineAnchor?,
    modifier: Modifier = Modifier,
    readMarkerLabel: String? = null,
    onLongPress: (MessageEntity) -> Unit,
    onReply: (MessageEntity) -> Unit,
    // React to a message; the whole entity is passed so a still-pending own row (msgid == null) is
    // queued by the VM instead of silently dropped (bug: react on a just-sent message did nothing).
    onReact: (MessageEntity, String) -> Unit,
    onImageClick: (String) -> Unit,
    onRetry: (MessageEntity) -> Unit,
    bufferId: Long? = null,
    conversationName: String? = null,
    directMessage: Boolean = false,
    canRetry: (MessageEntity) -> Boolean = { true },
    loadPreview: suspend (String) -> LinkPreview?,
    richContentReady: Boolean,
    showImages: Boolean,
    showLinkPreviews: Boolean,
    onOpenLink: (String) -> Unit,
    cachedPreview: (String) -> CachedLinkPreview? = { null },
    loadAudioMetadata: suspend (String, Long?) -> AudioMetadata? = { _, _ -> null },
    cachedAudioMetadata: (String) -> CachedAudioMetadata? = { null },
    audioPlaybackState: AudioPlaybackState = AudioPlaybackState(),
    audioWaveforms: Map<String, AudioWaveform> = emptyMap(),
    audioCacheStatuses: Map<String, AudioCacheStatus> = emptyMap(),
    onAudioToggle: (AudioPlaybackRequest) -> Unit = {},
    onAudioCacheInspect: (AudioAttachment) -> Unit = {},
    onAudioSeek: (AudioAttachment, Long) -> Unit = { _, _ -> },
    liveEntryIds: Set<Long> = emptySet(),
    onLiveEntryConsumed: (Long) -> Unit = {},
    reactionChips: (String) -> List<ReactionChip> = { emptyList() },
    replyPreview: (String) -> StateFlow<ReplyPreviewData?> = { MutableStateFlow(null) },
    onReplyPreviewClick: (String) -> Unit = {},
    onDelete: (MessageEntity) -> Unit = {},
    highlightMsgid: String? = null,
    dccTransfer: (MessageEntity) -> StateFlow<DccTransferEntity?> = { MutableStateFlow(null) },
    onAcceptDccTransfer: (Long, String, Boolean) -> Unit = { _, _, _ -> },
    onRejectDccTransfer: (Long) -> Unit = {},
    onRemoveDccTransfer: (Long) -> Unit = {},
    // Normalized nicks known in the current buffer (member list). Drives @mention coloring in the
    // message bodies (plans/17); passed straight through to each MessageBubble.
    knownNicks: Set<String> = emptySet(),
    // Behavioral settings threaded from viewModel.settings (plans/13 §2.3/§2.4). Style-only
    // concerns (density, nick color) flow through CompositionLocals instead.
    friends: Set<String> = emptySet(),
    fools: Set<String> = emptySet(),
    foolsMode: FoolsMode = FoolsMode.COLLAPSE,
    identityRules: IrcIdentityRules = IrcIdentityRules(),
    historyUiState: ChatHistoryUiState = ChatHistoryUiState.Hidden,
    onHistoryRetry: () -> Unit = {},
    // Effective per-row expansion (global expand-all + per-row overrides live in the caller); toggle
    // flips a single fool row either way so expand/re-collapse is bidirectional (bug #9).
    foolExpanded: (Long) -> Boolean = { false },
    onToggleFool: (Long) -> Unit = {},
    // Tapping a non-self sender's name/avatar opens the nick sheet (plans/16 §5.8).
    onSenderClick: (String) -> Unit = {},
    onAcceptInvite: (Long) -> Unit = {},
    onDismissInvite: (Long) -> Unit = {},
) {
    val scrolling by remember(listState) { derivedStateOf { listState.isScrollInProgress } }
    // Keep the user's expanded JOIN/PART runs above the volatile Paging rows. A history sync may
    // briefly replace or rechunk those rows, but overlapping event identities remain stable.
    var expandedSystemEventIds by remember(bufferId) { mutableStateOf(emptySet<Long>()) }
    // Scrolling postpones only cache misses. Parsed URLs and resolved previews remain renderable so
    // a recycled row does not lose rich content halfway through a fling.
    val canStartNewRichContentWork = richContentReady && !scrolling
    val formatMessageTime = rememberMessageTimeFormatter()
    LazyColumn(
        state = listState,
        reverseLayout = true,
        // Retained rows can predate messages sent by earlier orchestrated journeys. Keep the
        // timeline addressable so the real-stack acceptance test can scroll to an imported row
        // instead of confusing an off-screen row with a missing one. Scroll-driven paging: reaching
        // the older end triggers Paging APPEND via the prefetch window, no gesture plumbing needed.
        modifier = modifier
            .fillMaxSize()
            .testTag("chat_timeline"),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        // Stable keys stop paging invalidations (new message / echo confirm / page load) from
        // re-anchoring the viewport by index and reusing per-row state across messages (plans/15
        // #4). Placeholder rows fall back to the position key.
        items(
            count = items.itemCount,
            key = items.itemKey { it.id },
            // Own bubbles, other bubbles, ACTION rows, and retry rows have different composition
            // shapes. Keeping separate pools avoids structural churn at exactly the boundaries that
            // previously produced hitches when a fling crossed own messages.
            contentType = items.itemContentType(::messageContentType),
        ) { index ->
            val msg = items[index]
            if (msg == null) {
                MessagePlaceholderRow()
                return@items
            }
            val older = if (index + 1 < items.itemCount) items.peek(index + 1) else null
            val newer = if (index - 1 >= 0) items.peek(index - 1) else null

            if (msg.kind == MessageKind.INVITE) {
                LiveTimelineEntry(liveEntryIds, msg.id, onLiveEntryConsumed) {
                    InvitationCard(
                        message = msg,
                        onJoin = { onAcceptInvite(msg.id) },
                        onDismiss = { onDismissInvite(msg.id) },
                    )
                }
                return@items
            }

            if (msg.kind == MessageKind.NETSPLIT || msg.kind == MessageKind.NETJOIN) {
                LiveTimelineEntry(liveEntryIds, msg.id, onLiveEntryConsumed) {
                    NetworkBatchPill(msg)
                }
                return@items
            }

            if (msg.kind == MessageKind.DCC_TRANSFER) {
                LiveTimelineEntry(liveEntryIds, msg.id, onLiveEntryConsumed) {
                    val transferFlow = remember(msg.id) { dccTransfer(msg) }
                    val transfer by transferFlow.collectAsStateWithLifecycle()
                    DccTransferCard(
                        message = msg,
                        transfer = transfer,
                        onAccept = onAcceptDccTransfer,
                        onReject = onRejectDccTransfer,
                        onRemove = onRemoveDccTransfer,
                    )
                }
                return@items
            }

            // System-event collapse (plans/15 #15): render one pill on the run's *newest* item and
            // skip the rest. In a reversed list the newest of a contiguous system run is the item
            // whose just-newer neighbor is not a system event.
            if (isSystemKind(msg.kind)) {
                if (!isSystemRunChunkHead(index, newer?.let { isSystemKind(it.kind) } == true)) return@items
                LiveTimelineEntry(liveEntryIds, msg.id, onLiveEntryConsumed) {
                    SystemEventRun(
                        items = items,
                        index = index,
                        newest = msg,
                        readMarkerTime = readMarkerTime,
                        readMarkerLabel = readMarkerLabel,
                        expandedEventIds = expandedSystemEventIds,
                        onExpandedChange = { runIds, expanded ->
                            expandedSystemEventIds = updateExpandedSystemEvents(
                                expandedSystemEventIds,
                                runIds,
                                expanded,
                            )
                        },
                    )
                }
                return@items
            }

            // Fool COLLAPSE (plans/13 §2.4): render a tap-to-expand placeholder in place of the
            // bubble until its id is expanded. HIDE mode is filtered upstream so it never reaches
            // here; system-kind rows are handled above and never fool-treated.
            val isFool = foolsMode == FoolsMode.COLLAPSE &&
                isFoolMessage(msg, fools, identityRules)
            if (isFool && !foolExpanded(msg.id)) {
                LiveTimelineEntry(liveEntryIds, msg.id, onLiveEntryConsumed) {
                    FoolPlaceholderRow(
                        msg = msg,
                        older = older,
                        readMarkerTime = readMarkerTime,
                        readMarkerLabel = readMarkerLabel,
                        onExpand = { onToggleFool(msg.id) },
                    )
                }
                return@items
            }

            // Deep-jump pulse: fade a highlight tint in then back out on the target row (~1.6s).
            val highlighted = highlightMsgid != null && msg.msgid == highlightMsgid
            // Deep jumps are rare. Do not install an animation state object in every ordinary row;
            // only the single target needs one while the highlight is active.
            val highlightColor = if (highlighted) {
                val pulse = remember(msg.id, highlightMsgid) { Animatable(0f) }
                LaunchedEffect(msg.id, highlightMsgid) {
                    pulse.animateTo(1f, tween(durationMillis = 800))
                    pulse.animateTo(0f, tween(durationMillis = 800))
                }
                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f * pulse.value)
            } else {
                Color.Transparent
            }

            // Column (not Box): MessageRow emits several vertical siblings — the collapse chip,
            // bubble, retry row, and read-marker/day dividers. A Box would stack them on top of one
            // another (dividers over message text; the fool-collapse chip trapped behind the bubble).
            // A Column lays them out top-to-bottom so each affordance owns its own space and taps.
            val rowContent: @Composable () -> Unit = {
                Column(modifier = Modifier.fillMaxWidth().background(highlightColor)) {
                    MessageRow(
                        msg = msg,
                        networkId = networkId,
                        bufferId = bufferId,
                        conversationName = conversationName,
                        directMessage = directMessage,
                        older = older,
                        formatTime = formatMessageTime,
                        readMarkerTime = readMarkerTime,
                        readMarkerLabel = readMarkerLabel,
                        // An expanded fool row shows a small tap-to-re-collapse chip above its bubble so the
                        // toggle is bidirectional without stealing the bubble's long-press/link taps (#9).
                        onCollapseFool = if (isFool) ({ onToggleFool(msg.id) }) else null,
                        senderIsFriend = !msg.isSelf && msg.matchesConfiguredActor(friends, identityRules),
                        reactions = msg.msgid?.let(reactionChips).orEmpty(),
                        knownNicks = knownNicks,
                        identityRules = identityRules,
                        onLongPress = onLongPress,
                        onReply = onReply,
                        onReact = onReact,
                        onImageClick = onImageClick,
                        onRetry = onRetry,
                        canRetry = canRetry(msg),
                        onDelete = onDelete,
                        loadPreview = loadPreview,
                        showImages = showImages,
                        showLinkPreviews = showLinkPreviews,
                        canStartNewRichContentWork = canStartNewRichContentWork,
                        cachedPreview = cachedPreview,
                        loadAudioMetadata = loadAudioMetadata,
                        cachedAudioMetadata = cachedAudioMetadata,
                        audioPlaybackState = audioPlaybackState,
                        audioWaveforms = audioWaveforms,
                        audioCacheStatuses = audioCacheStatuses,
                        onAudioToggle = onAudioToggle,
                        onAudioCacheInspect = onAudioCacheInspect,
                        onAudioSeek = onAudioSeek,
                        onOpenLink = onOpenLink,
                        onSenderClick = onSenderClick,
                        replyPreview = replyPreview,
                        onReplyPreviewClick = onReplyPreviewClick,
                    )
                }
            }
            LiveTimelineEntry(liveEntryIds, msg.id, onLiveEntryConsumed, rowContent)
        }

        // Append spinner / end-of-history / error affordances (plans/15 #27). This item sits at the
        // top of the reversed list, i.e. visually above the oldest message where APPEND loads more.
        item(key = "append-state", contentType = "loadstate") {
            ChatHistoryFooter(
                state = historyUiState,
                onRetry = {
                    onHistoryRetry()
                    items.retry()
                },
            )
        }
    }
}

@Composable
private fun DccTransferCard(
    message: MessageEntity,
    transfer: DccTransferEntity?,
    onAccept: (Long, String, Boolean) -> Unit,
    onReject: (Long) -> Unit,
    onRemove: (Long) -> Unit,
) {
    if (transfer == null) {
        SystemEventPill(
            summary = message.text,
            lineCount = 1,
            loadLines = { listOf(message.text) },
            contentKey = message.id,
            modifier = Modifier.testTag("chat_dcc_transfer_compact_${message.id}"),
        )
        return
    }
    val privateRisk = remember(transfer.address, transfer.addressKind) {
        runCatching { dccEndpointRisk(resolveDccAddress(transfer.address, transfer.addressKind)) }
            .getOrDefault(DccEndpointRisk.UNSPECIFIED)
            .takeIf { it != DccEndpointRisk.PUBLIC }
    }
    val progress = transfer.sizeBytes?.takeIf { it > 0 }?.let { size ->
        (transfer.bytesTransferred.toFloat() / size.toFloat()).coerceIn(0f, 1f)
    }
    val direction = if (transfer.direction == DccDirection.INCOMING) "Incoming" else "Outgoing"
    val protocol = when (transfer.protocol) {
        DccTransferProtocol.SEND -> "Plain DCC SEND"
        DccTransferProtocol.SSEND -> "Secure DCC SSEND · identity unverified"
    }
    val status = dccStatusText(transfer)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("chat_dcc_transfer_${transfer.id}"),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "$direction file",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(transfer.displayFilename, style = MaterialTheme.typography.titleMedium)
            Text(
                text = listOfNotNull(
                    transfer.sizeBytes?.let(::formatDccBytes),
                    protocol,
                    status,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (privateRisk != null) {
                Text(
                    text = "Private or local endpoint: ${privateRisk.name.lowercase().replace('_', ' ')}. Allow only if you trust this peer and network.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            progress?.let {
                LinearProgressIndicator(
                    progress = { it },
                    modifier = Modifier.fillMaxWidth().testTag("chat_dcc_progress_${transfer.id}"),
                )
                Text(
                    text = "${formatDccBytes(transfer.bytesTransferred)} / ${formatDccBytes(transfer.sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DccTransferActions(transfer, privateRisk, onAccept, onReject, onRemove)
        }
    }
}

@Composable
private fun DccTransferActions(
    transfer: DccTransferEntity,
    privateRisk: DccEndpointRisk?,
    onAccept: (Long, String, Boolean) -> Unit,
    onReject: (Long) -> Unit,
    onRemove: (Long) -> Unit,
) {
    val incoming = transfer.direction == DccDirection.INCOMING
    val canAccept = incoming && transfer.state in setOf(
        DccTransferState.OFFERED,
        DccTransferState.PARTIAL,
        DccTransferState.FAILED,
    )
    val terminal = transfer.state in setOf(
        DccTransferState.COMPLETED,
        DccTransferState.REJECTED,
        DccTransferState.EXPIRED,
        DccTransferState.FAILED,
        DccTransferState.REMOVED,
    )
    if (!canAccept && !terminal) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (canAccept) {
            Button(
                onClick = {
                    onAccept(transfer.id, transfer.displayFilename, privateRisk != null)
                },
                modifier = Modifier.testTag("chat_dcc_accept_${transfer.id}"),
            ) {
                Text(if (privateRisk == null) "Save" else "Allow once & save")
            }
            OutlinedButton(
                onClick = { onReject(transfer.id) },
                modifier = Modifier.testTag("chat_dcc_reject_${transfer.id}"),
            ) {
                Text("Reject")
            }
        }
        if (terminal) {
            TextButton(
                onClick = { onRemove(transfer.id) },
                modifier = Modifier.testTag("chat_dcc_remove_${transfer.id}"),
            ) {
                Text("Remove record")
            }
        }
    }
}

private fun dccStatusText(transfer: DccTransferEntity): String = when (transfer.state) {
    DccTransferState.OFFERED -> "Waiting"
    DccTransferState.ACCEPTING -> "Starting"
    DccTransferState.ACTIVE -> "Transferring"
    DccTransferState.PARTIAL -> "Partial"
    DccTransferState.COMPLETED -> "Complete"
    DccTransferState.FAILED -> transfer.error?.let { "Failed: $it" } ?: "Failed"
    DccTransferState.REJECTED -> "Rejected"
    DccTransferState.EXPIRED -> "Expired"
    DccTransferState.REMOVED -> "Removed"
}

private fun formatDccBytes(bytes: Long): String {
    val units = listOf("B", "KiB", "MiB", "GiB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) "$bytes ${units[unit]}" else "%.1f %s".format(value, units[unit])
}

/** A quiet, stable-height skeleton prevents placeholder-only pages from measuring as zero rows. */
@Composable
internal fun MessagePlaceholderRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.CenterStart,
    ) {
        Spacer(
            Modifier
                .padding(horizontal = LocalSpacing.current.messageOuterHPad)
                .fillMaxWidth(0.38f)
                .height(10.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                    RoundedCornerShape(5.dp),
                ),
        )
    }
}

/** Applies the one-shot entrance uniformly to every kind of rendered, meaningful timeline row. */
@Composable
private fun LiveTimelineEntry(
    liveEntryIds: Set<Long>,
    messageId: Long,
    onConsumed: (Long) -> Unit,
    content: @Composable () -> Unit,
) {
    if (messageId in liveEntryIds) {
        LiveMessageEntry(messageId = messageId, onConsumed = onConsumed, content = content)
    } else {
        content()
    }
}

/**
 * One-shot live-message entrance. The row reveals upward from the bottom so older messages move by
 * the same smooth amount as the new row grows. The content subtree remains mounted after completion
 * so reply, preview, and audio state cannot restart at the end of the animation.
 */
@Composable
private fun LiveMessageEntry(
    messageId: Long,
    onConsumed: (Long) -> Unit,
    content: @Composable () -> Unit,
) {
    val reveal = remember(messageId) { Animatable(0f) }
    var complete by remember(messageId) { mutableStateOf(false) }
    val latestOnConsumed = rememberUpdatedState(onConsumed)

    DisposableEffect(messageId) {
        onDispose { latestOnConsumed.value(messageId) }
    }

    LaunchedEffect(messageId) {
        reveal.animateTo(1f, MotdMotion.fadeIn)
        complete = true
    }

    val motion = if (complete) {
        Modifier
    } else {
        Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val revealedHeight = (placeable.height * reveal.value).toInt()
                    .coerceIn(0, placeable.height)
                layout(placeable.width, revealedHeight) {
                    // Bottom alignment makes the bubble grow into the conversation instead of
                    // sliding its full height over the composer.
                    placeable.placeRelative(0, revealedHeight - placeable.height)
                }
            }
            .graphicsLayer { alpha = reveal.value }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds()
            .then(motion),
    ) {
        content()
    }
}

@Composable
private fun NetworkBatchPill(message: MessageEntity) {
    val payload = remember(message.eventPayload) { NetworkBatchPayloadV1.decode(message.eventPayload) }
    if (payload == null) {
        SystemEventPill(
            summary = message.text,
            lineCount = 1,
            loadLines = { listOf(message.text) },
            contentKey = message.id,
            modifier = Modifier.testTag("chat_network_batch_${message.id}"),
        )
        return
    }
    val action = if (message.kind == MessageKind.NETSPLIT) "split" else "rejoined"
    val summary = "${payload.nicks.size} ${if (payload.nicks.size == 1) "user" else "users"} $action " +
        "(${payload.serverA} ↔ ${payload.serverB})"
    SystemEventPill(
        summary = summary,
        lineCount = payload.nicks.size,
        loadLines = { payload.nicks },
        contentKey = message.id,
        modifier = Modifier.testTag("chat_network_batch_${message.kind.name.lowercase()}_${message.id}"),
    )
}

@Composable
private fun InvitationCard(
    message: MessageEntity,
    onJoin: () -> Unit,
    onDismiss: () -> Unit,
) {
    val payload = remember(message.eventPayload) { InvitePayloadV1.decode(message.eventPayload) }
    val state = message.inviteState
    if (payload == null || state == null || state == InviteState.HISTORICAL) {
        SystemEventPill(
            summary = message.text,
            lineCount = 1,
            loadLines = { listOf(message.text) },
            contentKey = message.id,
            modifier = Modifier.testTag("chat_invite_compact_${message.id}"),
        )
        return
    }
    if (state == InviteState.JOINED || state == InviteState.DISMISSED) {
        val resolution = if (state == InviteState.JOINED) "Joined" else "Dismissed"
        SystemEventPill(
            summary = "$resolution ${payload.channel}",
            lineCount = 1,
            loadLines = { listOf(message.text) },
            contentKey = message.id,
            modifier = Modifier.testTag("chat_invite_resolved_${message.id}"),
        )
        return
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("chat_invite_card_${message.id}"),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Invitation to ${payload.channel}", style = MaterialTheme.typography.titleMedium)
            Text(message.text, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            if (state == InviteState.FAILED) {
                Text("Could not join. You can retry.", color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onJoin,
                    enabled = state != InviteState.JOINING,
                    modifier = Modifier.testTag("chat_invite_join_${message.id}"),
                ) {
                    Text(if (state == InviteState.JOINING) "Joining…" else "Join")
                }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("chat_invite_dismiss_${message.id}"),
                ) {
                    Text("Dismiss")
                }
            }
        }
    }
}

/**
 * Render one bounded chunk of a collapsed system-event run. Very long bursts are split into
 * adjacent pills, so scrolling never scans or allocates the entire run. Lines remain lazy until
 * expansion. The
 * read-marker/day separators are computed against the oldest item of the run and the neighbor just
 * older than the whole run, matching the reversed-list boundary rules used for bubbles.
 */
@Composable
private fun SystemEventRun(
    items: LazyPagingItems<MessageEntity>,
    index: Int,
    newest: MessageEntity,
    readMarkerTime: TimelineAnchor?,
    readMarkerLabel: String?,
    expandedEventIds: Set<Long>,
    onExpandedChange: (Collection<Long>, Boolean) -> Unit,
) {
    // Gather at most one chunk: newest first (index), then older neighbors while still system events.
    val run = ArrayList<MessageEntity>()
    run.add(newest)
    var i = index + 1
    val chunkLimit = systemRunChunkLimit(index)
    while (i < items.itemCount && run.size < chunkLimit) {
        val m = items.peek(i) ?: break
        if (!isSystemKind(m.kind)) break
        run.add(m)
        i++
    }
    val oldest = run.last()
    val runIds = run.map { it.id }
    val olderThanRun = if (index + run.size < items.itemCount) items.peek(index + run.size) else null

    val summary = if (run.size == 1) newest.text else summarizeSystemRun(run)

    // Divider before the run when the run's newest crosses the marker and its older neighbor doesn't.
    val showNewDivider = readMarkerTime != null &&
        newest.timelineAnchor() > readMarkerTime &&
        (olderThanRun == null || olderThanRun.timelineAnchor() <= readMarkerTime)
    val showDay = remember(oldest.serverTime, olderThanRun?.serverTime) {
        olderThanRun == null || dayStart(oldest.serverTime) != dayStart(olderThanRun.serverTime)
    }

    // Column so the pill and any dividers stack vertically. A bare item slot stacks siblings on top
    // of each other (its MeasurePolicy behaves like a Box), which would overlap the divider text.
    Column(modifier = Modifier.fillMaxWidth()) {
        if (showNewDivider) {
            NewMessagesDivider(
                label = readMarkerLabel ?: stringResource(R.string.chat_new_messages),
                modifier = Modifier.testTag("chat_read_marker_divider"),
            )
        }
        SystemEventPill(
            summary = summary,
            lineCount = run.size,
            loadLines = { run.map { it.text } },
            contentKey = SystemRunContentKey(newest.id, oldest.id, run.size),
            expanded = systemRunExpanded(runIds, expandedEventIds),
            onExpandedChange = { expanded -> onExpandedChange(runIds, expanded) },
            modifier = Modifier.testTag("chat_system_pill"),
        )
        if (showDay) DaySeparator(timeMs = oldest.serverTime)
    }
}

/**
 * A run begins at its newest row and at fixed absolute-index chunk boundaries. This is deliberately
 * O(1): suppressed rows do no neighbor walk while flinging, and each event belongs to one head.
 */
internal fun isSystemRunChunkHead(index: Int, newerIsSystem: Boolean): Boolean =
    !newerIsSystem || index % MAX_COLLAPSED_SYSTEM_EVENTS == 0

/** Number of rows from [index] through the next absolute chunk boundary (at most 24). */
internal fun systemRunChunkLimit(index: Int): Int =
    MAX_COLLAPSED_SYSTEM_EVENTS - (index % MAX_COLLAPSED_SYSTEM_EVENTS)

/**
 * Summarize a run of system events by kind: JOIN → "joined", PART/QUIT → "left", others by kind
 * name. Produces "3 joined · 1 left" style text. Counts are grouped preserving first appearance.
 */
private fun summarizeSystemRun(run: List<MessageEntity>): String {
    val counts = LinkedHashMap<String, Int>()
    for (m in run) {
        val label = when (m.kind) {
            MessageKind.JOIN -> "joined"
            MessageKind.PART, MessageKind.QUIT -> "left"
            MessageKind.KICK -> "kicked"
            MessageKind.NICK -> "renamed"
            MessageKind.MODE -> "mode"
            MessageKind.TOPIC -> "topic"
            else -> "events"
        }
        counts[label] = (counts[label] ?: 0) + 1
    }
    return counts.entries.joinToString(" · ") { (label, n) -> "$n $label" }
}

/** Completion-tracked link-preview state so a failed/null fetch stops the loading skeleton. */
private sealed interface PreviewState {
    data object Idle : PreviewState
    data object Loading : PreviewState
    data class Done(val preview: LinkPreview?) : PreviewState
}

@Composable
private fun MessageRow(
    msg: MessageEntity,
    networkId: Long?,
    bufferId: Long?,
    conversationName: String?,
    directMessage: Boolean,
    older: MessageEntity?,
    formatTime: (Long) -> String,
    readMarkerTime: TimelineAnchor?,
    readMarkerLabel: String?,
    senderIsFriend: Boolean,
    reactions: List<ReactionChip>,
    knownNicks: Set<String>,
    identityRules: IrcIdentityRules,
    onLongPress: (MessageEntity) -> Unit,
    onReply: (MessageEntity) -> Unit,
    onReact: (MessageEntity, String) -> Unit,
    onImageClick: (String) -> Unit,
    onRetry: (MessageEntity) -> Unit,
    canRetry: Boolean,
    onDelete: (MessageEntity) -> Unit,
    loadPreview: suspend (String) -> LinkPreview?,
    showImages: Boolean,
    showLinkPreviews: Boolean,
    canStartNewRichContentWork: Boolean,
    cachedPreview: (String) -> CachedLinkPreview?,
    loadAudioMetadata: suspend (String, Long?) -> AudioMetadata?,
    cachedAudioMetadata: (String) -> CachedAudioMetadata?,
    audioPlaybackState: AudioPlaybackState,
    audioWaveforms: Map<String, AudioWaveform>,
    audioCacheStatuses: Map<String, AudioCacheStatus>,
    onAudioToggle: (AudioPlaybackRequest) -> Unit,
    onAudioCacheInspect: (AudioAttachment) -> Unit,
    onAudioSeek: (AudioAttachment, Long) -> Unit,
    onOpenLink: (String) -> Unit,
    onSenderClick: (String) -> Unit,
    replyPreview: (String) -> StateFlow<ReplyPreviewData?>,
    onReplyPreviewClick: (String) -> Unit,
    // Non-null for an expanded fool row: renders a "hide" chip above the bubble that re-collapses it.
    onCollapseFool: (() -> Unit)? = null,
) {
    // The lazy list reverses item order, not a row's children. Render the divider before the first
    // unread bubble so the boundary is visually above that message.
    val showNewDivider = readMarkerTime != null &&
        msg.timelineAnchor() > readMarkerTime &&
        (older == null || older.timelineAnchor() <= readMarkerTime)

    // Day separator when this message starts a new day relative to the older neighbor.
    val showDay = remember(msg.serverTime, older?.serverTime) {
        older == null || dayStart(msg.serverTime) != dayStart(older.serverTime)
    }

    // Telegram-style inter-bubble gap (COMFORTABLE only): a small burst gap while a same-sender
    // group continues, a larger break gap when a new group opens. Hoisted so the same value feeds
    // both the gap spacer and the bubble's grouped-corner/header logic below.
    val spacing = LocalSpacing.current
    val showSender = showsSender(msg, older)
    val gap = bubbleGap(showSender, older != null, spacing)

    if (showNewDivider) {
        NewMessagesDivider(
            label = readMarkerLabel ?: stringResource(R.string.chat_new_messages),
            modifier = Modifier.testTag("chat_read_marker_divider"),
        )
    }

    // A row asks Room for its reply target only while it is composed. This avoids timeline-wide
    // loaded-window scans during fast traversal; collection is lifecycle-cancelled off-screen.
    val resolvedReply: ReplyPreviewData? = if (msg.replyToMsgid != null) {
        val replyFlow = remember(msg.replyToMsgid) { replyPreview(msg.replyToMsgid) }
        val resolved by replyFlow.collectAsStateWithLifecycle()
        resolved
    } else {
        null
    }
    // A reply relationship remains visible even if its parent is not in local history yet. The
    // reactive lookup above replaces this marker as soon as echo confirmation or history inserts
    // the referenced msgid.
    val reply = resolvedReply ?: msg.replyToMsgid?.let {
        ReplyPreviewData(
            sender = stringResource(R.string.chat_action_reply),
            text = stringResource(R.string.chat_reply_target_unavailable),
        )
    }

    // URL discovery is unnecessary for the overwhelming majority of IRC lines. Completed parses
    // come from a bounded process cache first; only a genuine miss waits for the fling to settle.
    val mayContainUrl = remember(msg.text) {
        msg.text.contains("http://") || msg.text.contains("https://")
    }
    var richUrls by remember(msg.id, msg.text) {
        mutableStateOf(if (mayContainUrl) MessageUrlCache.get(msg.text) else MessageUrls.Empty)
    }
    val latestCanStartNewRichContentWork by rememberUpdatedState(canStartNewRichContentWork)
    LaunchedEffect(msg.id, msg.text, mayContainUrl) {
        if (!mayContainUrl || richUrls != null) return@LaunchedEffect
        snapshotFlow { latestCanStartNewRichContentWork }.first { it }
        val parsed = withContext(Dispatchers.Default) { messageUrls(msg.text) }
        MessageUrlCache.put(msg.text, parsed)
        richUrls = parsed
    }
    val visibleUrls = richUrls?.gated(showImages, showLinkPreviews)
    val imageUrl = visibleUrls?.imageUrl
    val linkUrl = visibleUrls?.linkUrl
    val immediateAudio = visibleUrls?.audio.orEmpty()
    val headCandidates = remember(msg.id, msg.text, showLinkPreviews) {
        if (showLinkPreviews) extensionlessAudioCandidates(msg.text) else emptyList()
    }
    var headAudio by remember(msg.id, headCandidates) {
        mutableStateOf(
            headCandidates.mapNotNull { cachedAudioMetadata(it)?.metadata?.toAttachment() },
        )
    }
    val latestCachedAudioMetadata by rememberUpdatedState(cachedAudioMetadata)
    val latestLoadAudioMetadata by rememberUpdatedState(loadAudioMetadata)
    LaunchedEffect(msg.id, headCandidates, networkId) {
        if (headCandidates.isEmpty()) return@LaunchedEffect
        snapshotFlow { latestCanStartNewRichContentWork }.first { it }
        val resolved = headCandidates.take(8).mapNotNull { url ->
            latestCachedAudioMetadata(url)?.metadata
                ?: try {
                    latestLoadAudioMetadata(url, networkId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
        }.map { it.toAttachment() }
        headAudio = resolved
    }
    val audioAttachments = remember(immediateAudio, headAudio) {
        (immediateAudio + headAudio).distinctBy { it.url }
    }
    val messageText = remember(msg.text, audioAttachments) {
        displayTextForAudioMessage(msg.text, audioAttachments)
    }
    val standaloneVoice = audioAttachments.size == 1 && audioAttachments.single().voice &&
        messageText.isBlank() && reply == null

    // A cached completion is rendered synchronously even while scrolling. A cache miss waits for
    // idle, then joins the repository's process-owned single-flight fetch. Null is a completed
    // negative result, not a loading state, so recycling does not restart a skeleton indefinitely.
    val initialCachedPreview = linkUrl?.let(cachedPreview)
    var previewState by remember(msg.id, linkUrl) {
        mutableStateOf<PreviewState>(initialCachedPreview?.let { PreviewState.Done(it.preview) } ?: PreviewState.Idle)
    }
    val latestCachedPreview by rememberUpdatedState(cachedPreview)
    LaunchedEffect(msg.id, linkUrl) {
        val url = linkUrl ?: return@LaunchedEffect
        if (previewState !is PreviewState.Idle) return@LaunchedEffect
        latestCachedPreview(url)?.let {
            previewState = PreviewState.Done(it.preview)
            return@LaunchedEffect
        }
        snapshotFlow { latestCanStartNewRichContentWork }.first { it }
        if (previewState !is PreviewState.Idle) return@LaunchedEffect
        latestCachedPreview(url)?.let {
            previewState = PreviewState.Done(it.preview)
            return@LaunchedEffect
        }
        previewState = PreviewState.Loading
        val preview = try {
            loadPreview(url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        previewState = PreviewState.Done(preview)
    }
    val preview = (previewState as? PreviewState.Done)?.preview?.withImageGate(showImages)
    val previewLoading = linkUrl != null && previewState is PreviewState.Loading
    val previewResolved = linkUrl != null && previewState is PreviewState.Done
    val formattedTime = remember(msg.serverTime, formatTime) { formatTime(msg.serverTime) }
    // Ordinary rows stay on the hot scrolling path without even resolving the accessibility
    // string; mention state is immutable for a stored row and only the sparse highlighted rows
    // need it.
    val mentionDescription = if (msg.hasMention && !msg.isSelf) {
        stringResource(R.string.chat_message_mentions_you)
    } else {
        null
    }

    // Gap sits on the older-neighbor side (before the bubble) so it separates this row from the
    // previous burst; day separators and read markers live on the newer side (after the bubble), so
    // the two never stack. 0.dp (no older neighbor, or non-COMFORTABLE) collapses the spacer in
    // place — Compose skips zero-height spacers in measurement.
    if (gap > 0.dp) Spacer(Modifier.height(gap))

    onCollapseFool?.let { FoolCollapseChip(sender = msg.sender, tag = foolCollapseTag(msg.msgid, msg.id), onCollapse = it) }

    SwipeToReplyContainer(
        // Keep the stable automation id and mention state on one semantics node. SwipeToReply adds
        // its custom action downstream without changing this message-level accessibility state.
        modifier = Modifier.semantics {
            semanticsTestTag = messageTag(msg)
            mentionDescription?.let { stateDescription = it }
        },
        onReply = { onReply(msg) },
    ) { rowModifier ->
        Column(modifier = rowModifier.fillMaxWidth()) {
            val messageBubble: @Composable () -> Unit = {
                MessageBubble(
                    // Per-message handle for long-press/react/reply/deep-jump. Prefer the stable
                    // server msgid; pending rows fall back to the local id for E2E selection.
                    modifier = Modifier,
                    sender = msg.sender,
                    networkId = networkId,
                    senderAccount = msg.senderAccount,
                    text = messageText,
                    timeMs = msg.serverTime,
                    formattedTime = formattedTime,
                    isSelf = msg.isSelf,
                    kind = msg.kind,
                    showSender = showSender,
                    hasMention = msg.hasMention,
                    senderIsFriend = senderIsFriend,
                    failed = msg.failed,
                    // Subtle "sending…" state before the 30s failure flip (plans/15 #21).
                    pending = msg.pendingLabel != null,
                    reply = reply,
                    onReplyClick = if (resolvedReply != null) {
                        msg.replyToMsgid?.let { parentMsgid -> { onReplyPreviewClick(parentMsgid) } }
                    } else {
                        null
                    },
                    imageUrl = imageUrl,
                    linkPreview = preview,
                    linkPreviewLoading = previewLoading,
                    linkPreviewResolved = previewResolved,
                    reactions = reactions,
                    knownNicks = knownNicks,
                    identityRules = identityRules,
                    onLongPress = { onLongPress(msg) },
                    // Pass the entity, not just msgid: the VM handles pending reactions uniformly.
                    onReact = { emoji -> onReact(msg, emoji) },
                    onImageClick = onImageClick,
                    onLinkPreviewClick = { linkUrl?.let(onOpenLink) },
                    // Only non-self senders open the nick sheet.
                    onSenderClick = if (msg.isSelf) null else ({ onSenderClick(msg.sender) }),
                )
            }
            if (headCandidates.isNotEmpty()) {
                // An extensionless URL may resolve into a standalone voice message after HEAD
                // metadata arrives. Shrink the provisional bubble while the player grows.
                AnimatedVisibility(
                    visible = !standaloneVoice,
                    enter = expandVertically(
                        animationSpec = MotdMotion.contentSize,
                        expandFrom = Alignment.Bottom,
                    ) + fadeIn(MotdMotion.microFadeIn),
                    exit = shrinkVertically(
                        animationSpec = MotdMotion.contentSize,
                        shrinkTowards = Alignment.Bottom,
                    ) + fadeOut(MotdMotion.microFadeOut),
                ) {
                    messageBubble()
                }
            } else if (!standaloneVoice) {
                messageBubble()
            }
            AudioAttachmentPlayers(
                attachments = audioAttachments,
                playbackState = audioPlaybackState,
                derivedWaveforms = audioWaveforms,
                cacheStatuses = audioCacheStatuses,
                networkId = networkId,
                isSelf = msg.isSelf,
                formattedTime = if (standaloneVoice) formattedTime else null,
                pending = msg.pendingLabel != null,
                failed = msg.failed,
                origin = if (bufferId != null && networkId != null && conversationName != null) {
                    AudioPlaybackOrigin(
                        bufferId = bufferId,
                        networkId = networkId,
                        conversation = conversationName,
                        sender = msg.sender,
                        isSelf = msg.isSelf,
                        directMessage = directMessage,
                        eventId = msg.id,
                        msgid = msg.msgid,
                        serverTime = msg.serverTime,
                    )
                } else {
                    null
                },
                onToggle = { attachment, routeNetworkId ->
                    val origin = if (bufferId != null && networkId != null && conversationName != null) {
                        AudioPlaybackOrigin(
                            bufferId = bufferId,
                            networkId = networkId,
                            conversation = conversationName,
                            sender = msg.sender,
                            isSelf = msg.isSelf,
                            directMessage = directMessage,
                            eventId = msg.id,
                            msgid = msg.msgid,
                            serverTime = msg.serverTime,
                        )
                    } else {
                        null
                    }
                    onAudioToggle(AudioPlaybackRequest(attachment, routeNetworkId, origin))
                },
                onInspectCache = onAudioCacheInspect,
                onSeek = onAudioSeek,
                onLongPress = { onLongPress(msg) },
                reactions = if (standaloneVoice) reactions else emptyList(),
                onReact = { emoji -> onReact(msg, emoji) },
            )
        }
    }
    if (msg.failed) {
        RetryRow(
            onRetry = if (canRetry) ({ onRetry(msg) }) else null,
            onDelete = { onDelete(msg) },
        )
    }

    if (showDay) DaySeparator(timeMs = msg.serverTime)
}

/**
 * COLLAPSE placeholder for a fool's message (plans/13 §2.4): a dimmed one-line "nick · hidden" row
 * that expands to the full bubble on tap for the rest of the session. Day-separator and read-marker
 * dividers are drawn exactly as [MessageRow] does so grouping boundaries stay intact whether or not
 * the row is expanded.
 */
@Composable
private fun FoolPlaceholderRow(
    msg: MessageEntity,
    older: MessageEntity?,
    readMarkerTime: TimelineAnchor?,
    readMarkerLabel: String?,
    onExpand: () -> Unit,
) {
    val showNewDivider = readMarkerTime != null &&
        msg.timelineAnchor() > readMarkerTime &&
        (older == null || older.timelineAnchor() <= readMarkerTime)
    val showDay = remember(msg.serverTime, older?.serverTime) {
        older == null || dayStart(msg.serverTime) != dayStart(older.serverTime)
    }

    // Column so the placeholder row and any dividers stack vertically rather than overlapping (a bare
    // item slot stacks its children like a Box).
    Column(modifier = Modifier.fillMaxWidth()) {
        if (showNewDivider) {
            NewMessagesDivider(
                label = readMarkerLabel ?: stringResource(R.string.chat_new_messages),
                modifier = Modifier.testTag("chat_read_marker_divider"),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Collapsed fool row is still a message container; keep it selectable/tappable.
                .testTag(messageTag(msg))
                .clickable { onExpand() }
                .alpha(0.7f)
                .padding(horizontal = LocalSpacing.current.messageOuterHPad, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.VisibilityOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = stringResource(R.string.chat_fool_hidden, msg.sender),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (showDay) DaySeparator(timeMs = msg.serverTime)
    }
}

/**
 * Tap-to-re-collapse chip drawn above an expanded fool's bubble (bug #9). Mirrors the dimmed
 * placeholder styling of [FoolPlaceholderRow] so the toggle reads as its inverse, and keeps the
 * bubble's own long-press/link taps intact by owning a separate tap target.
 */
@Composable
internal fun FoolCollapseChip(sender: String, tag: String, onCollapse: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clickable { onCollapse() }
            .alpha(0.7f)
            .padding(horizontal = LocalSpacing.current.messageOuterHPad, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.VisibilityOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = stringResource(R.string.chat_fool_collapse, sender),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

const val CHAT_HISTORY_RETRY_TAG = "chat_history_retry"
const val CHAT_HISTORY_LOADING_TAG = "chat_history_loading"

/**
 * Older-end paging footer. Scroll-driven APPEND drives history automatically, so the footer only
 * renders the shimmer, a retry affordance for recoverable errors, or a terminal status line. Only
 * persisted protocol completion may render the beginning-of-history claim.
 */
@Composable
fun ChatHistoryFooter(
    state: ChatHistoryUiState,
    onRetry: () -> Unit,
) {
    when (state) {
        ChatHistoryUiState.Hidden -> Unit
        ChatHistoryUiState.Loading -> androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .testTag(CHAT_HISTORY_LOADING_TAG),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
        }
        ChatHistoryUiState.Retry -> HistoryRetryFooter(
            text = stringResource(R.string.chat_history_error),
            onRetry = onRetry,
        )
        is ChatHistoryUiState.Unavailable -> HistoryStatusText(
            if (state.offline) {
                R.string.chat_history_footer_offline
            } else {
                R.string.chat_history_footer_negotiating
            },
        )
        ChatHistoryUiState.Unsupported -> HistoryStatusText(R.string.chat_history_footer_unsupported)
        ChatHistoryUiState.ConfirmedStart -> HistoryStatusText(R.string.chat_history_start)
    }
}

@Composable
private fun HistoryStatusText(textRes: Int) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistoryRetryFooter(text: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(
            onClick = onRetry,
            modifier = Modifier.heightIn(min = 48.dp).testTag(CHAT_HISTORY_RETRY_TAG),
        ) {
            Text(stringResource(R.string.chat_retry))
        }
    }
}

/** Right-aligned retry (when safe) and delete affordances under a failed message bubble. */
@Composable
private fun RetryRow(onRetry: (() -> Unit)?, onDelete: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalSpacing.current.messageOuterHPad, vertical = 2.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        if (onRetry != null) {
            RetryRowAction(
                icon = Icons.Filled.Refresh,
                label = stringResource(R.string.chat_retry),
                onClick = onRetry,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        }
        RetryRowAction(
            icon = Icons.Filled.DeleteOutline,
            label = stringResource(R.string.chat_delete_failed),
            onClick = onDelete,
        )
    }
}

/** One error-tinted, >=48dp-tall tappable label used by [RetryRow] (plans/15 #24). */
@Composable
private fun RetryRowAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .wrapContentHeight()
            .clickable { onClick() }
            .padding(horizontal = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(
            icon,
            contentDescription = null,
            tint = androidx.compose.material3.MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        androidx.compose.material3.Text(
            text = label,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
