package io.github.trevarj.motd.ui.chatlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.OnboardingPrefs
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.backend.ConnectionState
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.ChannelCloseCoordinator
import io.github.trevarj.motd.service.PresenceKey
import io.github.trevarj.motd.service.PresenceState
import io.github.trevarj.motd.service.ReadMarkerSnapshotter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Single UI state for the chat list screen. See plans/07 + plans/16 §3.4. */
data class ChatListState(
    val rows: List<ChatListRow> = emptyList(),
    /** Scoped archived rows; all global badges and drawer rollups deliberately exclude these. */
    val archivedRows: List<ChatListRow> = emptyList(),
    val connection: Map<Long, ConnectionState> = emptyMap(),
    val queryPresence: Map<Long, PresenceState> = emptyMap(),
    val networks: List<NetworkEntity> = emptyList(),
    val loading: Boolean = true,
    val onboardingComplete: Boolean = false,
    // Round 4 (plans/13 §3.5): global friend/fool sets drive chat-list sectioning.
    val friends: Set<String> = emptySet(),
    val fools: Set<String> = emptySet(),
    // Round 5 (plans/16 §3): drawer server selector + scoping.
    val selectedNetworkId: Long? = null,
    val drawerRows: List<DrawerRow> = emptyList(),
    val allUnread: Int = 0, // "All chats" unread rollup (non-muted)
    val allMentions: Int = 0, // "All chats" mention rollup
) {
    /** Effective unread count for the current drawer scope; muted activity stays row-local. */
    val scopedUnreadCount: Int
        get() = rows.filterNot { it.type == BufferType.SERVER || it.muted }.sumOf { it.unreadCount }

    /** The scoped network's name, or null when unscoped (drives the top-bar title/chip). */
    val selectedNetworkName: String?
        get() = selectedNetworkId?.let { id -> networks.firstOrNull { it.id == id }?.name }

    /** Every network is absent from the map or Disconnected -> the "Go online" affordance shows. */
    val allOffline: Boolean
        get() = networks.all { connection[it.id].let { s -> s == null || s is ConnectionState.Disconnected } }
}

@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val bufferRepository: BufferRepository,
    private val networkRepository: NetworkRepository,
    private val connectionManager: ConnectionManager,
    private val channelCloseCoordinator: ChannelCloseCoordinator,
    private val readMarkerRepository: ReadMarkerSnapshotter,
    private val settingsRepository: SettingsRepository,
    onboardingPrefs: OnboardingPrefs,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    init {
        // The coordinator is process-scoped and observes persisted pending closes, so creating a
        // fresh ViewModel after process/configuration recreation re-drives any unfinished leaves.
        channelCloseCoordinator.start()
    }

    // Scope selection survives config changes; null = unified list (default).
    private val selection = MutableStateFlow(savedStateHandle.get<Long?>(KEY_SELECTED))
    private val archiveOverrides = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    private val chatListRows = bufferRepository.observeChatList()
        .onEach { rows ->
            val settledIds = settledArchiveOverrideIds(rows, archiveOverrides.value)
            if (settledIds.isNotEmpty()) archiveOverrides.value = archiveOverrides.value - settledIds
        }
        .combine(archiveOverrides, ::applyArchiveOverrides)
    private val settingsAndOnboarding = combine(
        settingsRepository.settings,
        onboardingPrefs.completed,
        ::Pair,
    )

    val state: StateFlow<ChatListState> =
        combine(
            chatListRows,
            networkRepository.observeNetworks(),
            connectionManager.connectionStates.combine(connectionManager.presenceStates) { connection, presence ->
                connection to presence
            },
            settingsAndOnboarding,
            selection,
        ) { rows, networks, connectionAndPresence, settingsAndOnboarding, selected ->
            val (connection, presence) = connectionAndPresence
            val (settings, onboardingComplete) = settingsAndOnboarding
            // If the selected network was deleted, fall back to the unified list.
            val validSelection = selected?.takeIf { id -> networks.any { it.id == id } }
            if (validSelection != selected) setSelection(validSelection)

            val scopedRows = scopeRows(rows, validSelection, networks)
            val (activeRows, archivedRows) = partitionArchivedRows(scopedRows)
            ChatListState(
                rows = activeRows,
                archivedRows = archivedRows,
                connection = connection,
                queryPresence = scopedRows.asSequence()
                    .filter { it.type == BufferType.QUERY }
                    .mapNotNull { row ->
                        val normalize = connectionManager.liveIdentityRules(row.networkId)?.let { it::normalize }
                            ?: return@mapNotNull null
                        presence[PresenceKey(row.networkId, normalize(row.displayName))]?.let { row.bufferId to it }
                    }
                    .toMap(),
                networks = networks,
                loading = false,
                onboardingComplete = onboardingComplete,
                friends = settings.friends,
                fools = settings.fools,
                selectedNetworkId = validSelection,
                drawerRows = buildDrawerRows(networks, rows.filterNot(ChatListRow::archived), connection),
                allUnread = rows.filterNot { it.muted || it.archived }.sumOf { it.unreadCount },
                allMentions = rows.filterNot { it.muted || it.archived }.sumOf { it.mentionCount },
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ChatListState(),
        )

    fun setPinned(bufferId: Long, pinned: Boolean) = setPinned(listOf(bufferId), pinned)

    fun setPinned(bufferIds: Collection<Long>, pinned: Boolean) {
        val ids = bufferIds.toList().distinct()
        if (ids.isEmpty()) return
        viewModelScope.launch { ids.forEach { bufferRepository.setPinned(it, pinned) } }
    }

    fun setMuted(bufferId: Long, muted: Boolean) = setMuted(listOf(bufferId), muted)

    fun setMuted(bufferIds: Collection<Long>, muted: Boolean) {
        val ids = bufferIds.toList().distinct()
        if (ids.isEmpty()) return
        viewModelScope.launch { ids.forEach { bufferRepository.setMuted(it, muted) } }
    }

    fun setArchived(bufferId: Long, archived: Boolean) = setArchived(listOf(bufferId), archived)

    fun setArchived(bufferIds: Collection<Long>, archived: Boolean) {
        val ids = bufferIds.toList().distinct()
        if (ids.isEmpty()) return
        archiveOverrides.value = archiveOverrides.value + ids.associateWith { archived }
        viewModelScope.launch {
            runCatching {
                ids.forEach { bufferRepository.setArchived(it, archived) }
            }.onFailure {
                archiveOverrides.value = archiveOverrides.value - ids.toSet()
            }
        }
    }

    /**
     * [rawInput] is the trimmed text the user typed in [NewConversationSheet]'s "join channel" tab —
     * review fix: that shared composable used to apply an IRC-shaped `"#$input"` transform itself
     * before this method ever saw it, so entering a bare XMPP room JID tried to join a `#`-prefixed
     * target instead. [ConnectionManager.roomTargetSyntax] supplies whatever backend-specific
     * transform applies (IRC's `#`-prefix; XMPP has none, so a null capability means "use verbatim").
     */
    fun joinChannel(networkId: Long, rawInput: String) = viewModelScope.launch {
        val target = connectionManager.roomTargetSyntax(networkId)?.targetFor(rawInput) ?: rawInput
        connectionManager.joinChannel(networkId, target)
    }

    /**
     * Delete a chat/buffer from the list. QUERY/SERVER rows are local-only and are removed at once.
     * CHANNEL rows are marked pending immediately (which hides them from every normal projection);
     * the process-scoped coordinator performs the server close and removes history only after it
     * succeeds. Scope selection keys off networkId, never a bufferId, so no scope reset is needed.
     */
    fun deleteBuffer(row: ChatListRow) = deleteBuffers(listOf(row))

    fun deleteBuffers(rows: Collection<ChatListRow>) {
        val targets = rows.toList().distinctBy(ChatListRow::bufferId)
        if (targets.isEmpty()) return
        viewModelScope.launch {
            targets.forEach { row ->
                if (row.type == BufferType.CHANNEL) {
                    channelCloseCoordinator.requestClose(row.bufferId)
                } else {
                    bufferRepository.deleteBuffer(row.bufferId)
                }
            }
        }
    }

    /** Find-or-create a query buffer, then hand the id to [onOpen] for navigation. */
    fun messageUser(networkId: Long, nick: String, onOpen: (Long) -> Unit) = viewModelScope.launch {
        val bufferId = connectionManager.ensureQueryBuffer(networkId, nick)
        onOpen(bufferId)
    }

    // -- Round 5: drawer selection + per-network / global connectivity (plans/16 §3.4) --

    /** Scope the list to [networkId] (root includes children); null clears the scope. */
    fun selectNetwork(networkId: Long?) = setSelection(networkId)

    fun connect(networkId: Long) = viewModelScope.launch { connectionManager.connect(networkId) }

    fun disconnect(networkId: Long) = viewModelScope.launch { connectionManager.disconnect(networkId) }

    /** Global go-offline: disconnect every network (in-memory intent, resets on restart). */
    fun goOffline() = viewModelScope.launch {
        state.value.networks.forEach { connectionManager.disconnect(it.id) }
    }

    /** Global go-online: connect everything (explicit "connect all", may include autoConnect=false). */
    fun goOnline() = viewModelScope.launch {
        state.value.networks.forEach { connectionManager.connect(it.id) }
    }

    /** Find-or-create the SERVER buffer for [networkId], then navigate to it. */
    fun openServerBuffer(networkId: Long, onOpen: (Long) -> Unit) = viewModelScope.launch {
        onOpen(connectionManager.ensureServerBuffer(networkId))
    }

    /** Mark every currently unread chat in the current drawer scope through one Room snapshot. */
    fun markCurrentScopeRead() {
        val bufferIds = unreadBufferIds(state.value.rows)
        if (bufferIds.isEmpty()) return
        viewModelScope.launch {
            readMarkerRepository.latestIncoming(bufferIds).forEach { marker ->
                val timestamp = marker.timestamp ?: return@forEach
                val eventId = marker.eventId ?: return@forEach
                runCatching {
                    connectionManager.markRead(
                        marker.bufferId,
                        io.github.trevarj.motd.data.db.TimelineAnchor(timestamp, eventId),
                    )
                }
            }
        }
    }

    private fun setSelection(networkId: Long?) {
        selection.value = networkId
        savedStateHandle[KEY_SELECTED] = networkId
    }

    private companion object {
        const val KEY_SELECTED = "selected_network"
    }
}

/** Pure selection seam: muted/SERVER/zero-unread rows never participate in mark-all. */
internal fun unreadBufferIds(rows: List<ChatListRow>): List<Long> = rows
    .asSequence()
    .filter { !it.muted && it.type != BufferType.SERVER && it.unreadCount > 0 }
    .map { it.bufferId }
    .distinct()
    .toList()

/** Pending archive writes should move rows immediately, then disappear once Room agrees. */
internal fun applyArchiveOverrides(
    rows: List<ChatListRow>,
    overrides: Map<Long, Boolean>,
): List<ChatListRow> {
    if (overrides.isEmpty()) return rows
    return rows.map { row ->
        val archived = overrides[row.bufferId] ?: return@map row
        if (row.archived == archived) row else row.copy(archived = archived)
    }
}

/** Override entries are only optimistic; matched Room emissions become authoritative again. */
internal fun settledArchiveOverrideIds(
    rows: List<ChatListRow>,
    overrides: Map<Long, Boolean>,
): Set<Long> {
    if (overrides.isEmpty()) return emptySet()
    val byId = rows.associateBy(ChatListRow::bufferId)
    return overrides.filter { (id, archived) -> byId[id]?.archived == archived }.keys
}
