package io.github.trevarj.motd.ui.chatlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.TabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.Protocol
import io.github.trevarj.motd.ui.theme.MotdTheme

/**
 * Bottom sheet with two actions: join a channel or start a query. Network selection is a dropdown
 * (auto-selected when there is a single network). Emits the chosen network id + input; the caller
 * routes to [ConnectionManager.joinChannel] / [ConnectionManager.ensureQueryBuffer].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewConversationSheet(
    networks: List<NetworkEntity>,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onJoinChannel: (networkId: Long, channel: String) -> Unit,
    onMessageUser: (networkId: Long, nick: String) -> Unit,
    // Round 5 (plans/16 §3.5): seed the network from the active scope + browse entry.
    preselectedNetworkId: Long? = null,
    onBrowseChannels: (networkId: Long) -> Unit = {},
    // IRC-gateway join (Biboumi): discovered gateways + recently-used servers per network, and the
    // load/persist hooks the ViewModel supplies.
    gatewaysByNetwork: Map<Long, List<String>> = emptyMap(),
    recentServersByNetwork: Map<Long, List<String>> = emptyMap(),
    onPrepareGatewayJoin: (networkId: Long) -> Unit = {},
    onPersistIrcServer: (networkId: Long, server: String) -> Unit = { _, _ -> },
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("new_conversation_sheet"),
    ) {
        NewConversationSheetContent(
            networks = networks,
            preselectedNetworkId = preselectedNetworkId,
            onJoinChannel = onJoinChannel,
            onMessageUser = onMessageUser,
            onBrowseChannels = onBrowseChannels,
            gatewaysByNetwork = gatewaysByNetwork,
            recentServersByNetwork = recentServersByNetwork,
            onPrepareGatewayJoin = onPrepareGatewayJoin,
            onPersistIrcServer = onPersistIrcServer,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewConversationSheetContent(
    networks: List<NetworkEntity>,
    onJoinChannel: (networkId: Long, channel: String) -> Unit,
    onMessageUser: (networkId: Long, nick: String) -> Unit,
    preselectedNetworkId: Long? = null,
    onBrowseChannels: (networkId: Long) -> Unit = {},
    gatewaysByNetwork: Map<Long, List<String>> = emptyMap(),
    recentServersByNetwork: Map<Long, List<String>> = emptyMap(),
    onPrepareGatewayJoin: (networkId: Long) -> Unit = {},
    onPersistIrcServer: (networkId: Long, server: String) -> Unit = { _, _ -> },
) {
    var tab by remember { mutableIntStateOf(0) }
    // Joining channels / messaging users only works on bound child networks or direct networks;
    // the soju BOUNCER_ROOT is just the control connection, so exclude it from selection (a JOIN
    // there silently does nothing). Browse was already gated out for the same reason.
    val selectableNetworks = remember(networks) {
        networks.filter { it.role != NetworkRole.BOUNCER_ROOT }
    }
    var selectedNetwork by remember(selectableNetworks) {
        mutableStateOf(
            selectableNetworks.firstOrNull { it.id == preselectedNetworkId }
                ?: selectableNetworks.firstOrNull(),
        )
    }
    var input by remember { mutableStateOf("") }
    // IRC-gateway join sub-mode (Biboumi): only offered on the join tab of an XMPP network that has
    // a discovered gateway. Server/channel are edited separately from [input] so switching modes
    // never smears a half-typed value across the two shapes.
    var ircMode by remember { mutableStateOf(false) }
    var ircServer by remember { mutableStateOf("") }
    var ircChannel by remember { mutableStateOf("") }

    val isXmppNetwork = selectedNetwork?.protocol == Protocol.XMPP
    val gateways = selectedNetwork?.let { gatewaysByNetwork[it.id] }.orEmpty()
    val recentServers = selectedNetwork?.let { recentServersByNetwork[it.id] }.orEmpty()
    val gatewayJoinAvailable = tab == 0 && isXmppNetwork && gateways.isNotEmpty()
    val ircJoinActive = gatewayJoinAvailable && ircMode
    var selectedGateway by remember(gateways) { mutableStateOf(gateways.firstOrNull()) }

    // Discover gateways/recents for the target network as soon as it (or the tab) changes, so the
    // segmented control can appear without the user doing anything. Idempotent + cached upstream.
    LaunchedEffect(selectedNetwork?.id, tab) {
        selectedNetwork?.takeIf { it.protocol == Protocol.XMPP }?.let { onPrepareGatewayJoin(it.id) }
    }

    val trimmedInput = input.trim()
    // Room/user JIDs have no channel-style prefix; only the message-user JID is validated so a
    // malformed address cannot be dispatched as a nick (Task 9).
    val inputValid = if (ircJoinActive) {
        ircServer.trim().isNotEmpty() && ircChannel.trim().isNotEmpty() && selectedGateway != null
    } else {
        trimmedInput.isNotEmpty() && (tab == 0 || !isXmppNetwork || isValidJid(trimmedInput))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
            .testTag("new_conversation_content"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0; input = "" },
                text = { Text(stringResource(R.string.new_sheet_join_channel)) },
                modifier = Modifier.testTag("new_conversation_join_tab"),
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1; input = "" },
                text = { Text(stringResource(R.string.new_sheet_message_user)) },
                modifier = Modifier.testTag("new_conversation_message_tab"),
            )
        }

        NetworkDropdown(
            networks = selectableNetworks,
            selected = selectedNetwork,
            onSelect = { selectedNetwork = it },
        )

        // XMPP room vs IRC channel toggle — only when the selected XMPP network exposes a gateway.
        if (gatewayJoinAvailable) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !ircMode,
                    onClick = { ircMode = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    modifier = Modifier.testTag("new_conversation_mode_xmpp"),
                ) { Text(stringResource(R.string.new_sheet_mode_xmpp_room)) }
                SegmentedButton(
                    selected = ircMode,
                    onClick = { ircMode = true },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    modifier = Modifier.testTag("new_conversation_mode_irc"),
                ) { Text(stringResource(R.string.new_sheet_mode_irc_channel)) }
            }
        }

        if (ircJoinActive) {
            IrcServerDropdown(
                server = ircServer,
                options = ircServerOptions(recentServers),
                onServerChange = { ircServer = it },
            )
            OutlinedTextField(
                value = ircChannel,
                onValueChange = { ircChannel = it },
                singleLine = true,
                prefix = { Text(stringResource(R.string.new_sheet_channel_prefix)) },
                label = { Text(stringResource(R.string.new_sheet_irc_channel_hint)) },
                modifier = Modifier.fillMaxWidth().testTag("new_conversation_irc_channel"),
            )
            // A single gateway is the common case; only surface a picker when there is a choice.
            if (gateways.size > 1) {
                GatewayDropdown(
                    gateways = gateways,
                    selected = selectedGateway,
                    onSelect = { selectedGateway = it },
                )
            }
        } else {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("new_conversation_input"),
                prefix = if (tab == 0 && !isXmppNetwork) {
                    { Text(stringResource(R.string.new_sheet_channel_prefix)) }
                } else {
                    null
                },
                label = {
                    Text(
                        stringResource(
                            if (isXmppNetwork) {
                                if (tab == 0) R.string.new_sheet_room_jid_hint else R.string.new_sheet_jid_hint
                            } else {
                                if (tab == 0) R.string.new_sheet_channel_hint else R.string.new_sheet_nick_hint
                            },
                        ),
                    )
                },
            )
        }

        Button(
            onClick = {
                val net = selectedNetwork ?: return@Button
                if (ircJoinActive) {
                    val gateway = selectedGateway ?: return@Button
                    val server = ircServer.trim()
                    val channel = ircChannel.trim()
                    if (server.isEmpty() || channel.isEmpty()) return@Button
                    onJoinChannel(net.id, composeGatewayJoinTarget(server, channel, gateway))
                    onPersistIrcServer(net.id, server)
                    return@Button
                }
                val value = input.trim()
                if (value.isEmpty()) return@Button
                if (tab == 0) {
                    onJoinChannel(net.id, joinTarget(net, value))
                } else {
                    if (net.protocol == Protocol.XMPP && !isValidJid(value)) return@Button
                    onMessageUser(net.id, value)
                }
            },
            enabled = selectedNetwork != null && inputValid,
            modifier = Modifier.fillMaxWidth().testTag("new_conversation_submit"),
        ) {
            Text(
                stringResource(if (tab == 0) R.string.new_sheet_join else R.string.new_sheet_message),
            )
        }

        // Keep this action slot on both tabs. Without it, hiding Browse for direct messages also
        // removes the Column spacing before it and makes the bottom sheet visibly jump in height.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("new_conversation_action_slot"),
            contentAlignment = Alignment.Center,
        ) {
            // Browse: LIST is meaningless on the unbound soju root, so gate BOUNCER_ROOT out.
            if (tab == 0) {
                val net = selectedNetwork
                TextButton(
                    onClick = { net?.let { onBrowseChannels(it.id) } },
                    enabled = net != null && net.role != NetworkRole.BOUNCER_ROOT,
                    modifier = Modifier.fillMaxWidth().testTag("new_conversation_browse"),
                ) {
                    Text(stringResource(R.string.new_sheet_browse))
                }
            }
        }
    }
}

internal fun channelJoinTarget(channelName: String): String = "#${channelName.trim()}"

/** Seed servers for the IRC-gateway join dropdown; free-text entry is still allowed on top of these. */
internal val DEFAULT_IRC_SERVERS = listOf("irc.libera.chat", "irc.oftc.net")

/**
 * Server options for the IRC-gateway dropdown: recently-used servers first (already most-recent
 * first), then the built-in defaults, de-duplicated case-insensitively so a remembered default is
 * not listed twice.
 */
internal fun ircServerOptions(recentServers: List<String>): List<String> {
    val seen = mutableSetOf<String>()
    val result = mutableListOf<String>()
    for (candidate in recentServers + DEFAULT_IRC_SERVERS) {
        val trimmed = candidate.trim()
        if (trimmed.isEmpty()) continue
        if (seen.add(trimmed.lowercase())) result += trimmed
    }
    return result
}

/**
 * Compose a Biboumi gateway join target `<#channel>%<server>@<gateway>` (e.g.
 * `#systemcrafters%irc.libera.chat@irc.xmpp.glvortex.net`). The channel gets a leading '#' if the
 * user omitted it; server/channel are trimmed. Tested like [joinTarget].
 */
internal fun composeGatewayJoinTarget(server: String, channel: String, gateway: String): String {
    val trimmedChannel = channel.trim()
    val name = if (trimmedChannel.startsWith("#")) trimmedChannel else "#$trimmedChannel"
    return "$name%${server.trim()}@$gateway"
}

/**
 * Join target for [net]: IRC keeps the `#`-prefix convention via [channelJoinTarget]; XMPP MUC
 * rooms are addressed by a bare room JID. A full room JID (contains `@`) is used as-is; a bare
 * room name expands to the account's conventional conference service —
 * `name@conference.<account domain>` — so joining "motd" on `user@example.net` targets
 * `motd@conference.example.net` without the user typing the service host.
 */
/**
 * Picker label with an explicit protocol tag, so "which of these speaks IRC vs XMPP" never has
 * to be guessed from the network name alone.
 */
internal fun networkPickerLabel(net: NetworkEntity): String =
    "${net.name} · ${if (net.protocol == Protocol.XMPP) "XMPP" else "IRC"}"

internal fun joinTarget(net: NetworkEntity, value: String): String {
    if (net.protocol != Protocol.XMPP) return channelJoinTarget(value)
    // JIDs are case-normalized to lowercase everywhere in the XMPP pipeline, so both branches
    // lowercase; a full JID is otherwise passed through, and the IRC-style '#' prefix is only
    // stripped from bare names.
    val trimmed = value.trim().lowercase()
    if (trimmed.isEmpty() || '@' in trimmed) return trimmed
    val name = trimmed.removePrefix("#")
    if (name.isEmpty()) return name
    // substringBefore('/') defends against a resource-suffixed stored JID (me@example.net/phone).
    val accountDomain = net.jid?.substringAfter('@', "")?.substringBefore('/').orEmpty()
        .ifEmpty { net.host }
    return "$name@conference.$accountDomain"
}

/**
 * Minimal client-side JID shape check (local@domain[/resource]): rejects obviously malformed
 * addresses before dispatching a message-user request on an XMPP network (Task 9). Full JID
 * validation (XEP-0106 escaping, Unicode nodeprep) is left to the server.
 */
internal fun isValidJid(value: String): Boolean {
    val withoutResource = value.trim().substringBefore('/')
    val at = withoutResource.indexOf('@')
    if (at <= 0 || at == withoutResource.length - 1) return false
    val domain = withoutResource.substring(at + 1)
    return domain.contains('.') && !domain.startsWith('.') && !domain.endsWith('.')
}

@Composable
private fun NetworkDropdown(
    networks: List<NetworkEntity>,
    selected: NetworkEntity?,
    onSelect: (NetworkEntity) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = networks.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = selected?.let(::networkPickerLabel) ?: stringResource(R.string.new_sheet_network))
                Spacer(Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            networks.forEach { net ->
                DropdownMenuItem(
                    text = { Text(networkPickerLabel(net)) },
                    onClick = {
                        onSelect(net)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * Editable exposed dropdown for the IRC server: seeded with [options] (defaults + recents) but
 * accepting free-text so a server not in the list can still be typed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IrcServerDropdown(
    server: String,
    options: List<String>,
    onServerChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = server,
            onValueChange = { onServerChange(it); expanded = true },
            singleLine = true,
            label = { Text(stringResource(R.string.new_sheet_irc_server_hint)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                .fillMaxWidth()
                .testTag("new_conversation_irc_server"),
        )
        val filtered = options.filter { it.contains(server.trim(), ignoreCase = true) }
        if (filtered.isNotEmpty()) {
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                filtered.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = { onServerChange(option); expanded = false },
                    )
                }
            }
        }
    }
}

/** Gateway picker, shown only when a network exposes more than one IRC gateway component. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GatewayDropdown(
    gateways: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.orEmpty(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.new_sheet_irc_gateway_hint)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
                .testTag("new_conversation_irc_gateway"),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            gateways.forEach { gateway ->
                DropdownMenuItem(
                    text = { Text(gateway) },
                    onClick = { onSelect(gateway); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun NewConversationSheetPreview() {
    MotdTheme {
        NewConversationSheetContent(
            networks = listOf(
                NetworkEntity(
                    id = 1, name = "Libera", role = NetworkRole.DIRECT,
                    host = "irc.libera.chat", port = 6697,
                    nick = "me", username = "me", realname = "Me",
                ),
            ),
            onJoinChannel = { _, _ -> },
            onMessageUser = { _, _ -> },
        )
    }
}
