package io.github.trevarj.motd.ui.settings.xmpp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.data.repo.XmppAccountRepository
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.xmppbackend.XmppChatBackend
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class XmppAccountUiState(
    val loaded: Boolean = false,
    val isEdit: Boolean = false,
    val networkId: Long? = null,
    val displayName: String = "",
    val jid: String = "",
    val password: String = "",
    val resource: String = "",
    val autoConnect: Boolean = true,
    /** True while [XmppAccountViewModel.save] is in flight; guards against a double-tap creating
     *  two rows (this screen deliberately does not dedup by identity — see [XmppAccountRepository]). */
    val saving: Boolean = false,
) {
    /** "jid must contain @" (docs/backend-neutral-xmpp-rollout.md baseline account UI validation). */
    val jidValid: Boolean get() = jid.trim().let { it.isNotEmpty() && '@' in it }
    val passwordValid: Boolean get() = password.isNotEmpty()
    val isValid: Boolean get() = jidValid && passwordValid
    val canSave: Boolean get() = isValid && !saving
}

/**
 * XMPP's protocol-owned account create/edit surface (docs/backend-neutral-xmpp-rollout.md baseline
 * "account creation and edits" + "the minimum protocol-aware conversation and account UI"). One
 * screen/ViewModel serves both flows — unlike IRC's split AddNetwork/NetworkSettings pair, an XMPP
 * account's fields are the same short list (name, JID, password, resource) whether creating or
 * editing. [init] with a null id starts a blank create form; a non-null id loads that network row
 * and its [io.github.trevarj.motd.data.db.XmppAccountEntity] for editing.
 */
@HiltViewModel
class XmppAccountViewModel @Inject constructor(
    private val xmppAccountRepository: XmppAccountRepository,
    private val networkRepository: NetworkRepository,
    private val connectionManager: ConnectionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(XmppAccountUiState())
    val state: StateFlow<XmppAccountUiState> = _state.asStateFlow()

    /** [networkId] null starts a fresh create form; non-null loads that row for editing. Repeated
     *  calls with the screen's own (stable) networkId argument are no-ops once loaded, matching
     *  [io.github.trevarj.motd.ui.settings.NetworkSettingsViewModel.init]'s idiom. */
    fun init(networkId: Long?) {
        if (_state.value.loaded) return
        if (networkId == null) {
            _state.value = _state.value.copy(loaded = true)
            return
        }
        viewModelScope.launch {
            val network = networkRepository.networkById(networkId)
            val account = xmppAccountRepository.account(networkId)
            _state.value = _state.value.copy(
                loaded = true,
                isEdit = true,
                networkId = networkId,
                displayName = network?.name.orEmpty(),
                jid = account?.jid.orEmpty(),
                password = account?.password.orEmpty(),
                resource = account?.resource.orEmpty(),
                autoConnect = network?.autoConnect ?: true,
            )
        }
    }

    fun editDisplayName(value: String) { _state.value = _state.value.copy(displayName = value) }
    fun editJid(value: String) { _state.value = _state.value.copy(jid = value) }
    fun editPassword(value: String) { _state.value = _state.value.copy(password = value) }
    fun editResource(value: String) { _state.value = _state.value.copy(resource = value) }
    fun setAutoConnect(value: Boolean) { _state.value = _state.value.copy(autoConnect = value) }

    /**
     * Persist the form. Create writes both rows atomically
     * ([XmppAccountRepository.createAccount]) then kicks a connection attempt, mirroring
     * [io.github.trevarj.motd.ui.settings.addnetwork.AddNetworkViewModel.submit]'s connect-on-save —
     * fire-and-forget here rather than observing [ConnectionManager.connectionStates] for a
     * TESTING/FAILED phase: this baseline is deliberately "the minimum protocol-aware account UI"
     * (docs/backend-neutral-xmpp-rollout.md), not full parity with IRC's connect-test wizard. Edit
     * updates both rows without reconnecting, mirroring
     * [io.github.trevarj.motd.ui.settings.NetworkSettingsViewModel.save] (a live session keeps
     * running on its old credentials until the next reconnect).
     */
    fun save(onDone: () -> Unit) {
        val current = _state.value
        if (!current.canSave) return
        _state.value = current.copy(saving = true)
        viewModelScope.launch {
            val jid = current.jid.trim()
            val resource = current.resource.trim().ifBlank { null }
            val name = current.displayName.trim().ifBlank { jid }
            if (current.isEdit) {
                val networkId = current.networkId ?: return@launch
                val updated = buildXmppNetworkEntity(
                    id = networkId,
                    name = name,
                    jid = jid,
                    autoConnect = current.autoConnect,
                )
                xmppAccountRepository.updateAccount(updated, jid, current.password, resource)
            } else {
                val entity = buildXmppNetworkEntity(name = name, jid = jid)
                val networkId = xmppAccountRepository.createAccount(entity, jid, current.password, resource)
                connectionManager.connect(networkId)
            }
            onDone()
        }
    }

    /** Delete follows [io.github.trevarj.motd.ui.settings.NetworkSettingsViewModel.delete]'s idiom:
     *  disconnect first, then delete the network row — the `xmpp_accounts` detail cascades off its
     *  `networkId` foreign key (see [io.github.trevarj.motd.data.db.XmppAccountEntity]), so no
     *  separate delete call is needed here. */
    fun delete(onDone: () -> Unit) {
        val networkId = _state.value.networkId ?: return
        viewModelScope.launch {
            connectionManager.disconnect(networkId)
            networkRepository.deleteNetwork(networkId)
            onDone()
        }
    }
}

/**
 * Build the shared [NetworkEntity] row for an XMPP account (docs/backend-neutral-xmpp-rollout.md:
 * "the IRC-shaped columns on the network row ... are grandfathered and remain owned by the IRC
 * adapter"). XMPP's real identity/credentials live entirely in
 * [io.github.trevarj.motd.data.db.XmppAccountEntity]; the NOT NULL IRC-shaped columns below are
 * filled with the same inert placeholders [io.github.trevarj.motd.xmppbackend.XmppProcessorTest] and
 * [io.github.trevarj.motd.xmppbackend.XmppConnectionManagerTest] already use for their own XMPP
 * network fixtures, rather than a second, inconsistent placeholder scheme:
 *  - [NetworkEntity.host] = [XMPP_INERT_HOST] ("unused.invalid", RFC 2606's reserved-invalid TLD) —
 *    reads unambiguously as a placeholder rather than a value someone might mistake for the
 *    account's real domain.
 *  - [NetworkEntity.port] = [XMPP_INERT_PORT] (5222, the conventional XMPP client-to-server port) —
 *    cosmetic only; `XmppConnectionManager`/`XmppAccountActor`/Smack resolve the real connection
 *    target from the JID's domain, never from this column.
 *  - [NetworkEntity.nick]/[NetworkEntity.username]/[NetworkEntity.realname] = [XMPP_INERT_IDENTITY]
 *    ("unused") — IRC USER-command concepts with no XMPP meaning.
 *
 * Every other IRC-only column (SASL, server password, cert alias, ws/obfuscation transport, bouncer
 * linkage) is left at its neutral null/default; confirmed unread by `XmppConnectionManager`/
 * `XmppAccountActor`/`XmppProcessor`, which only ever look at this row's id/protocol/autoConnect.
 *
 * Known gap (flagged, not fixed here — fixing it would need a protocol branch in shared code):
 * [io.github.trevarj.motd.ui.settings.SettingsScreen]'s `networkSupporting()` unconditionally
 * renders "host:port" as every network row's subtitle in Settings > Networks, so an XMPP row shows
 * the placeholder "unused.invalid:5222" there instead of anything JID-derived. Fixing this properly
 * needs a neutral per-row subtitle hook shared code can render without switching on protocol; that
 * is a shared-architecture change, not something this account-UI slice should freelance.
 */
internal fun buildXmppNetworkEntity(
    id: Long = 0,
    name: String,
    jid: String,
    autoConnect: Boolean = true,
): NetworkEntity = NetworkEntity(
    id = id,
    name = name,
    role = NetworkRole.DIRECT,
    host = XMPP_INERT_HOST,
    port = XMPP_INERT_PORT,
    nick = XMPP_INERT_IDENTITY,
    username = XMPP_INERT_IDENTITY,
    realname = XMPP_INERT_IDENTITY,
    autoConnect = autoConnect,
    protocol = XmppChatBackend.XMPP_PROTOCOL.value,
)

/** See [buildXmppNetworkEntity]'s KDoc. */
internal const val XMPP_INERT_HOST = "unused.invalid"

/** See [buildXmppNetworkEntity]'s KDoc. */
internal const val XMPP_INERT_PORT = 5222

/** See [buildXmppNetworkEntity]'s KDoc. */
internal const val XMPP_INERT_IDENTITY = "unused"
