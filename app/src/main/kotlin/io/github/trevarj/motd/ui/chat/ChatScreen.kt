package io.github.trevarj.motd.ui.chat

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Trace
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetState
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.trevarj.motd.R
import io.github.trevarj.motd.audio.AudioAttachment
import io.github.trevarj.motd.audio.AudioCacheStatus
import io.github.trevarj.motd.audio.AudioMetadata
import io.github.trevarj.motd.audio.AudioPlaybackState
import io.github.trevarj.motd.audio.AudioPlaybackRequest
import io.github.trevarj.motd.audio.AudioPlaybackOrigin
import io.github.trevarj.motd.audio.AudioWaveform
import io.github.trevarj.motd.audio.CachedAudioMetadata
import io.github.trevarj.motd.audio.VoiceSendProgress
import io.github.trevarj.motd.audio.formatAudioDuration
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.DccTransferEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.matchesConfiguredNick
import io.github.trevarj.motd.data.visibility.MessageVisibilityPolicy
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.backend.ConnectionState
import io.github.trevarj.motd.diagnostics.AutoFollowTrace
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.service.HistoryResyncState
import io.github.trevarj.motd.service.HistoryRefreshRange
import io.github.trevarj.motd.service.HistorySyncStatus
import io.github.trevarj.motd.ui.components.Avatar
import io.github.trevarj.motd.ui.components.AudioMiniPlayer
import io.github.trevarj.motd.ui.components.AutocompletePanel
import io.github.trevarj.motd.ui.components.Composer
import io.github.trevarj.motd.ui.components.ComposerReply
import io.github.trevarj.motd.ui.components.WaveformScrubber
import io.github.trevarj.motd.ui.components.typingText
import io.github.trevarj.motd.ui.theme.ConversationTypography
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdSizes
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.LocalSpacing
import io.github.trevarj.motd.ui.theme.spacingFor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Pause after the last keystroke before the nick-autocomplete panel becomes visible, so fast
 *  typing doesn't flash suggestions on every character. */
private const val AUTOCOMPLETE_SHOW_DEBOUNCE_MS = 250L
private const val REACTION_PREFETCH_ROWS = 12
private const val MAX_VISIBLE_REACTION_MSGIDS = 80
private const val MAX_UNREAD_BADGE_COUNT = 100
internal const val HISTORY_SYNC_INDICATOR_DELAY_MS = 5_000L
internal const val EMPTY_HISTORY_LOADING_INDICATOR_DELAY_MS = 400L

private data class PendingDccAccept(
    val transferId: Long,
    val allowPrivateEndpoint: Boolean,
)

/** How long (ms) the scroll-to-bottom FAB must be held to skip the mention walk and jump to newest. */
internal const val SCROLL_TO_BOTTOM_FAB_HOLD_MS = 450
private const val SCROLL_TO_BOTTOM_FAB_SETTLE_MS = 160
private const val SCROLL_TO_BOTTOM_FAB_HELD_SCALE = 0.92f

/** Continuous icon compression used while the FAB's hold ring fills. */
internal fun scrollToBottomFabIconScale(progress: Float): Float =
    1f - (1f - SCROLL_TO_BOTTOM_FAB_HELD_SCALE) * progress.coerceIn(0f, 1f)

internal class ChatForegroundLifecycleGate(
    private val onResume: () -> Unit,
    private val onPause: () -> Unit,
) {
    private var resumed = false

    fun sync(isResumed: Boolean) {
        if (isResumed == resumed) return
        resumed = isResumed
        if (isResumed) onResume() else onPause()
    }

    fun onEvent(event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_RESUME -> sync(true)
            Lifecycle.Event.ON_PAUSE,
            Lifecycle.Event.ON_STOP,
            Lifecycle.Event.ON_DESTROY,
            -> sync(false)
            else -> Unit
        }
    }

    fun dispose() = sync(false)
}

/** Ensures the outgoing chat surface releases the IME before navigation reveals the list. */
internal fun dismissKeyboardBeforeNavigating(
    clearFocus: () -> Unit,
    hideKeyboard: () -> Unit,
    onBack: () -> Unit,
) {
    clearFocus()
    hideKeyboard()
    onBack()
}

/** Stateful entry: wires the ViewModel, lifecycle mark-read, and navigation. */
@Composable
fun ChatScreen(
    bufferId: Long,
    onBack: () -> Unit = {},
    showBack: Boolean = true,
    onOpenChannelInfo: (Long) -> Unit = {},
    onOpenSearch: (Long) -> Unit = {},
    onOpenImage: (String) -> Unit = {},
    // /msg and /query resolve-or-create a QUERY buffer via the VM, then navigate to it.
    onOpenBuffer: (Long) -> Unit = {},
    onOpenAudioOrigin: (AudioPlaybackOrigin) -> Unit = {},
    // Round 5 (plans/16): /list opens the channel browser. Body lands in WP-V3.
    onOpenChannelList: (Long) -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
    voiceViewModel: VoiceMessageViewModel = hiltViewModel(),
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val onHeaderBack = remember(focusManager, keyboardController, onBack) {
        {
            dismissKeyboardBeforeNavigating(
                clearFocus = focusManager::clearFocus,
                hideKeyboard = { keyboardController?.hide() },
                onBack = onBack,
            )
        }
    }
    var mentionRequest by remember { mutableStateOf<Pair<Long, String>?>(null) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val items = viewModel.messages.collectAsLazyPagingItems()
    val memberNicks by viewModel.memberNicks.collectAsStateWithLifecycle()
    val knownNicks by viewModel.knownNicks.collectAsStateWithLifecycle()
    val voiceState by voiceViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingVoiceStart by remember { mutableStateOf<Boolean?>(null) }
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val locked = pendingVoiceStart
        pendingVoiceStart = null
        if (granted && locked != null) voiceViewModel.startRecording(locked)
        else voiceViewModel.clearError()
    }

    fun startVoiceRecording(locked: Boolean) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            voiceViewModel.startRecording(locked)
        } else {
            pendingVoiceStart = locked
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Composition survives Home/recents, so use the actual resumed lifecycle instead of treating
    // "still composed" as foreground. This gates notifications and chat sounds correctly.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel, voiceViewModel) {
        val gate = ChatForegroundLifecycleGate(
            onResume = viewModel::onResume,
            onPause = {
                viewModel.onPause()
                voiceViewModel.stopForBackground()
            },
        )
        val observer = LifecycleEventObserver { _, event -> gate.onEvent(event) }
        lifecycleOwner.lifecycle.addObserver(observer)
        gate.sync(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            gate.dispose()
        }
    }

    val chipsByMsgid by viewModel.reactionChips.collectAsStateWithLifecycle()
    val identityRules by viewModel.identityRules.collectAsStateWithLifecycle()
    val reactionChipsForMessage = remember(chipsByMsgid) {
        { msgid: String -> chipsByMsgid[msgid].orEmpty() }
    }
    // The VM resolves the live ISUPPORT normalizer. Memoize the returned lambda so unrelated
    // header/composer state changes do not invalidate every lazy-list row through a new function
    // identity.
    val nickNormalizer = remember(identityRules) { identityRules::normalize }

    val jumpTarget by viewModel.jumpTarget.collectAsStateWithLifecycle()
    val initialTarget by viewModel.initialTarget.collectAsStateWithLifecycle()
    val entryPositionSettled by viewModel.entryPositionSettled.collectAsStateWithLifecycle()
    val entryMessageUnavailable by viewModel.entryMessageUnavailable.collectAsStateWithLifecycle()
    // Read marker frozen on entry so the "New messages" divider doesn't flash away (plans/15 #2).
    val readMarkerSnapshot by viewModel.readMarkerSnapshot.collectAsStateWithLifecycle()
    // Live read marker drives the FAB unread badge so it clears as messages are read (not on exit).
    val localReadAnchor by viewModel.localReadAnchor.collectAsStateWithLifecycle()
    val rawNewestAnchor by viewModel.rawNewestAnchor.collectAsStateWithLifecycle()
    val composerDraft by viewModel.composerDraft.collectAsStateWithLifecycle()
    // Timeline behavioral settings collected separately from ChatState (plans/13 §2.5).
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val hiddenFoolsRevealed by viewModel.hiddenFoolsRevealed.collectAsStateWithLifecycle()
    val appearance by viewModel.appearance.collectAsStateWithLifecycle(
        initialValue = io.github.trevarj.motd.data.prefs.AppearanceConfig(),
    )
    val contentPreviews by viewModel.contentPreviews.collectAsStateWithLifecycle()
    val audioPlaybackState by viewModel.audioPlaybackState.collectAsStateWithLifecycle()
    val audioWaveforms by viewModel.audioWaveforms.collectAsStateWithLifecycle()
    val audioCacheStatuses by viewModel.audioCacheStatuses.collectAsStateWithLifecycle()
    val replyConfig by viewModel.replyConfig.collectAsStateWithLifecycle()
    val historyAvailability by viewModel.historyAvailability.collectAsStateWithLifecycle()
    // Round 5: nick sheet + replay-safe UI events (plans/16 §5.6/§5.8).
    val nickSheet by viewModel.nickSheet.collectAsStateWithLifecycle()
    val uiEvents by viewModel.uiEvents.collectAsStateWithLifecycle()
    val historyResyncState by viewModel.historyResyncState.collectAsStateWithLifecycle()
    val historySyncStatus by viewModel.historySyncStatus.collectAsStateWithLifecycle()
    val isServerBuffer = state.buffer?.type == BufferType.SERVER
    val titleTarget = chatTitleTarget(state.buffer?.type)

    ChatContent(
        state = state,
        items = items,
        composerEnabled = (!isServerBuffer || state.connState is ConnectionState.Ready) && !state.parted,
        friends = settings.friends,
        fools = settings.fools,
        foolsMode = settings.foolsMode,
        hiddenFoolsRevealed = hiddenFoolsRevealed,
        onHiddenFoolsRevealedChange = viewModel::setHiddenFoolsRevealed,
        showJoinPartQuit = settings.showJoinPartQuit,
        chatWallpaper = appearance.wallpaper,
        conversationFontScalePercent = appearance.conversationFontScalePercent,
        showComposerEmoji = settings.showComposerEmoji,
        visibleReplyPrefix = replyConfig.visibleChannelPrefix,
        showImages = contentPreviews.showImages,
        showLinkPreviews = contentPreviews.showLinkPreviews,
        reactionChips = reactionChipsForMessage,
        replyPreview = viewModel::replyPreview,
        onReplyPreviewClick = viewModel::jumpToRepliedMessage,
        dccTransfer = viewModel::dccTransfer,
        onAcceptDccTransfer = viewModel::acceptDccTransfer,
        onRejectDccTransfer = viewModel::rejectDccTransfer,
        onRemoveDccTransfer = viewModel::removeDccTransfer,
        onSendDccFile = viewModel::sendDccFile,
        memberNicks = memberNicks,
        knownNicks = knownNicks,
        identityRules = identityRules,
        readMarkerSnapshot = readMarkerSnapshot,
        readMarkerLive = localReadAnchor,
        rawNewestAnchor = rawNewestAnchor,
        onMarkRead = viewModel::markRead,
        countUnreadBelowViewport = viewModel::countUnreadBelowViewport,
        nearestUnreadMentionBelow = viewModel::nearestUnreadMentionBelow,
        onBack = onHeaderBack,
        showBack = showBack,
        // Channel titles open Channel Info; query titles describe the other user. SERVER buffers
        // have neither channel nor peer details, so their title remains inert.
        onOpenChannelInfo = { id ->
            when (titleTarget) {
                ChatTitleTarget.CHANNEL_INFO -> onOpenChannelInfo(id)
                ChatTitleTarget.NICK_DETAILS -> state.buffer?.displayName?.let(viewModel::openNickSheet)
                ChatTitleTarget.NONE -> Unit
            }
        },
        onOpenSearch = onOpenSearch,
        onOpenImage = onOpenImage,
        nickNormalizer = nickNormalizer,
        onSubmit = { raw -> viewModel.submit(raw, onOpenBuffer = onOpenBuffer, onOpenChannelList = onOpenChannelList) },
        onTyping = viewModel::sendTyping,
        onSetReply = viewModel::setReply,
        onReact = viewModel::react,
        onRetry = viewModel::retry,
        onDelete = viewModel::deleteFailed,
        onAcceptInvite = viewModel::acceptInvite,
        onDismissInvite = viewModel::dismissInvite,
        onRejoin = viewModel::rejoinChannel,
        loadPreview = viewModel::linkPreview,
        cachedPreview = viewModel::cachedLinkPreview,
        loadAudioMetadata = viewModel::audioMetadata,
        cachedAudioMetadata = viewModel::cachedAudioMetadata,
        audioPlaybackState = audioPlaybackState,
        audioWaveforms = audioWaveforms,
        audioCacheStatuses = audioCacheStatuses,
        onAudioToggle = viewModel::toggleAudio,
        onAudioCacheInspect = viewModel::inspectAudioCache,
        onAudioSeek = viewModel::seekAudio,
        onAudioSpeed = viewModel::setAudioSpeed,
        onAudioToggleActive = viewModel::toggleActiveAudio,
        onAudioCancelLoading = viewModel::cancelAudioLoading,
        onAudioRetry = viewModel::retryActiveAudio,
        onAudioDismiss = viewModel::dismissActiveAudio,
        onOpenAudioOrigin = onOpenAudioOrigin,
        voiceState = voiceState,
        voiceEnabled = !isServerBuffer && (!state.parted),
        onVoiceHoldStart = { startVoiceRecording(locked = false) },
        onVoiceHoldStop = voiceViewModel::stopRecording,
        onVoiceHoldCancel = voiceViewModel::cancelRecording,
        onVoiceLock = voiceViewModel::lockRecording,
        onVoiceDelete = voiceViewModel::deleteStaged,
        onVoiceSend = voiceViewModel::send,
        onVoiceToggleEncryption = voiceViewModel::toggleEncryption,
        onVoiceDestinationSelected = voiceViewModel::setDestination,
        onVoiceErrorDismissed = voiceViewModel::clearError,
        onVoiceNoticeDismissed = voiceViewModel::clearNotice,
        consumePrefill = viewModel::consumePrefill,
        composerDraft = composerDraft,
        onDraftChanged = viewModel::saveDraft,
        mentionPrefill = mentionRequest,
        jumpTarget = jumpTarget,
        initialTarget = initialTarget,
        entryPositionInitiallySettled = entryPositionSettled,
        entryMessageUnavailable = entryMessageUnavailable,
        onJumpHandled = viewModel::onJumpHandled,
        onInitialPositionHandled = viewModel::onInitialPositionHandled,
        onInitialPositionUnresolved = viewModel::onInitialPositionUnresolved,
        onScrollPositionChanged = viewModel::saveScrollPosition,
        onClearScrollPosition = viewModel::clearScrollPosition,
        onVisibleMsgidsChanged = viewModel::setVisibleMsgids,
        onNeedMembers = viewModel::ensureMembersObserved,
        onJumpUnresolved = viewModel::onJumpUnresolved,
        onReresolveJump = viewModel::reresolveJumpOnce,
        onReresolveInitial = viewModel::reresolveInitialOnce,
        isServerBuffer = isServerBuffer,
        onSenderClick = viewModel::openNickSheet,
        uiEvent = uiEvents.firstOrNull(),
        onUiEventAcknowledged = viewModel::acknowledgeUiEvent,
        onRetryReplyJump = viewModel::retryReplyJump,
        historyResyncState = historyResyncState,
        historySyncStatus = historySyncStatus,
        historyAvailability = historyAvailability,
        onRefreshHistory = viewModel::refreshHistory,
        onCancelHistoryRefresh = viewModel::cancelHistoryRefresh,
        onHistoryResyncShown = viewModel::consumeHistoryResyncState,
        conversationLayout = state.conversationLayout,
        onConversationLayoutSelected = viewModel::setConversationLayoutOverride,
    )

    // Nick sheet (plans/16 §5.8): actions render immediately; whois fills in when it lands.
    nickSheet?.let { sheet ->
        val norm = identityRules::normalize
        val myNick = (state.connState as? ConnectionState.Ready)?.selfHandle
        val isSelf = myNick != null && norm(sheet.nick) == norm(myNick)
        NickActionSheet(
            nick = sheet.nick,
            networkId = state.buffer?.networkId,
            isSelf = isSelf,
            isFriend = identityRules.matchesConfiguredNick(sheet.nick, settings.friends),
            isFool = identityRules.matchesConfiguredNick(sheet.nick, settings.fools),
            canModerate = viewModel.canModerate(),
            whois = sheet.details,
            presence = state.buffer?.networkId?.let { networkId ->
                state.presence[
                    io.github.trevarj.motd.service.PresenceKey(networkId, norm(sheet.nick)),
                ]
            },
            onDismiss = viewModel::dismissNickSheet,
            onMessage = { viewModel.dismissNickSheet(); viewModel.submit("/query ${sheet.nick}", onOpenBuffer) },
            onMention = {
                mentionRequest = System.nanoTime() to "${sheet.nick}: "
                viewModel.dismissNickSheet()
            },
            onToggleFriend = { viewModel.toggleFriend(sheet.nick) },
            onToggleFool = { viewModel.toggleFool(sheet.nick) },
            onIgnoreNetwork = { viewModel.ignoreNickOnNetwork(sheet.nick) },
            onOp = { grant -> viewModel.setMemberMode(sheet.nick, 'o', grant) },
            onVoice = { grant -> viewModel.setMemberMode(sheet.nick, 'v', grant) },
            onKick = { reason -> viewModel.dismissNickSheet(); viewModel.kick(sheet.nick, reason) },
            onBan = { viewModel.dismissNickSheet(); viewModel.ban(sheet.nick) },
            showMention = state.buffer?.type == BufferType.CHANNEL,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatContent(
    state: ChatState,
    items: LazyPagingItems<MessageEntity>,
    composerEnabled: Boolean,
    onBack: () -> Unit,
    showBack: Boolean = true,
    onOpenChannelInfo: (Long) -> Unit,
    onOpenSearch: (Long) -> Unit,
    onOpenImage: (String) -> Unit,
    nickNormalizer: (String) -> String,
    onSubmit: (String) -> Unit,
    onTyping: (Boolean) -> Unit,
    onSetReply: (MessageEntity?) -> Unit,
    // React to a message. Takes the whole entity so a still-pending own message (msgid == null) can
    // be queued rather than silently dropped; the VM defers the send until the msgid lands.
    onReact: (MessageEntity, String) -> Unit,
    onRetry: (MessageEntity) -> Unit,
    loadPreview: suspend (String) -> io.github.trevarj.motd.data.repo.LinkPreview?,
    cachedPreview: (String) -> io.github.trevarj.motd.data.repo.CachedLinkPreview? = { null },
    loadAudioMetadata: suspend (String, Long?) -> AudioMetadata? = { _, _ -> null },
    cachedAudioMetadata: (String) -> CachedAudioMetadata? = { null },
    audioPlaybackState: AudioPlaybackState = AudioPlaybackState(),
    audioWaveforms: Map<String, AudioWaveform> = emptyMap(),
    audioCacheStatuses: Map<String, AudioCacheStatus> = emptyMap(),
    onAudioToggle: (AudioPlaybackRequest) -> Unit = {},
    onAudioCacheInspect: (AudioAttachment) -> Unit = {},
    onAudioSeek: (AudioAttachment, Long) -> Unit = { _, _ -> },
    onAudioSpeed: (AudioAttachment, Float) -> Unit = { _, _ -> },
    onAudioToggleActive: () -> Unit = {},
    onAudioCancelLoading: () -> Unit = {},
    onAudioRetry: () -> Unit = {},
    onAudioDismiss: () -> Unit = {},
    onOpenAudioOrigin: (AudioPlaybackOrigin) -> Unit = {},
    voiceState: VoiceMessageUiState = VoiceMessageUiState(),
    voiceEnabled: Boolean = true,
    onVoiceHoldStart: () -> Unit = {},
    onVoiceHoldStop: () -> Unit = {},
    onVoiceHoldCancel: () -> Unit = {},
    onVoiceLock: () -> Unit = {},
    onVoiceDelete: () -> Unit = {},
    onVoiceSend: () -> Unit = {},
    onVoiceToggleEncryption: () -> Unit = {},
    onVoiceDestinationSelected: (io.github.trevarj.motd.attachment.PasteBackendConfig?) -> Unit = {},
    onVoiceErrorDismissed: () -> Unit = {},
    onVoiceNoticeDismissed: () -> Unit = {},
    reactionChips: (String) -> List<io.github.trevarj.motd.ui.components.ReactionChip> = { emptyList() },
    replyPreview: (String) -> StateFlow<io.github.trevarj.motd.ui.components.ReplyPreviewData?> = {
        kotlinx.coroutines.flow.MutableStateFlow(null)
    },
    onReplyPreviewClick: (String) -> Unit = {},
    dccTransfer: (MessageEntity) -> StateFlow<DccTransferEntity?> = {
        kotlinx.coroutines.flow.MutableStateFlow(null)
    },
    onAcceptDccTransfer: (Long, Uri, Boolean) -> Unit = { _, _, _ -> },
    onRejectDccTransfer: (Long) -> Unit = {},
    onRemoveDccTransfer: (Long) -> Unit = {},
    onSendDccFile: (Uri) -> Unit = {},
    memberNicks: List<String> = emptyList(),
    knownNicks: Set<String> = emptySet(),
    identityRules: IrcIdentityRules = IrcIdentityRules(),
    friends: Set<String> = emptySet(),
    fools: Set<String> = emptySet(),
    foolsMode: FoolsMode = FoolsMode.COLLAPSE,
    hiddenFoolsRevealed: Boolean = false,
    onHiddenFoolsRevealedChange: (Boolean) -> Unit = {},
    showJoinPartQuit: Boolean = true,
    chatWallpaper: io.github.trevarj.motd.data.prefs.WallpaperSelection = io.github.trevarj.motd.data.prefs.WallpaperSelection(),
    conversationFontScalePercent: Int = io.github.trevarj.motd.data.prefs.DEFAULT_FONT_SCALE_PERCENT,
    showComposerEmoji: Boolean = true,
    visibleReplyPrefix: Boolean = false,
    showImages: Boolean = true,
    showLinkPreviews: Boolean = true,
    readMarkerSnapshot: io.github.trevarj.motd.data.db.TimelineAnchor? = null,
    // Live buffer read marker (advances with markRead); drives the FAB unread badge count.
    readMarkerLive: io.github.trevarj.motd.data.db.TimelineAnchor? = null,
    rawNewestAnchor: io.github.trevarj.motd.data.db.TimelineAnchor? = null,
    onMarkRead: (io.github.trevarj.motd.data.db.TimelineAnchor) -> Unit = {},
    onDelete: (MessageEntity) -> Unit = {},
    onAcceptInvite: (Long) -> Unit = {},
    onDismissInvite: (Long) -> Unit = {},
    // Re-join the current channel from the parted banner.
    onRejoin: () -> Unit = {},
    consumePrefill: () -> String? = { null },
    composerDraft: ComposerDraftState = ComposerDraftState(),
    onDraftChanged: (String) -> Unit = {},
    // Immediate nick-sheet mention request. The nonce permits mentioning the same nick repeatedly.
    mentionPrefill: Pair<Long, String>? = null,
    jumpTarget: ChatPositionTarget? = null,
    initialTarget: ChatPositionTarget? = null,
    entryPositionInitiallySettled: Boolean = false,
    entryMessageUnavailable: Boolean = false,
    onJumpHandled: (Long) -> Unit = {},
    onInitialPositionHandled: () -> Unit = {},
    onInitialPositionUnresolved: () -> Unit = {},
    onScrollPositionChanged: (ChatScrollPosition) -> Unit = {},
    onClearScrollPosition: () -> Unit = {},
    onVisibleMsgidsChanged: (List<String>) -> Unit = {},
    onNeedMembers: () -> Unit = {},
    onJumpUnresolved: (Long) -> Unit = {},
    onReresolveJump: (Long) -> Unit = {},
    onReresolveInitial: (ChatPositionTarget) -> Unit = {},
    // Round 5 (plans/16 §5.6/§5.8): SERVER-buffer raw-send + nick sheet plumbing.
    isServerBuffer: Boolean = false,
    onSenderClick: (String) -> Unit = {},
    uiEvent: QueuedChatUiEvent? = null,
    onUiEventAcknowledged: (Long) -> Unit = {},
    onRetryReplyJump: (ReplyJumpRequest) -> Unit = {},
    historyResyncState: HistoryResyncState = HistoryResyncState.Idle,
    historySyncStatus: HistorySyncStatus = HistorySyncStatus.Idle,
    historyAvailability: HistoryAvailability = HistoryAvailability.NegotiatingOrOffline,
    onRefreshHistory: (HistoryRefreshRange) -> Unit = {},
    onCancelHistoryRefresh: () -> Unit = {},
    onHistoryResyncShown: () -> Unit = {},
    countUnreadBelowViewport: suspend (Int, io.github.trevarj.motd.data.db.TimelineAnchor) -> Int = { _, _ -> 0 },
    nearestUnreadMentionBelow: suspend (Int, io.github.trevarj.motd.data.db.TimelineAnchor) -> Int? = { _, _ -> null },
    conversationLayout: ConversationLayoutState = ConversationLayoutState(),
    onConversationLayoutSelected: (io.github.trevarj.motd.data.prefs.LayoutDensity?) -> Unit = {},
) {
    val listState = rememberLazyListState()
    val autoFollow = remember { AutoFollowTracker(items.itemCount) }
    var liveEntryIds by remember(state.buffer?.id) { mutableStateOf(emptySet<Long>()) }
    val visibilityPolicy = remember(
        showJoinPartQuit,
        fools,
        foolsMode,
        hiddenFoolsRevealed,
        identityRules,
    ) {
        MessageVisibilityPolicy(
            MessageVisibilitySpec(
                showJoinPartQuit,
                fools,
                foolsMode,
                revealHiddenFools = hiddenFoolsRevealed,
            ),
            identityRules,
        )
    }
    val historyUiState = chatHistoryUiState(
        bufferType = state.buffer?.type,
        connectionState = state.connState,
        availability = historyAvailability,
        append = items.loadState.append,
        historyComplete = state.buffer?.historyComplete == true,
    )
    val readyRetryGate = remember(items) { HistoryReadyRetryGate() }
    LaunchedEffect(historyAvailability, items.loadState.append) {
        if (readyRetryGate.update(historyAvailability, items.loadState.append)) {
            items.retry()
        }
    }
    val traceBufferId = state.buffer?.id
    val traceSessionId = remember(traceBufferId) {
        traceBufferId?.let { AutoFollowTrace.nextSessionId() }
    }
    val scope = rememberCoroutineScope()
    // Expanded fool rows (plans/13 §2.4): keyed by MessageEntity.id, expand-only for the session.
    // Ephemeral by design (lost on config change; accepted per plans/13 Risks #6).
    var expandedFools by remember { mutableStateOf(setOf<Long>()) }
    val clipboard: Clipboard = LocalClipboard.current
    val ctx = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val historySnackbarHostState = remember { SnackbarHostState() }
    var pendingDccAccept by remember { mutableStateOf<PendingDccAccept?>(null) }
    val dccDestinationPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val pending = pendingDccAccept
        pendingDccAccept = null
        if (uri != null && pending != null) {
            onAcceptDccTransfer(pending.transferId, uri, pending.allowPrivateEndpoint)
        }
    }
    LaunchedEffect(voiceState.notice) {
        val notice = voiceState.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(notice)
        onVoiceNoticeDismissed()
    }
    // The ViewModel owns the durable value; this local state only retains cursor/selection details.
    var composerText by remember(traceBufferId) {
        mutableStateOf(TextFieldValue(""))
    }
    var attachmentSheetOpen by rememberSaveable { mutableStateOf(false) }
    var uploadCurrentDraftDirectly by rememberSaveable { mutableStateOf(false) }
    var longDraftPrompt by rememberSaveable { mutableStateOf(false) }
    var overflowOpen by rememberSaveable { mutableStateOf(false) }
    var conversationLayoutSheetOpen by rememberSaveable { mutableStateOf(false) }
    var historyRefreshSheetOpen by rememberSaveable { mutableStateOf(false) }
    var highlightMsgid by rememberSaveable { mutableStateOf<String?>(null) }
    // Global fool expand/collapse toggle (plans/13 §2.4): when true every collapsed fool row in the
    // buffer renders expanded; per-row toggles still override individually via [expandedFools] and
    // [collapsedFools]. Ephemeral per composition, like expandedFools.
    var expandAllFools by remember { mutableStateOf(false) }
    // Rows the user explicitly re-collapsed while expand-all is on (so a global expand is still
    // individually reversible). Cleared whenever expand-all is toggled off.
    var collapsedFools by remember { mutableStateOf(setOf<Long>()) }

    LaunchedEffect(traceBufferId) {
        if (traceBufferId == null) return@LaunchedEffect
        withFrameNanos {
            AutoFollowTrace.record("first_frame", traceBufferId, traceSessionId) {
                "item_count=${items.itemCount}"
            }
            Trace.beginSection("motd chat first frame")
            try {
                // Instant-like marker for Perfetto/gfx correlation without spanning suspension.
            } finally {
                Trace.endSection()
            }
        }
    }

    // Entry position is resolved once after refresh. Until then do not expose a transient FAB or
    // advance read state from a default index-0 layout.
    var initialPositionSettled by remember(entryPositionInitiallySettled) {
        mutableStateOf(entryPositionInitiallySettled)
    }
    // The first Paging emission after entry settlement reflects data loaded for the target, not a
    // live arrival. Consume it without auto-follow so an unread target remains on screen.
    var suppressNextAutoFollow by remember { mutableStateOf(!entryPositionInitiallySettled) }

    var prefillConsumed by remember(traceBufferId) { mutableStateOf(false) }

    // Apply hydration/accepted-send clears without re-saving the same value from the screen.
    LaunchedEffect(traceBufferId, composerDraft.hydrated, composerDraft.revision) {
        if (traceBufferId == null || !composerDraft.hydrated) return@LaunchedEffect
        if (composerText.text != composerDraft.text) {
            composerText = TextFieldValue(
                composerDraft.text,
                TextRange(composerDraft.text.length),
            )
        }
        if (!prefillConsumed) {
            prefillConsumed = true
            consumePrefill()?.let { prefill ->
                composerText = appendPrefill(composerText, prefill)
                onDraftChanged(composerText.text)
            }
        }
    }
    LaunchedEffect(mentionPrefill) {
        mentionPrefill?.second?.let {
            composerText = appendPrefill(composerText, it)
            onDraftChanged(composerText.text)
        }
    }
    val latestComposerText by rememberUpdatedState(composerText)
    val latestBufferType by rememberUpdatedState(state.buffer?.type)
    val latestVisibleReplyPrefix by rememberUpdatedState(visibleReplyPrefix)
    val timelineReply = remember(onSetReply, onDraftChanged) {
        { target: MessageEntity ->
            onSetReply(target)
            composerText = composerTextForReply(
                value = latestComposerText,
                sender = target.sender,
                bufferType = latestBufferType,
                visibleReplyPrefix = latestVisibleReplyPrefix,
            )
            onDraftChanged(composerText.text)
        }
    }

    val jumpNotLoaded = stringResource(R.string.chat_jump_not_loaded)
    // Only an explicit message destination reports failure; normal entry positioning is silent.
    LaunchedEffect(entryMessageUnavailable) {
        if (shouldPresentUnresolvedEntrySnackbar(entryMessageUnavailable)) {
            snackbarHostState.showSnackbar(jumpNotLoaded)
        }
    }

    val eventText = uiEvent?.value?.let { event ->
        when (event) {
            ChatUiEvent.InvalidCommand -> stringResource(R.string.chat_server_invalid_command)
            ChatUiEvent.ReactionBlocked -> stringResource(R.string.chat_reaction_blocked)
            ChatUiEvent.ReactionTargetUnavailable -> stringResource(R.string.chat_react_failed)
            ChatUiEvent.ReactionSendFailed -> stringResource(R.string.chat_reaction_send_failed)
            ChatUiEvent.SendRejected -> stringResource(R.string.chat_send_rejected)
            ChatUiEvent.NotInChannel -> stringResource(R.string.chat_not_in_channel)
            ChatUiEvent.ConversationLayoutWriteFailed -> stringResource(R.string.chat_layout_write_failed)
            ChatUiEvent.HistoryOffline -> stringResource(R.string.chat_history_offline)
            is ChatUiEvent.HistoryUpdated -> pluralStringResource(
                R.plurals.chat_history_updated,
                event.inserted,
                event.inserted,
            )
            ChatUiEvent.HistoryUpToDate -> stringResource(R.string.chat_history_up_to_date)
            ChatUiEvent.HistoryUnsupported -> stringResource(R.string.chat_history_unsupported)
            ChatUiEvent.HistoryFailed -> stringResource(R.string.chat_history_failed)
            is ChatUiEvent.HistoryIncomplete -> pluralStringResource(
                R.plurals.chat_history_resync_incomplete,
                event.inserted,
                event.inserted,
            )
            is ChatUiEvent.HistoryCapped -> pluralStringResource(
                R.plurals.chat_history_resync_capped,
                event.inserted,
                event.inserted,
                event.limit,
            )
            is ChatUiEvent.ReplyJumpUnavailable -> jumpNotLoaded
        }
    }
    val retryLabel = stringResource(R.string.chat_retry)
    LaunchedEffect(uiEvent?.id) {
        val pending = uiEvent ?: return@LaunchedEffect
        val text = eventText ?: return@LaunchedEffect
        val actionLabel = retryLabel.takeIf { pending.value.hasRetryAction() }
        val result = if (pending.value.isHistoryRefreshNotice()) {
            historySnackbarHostState.showSnackbar(
                message = text,
                actionLabel = actionLabel,
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
        } else {
            snackbarHostState.showSnackbar(
                message = text,
                actionLabel = actionLabel,
            )
        }
        handleChatUiEventResult(
            event = pending,
            actionPerformed = result == SnackbarResult.ActionPerformed,
            retryReplyJump = onRetryReplyJump,
            retryMissingHistory = { onRefreshHistory(HistoryRefreshRange.MISSING) },
            acknowledge = onUiEventAcknowledged,
        )
    }

    suspend fun materializeTarget(target: ChatPositionTarget, scroll: Boolean): MessageEntity? {
        // itemCount is used only to establish that the position is addressable. A non-null peek is
        // the sole proof that the target placeholder has materialized.
        val pageReady = withTimeoutOrNull(TARGET_MATERIALIZATION_TIMEOUT_MS) {
            snapshotFlow { Triple(items.loadState.refresh, items.loadState.append, items.itemCount) }
                .first { (refresh, append, count) ->
                    refresh is LoadState.NotLoading &&
                        initialPagingPage(count, append) != InitialPagingPage.Pending
                }
        } != null
        if (!pageReady) return null
        if (target.index !in 0 until items.itemCount) return null
        return requestAndAwaitTarget(
            index = target.index,
            request = { index ->
                val count = items.itemCount
                if (index !in 0 until count) {
                    false
                } else {
                    try {
                        if (scroll) listState.scrollToItem(index, target.offset)
                        // This is the only item access: it sends Paging a load hint for the exact target.
                        items[index]
                        true
                    } catch (_: IndexOutOfBoundsException) {
                        // A new Paging generation replaced the snapshot between the bound and access.
                        false
                    }
                }
            },
            snapshots = snapshotFlow {
                targetMaterialization(items, target.index)
            },
        )
    }

    // Deep jumps request one resolved placeholder, then validate both of its exact identities.
    LaunchedEffect(jumpTarget) {
        val j = jumpTarget ?: return@LaunchedEffect
        AutoFollowTrace.record("deep_jump_start", traceBufferId, traceSessionId) {
            "target_index=${j.index} item_count=${items.itemCount}"
        }
        val targetRow = try {
            materializeTarget(j, scroll = true)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            null
        }
        if (targetRow == null) {
            if (j.expectedEventId != null || j.expectedMsgid != null) onReresolveJump(j.requestToken)
            else onJumpUnresolved(j.requestToken)
        } else if (!positionTargetMatches(j, targetRow)) {
            onReresolveJump(j.requestToken)
        } else {
            AutoFollowTrace.record("deep_jump_settled", traceBufferId, traceSessionId) {
                "target_index=${j.index} item_count=${items.itemCount}"
            }
            if (visibilityPolicy.isFool(targetRow)) expandedFools += targetRow.id
            highlightMsgid = j.highlightMsgid
            initialPositionSettled = true
            suppressNextAutoFollow = true
            onJumpHandled(j.requestToken)
        }
    }

    // Normal entry shares the deep-link paging mechanics but has no highlight. It is separate so
    // a deep link always wins and so a completed normal entry cannot be replayed on recomposition.
    LaunchedEffect(initialTarget) {
        val target = initialTarget ?: return@LaunchedEffect
        AutoFollowTrace.record("initial_position_start", traceBufferId, traceSessionId) {
            "target_index=${target.index} target_offset=${target.offset} " +
                "saved=${target.fromSavedPosition} item_count=${items.itemCount}"
        }
        val pageReady = withTimeoutOrNull(TARGET_MATERIALIZATION_TIMEOUT_MS) {
            snapshotFlow { Triple(items.loadState.refresh, items.loadState.append, items.itemCount) }
                .first { (refresh, append, count) ->
                    refresh is LoadState.NotLoading &&
                        initialPagingPage(count, append) != InitialPagingPage.Pending
                }
        } != null
        if (!pageReady) {
            onInitialPositionUnresolved()
            return@LaunchedEffect
        }
        val currentlyAtBottom = listState.firstVisibleItemIndex == 0 &&
            listState.firstVisibleItemScrollOffset <= AUTOSCROLL_BOTTOM_TOLERANCE_PX
        val terminalEmpty = target.index == 0 && target.expectedEventId == null &&
            target.expectedMsgid == null &&
            initialPagingPage(items.itemCount, items.loadState.append) == InitialPagingPage.TerminalEmpty
        val targetRow = if (terminalEmpty) {
            null
        } else if (target.placeAtTop) {
            // Open-at-first-unread: load the first-unread row OFF-SCREEN (the viewport stays at
            // newest, so no read history flashes), then snap the viewport so the first unread tops
            // the window with the remaining unread continuing below it. The snap runs before
            // initialPositionSettled is set, so the scroll-state collector (gated on settlement)
            // cannot misclassify it as a user drag.
            val row = try {
                materializeTarget(target, scroll = false)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                null
            }
            if (row != null) {
                val rowsFit = listState.layoutInfo.visibleItemsInfo.size
                if (rowsFit >= 1) {
                    listState.scrollToItem(firstUnreadTopAnchorIndex(target.index, rowsFit))
                }
            }
            row
        } else {
            try {
                materializeTarget(
                    target,
                    scroll = shouldScrollToInitialTarget(target, currentlyAtBottom),
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                null
            }
        }
        if (terminalEmpty) {
            initialPositionSettled = true
            suppressNextAutoFollow = true
            onInitialPositionHandled()
        } else if (targetRow != null && positionTargetMatches(target, targetRow)) {
            AutoFollowTrace.record("initial_position_settled", traceBufferId, traceSessionId) {
                "target_index=${target.index} index=${listState.firstVisibleItemIndex} " +
                    "offset=${listState.firstVisibleItemScrollOffset} at_bottom=$currentlyAtBottom " +
                    "place_at_top=${target.placeAtTop}"
            }
            initialPositionSettled = true
            suppressNextAutoFollow = true
            onInitialPositionHandled()
        } else if (targetRow != null) {
            onReresolveInitial(target)
        } else if (target.expectedEventId != null || target.expectedMsgid != null) {
            onReresolveInitial(target)
        } else {
            AutoFollowTrace.record("initial_position_unresolved", traceBufferId, traceSessionId) {
                "target_index=${target.index} item_count=${items.itemCount} " +
                    "append=${loadStateName(items.loadState.append)}"
            }
            onInitialPositionUnresolved()
        }
    }

    // Clear the highlight after the pulse settles (~1.6s).
    LaunchedEffect(highlightMsgid) {
        if (highlightMsgid != null) {
            kotlinx.coroutines.delay(1_600)
            highlightMsgid = null
        }
    }

    fun saveCurrentScrollPosition() {
        if (!initialPositionSettled) return
        val index = listState.firstVisibleItemIndex
        if (isAtEffectiveBottom(
                firstVisibleIndex = index,
                firstVisibleOffset = listState.firstVisibleItemScrollOffset,
                itemCount = items.itemCount,
                peek = items::peek,
                policy = visibilityPolicy,
            )
        ) {
            onClearScrollPosition()
            return
        }
        // Paging can replace the outgoing buffer's snapshot with the incoming empty QUERY snapshot
        // between itemCount/index reads and onDispose. Treat that transition as no anchor to save;
        // never index the stale snapshot (the previous direct peek crashed DM navigation).
        val (anchorIndex, row) = nearestAnchorRow(
            firstVisibleIndex = index,
            itemCount = items.itemCount,
            peek = items::peek,
            policy = visibilityPolicy,
        ) ?: run {
            onClearScrollPosition()
            return
        }
        AutoFollowTrace.record("position_saved", traceBufferId, traceSessionId) {
            "index=$index anchor_index=$anchorIndex offset=${listState.firstVisibleItemScrollOffset} " +
                "row=${row.id} at_bottom=false following=${autoFollow.following}"
        }
        onScrollPositionChanged(
            ChatScrollPosition(
                index = anchorIndex,
                offset = listState.firstVisibleItemScrollOffset.takeIf { anchorIndex == index } ?: 0,
                msgid = row.msgid,
                serverTime = row.serverTime,
                rowId = row.id,
            ),
        )
    }

    // The previous collector allocated and wrote to the position cache for nearly every pixel of a
    // fling. We only need the final anchor: persist when scrolling settles, plus once on disposal so
    // a back gesture during an active fling still retains the current location.
    LaunchedEffect(initialPositionSettled, listState, visibilityPolicy) {
        if (!initialPositionSettled) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling -> if (!scrolling) saveCurrentScrollPosition() }
    }
    DisposableEffect(initialPositionSettled, listState, visibilityPolicy) {
        onDispose { saveCurrentScrollPosition() }
    }

    LaunchedEffect(initialPositionSettled, listState) {
        snapshotFlow {
            // While scrolling, deliberately stop observing itemCount/index. snapshotFlow then
            // unregisters those hot reads until the idle edge, preventing a DB query restart for
            // every row crossed by a fling while keeping the last reaction map on screen.
            if (!initialPositionSettled || listState.isScrollInProgress) {
                null
            } else {
                items.itemCount to listState.firstVisibleItemIndex
            }
        }
            .distinctUntilChanged()
            .collect { idleWindow ->
                if (idleWindow != null) {
                    onVisibleMsgidsChanged(visibleReactionMsgids(items, listState))
                }
            }
    }

    // Long-press action sheet target.
    var sheetTarget by remember { mutableStateOf<MessageEntity?>(null) }
    val sheetState = rememberModalBottomSheetState()
    val historyRefreshSheetState = rememberModalBottomSheetState()

    // Raw ignored tails do not make the user leave the meaningful bottom of the conversation.
    val atBottom by remember(listState, items, visibilityPolicy) {
        derivedStateOf {
            isAtEffectiveBottom(
                firstVisibleIndex = listState.firstVisibleItemIndex,
                firstVisibleOffset = listState.firstVisibleItemScrollOffset,
                itemCount = items.itemCount,
                peek = items::peek,
                policy = visibilityPolicy,
            )
        }
    }

    // Count nested programmatic scrolls rather than using a Boolean: an incoming pin may supersede
    // an explicit animation, and the cancelled animation must not briefly masquerade as a user drag.
    var programmaticScrolls by remember { mutableIntStateOf(0) }
    val autoScrolling = programmaticScrolls > 0
    suspend fun scrollToNewest(animate: Boolean, reason: String) {
        AutoFollowTrace.record("scroll_start", traceBufferId, traceSessionId) {
            "reason=$reason animate=$animate index=${listState.firstVisibleItemIndex} " +
                "offset=${listState.firstVisibleItemScrollOffset} following=${autoFollow.following}"
        }
        autoFollow.requestFollow()
        programmaticScrolls++
        try {
            if (animate) listState.animateScrollToItem(0) else listState.scrollToItem(0)
        } finally {
            programmaticScrolls--
            AutoFollowTrace.record("scroll_end", traceBufferId, traceSessionId) {
                "reason=$reason index=${listState.firstVisibleItemIndex} " +
                    "offset=${listState.firstVisibleItemScrollOffset} following=${autoFollow.following}"
            }
        }
    }

    // Jump to a mid-list row (e.g. the nearest unread @mention) without arming auto-follow: a live
    // arrival must not yank the viewport away from the mention the user just navigated to.
    suspend fun scrollToIndex(index: Int, animate: Boolean, reason: String) {
        AutoFollowTrace.record("scroll_start", traceBufferId, traceSessionId) {
            "reason=$reason animate=$animate target=$index index=${listState.firstVisibleItemIndex} " +
                "offset=${listState.firstVisibleItemScrollOffset} following=${autoFollow.following}"
        }
        programmaticScrolls++
        try {
            if (animate) listState.animateScrollToItem(index) else listState.scrollToItem(index)
        } finally {
            programmaticScrolls--
            AutoFollowTrace.record("scroll_end", traceBufferId, traceSessionId) {
                "reason=$reason index=${listState.firstVisibleItemIndex} " +
                    "offset=${listState.firstVisibleItemScrollOffset} following=${autoFollow.following}"
            }
        }
    }

    // Record only actual scroll-state/programmatic edges. An index/offset change caused by a Paging
    // prepend does not emit here, so it cannot be mistaken for the user leaving the bottom.
    LaunchedEffect(listState, initialPositionSettled) {
        if (!initialPositionSettled) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress to (programmaticScrolls > 0) }
            .distinctUntilChanged()
            .collect { (scrolling, programmatic) ->
                val before = autoFollow.following
                autoFollow.onScrollStateChanged(scrolling, programmatic, atBottom)
                AutoFollowTrace.record("scroll_intent", traceBufferId, traceSessionId) {
                    "scrolling=$scrolling programmatic=$programmatic at_bottom=$atBottom " +
                        "following_before=$before following_after=${autoFollow.following} " +
                        "index=${listState.firstVisibleItemIndex} offset=${listState.firstVisibleItemScrollOffset}"
                }
                if (!scrolling) {
                    AutoFollowTrace.record("viewport_settled", traceBufferId, traceSessionId) {
                        "at_bottom=$atBottom following=${autoFollow.following} " +
                            "index=${listState.firstVisibleItemIndex} offset=${listState.firstVisibleItemScrollOffset}"
                    }
                }
            }
    }

    // Keep one collector alive for the whole settled entry. Keying a LaunchedEffect directly on
    // itemCount cancelled an in-flight animateScrollToItem when the next message arrived; because
    // that animation also set isScrollInProgress, the replacement effect believed the user had
    // scrolled away and permanently stopped following a burst. Live arrivals snap to index zero;
    // animation is reserved for explicit send/FAB actions.
    LaunchedEffect(items, initialPositionSettled, visibilityPolicy) {
        if (!initialPositionSettled) return@LaunchedEffect
        snapshotFlow {
            items.itemCount to newestEffectiveMessageId(items.itemCount, items::peek, visibilityPolicy)
        }
            .distinctUntilChanged()
            .collect { (newCount, newestEffectiveId) ->
                val oldCount = autoFollow.presentedItemCount
                val followingBefore = autoFollow.following
                if (suppressNextAutoFollow) {
                    autoFollow.reset(newCount, atBottom, newestEffectiveId)
                    liveEntryIds = emptySet()
                    suppressNextAutoFollow = false
                    AutoFollowTrace.record("paging_initial", traceBufferId, traceSessionId) {
                        "old_count=$oldCount new_count=$newCount at_bottom=$atBottom " +
                            "following=${autoFollow.following} refresh=${loadStateName(items.loadState.refresh)} " +
                            "append=${loadStateName(items.loadState.append)}"
                    }
                } else {
                    val change = autoFollow.onTimelineChangedWithEntry(newCount, newestEffectiveId)
                    val animatedEntryId = change.liveEntryId?.takeUnless {
                        extendsSystemRun(it, newCount, items::peek)
                    }
                    liveEntryIds = appendLiveEntryId(liveEntryIds, animatedEntryId)
                    val newest = if (newCount > 0) items.peek(0) else null
                    AutoFollowTrace.record("follow_decision", traceBufferId, traceSessionId) {
                        "old_count=$oldCount new_count=$newCount at_bottom=$atBottom " +
                            "following_before=$followingBefore following_after=${autoFollow.following} " +
                            "follow=${change.shouldFollow} live_entry=${change.liveEntryId ?: -1} " +
                            "newest_row=${newest?.id ?: -1} " +
                            "newest_kind=${newest?.kind?.name ?: "NONE"} " +
                            "refresh=${loadStateName(items.loadState.refresh)} " +
                            "append=${loadStateName(items.loadState.append)}"
                    }
                    if (change.shouldFollow) {
                        // Apply the new index-zero anchor to the same remeasure that presents the
                        // Paging update. A suspending scroll can expose the old anchor for one frame.
                        autoFollow.requestFollow()
                        listState.requestScrollToItem(0)
                    }
                }
            }
    }
    val buffer = state.buffer
    val titleTarget = chatTitleTarget(buffer?.type)
    val titleClickLabel = when (titleTarget) {
        ChatTitleTarget.CHANNEL_INFO -> stringResource(R.string.chat_open_channel_info)
        ChatTitleTarget.NICK_DETAILS -> stringResource(R.string.chat_open_nick_details)
        ChatTitleTarget.NONE -> null
    }
    // Mark read on new-message-while-at-bottom only (plans/07/15 #2): syncing while scrolled up
    // reading history would clear unread on other clients and destroy the local unread UX.
    LaunchedEffect(rawNewestAnchor, atBottom, initialPositionSettled) {
        val newest = rawNewestAnchor ?: return@LaunchedEffect
        if (initialPositionSettled && atBottom && newest.serverTime > 0) {
            AutoFollowTrace.record("viewport_markread", traceBufferId, traceSessionId) {
                "marker=${newest.serverTime}:${newest.eventId} item_count=${items.itemCount}"
            }
            onMarkRead(newest)
        }
    }
    val recentSpeakers = remember(items.itemCount) {
        // Exclude system-event senders and self so recency ranking reflects real conversation
        // partners (plans/15 #30). Only the newest rows matter for recency, so cap the scan (the list
        // is reverse-laid-out, so index 0.. are the newest) to stay cheap on large loaded windows.
        (0 until minOf(items.itemCount, 60))
            .mapNotNull { items.peek(it) }
            .filterNot { isSystemKind(it.kind) || it.isSelf }
            .map { it.sender }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                title = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .then(
                                if (titleClickLabel != null) {
                                    Modifier.clickable(onClickLabel = titleClickLabel) {
                                        buffer?.let { onOpenChannelInfo(it.id) }
                                    }
                                } else {
                                    Modifier
                                },
                            )
                            .testTag("chat_title"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(
                            name = buffer?.displayName ?: "",
                            size = MotdSizes.headerAvatar,
                            isChannel = buffer?.type == BufferType.CHANNEL,
                            networkId = buffer?.networkId,
                        )
                        Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(
                                text = buffer?.displayName ?: "",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            AnimatedContent(
                                targetState = chatSubtitleModel(state, ctx),
                                transitionSpec = {
                                    fadeIn(MotdMotion.microFadeIn) togetherWith
                                        fadeOut(MotdMotion.microFadeOut)
                                },
                                label = "chat_subtitle",
                            ) { subtitle ->
                                when (subtitle) {
                                    is ChatSubtitleModel.Text -> {
                                        Text(
                                            text = subtitle.value,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    null -> Unit
                                }
                            }
                        }
                        if (titleTarget != ChatTitleTarget.NONE) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.chat_back),
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { buffer?.let { onOpenSearch(it.id) } }) {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = stringResource(R.string.chat_search),
                        )
                    }
                    IconButton(
                        onClick = { overflowOpen = true },
                        modifier = Modifier.testTag("chat_overflow"),
                    ) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                    }
                    DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                        DropdownMenuItem(
                            modifier = Modifier.testTag("chat_layout_menu"),
                            text = {
                                Column {
                                    Text(stringResource(R.string.chat_layout_title))
                                    Text(
                                        stringResource(
                                            R.string.chat_layout_overflow_summary,
                                            densityLabel(conversationLayout.effective),
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            },
                            onClick = {
                                overflowOpen = false
                                conversationLayoutSheetOpen = true
                            },
                        )
                        if (fools.isNotEmpty()) {
                            val foolsShown = if (foolsMode == FoolsMode.HIDE) {
                                hiddenFoolsRevealed
                            } else {
                                expandAllFools
                            }
                            DropdownMenuItem(
                                modifier = Modifier.testTag("chat_toggle_fools_visibility"),
                                text = {
                                    Text(
                                        stringResource(
                                            if (foolsShown) R.string.chat_fool_collapse_all
                                            else R.string.chat_fool_expand_all,
                                        ),
                                    )
                                },
                                onClick = {
                                    overflowOpen = false
                                    if (foolsMode == FoolsMode.HIDE) {
                                        onHiddenFoolsRevealedChange(!hiddenFoolsRevealed)
                                    } else {
                                        expandAllFools = !expandAllFools
                                        expandedFools = emptySet()
                                        collapsedFools = emptySet()
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        if (foolsShown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = null,
                                    )
                                },
                            )
                        }
                        val running = historyResyncState as? HistoryResyncState.Running
                        val historyBusy = running != null ||
                            historyResyncState == HistoryResyncState.WaitingForCapability
                        if (historyBusy) {
                            DropdownMenuItem(
                                modifier = Modifier.testTag("chat_cancel_history_refresh"),
                                text = {
                                    Text(
                                        if (running?.limit != null) {
                                            stringResource(
                                                R.string.chat_history_refreshing_progress,
                                                running.fetched,
                                                running.limit,
                                            )
                                        } else {
                                            stringResource(R.string.chat_history_refreshing)
                                        },
                                    )
                                },
                                onClick = {
                                    overflowOpen = false
                                    onCancelHistoryRefresh()
                                },
                                leadingIcon = { CircularProgressIndicator(modifier = Modifier.size(24.dp)) },
                            )
                        } else {
                            DropdownMenuItem(
                                modifier = Modifier.testTag("chat_refresh_history"),
                                text = { Text(stringResource(R.string.chat_refresh_history)) },
                                onClick = {
                                    overflowOpen = false
                                    historyRefreshSheetOpen = true
                                },
                                leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        // TopAppBar owns the status-bar inset. The chat surface draws edge-to-edge horizontally,
        // while these consuming modifiers keep the composer above navigation and animated IME
        // insets without double-padding their overlap.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            val density = LocalDensity.current
            val imeContentHeightPx = (
                WindowInsets.ime.getBottom(density) -
                    WindowInsets.navigationBars.getBottom(density)
                ).coerceAtLeast(0)
            ConversationTypography(conversationFontScalePercent) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    // Subtle IRC-themed wallpaper layered UNDER the message list only (never over the
                    // composer). NONE renders the plain theme background; MessageList is untouched.
                    ChatWallpaperBackground(chatWallpaper, modifier = Modifier.matchParentSize())
                    CompositionLocalProvider(
                        LocalSpacing provides remember(conversationLayout.effective) {
                            spacingFor(conversationLayout.effective)
                        },
                    ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("chat_layout_effective_${conversationLayout.effective.name.lowercase()}"),
                    ) {
                    MessageList(
                        items = items,
                        listState = listState,
                        liveEntryIds = liveEntryIds,
                        onLiveEntryConsumed = { id ->
                            liveEntryIds = consumeLiveEntryId(liveEntryIds, id)
                        },
                        networkId = state.buffer?.networkId,
                        bufferId = state.buffer?.id,
                        conversationName = state.buffer?.displayName,
                        directMessage = state.buffer?.type == BufferType.QUERY,
                        // Frozen read-marker so the "New messages" divider stays put (plans/15 #2).
                        readMarkerTime = readMarkerSnapshot,
                        reactionChips = reactionChips,
                        replyPreview = replyPreview,
                        onReplyPreviewClick = onReplyPreviewClick,
                        onLongPress = { sheetTarget = it },
                        onReply = timelineReply,
                        onReact = onReact,
                        onImageClick = onOpenImage,
                        onRetry = onRetry,
                        canRetry = { message ->
                            state.buffer?.let { buffer ->
                                io.github.trevarj.motd.service.isGenericRetryEligible(buffer, message)
                            } == true
                        },
                        onDelete = onDelete,
                        onAcceptInvite = onAcceptInvite,
                        onDismissInvite = onDismissInvite,
                        dccTransfer = dccTransfer,
                        onAcceptDccTransfer = { transferId, filename, allowPrivate ->
                            pendingDccAccept = PendingDccAccept(transferId, allowPrivate)
                            dccDestinationPicker.launch(filename)
                        },
                        onRejectDccTransfer = onRejectDccTransfer,
                        onRemoveDccTransfer = onRemoveDccTransfer,
                        loadPreview = loadPreview,
                        richContentReady = initialPositionSettled,
                        showImages = showImages,
                        showLinkPreviews = showLinkPreviews,
                        cachedPreview = cachedPreview,
                        loadAudioMetadata = loadAudioMetadata,
                        cachedAudioMetadata = cachedAudioMetadata,
                        audioPlaybackState = audioPlaybackState,
                        audioWaveforms = audioWaveforms,
                        audioCacheStatuses = audioCacheStatuses,
                        onAudioToggle = onAudioToggle,
                        onAudioCacheInspect = onAudioCacheInspect,
                        onAudioSeek = onAudioSeek,
                        // Link-preview tap opens the URL in the system browser.
                        onOpenLink = { ctx.startActivity(Intent(Intent.ACTION_VIEW, it.toUri())) },
                        highlightMsgid = highlightMsgid,
                        knownNicks = knownNicks,
                        friends = friends,
                        fools = fools,
                        foolsMode = foolsMode,
                        identityRules = identityRules,
                        historyUiState = historyUiState,
                        onHistoryRetry = {
                            val retryResync = historyResyncState is HistoryResyncState.Failed ||
                                historyResyncState is HistoryResyncState.Incomplete ||
                                historyResyncState is HistoryResyncState.Capped
                            onHistoryResyncShown()
                            if (retryResync) onRefreshHistory(HistoryRefreshRange.MISSING)
                        },
                        // Effective expansion: global expand-all default, minus rows the user
                        // re-collapsed; otherwise only individually expanded rows show (bug #9).
                        foolExpanded = { id ->
                            if (expandAllFools) id !in collapsedFools else id in expandedFools
                        },
                        // Bidirectional per-row toggle, respecting the global default.
                        onToggleFool = { id ->
                            if (expandAllFools) {
                                collapsedFools =
                                    if (id in collapsedFools) collapsedFools - id else collapsedFools + id
                            } else {
                                expandedFools =
                                    if (id in expandedFools) expandedFools - id else expandedFools + id
                            }
                        },
                        onSenderClick = onSenderClick,
                    )
                    }
                    }

                    // Paging begins with a transient empty refresh before Room delivers its first
                    // page. Only show the empty state once APPEND proves the buffer is terminally
                    // empty; otherwise the large placeholder flashes during every chat entry.
                    if (items.loadState.refresh is LoadState.NotLoading &&
                        initialPagingPage(items.itemCount, items.loadState.append) ==
                        InitialPagingPage.TerminalEmpty &&
                        !historySyncStatus.isActive
                    ) {
                        io.github.trevarj.motd.ui.components.EmptyState(
                            icon = Icons.Outlined.Forum,
                            title = stringResource(R.string.chat_empty_title),
                            message = stringResource(R.string.chat_empty_message),
                        )
                    }

                    // Keep the hot firstVisibleItemIndex read inside the FAB subtree. Reading it in
                    // ChatContent made every row boundary re-run the entire Scaffold/list/composer.
                    ViewportScrollToBottomFab(
                        listState = listState,
                        readMarker = readMarkerLive,
                        visibilityPolicy = visibilityPolicy,
                        countUnreadBelowViewport = countUnreadBelowViewport,
                        nearestUnreadMentionBelow = nearestUnreadMentionBelow,
                        visible = initialPositionSettled && !atBottom && !autoScrolling,
                        onJumpMention = { index ->
                            scope.launch { scrollToIndex(index, animate = true, reason = "jump_mention_fab") }
                        },
                        onJumpNewest = { scope.launch { scrollToNewest(animate = true, reason = "jump_fab") } },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    )

                    val stagedVoicePlaybackId = voiceState.staged?.let { "voice:${it.file.toURI()}" }
                    TimelineTopOverlays(
                        audioPlayer = {
                            if (audioPlaybackState.activeId != stagedVoicePlaybackId) {
                                AudioMiniPlayer(
                                    state = audioPlaybackState,
                                    onToggle = onAudioToggleActive,
                                    onCancelLoading = onAudioCancelLoading,
                                    onRetry = onAudioRetry,
                                    onDismiss = onAudioDismiss,
                                    onSeek = { positionMs ->
                                        audioPlaybackState.attachment?.let { onAudioSeek(it, positionMs) }
                                    },
                                    onOpenOrigin = onOpenAudioOrigin,
                                    onSpeed = { speed ->
                                        audioPlaybackState.attachment?.let { onAudioSpeed(it, speed) }
                                    },
                                    includeNetwork =
                                        audioPlaybackState.origin?.networkId != state.buffer?.networkId,
                                )
                            }
                        },
                        historyIndicator = {
                            TimelineHistorySyncIndicator(
                                status = historySyncStatus,
                                timelineEmpty = items.itemCount == 0,
                                retryEnabled = state.connState is ConnectionState.Ready,
                                onRetry = { onRefreshHistory(HistoryRefreshRange.MISSING) },
                            )
                        },
                    )
                }

                val completions = remember(composerText, memberNicks, recentSpeakers) {
                    autocompleteFor(composerText, memberNicks, recentSpeakers, nickNormalizer)
                }
                val needsMemberCompletion = remember(composerText) {
                    composerNeedsMemberNicks(composerText)
                }
                LaunchedEffect(needsMemberCompletion) {
                    if (needsMemberCompletion) onNeedMembers()
                }
                // Debounce the SHOW so fast typing doesn't flash the suggestion panel on every
                // keystroke: only reveal completions after a brief pause. Hiding stays immediate
                // (an empty result clears the panel at once) so the panel never lingers stale.
                var showAutocomplete by remember { mutableStateOf(false) }
                LaunchedEffect(completions) {
                    if (completions.isEmpty()) {
                        showAutocomplete = false
                    } else {
                        kotlinx.coroutines.delay(AUTOCOMPLETE_SHOW_DEBOUNCE_MS)
                        showAutocomplete = true
                    }
                }
                VoiceComposerPanel(
                    state = voiceState,
                    playbackState = audioPlaybackState,
                    onDelete = onVoiceDelete,
                    onCancelRecording = onVoiceHoldCancel,
                    onSend = onVoiceSend,
                    onPreview = { attachment -> onAudioToggle(AudioPlaybackRequest(attachment, null)) },
                    onPreviewSeek = { attachment, positionMs -> onAudioSeek(attachment, positionMs) },
                    onToggleEncryption = onVoiceToggleEncryption,
                    onDestinationSelected = onVoiceDestinationSelected,
                    onErrorDismissed = onVoiceErrorDismissed,
                )
                if (state.parted) {
                    PartedChannelBanner(
                        channel = state.buffer?.displayName.orEmpty(),
                        onRejoin = onRejoin,
                    )
                }
                Composer(
                    value = composerText,
                    onValueChange = {
                        val wasBlank = composerText.text.isBlank()
                        composerText = it
                        onDraftChanged(it.text)
                        if (it.text.isNotBlank()) onTyping(true)
                        else if (!wasBlank) onTyping(false)
                    },
                    onSend = {
                        val text = composerText.text
                        if (text.isNotBlank()) {
                            if (isLongDraft(text)) {
                                AutoFollowTrace.record("long_draft_prompt_open", traceBufferId, traceSessionId)
                                longDraftPrompt = true
                            } else {
                                AutoFollowTrace.record("composer_submit", traceBufferId, traceSessionId) {
                                    "long_draft=false"
                                }
                                onSubmit(text)
                                scope.launch {
                                    scrollToNewest(animate = true, reason = "composer_send_action")
                                }
                            }
                        }
                    },
                    enabled = composerEnabled,
                    reply = state.replyTo?.let { ComposerReply(it.sender, it.text) },
                    onCancelReply = { onSetReply(null) },
                    // SERVER buffers send raw commands; hint that in the placeholder (plans/16 §5.6).
                    placeholder = if (isServerBuffer) {
                        stringResource(R.string.chat_server_composer_hint)
                    } else {
                        stringResource(R.string.chat_composer_placeholder)
                    },
                    showEmojiButton = showComposerEmoji,
                    onAttachment = { uploadCurrentDraftDirectly = false; attachmentSheetOpen = true },
                    voiceEnabled = voiceEnabled && voiceState.staged == null && composerText.text.isBlank(),
                    voiceRecording = voiceState.recording != null,
                    onVoiceHoldStart = onVoiceHoldStart,
                    onVoiceHoldStop = onVoiceHoldStop,
                    onVoiceHoldCancel = onVoiceHoldCancel,
                    onVoiceLock = onVoiceLock,
                    imeHeightPx = imeContentHeightPx,
                    autocomplete = if (showAutocomplete && completions.isNotEmpty()) {
                        {
                            AutocompletePanel(
                                candidates = completions.map { it.display },
                                isCommand = completions.firstOrNull()?.isCommand == true,
                                networkId = state.buffer?.networkId,
                                onPick = { picked ->
                                    composerText = applyPick(composerText, picked)
                                    onDraftChanged(composerText.text)
                                },
                            )
                        }
                    } else null,
                )
            }
            }
            SnackbarHost(
                hostState = historySnackbarHostState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("chat_history_notice"),
                snackbar = { notice ->
                    Snackbar(
                        action = notice.visuals.actionLabel?.let { actionLabel ->
                            {
                                TextButton(
                                    onClick = { notice.performAction() },
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.inversePrimary,
                                    ),
                                ) {
                                    Text(actionLabel)
                                }
                            }
                        },
                        dismissAction = {
                            IconButton(
                                onClick = { notice.dismiss() },
                                modifier = Modifier.testTag("chat_history_notice_dismiss"),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.action_dismiss),
                                )
                            }
                        },
                        content = { Text(notice.visuals.message) },
                    )
                },
            )
        }
    }

    AttachmentSheets(
        open = attachmentSheetOpen,
        currentDraft = composerText.text,
        networkId = state.buffer?.networkId,
        sojuFileHostAvailable = state.attachmentUploadAvailable,
        startWithCurrentDraft = uploadCurrentDraftDirectly,
        directFileTransferAvailable = state.buffer?.type == BufferType.QUERY &&
            state.connState is ConnectionState.Ready,
        onDismiss = { attachmentSheetOpen = false; uploadCurrentDraftDirectly = false },
        onInsertUrl = {
            composerText = io.github.trevarj.motd.ui.components.insertAtCursor(composerText, it)
            onDraftChanged(composerText.text)
        },
        onReplaceDraft = {
            composerText = TextFieldValue(it, androidx.compose.ui.text.TextRange(it.length))
            onDraftChanged(composerText.text)
        },
        onDirectFile = onSendDccFile,
    )
    if (historyRefreshSheetOpen) {
        HistoryRefreshSheet(
            sheetState = historyRefreshSheetState,
            onDismiss = { historyRefreshSheetOpen = false },
            onRange = {
                historyRefreshSheetOpen = false
                onRefreshHistory(it)
            },
        )
    }
    if (longDraftPrompt) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                AutoFollowTrace.record("long_draft_dismiss", traceBufferId, traceSessionId)
                longDraftPrompt = false
            },
            title = { Text("Long draft") },
            text = { Text("Upload the draft as a paste, or send it as ordinary IRC messages?") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = {
                AutoFollowTrace.record("long_draft_upload", traceBufferId, traceSessionId)
                longDraftPrompt = false
                uploadCurrentDraftDirectly = true
                attachmentSheetOpen = true
            }) { Text("Upload as paste") } },
            dismissButton = { Row {
                androidx.compose.material3.TextButton(onClick = {
                    AutoFollowTrace.record("long_draft_send_messages", traceBufferId, traceSessionId)
                    longDraftPrompt = false
                    onSubmit(composerText.text)
                    scope.launch { scrollToNewest(animate = true, reason = "long_draft_send") }
                }) { Text("Send as messages") }
                androidx.compose.material3.TextButton(onClick = {
                    AutoFollowTrace.record("long_draft_cancel", traceBufferId, traceSessionId)
                    longDraftPrompt = false
                }) { Text("Cancel") }
            } },
        )
    }

    sheetTarget?.let { target ->
        // Dismiss with the M3 hide animation, clearing the target only once it settles (plans/15 #31).
        val hideThen: (() -> Unit) -> Unit = { after ->
            scope.launch { sheetState.hide() }.invokeOnCompletion {
                sheetTarget = null
                after()
            }
        }
        MessageActionSheet(
            sheetState = sheetState,
            isServerBuffer = isServerBuffer,
            onDismiss = { sheetTarget = null },
            onReply = {
                hideThen {
                    onSetReply(target)
                    composerText = composerTextForReply(
                        value = composerText,
                        sender = target.sender,
                        bufferType = state.buffer?.type,
                        visibleReplyPrefix = visibleReplyPrefix,
                    )
                    onDraftChanged(composerText.text)
                }
            },
            // Pass the whole target: the VM queues the react when target.msgid is still null (own
            // pending message) instead of silently dropping it.
            onReact = { emoji -> hideThen { onReact(target, emoji) } },
            reactionEnabled = { emoji ->
                val capability = state.reactionCapability
                val mine = target.msgid?.let { msgid ->
                    reactionChips(msgid).firstOrNull { it.emoji == emoji }?.mine
                } == true
                capability != null && (if (mine) capability.canRemoveOwn else capability.canAdd)
            },
            onCopy = {
                hideThen {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", target.text)))
                    }
                }
            },
            onQuote = {
                // Append the quote to the existing draft with the cursor at the end (plans/15 #19).
                hideThen {
                    composerText = appendPrefill(composerText, "> ${target.text}\n")
                    onDraftChanged(composerText.text)
                }
            },
        )
    }

    if (conversationLayoutSheetOpen) {
        ConversationLayoutSheet(
            state = conversationLayout,
            onSelect = { override ->
                onConversationLayoutSelected(override)
                conversationLayoutSheetOpen = false
            },
            onDismiss = { conversationLayoutSheetOpen = false },
        )
    }
}

internal enum class ChatTitleTarget { CHANNEL_INFO, NICK_DETAILS, NONE }

/** The title bar destination is a property of the buffer, not its display-name prefix. */
internal fun chatTitleTarget(type: BufferType?): ChatTitleTarget = when (type) {
    BufferType.CHANNEL -> ChatTitleTarget.CHANNEL_INFO
    BufferType.QUERY -> ChatTitleTarget.NICK_DETAILS
    BufferType.SERVER, null -> ChatTitleTarget.NONE
}

internal data class PagingTargetGeneration(
    val itemCount: Int,
    val placeholdersBefore: Int,
    val placeholdersAfter: Int,
    val firstLoadedId: Long?,
    val lastLoadedId: Long?,
)

internal fun relevantTargetLoadState(
    index: Int,
    loadedStart: Int,
    loadedEnd: Int,
    prepend: LoadState,
    append: LoadState,
): LoadState? = when {
    index < loadedStart -> prepend
    index >= loadedEnd -> append
    else -> null
}

/** Snapshot only the requested position and the load direction capable of materializing it. */
internal fun targetMaterialization(
    items: LazyPagingItems<MessageEntity>,
    index: Int,
): TargetMaterialization<MessageEntity> {
    val itemCount = items.itemCount
    val snapshot = items.itemSnapshotList
    val loadedStart = snapshot.placeholdersBefore
    val loadedEnd = loadedStart + snapshot.items.size
    val directionalState = relevantTargetLoadState(
        index,
        loadedStart,
        loadedEnd,
        items.loadState.prepend,
        items.loadState.append,
    )
    val refresh = items.loadState.refresh
    return TargetMaterialization(
        item = pagingSnapshotItemOrNull(index, itemCount, items::peek),
        loading = refresh is LoadState.Loading || directionalState is LoadState.Loading,
        addressable = index in 0 until itemCount,
        failed = refresh is LoadState.Error || directionalState is LoadState.Error,
        generation = PagingTargetGeneration(
            itemCount = itemCount,
            placeholdersBefore = snapshot.placeholdersBefore,
            placeholdersAfter = snapshot.placeholdersAfter,
            firstLoadedId = snapshot.items.firstOrNull()?.id,
            lastLoadedId = snapshot.items.lastOrNull()?.id,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryRefreshSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onRange: (HistoryRefreshRange) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("chat_history_refresh_sheet"),
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(
                stringResource(R.string.chat_history_refresh_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                stringResource(R.string.chat_history_refresh_description),
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            HistoryRefreshOption(HistoryRefreshRange.MISSING, R.string.chat_history_range_missing, onRange)
            HistoryRefreshOption(HistoryRefreshRange.HOURS_24, R.string.chat_history_range_24h, onRange)
            HistoryRefreshOption(HistoryRefreshRange.DAYS_7, R.string.chat_history_range_7d, onRange)
            HistoryRefreshOption(HistoryRefreshRange.DAYS_30, R.string.chat_history_range_30d, onRange)
            HistoryRefreshOption(HistoryRefreshRange.ALL_AVAILABLE, R.string.chat_history_range_all, onRange)
            androidx.compose.material3.TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

@Composable
private fun HistoryRefreshOption(
    range: HistoryRefreshRange,
    @androidx.annotation.StringRes label: Int,
    onRange: (HistoryRefreshRange) -> Unit,
) {
    androidx.compose.material3.TextButton(
        onClick = { onRange(range) },
        modifier = Modifier.fillMaxWidth().testTag("chat_history_range_${range.name.lowercase()}"),
    ) {
        Text(stringResource(label))
    }
}

/** Safely read a Paging snapshot that may be replaced between its count read and item lookup. */
internal inline fun <T> pagingSnapshotItemOrNull(
    index: Int,
    itemCount: Int,
    lookup: (Int) -> T?,
): T? {
    if (index !in 0 until itemCount) return null
    return try {
        lookup(index)
    } catch (_: IndexOutOfBoundsException) {
        null
    }
}

/**
 * Append [prefill] to [value], inserting a single space when the current text is non-empty and
 * doesn't already end in whitespace. Places the cursor at the end (plans/11 §A).
 */
fun appendPrefill(value: TextFieldValue, prefill: String): TextFieldValue {
    val current = value.text
    val sep = if (current.isNotEmpty() && !current.last().isWhitespace()) " " else ""
    val text = current + sep + prefill
    return TextFieldValue(text = text, selection = androidx.compose.ui.text.TextRange(text.length))
}

/** Restore a buffer draft, then merge a one-shot mention prefill without losing the cursor. */
internal fun restoreComposerDraft(draft: String?, prefill: String?): TextFieldValue {
    val saved = draft.orEmpty()
    val value = TextFieldValue(saved, TextRange(saved.length))
    return prefill?.let { appendPrefill(value, it) } ?: value
}

/** Add the visible channel-reply prefix while preserving the current selection. */
fun prependReplyPrefix(value: TextFieldValue, sender: String): TextFieldValue {
    if (sender.isBlank()) return value
    val prefix = "$sender: "
    if (value.text.startsWith(prefix)) return value
    val text = prefix + value.text
    return value.copy(
        text = text,
        selection = androidx.compose.ui.text.TextRange(
            value.selection.start + prefix.length,
            value.selection.end + prefix.length,
        ),
    )
}

/** Apply the configured visible prefix consistently for every reply gesture. */
internal fun composerTextForReply(
    value: TextFieldValue,
    sender: String,
    bufferType: BufferType?,
    visibleReplyPrefix: Boolean,
): TextFieldValue = if (visibleReplyPrefix && bufferType == BufferType.CHANNEL) {
    prependReplyPrefix(value, sender)
} else {
    value
}

/**
 * Header subtitle: typing summary if anyone is typing, else a localized member count for channels.
 * Uses the [Context] typing overload and a plural for the count (plans/15 #25).
 */
/** A durable explicit-jump failure is the sole source of the not-loaded snackbar. */
internal fun shouldPresentUnresolvedEntrySnackbar(entryMessageUnavailable: Boolean): Boolean =
    entryMessageUnavailable

/**
 * A completed REFRESH may still be followed by a Room/RemoteMediator APPEND. Do not decide entry
 * positioning from a transient empty window; only rows or a terminal empty append are conclusive.
 */
internal fun initialPagingPage(itemCount: Int, append: LoadState): InitialPagingPage = when {
    itemCount > 0 -> InitialPagingPage.RowsAvailable
    append is LoadState.Error -> InitialPagingPage.TerminalEmpty
    append is LoadState.NotLoading && append.endOfPaginationReached -> InitialPagingPage.TerminalEmpty
    else -> InitialPagingPage.Pending
}

internal enum class InitialPagingPage { Pending, RowsAvailable, TerminalEmpty }

internal fun loadStateName(state: LoadState): String = when (state) {
    is LoadState.Loading -> "LOADING"
    is LoadState.NotLoading -> if (state.endOfPaginationReached) "DONE" else "IDLE"
    is LoadState.Error -> "ERROR"
}

internal fun visibleReactionMsgids(
    items: LazyPagingItems<MessageEntity>,
    listState: androidx.compose.foundation.lazy.LazyListState,
): List<String> {
    val visible = listState.layoutInfo.visibleItemsInfo
        .map { it.index }
        .filter { it >= 0 && it < items.itemCount }
    val start: Int
    val endExclusive: Int
    if (visible.isEmpty()) {
        start = 0
        endExclusive = minOf(items.itemCount, REACTION_PREFETCH_ROWS * 2)
    } else {
        start = (visible.minOrNull() ?: 0).minus(REACTION_PREFETCH_ROWS).coerceAtLeast(0)
        endExclusive = ((visible.maxOrNull() ?: 0) + REACTION_PREFETCH_ROWS + 1)
            .coerceAtMost(items.itemCount)
    }
    if (start >= endExclusive) return emptyList()
    return (start until endExclusive)
        .asSequence()
        .mapNotNull { items.peek(it)?.msgid }
        .distinct()
        .take(MAX_VISIBLE_REACTION_MSGIDS)
        .toList()
}

internal fun composerNeedsMemberNicks(value: TextFieldValue): Boolean {
    val text = value.text
    if (text.startsWith("/") && !text.startsWith("//") && !text.contains(' ')) return false
    val token = nickTokenAt(text, value.selection.end) ?: return false
    val atPrefixed = token.start < text.length && text[token.start] == '@'
    return token.text.length >= 2 || atPrefixed
}

const val CHAT_HISTORY_SYNC_INDICATOR_TAG = "chat_history_sync_indicator"
const val CHAT_HISTORY_SYNC_RETRY_TAG = "chat_history_sync_retry"

private val HistorySyncStatus.isActive: Boolean
    get() = this == HistorySyncStatus.Checking || this == HistorySyncStatus.Syncing

/** Pins transient timeline chrome below the title bar without allowing the layers to overlap. */
@Composable
internal fun BoxScope.TimelineTopOverlays(
    audioPlayer: @Composable () -> Unit,
    historyIndicator: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Keep playback controls stationary when delayed history progress becomes visible.
        audioPlayer()
        historyIndicator()
    }
}

/** A stable overlay so timeline inserts cannot move history progress or its retry action. */
@Composable
internal fun TimelineHistorySyncIndicator(
    status: HistorySyncStatus,
    timelineEmpty: Boolean,
    retryEnabled: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = status.isActive
    var activeVisible by remember { mutableStateOf(false) }
    LaunchedEffect(active, timelineEmpty) {
        if (active) {
            kotlinx.coroutines.delay(
                if (timelineEmpty) EMPTY_HISTORY_LOADING_INDICATOR_DELAY_MS
                else HISTORY_SYNC_INDICATOR_DELAY_MS,
            )
            activeVisible = true
        } else {
            activeVisible = false
        }
    }
    val visible = if (active) {
        activeVisible
    } else {
        status is HistorySyncStatus.Failed
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .testTag(CHAT_HISTORY_SYNC_INDICATOR_TAG),
        enter = fadeIn(MotdMotion.microFadeIn) + scaleIn(MotdMotion.microFadeIn, initialScale = 0.96f),
        exit = fadeOut(MotdMotion.microFadeOut) + scaleOut(MotdMotion.microFadeOut, targetScale = 0.96f),
    ) {
        val content: @Composable () -> Unit = {
            when (status) {
                HistorySyncStatus.Checking,
                HistorySyncStatus.Syncing,
                -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = stringResource(
                            if (timelineEmpty) R.string.chat_history_loading_messages
                            else R.string.chat_history_syncing_messages,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                is HistorySyncStatus.Failed -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.chat_history_sync_failed_inline),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Button(
                        onClick = onRetry,
                        enabled = retryEnabled,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag(CHAT_HISTORY_SYNC_RETRY_TAG),
                    ) {
                        Text(stringResource(R.string.chat_retry))
                    }
                }
                is HistorySyncStatus.Partial,
                HistorySyncStatus.Idle -> Unit
            }
        }
        if (timelineEmpty && active) {
            Box(
                modifier = Modifier
                    .semantics { liveRegion = LiveRegionMode.Polite },
                contentAlignment = Alignment.Center,
            ) {
                content()
            }
        } else {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 3.dp,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    content()
                }
            }
        }
    }
}

internal sealed interface ChatSubtitleModel {
    data class Text(val value: String) : ChatSubtitleModel
}

internal fun chatSubtitle(state: ChatState, context: android.content.Context): String? =
    (chatSubtitleModel(state, context) as? ChatSubtitleModel.Text)?.value

internal fun chatSubtitleModel(
    state: ChatState,
    context: android.content.Context,
): ChatSubtitleModel? {
    when (val connection = state.connState) {
        null -> return null
        ConnectionState.Connecting -> return ChatSubtitleModel.Text(context.getString(R.string.drawer_state_connecting))
        ConnectionState.Authenticating -> return ChatSubtitleModel.Text(context.getString(R.string.drawer_state_registering))
        ConnectionState.Disconnected -> return ChatSubtitleModel.Text(context.getString(R.string.drawer_state_disconnected))
        is ConnectionState.Failed -> return if (connection.fatal) {
            ChatSubtitleModel.Text(connection.reason)
        } else {
            ChatSubtitleModel.Text(context.getString(R.string.drawer_state_connecting))
        }
        is ConnectionState.Ready -> Unit
    }
    if (state.typingNicks.isNotEmpty()) {
        return ChatSubtitleModel.Text(typingText(context, state.typingNicks))
    }
    val buffer = state.buffer ?: return null
    return if (buffer.type == BufferType.CHANNEL && state.memberCount != null) {
        val n = state.memberCount
        ChatSubtitleModel.Text(context.resources.getQuantityString(R.plurals.chat_member_count, n, n))
    } else {
        null
    }
}

@Composable
internal fun ScrollToBottomFab(
    visible: Boolean,
    unread: Int,
    mentionPending: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // Keep the latest callbacks so the long-lived pointerInput gesture always dispatches to the
    // current mention/bottom resolution, even as mentionTarget updates between recompositions.
    val latestOnClick by rememberUpdatedState(onClick)
    val latestOnLongClick by rememberUpdatedState(onLongClick)
    // Hold progress 0..1: fills a ring around the FAB while pressed, so the user can see how long
    // to hold before the long-press fires. The same value gently compresses the arrow so progress
    // and completion read as one continuous motion rather than two competing animations.
    val holdProgress = remember { Animatable(0f) }
    val ringColor = MaterialTheme.colorScheme.onPrimaryContainer

    fun settle() {
        scope.launch {
            holdProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = SCROLL_TO_BOTTOM_FAB_SETTLE_MS,
                    easing = FastOutSlowInEasing,
                ),
            )
        }
    }

    fun fire() {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        settle()
        latestOnLongClick()
    }

    AnimatedVisibility(visible = visible, enter = scaleIn(), exit = scaleOut(), modifier = modifier) {
        BadgedBox(
            badge = {
                // An unread @mention of our nick takes priority over the plain unread count: the
                // "@" badge signals the next tap stops at that mention before continuing to bottom.
                when {
                    mentionPending -> Badge { Text("@") }
                    unread > 0 -> Badge { Text(if (unread >= MAX_UNREAD_BADGE_COUNT) "99+" else "$unread") }
                }
            },
        ) {
            // A custom hold-to-fire gesture owns both tap and long-press: a quick tap performs the
            // mention-walk/bottom jump via onClick, while holding past HOLD_MS draws a filling
            // progress ring and then fires onLongClick (skip straight to newest). The semantic
            // onClick remains available to accessibility and non-touch input.
            FloatingActionButton(
                // Keep the semantic click functional for accessibility and non-touch input. The
                // custom pointer recognizer consumes physical releases before Surface sees them.
                onClick = latestOnClick,
                modifier = Modifier
                    .testTag("chat_scroll_to_bottom_fab")
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            val holdJob = scope.launch {
                                holdProgress.snapTo(0f)
                                holdProgress.animateTo(
                                    1f,
                                    tween(
                                        durationMillis = SCROLL_TO_BOTTOM_FAB_HOLD_MS,
                                        easing = LinearEasing,
                                    ),
                                )
                            }
                            // Observe and consume the release before the FAB's internal Surface
                            // click so the physical tap dispatches exactly once through this path.
                            val releaseResult = withTimeoutOrNull(SCROLL_TO_BOTTOM_FAB_HOLD_MS.toLong()) {
                                Result.success(waitForUpOrCancellation(PointerEventPass.Initial))
                            }
                            holdJob.cancel()
                            when {
                                releaseResult == null -> {
                                    // Held past the threshold: skip mentions, jump to newest.
                                    fire()
                                    // Swallow the trailing release so it doesn't leak to handlers
                                    // behind the FAB.
                                    waitForUpOrCancellation(PointerEventPass.Initial)?.consume()
                                }
                                releaseResult.getOrNull() != null -> {
                                    // Released early: settle the partial ring and perform a tap.
                                    releaseResult.getOrNull()?.consume()
                                    settle()
                                    latestOnClick()
                                }
                                else -> {
                                    // Leaving the gesture bounds or another recognizer taking over
                                    // cancels cleanly instead of being mistaken for a completed hold.
                                    settle()
                                }
                            }
                        }
                    }
                    .drawWithContent {
                        drawContent()
                        val progress = holdProgress.value
                        if (progress > 0f) {
                            val stroke = 3.dp.toPx()
                            val inset = stroke / 2 + 2.dp.toPx()
                            val diameter = minOf(size.width, size.height) - inset * 2
                            if (diameter > 0f) {
                                val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                                val arcSize = Size(diameter, diameter)
                                drawArc(
                                    color = ringColor.copy(alpha = 0.22f * (progress * 4f).coerceAtMost(1f)),
                                    startAngle = -90f,
                                    sweepAngle = 360f,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                                )
                                drawArc(
                                    color = ringColor,
                                    startAngle = -90f,
                                    sweepAngle = 360f * progress,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                                )
                            }
                        }
                    },
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.chat_scroll_to_bottom),
                    modifier = Modifier.scale(scrollToBottomFabIconScale(holdProgress.value)),
                )
            }
        }
    }
}

/**
 * Viewport-aware FAB wrapper. This is intentionally its own restart scope: the first visible index
 * changes repeatedly during a fling, while the expensive chat scaffold and lazy-list declaration do
 * not. Only this small badge subtree recomposes at message boundaries.
 *
 * When an unread @mention of our nick sits below the viewport, the badge shows "@" and a tap jumps
 * to the nearest such mention (recomputed each tap, so repeated taps walk through mentions newest-
 * to-oldest before falling through to the bottom). Otherwise the badge shows the unread count and a
 * tap scrolls to the newest row.
 */
@Composable
private fun ViewportScrollToBottomFab(
    listState: androidx.compose.foundation.lazy.LazyListState,
    readMarker: io.github.trevarj.motd.data.db.TimelineAnchor?,
    visibilityPolicy: MessageVisibilityPolicy,
    countUnreadBelowViewport: suspend (Int, io.github.trevarj.motd.data.db.TimelineAnchor) -> Int,
    nearestUnreadMentionBelow: suspend (Int, io.github.trevarj.motd.data.db.TimelineAnchor) -> Int?,
    visible: Boolean,
    onJumpMention: (Int) -> Unit,
    onJumpNewest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstVisible by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex }
    }
    val latestCounter by rememberUpdatedState(countUnreadBelowViewport)
    val latestMentionJump by rememberUpdatedState(nearestUnreadMentionBelow)
    var unread by remember(readMarker, visibilityPolicy) { mutableIntStateOf(0) }
    var mentionTarget by remember(readMarker, visibilityPolicy) { mutableStateOf<Int?>(null) }
    LaunchedEffect(firstVisible, readMarker, visibilityPolicy) {
        if (readMarker == null || firstVisible <= 0) {
            unread = 0
            mentionTarget = null
        } else {
            unread = latestCounter(firstVisible, readMarker).coerceIn(0, MAX_UNREAD_BADGE_COUNT)
            mentionTarget = latestMentionJump(firstVisible, readMarker)
        }
    }
    val pending = mentionTarget
    // A tap follows the nearest pending @mention (the walk) before falling through to newest; a
    // long-press skips the walk and always goes to newest. Routing lives in a pure helper so it is
    // unit-testable without composition.
    val dispatch: (Boolean) -> Unit = { longPress ->
        when (val jump = scrollToBottomFabJump(longPress, pending)) {
            is ScrollToBottomFabJump.Mention -> onJumpMention(jump.index)
            ScrollToBottomFabJump.Newest -> onJumpNewest()
        }
    }
    ScrollToBottomFab(
        visible = visible,
        unread = unread,
        mentionPending = pending != null,
        onClick = { dispatch(false) },
        onLongClick = { dispatch(true) },
        modifier = modifier,
    )
}

@Preview
@Composable
private fun ChatContentPreview() {
    MotdTheme {
        ChatContentPreviewBody()
    }
}

@Preview(name = "Conversation 140% + large system font", fontScale = 1.5f)
@Composable
private fun ChatContentLargeTextPreview() {
    MotdTheme {
        ChatContentPreviewBody(conversationFontScalePercent = 140)
    }
}

@Composable
private fun VoiceComposerPanel(
    state: VoiceMessageUiState,
    playbackState: AudioPlaybackState,
    onDelete: () -> Unit,
    onCancelRecording: () -> Unit,
    onSend: () -> Unit,
    onPreview: (AudioAttachment) -> Unit,
    onPreviewSeek: (AudioAttachment, Long) -> Unit,
    onToggleEncryption: () -> Unit,
    onDestinationSelected: (io.github.trevarj.motd.attachment.PasteBackendConfig?) -> Unit,
    onErrorDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var destinationSheet by remember { mutableStateOf(false) }
    state.recording?.let { recording ->
        Surface(
            modifier = modifier.fillMaxWidth().testTag("voice_recording_panel"),
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Mic, null)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (recording.locked) "Recording locked" else "Recording",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (recording.locked) {
                            "${formatAudioDuration(recording.elapsedMs)} · Tap stop to review"
                        } else {
                            "${formatAudioDuration(recording.elapsedMs)} · Slide left to cancel"
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (recording.locked) {
                    IconButton(onClick = onCancelRecording, modifier = Modifier.testTag("voice_cancel_locked")) {
                        Icon(Icons.Filled.Delete, "Cancel recording")
                    }
                } else {
                    Column(
                        modifier = Modifier.testTag("voice_lock_hint"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Outlined.Lock, null, modifier = Modifier.size(20.dp))
                        Text("Swipe up", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
    state.staged?.let { staged ->
        val progress = state.progress
        val destination = staged.destination
        val preview = remember(staged.file, staged.durationMs, staged.mimeType, staged.sizeBytes) {
            AudioAttachment(
                url = staged.file.toURI().toString(),
                title = "Voice message",
                mimeType = staged.mimeType,
                durationMs = staged.durationMs,
                sizeBytes = staged.sizeBytes,
                voice = true,
            )
        }
        val previewActive = playbackState.activeId == preview.playbackId
        val previewPlaying = previewActive && playbackState.playing
        val previewDurationMs = playbackState.durationMs?.takeIf { previewActive && it > 0 } ?: staged.durationMs
        val previewPositionMs = playbackState.positionMs.takeIf { previewActive } ?: 0L
        Surface(
            modifier = modifier.fillMaxWidth().testTag("voice_preview_panel"),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp,
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Mic, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Voice message", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${formatAudioDuration(staged.durationMs)} · ${staged.mimeType} · ${formatBytes(staged.sizeBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onPreview(preview) }, enabled = progress == null, modifier = Modifier.testTag("voice_preview_play")) {
                        Icon(if (previewPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (previewPlaying) "Pause" else "Play")
                    }
                    IconButton(onClick = onDelete, enabled = progress == null, modifier = Modifier.testTag("voice_delete")) {
                        Icon(Icons.Filled.Delete, "Delete")
                    }
                }
                WaveformScrubber(
                    value = (previewPositionMs.toFloat() / previewDurationMs.coerceAtLeast(1L)).coerceIn(0f, 1f),
                    onValueChange = { fraction ->
                        onPreviewSeek(preview, (fraction * previewDurationMs).toLong())
                    },
                    onValueChangeFinished = {},
                    seed = preview.playbackId,
                    enabled = progress == null && previewActive && !playbackState.loading,
                    bufferedValue = if (previewActive && previewDurationMs > 0) {
                        (playbackState.bufferedMs.toFloat() / previewDurationMs).coerceIn(0f, 1f)
                    } else {
                        0f
                    },
                    waveform = staged.waveform,
                    modifier = Modifier.fillMaxWidth().testTag("voice_preview_scrubber"),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Lock, null)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Encrypt upload", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            if (staged.encrypted) {
                                "The host cannot listen. IRC servers and bouncers can see the key in the link."
                            } else {
                                "Standard audio link. The host and anyone with the link can play it in any client."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = staged.encrypted,
                        onCheckedChange = { onToggleEncryption() },
                        enabled = progress == null,
                        modifier = Modifier.testTag("voice_encryption_toggle"),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            destination?.backend?.label ?: "Soju file host",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            destination?.let(::backendRetention)
                                ?: "Uses the file host advertised by this IRC network",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { destinationSheet = true }, enabled = progress == null, modifier = Modifier.testTag("voice_destination")) {
                        Text("Change")
                    }
                }
                when (progress) {
                    is VoiceSendProgress.Uploading -> {
                        if (progress.totalBytes != null && progress.totalBytes > 0) {
                            LinearProgressIndicator(
                                progress = { (progress.bytesSent.toFloat() / progress.totalBytes).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    is VoiceSendProgress.Complete,
                    null,
                    -> Unit
                }
                Button(onClick = onSend, enabled = progress == null, modifier = Modifier.fillMaxWidth().testTag("voice_send")) {
                    Icon(Icons.Outlined.CloudUpload, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Send")
                }
            }
        }
        if (destinationSheet) {
            VoiceDestinationSheet(
                staged = staged,
                config = destination ?: io.github.trevarj.motd.attachment.PasteBackendConfig(),
                onSelect = {
                    destinationSheet = false
                    onDestinationSelected(it)
                },
                onDismiss = { destinationSheet = false },
            )
        }
    }
    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = onErrorDismissed,
            title = { Text("Voice message") },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = onErrorDismissed) { Text("OK") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceDestinationSheet(
    staged: StagedVoiceMessage,
    config: io.github.trevarj.motd.attachment.PasteBackendConfig,
    onSelect: (io.github.trevarj.motd.attachment.PasteBackendConfig?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Voice destination", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            androidx.compose.material3.ListItem(
                headlineContent = { Text("Soju file host", fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text("Use the file host advertised by this IRC network") },
                modifier = Modifier.clickable { onSelect(null) },
            )
            uploadDestinations(staged.source, config).forEach { destination ->
                androidx.compose.material3.ListItem(
                    headlineContent = { Text(destination.label, fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text(backendRetention(destination.config)) },
                    modifier = Modifier.clickable { onSelect(destination.config) },
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/**
 * Persistent banner shown when the user has parted the current channel (locally or via a
 * bouncer-reflected self-PART). Disables the composer and offers a one-tap rejoin so a message
 * never silently disappears into a channel the user is no longer a member of.
 */
@Composable
private fun PartedChannelBanner(
    channel: String,
    onRejoin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("chat_parted_banner"),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.chat_parted_banner_text, channel),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            TextButton(onClick = onRejoin, modifier = Modifier.testTag("chat_parted_rejoin")) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Login,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(stringResource(R.string.chat_parted_banner_rejoin))
            }
        }
    }
}
