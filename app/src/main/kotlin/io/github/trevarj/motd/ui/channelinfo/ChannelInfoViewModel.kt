package io.github.trevarj.motd.ui.channelinfo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.UserDao
import io.github.trevarj.motd.data.db.NetworkIdentityDao
import io.github.trevarj.motd.data.db.identityRules
import io.github.trevarj.motd.data.db.ircTarget
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.matchesConfiguredNick
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.NetworkIgnoreRepository
import io.github.trevarj.motd.data.repo.NoopNetworkIgnoreRepository
import io.github.trevarj.motd.backend.ConnectionState
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.ircbackend.IrcSessions
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.RosterLoadState
import io.github.trevarj.motd.ui.chat.ComposerDraftStore
import io.github.trevarj.motd.ui.chat.NickSheetState
import io.github.trevarj.motd.ui.chat.WhoisInfo
import io.github.trevarj.motd.ui.chat.parseWhois
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import javax.inject.Inject

data class ChannelInfoUiState(
    val buffer: BufferEntity? = null,
    val sections: List<MemberSection> = emptyList(),
    val memberCount: Int? = null,
    val rosterState: RosterLoadState = RosterLoadState.NOT_LOADED,
    val hasStaleMembers: Boolean = false,
    // Round 4 (plans/13 §3.6): global friend/fool sets. Fools are pulled into their own section.
    val foolMembers: List<MemberEntity> = emptyList(),
    val friends: Set<String> = emptySet(),
    val fools: Set<String> = emptySet(),
    val identityRules: IrcIdentityRules = IrcIdentityRules(),
    // Round 5 (plans/16 §5.8): true when the viewer holds op in this channel (moderation gate).
    val canModerate: Boolean = false,
    // Fuzzy member search. When [searchResults] is non-null the list renders a flat ranked set
    // instead of the prefix sections; null (query blank) means sectioned mode.
    val query: String = "",
    val searchResults: List<MemberEntity>? = null,
    // Network latency for this channel's network (#34); null until the first PONG completes or
    // while disconnected. Surfaced subtly in Channel Info rather than the chat header.
    val lagMs: Long? = null,
    val connected: Boolean = false,
)

/** Local write acceptance, distinct from a later server echo or numeric rejection. */
sealed interface TopicMutationState {
    data object Idle : TopicMutationState
    data object Submitting : TopicMutationState
    data object Accepted : TopicMutationState
    data object Failed : TopicMutationState
}

/** Local PART write acceptance, distinct from a later self-PART echo or server rejection. */
sealed interface LeaveMutationState {
    data object Idle : LeaveMutationState
    data object Submitting : LeaveMutationState
    data object Failed : LeaveMutationState
}

/** One-shot screen effects emitted only after a local operation is accepted. */
sealed interface ChannelInfoOperationEvent {
    data object LeaveAccepted : ChannelInfoOperationEvent
}

internal data class RosterPresentation(val memberCount: Int?, val hasStaleMembers: Boolean)

internal fun rosterPresentation(cachedCount: Int, state: RosterLoadState): RosterPresentation =
    RosterPresentation(
        memberCount = cachedCount.takeIf { state == RosterLoadState.LOADED },
        hasStaleMembers = cachedCount > 0 && state != RosterLoadState.LOADED,
    )

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChannelInfoViewModel @Inject constructor(
    private val bufferRepository: BufferRepository,
    private val connectionManager: ConnectionManager,
    private val ircSessions: IrcSessions,
    private val draftStore: ComposerDraftStore,
    private val settingsRepository: SettingsRepository,
    private val userDao: UserDao,
    private val networkIdentityDao: NetworkIdentityDao,
    private val networkIgnoreRepository: NetworkIgnoreRepository = NoopNetworkIgnoreRepository,
) : ViewModel() {

    private val bufferIdFlow = MutableStateFlow<Long?>(null)
    private val _topicMutation = MutableStateFlow<TopicMutationState>(TopicMutationState.Idle)
    val topicMutation: StateFlow<TopicMutationState> = _topicMutation
    private val _leaveMutation = MutableStateFlow<LeaveMutationState>(LeaveMutationState.Idle)
    val leaveMutation: StateFlow<LeaveMutationState> = _leaveMutation
    private val _operationEvents = MutableSharedFlow<ChannelInfoOperationEvent>(extraBufferCapacity = 1)
    val operationEvents: SharedFlow<ChannelInfoOperationEvent> = _operationEvents.asSharedFlow()

    fun init(bufferId: Long) {
        bufferIdFlow.value = bufferId
        viewModelScope.launch { connectionManager.requestMembers(bufferId) }
    }

    private val bufferFlow = bufferIdFlow.flatMapLatest { id ->
        if (id == null) flowOf(null) else bufferRepository.observeBuffer(id)
    }

    private val membersFlow = bufferIdFlow.flatMapLatest { id ->
        if (id == null) flowOf(emptyList<MemberEntity>()) else bufferRepository.observeMembers(id)
    }

    // Fuzzy member search input. The visible query lives in the screen's local IME state; this
    // flow mirrors it so the sections/search-results re-derive without a network fetch.
    private val queryFlow = MutableStateFlow("")
    fun setQuery(query: String) { queryFlow.value = query }

    // Per-nick last-spoke time in this channel (PRIVMSG/NOTICE/ACTION, isSelf=0). Keyed by the
    // normalized actor stored on messages; looked up via identityRules.normalize(member.nick).
    private val lastSpokeFlow = bufferIdFlow.flatMapLatest { id ->
        if (id == null) flowOf(emptyMap<String, Long>()) else bufferRepository.observeLastSpokeByNick(id)
    }

    private val identityRulesFlow = bufferFlow.flatMapLatest { buffer ->
        if (buffer == null) {
            flowOf(IrcIdentityRules())
        } else {
            networkIdentityDao.observe(buffer.networkId).map { it?.identityRules ?: IrcIdentityRules() }
        }
    }

    // Gather the per-roster inputs that don't depend on [bufferFlow]'s prefix order: lastSpoke,
    // the search query, friend/fool sets, and the identity rules. Sectioning/ranking happen in the
    // outer combine where [order] (derived from buffer) is available.
    private data class DerivedRoster(
        val lastSpoke: Map<String, Long>,
        val query: String,
        val friends: Set<String>,
        val fools: Set<String>,
        val identityRules: IrcIdentityRules,
    )

    private val derivedRosterFlow = combine(
        lastSpokeFlow,
        queryFlow,
        settingsRepository.settings,
        identityRulesFlow,
    ) { lastSpoke, query, settings, identityRules ->
        DerivedRoster(lastSpoke, query, settings.friends, settings.fools, identityRules)
    }

    // Network latency + Ready flag for this channel's network (#34). Pairs so a single 5-arg
    // combine can carry both into [state] without exceeding the combine arity limit.
    private val networkLagFlow = bufferFlow.flatMapLatest { buffer ->
        if (buffer == null) {
            flowOf<Pair<Long?, Boolean>>(null to false)
        } else {
            connectionManager.lagStates
                .combine(connectionManager.connectionStates) { lags, states ->
                    lags[buffer.networkId] to (states[buffer.networkId] is ConnectionState.Ready)
                }
        }
    }

    val state: StateFlow<ChannelInfoUiState> =
        combine(
            bufferFlow,
            membersFlow,
            derivedRosterFlow,
            connectionManager.rosterStates,
            networkLagFlow,
        ) { buffer, members, derived, rosterStates, networkLag ->
            val (lagMs, connected) = networkLag
            val order = prefixOrderForBuffer(buffer)
            val identityRules = derived.identityRules
            val lookup: (MemberEntity) -> Long? = { derived.lastSpoke[identityRules.normalize(it.nick)] }
            val sections: List<MemberSection>
            val foolMembers: List<MemberEntity>
            val searchResults: List<MemberEntity>?
            if (derived.query.isBlank()) {
                val social = sectionMembersSocial(
                    members, order, derived.fools, identityRules,
                    comparator = activityMemberComparator(identityRules, lookup),
                )
                sections = social.sections
                foolMembers = social.fools
                searchResults = null
            } else {
                sections = emptyList()
                foolMembers = emptyList()
                searchResults = rankMembersFuzzy(derived.query, members, identityRules::normalize, lookup)
            }
            val rosterState = buffer?.let { rosterStates[it.id] } ?: RosterLoadState.NOT_LOADED
            val presentation = rosterPresentation(members.size, rosterState)
            ChannelInfoUiState(
                buffer = buffer,
                sections = sections,
                memberCount = presentation.memberCount,
                rosterState = rosterState,
                hasStaleMembers = presentation.hasStaleMembers,
                foolMembers = foolMembers,
                friends = derived.friends,
                fools = derived.fools,
                identityRules = identityRules,
                canModerate = viewerCanModerate(buffer, members, order),
                query = derived.query,
                searchResults = searchResults,
                lagMs = lagMs,
                connected = connected,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ChannelInfoUiState(),
        )

    fun retryMembers() = viewModelScope.launch {
        state.value.buffer?.let { connectionManager.requestMembers(it.id, force = true) }
    }

    // Resolve prefix order from the live client's ISUPPORT when connected; fallback otherwise.
    private fun prefixOrderForBuffer(buffer: BufferEntity?): String {
        val networkId = buffer?.networkId ?: return DEFAULT_PREFIX_ORDER
        val client = ircSessions.sessionFor(networkId) ?: return DEFAULT_PREFIX_ORDER
        return prefixOrderFrom(client.isupport.prefixModes)
    }

    fun setPinned(pinned: Boolean) = viewModelScope.launch {
        state.value.buffer?.let { bufferRepository.setPinned(it.id, pinned) }
    }

    fun setMuted(muted: Boolean) = viewModelScope.launch {
        state.value.buffer?.let { bufferRepository.setMuted(it.id, muted) }
    }

    /** Reset a previous local PART failure before showing the leave confirmation. */
    fun beginLeave() {
        _leaveMutation.value = LeaveMutationState.Idle
    }

    /**
     * Leave only after the live transport accepts PART. This is deliberately not durable: a
     * later server rejection still needs labeled-response correlation (or the self-PART echo).
     */
    fun part() {
        if (_leaveMutation.value is LeaveMutationState.Submitting) return
        val bufferId = bufferIdFlow.value
        if (bufferId == null) {
            _leaveMutation.value = LeaveMutationState.Failed
            return
        }
        _leaveMutation.value = LeaveMutationState.Submitting
        viewModelScope.launch {
            val accepted = try {
                connectionManager.partChannelForClose(bufferId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (accepted) {
                _leaveMutation.value = LeaveMutationState.Idle
                _operationEvents.emit(ChannelInfoOperationEvent.LeaveAccepted)
            } else {
                _leaveMutation.value = LeaveMutationState.Failed
            }
        }
    }

    /** Open (or create) a DM with [nick], then hand the buffer id to [onOpen]. */
    fun messageMember(nick: String, onOpen: (Long) -> Unit) = viewModelScope.launch {
        val networkId = state.value.buffer?.networkId ?: return@launch
        val bufferId = connectionManager.ensureQueryBuffer(networkId, nick)
        onOpen(bufferId)
    }

    /**
     * Queue a "$nick: " prefill on the current buffer's composer draft, then [onDone] (pops back
     * to the chat). ChatScreen reads it via [ComposerDraftStore.consume] on re-entry (plans/11 §A).
     */
    fun mentionMember(nick: String, onDone: () -> Unit) {
        state.value.buffer?.let { draftStore.push(it.id, "$nick: ") }
        onDone()
    }

    /** Toggle [nick]'s friend membership (adding removes it from fools; see SettingsRepository). */
    fun toggleFriend(nick: String) = viewModelScope.launch {
        val current = state.value
        settingsRepository.setFriend(
            nick,
            !current.identityRules.matchesConfiguredNick(nick, current.friends),
            current.identityRules,
        )
    }

    /** Toggle [nick]'s fool membership (adding removes it from friends). */
    fun toggleFool(nick: String) = viewModelScope.launch {
        val current = state.value
        settingsRepository.setFool(
            nick,
            !current.identityRules.matchesConfiguredNick(nick, current.fools),
            current.identityRules,
        )
    }

    fun ignoreNickOnNetwork(nick: String) = viewModelScope.launch {
        val networkId = state.value.buffer?.networkId ?: return@launch
        networkIgnoreRepository.addIgnore(networkId, nick)
        dismissNickSheet()
    }

    // --- nick sheet + whois (plans/16 §5.8) ---

    private val _nickSheet = MutableStateFlow<NickSheetState?>(null)
    val nickSheet: StateFlow<NickSheetState?> = _nickSheet
    private var nickDetailsJob: Job? = null

    /** Open the nick sheet for [nick]; WHOIS via labeled-response when available (see ChatViewModel). */
    fun openNickSheet(nick: String) {
        _nickSheet.value = NickSheetState(nick = nick)
        val networkId = state.value.buffer?.networkId ?: return
        viewModelScope.launch { state.value.buffer?.let { connectionManager.requestMembers(it.id) } }
        val client = ircSessions.sessionFor(networkId)
        val normalized =
            (connectionManager.liveIdentityRules(networkId) ?: state.value.identityRules).normalize(nick)
        nickDetailsJob?.cancel()
        nickDetailsJob = viewModelScope.launch {
            combine(
                userDao.observeByNick(networkId, normalized),
                connectionManager.presenceStates,
            ) { cached, presence ->
                cached to presence[io.github.trevarj.motd.service.PresenceKey(networkId, normalized)]
            }.collect { (cached, presence) ->
                val current = _nickSheet.value
                if (current?.nick == nick) _nickSheet.value = current.copy(cached = cached, presence = presence)
            }
        }
        if (client == null) return
        val whoisMsg = IrcMessage(command = "WHOIS", params = listOf(nick))
        if (client.hasCap("labeled-response")) {
            viewModelScope.launch {
                val lines = runCatching { client.sendLabeled(whoisMsg) }.getOrNull().orEmpty()
                val info: WhoisInfo? = parseWhois(lines)
                if (info != null && _nickSheet.value?.nick == nick) {
                    _nickSheet.value = _nickSheet.value?.copy(whois = info)
                }
            }
        } else {
            viewModelScope.launch { client.send(whoisMsg) }
        }
    }

    fun dismissNickSheet() {
        nickDetailsJob?.cancel()
        nickDetailsJob = null
        _nickSheet.value = null
    }

    // --- moderation executors (plans/16 §5.8) ---

    /** MODE <channel> +o/-o/+v/-v <nick>. */
    fun setMemberMode(nick: String, mode: Char, grant: Boolean) = viewModelScope.launch {
        val buffer = state.value.buffer ?: return@launch
        val flag = (if (grant) "+" else "-") + mode
        ircSessions.sessionFor(buffer.networkId)
            ?.send(IrcMessage(command = "MODE", params = listOf(buffer.ircTarget, flag, nick)))
    }

    /** KICK <channel> <nick> [:reason]. */
    fun kick(nick: String, reason: String?) = viewModelScope.launch {
        val buffer = state.value.buffer ?: return@launch
        val params = if (reason.isNullOrBlank()) {
            listOf(buffer.ircTarget, nick)
        } else {
            listOf(buffer.ircTarget, nick, reason)
        }
        ircSessions.sessionFor(buffer.networkId)?.send(IrcMessage(command = "KICK", params = params))
    }

    /** MODE <channel> +b <banMask(nick)>. */
    fun ban(nick: String) = viewModelScope.launch {
        val buffer = state.value.buffer ?: return@launch
        ircSessions.sessionFor(buffer.networkId)
            ?.send(IrcMessage(command = "MODE", params = listOf(buffer.ircTarget, "+b", banMask(nick))))
    }

    fun setBanMask(mask: String, grant: Boolean) = viewModelScope.launch {
        val buffer = state.value.buffer ?: return@launch
        val trimmed = mask.trim().takeIf(String::isNotBlank) ?: return@launch
        val flag = if (grant) "+b" else "-b"
        ircSessions.sessionFor(buffer.networkId)
            ?.send(IrcMessage(command = "MODE", params = listOf(buffer.ircTarget, flag, trimmed)))
    }

    fun invite(nick: String) = viewModelScope.launch {
        val buffer = state.value.buffer ?: return@launch
        val trimmed = nick.trim().takeIf(String::isNotBlank) ?: return@launch
        ircSessions.sessionFor(buffer.networkId)
            ?.send(IrcMessage(command = "INVITE", params = listOf(trimmed, buffer.ircTarget)))
    }

    fun setChannelMode(modes: String, args: String) = viewModelScope.launch {
        val buffer = state.value.buffer ?: return@launch
        val trimmedModes = modes.trim().takeIf(String::isNotBlank) ?: return@launch
        val params = listOf(buffer.ircTarget, trimmedModes) +
            args.split(' ').map(String::trim).filter(String::isNotBlank)
        ircSessions.sessionFor(buffer.networkId)?.send(IrcMessage(command = "MODE", params = params))
    }

    /** Reset a previous local result before opening the editor again. */
    fun beginTopicEdit() {
        _topicMutation.value = TopicMutationState.Idle
    }

    /**
     * Set the channel topic. Acceptance only means a Ready client wrote TOPIC to its live
     * transport; a later TopicChanged echo owns the Room update and numeric 482 stays separate.
     */
    fun setTopic(topic: String) {
        if (_topicMutation.value is TopicMutationState.Submitting) return
        val bufferId = bufferIdFlow.value
        if (bufferId == null) {
            _topicMutation.value = TopicMutationState.Failed
            return
        }
        _topicMutation.value = TopicMutationState.Submitting
        viewModelScope.launch {
            val accepted = try {
                connectionManager.setChannelTopic(bufferId, topic)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            _topicMutation.value = if (accepted) {
                TopicMutationState.Accepted
            } else {
                TopicMutationState.Failed
            }
        }
    }

    /**
     * True when the viewer's own member row holds op-or-above in this CHANNEL buffer (Confirmed #7).
     * Self nick comes from the live client's Ready state; prefix order from ISUPPORT.
     */
    private fun viewerCanModerate(buffer: BufferEntity?, members: List<MemberEntity>, prefixOrder: String): Boolean {
        if (buffer?.type != BufferType.CHANNEL) return false
        val client = ircSessions.sessionFor(buffer.networkId) ?: return false
        val myNick = (connectionManager.connectionStates.value[buffer.networkId] as? ConnectionState.Ready)?.selfHandle
            ?: return false
        val normalize: (String) -> String = { client.isupport.normalize(it) }
        val me = members.firstOrNull { normalize(it.nick) == normalize(myNick) } ?: return false
        return canModerate(me.prefixes, prefixOrder)
    }
}
