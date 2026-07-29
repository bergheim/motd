package io.github.trevarj.motd.ui.settings.addnetwork

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.github.trevarj.motd.R
import io.github.trevarj.motd.backend.ProtocolId
import io.github.trevarj.motd.ui.nav.ProtocolAccountUi
import io.github.trevarj.motd.ui.theme.MotdTheme

/**
 * Add-account protocol chooser (docs/backend-neutral-xmpp-rollout.md): reached only when more than
 * one backend is registered (see `AccountRoutingViewModel.createDestination`) — a single registered
 * backend goes straight to its own create flow and this screen is never shown. Purely a renderer of
 * [choices]: one row per registered [ProtocolAccountUi], no protocol switch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtocolPickerScreen(
    choices: List<ProtocolAccountUi>,
    onBack: () -> Unit,
    onChoose: (Any) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.protocol_picker_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.onboarding_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            items(choices, key = { it.protocol.value }) { choice ->
                ListItem(
                    headlineContent = { Text(stringResource(choice.labelRes)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .testTag("protocol_picker_row_${choice.protocol.value}")
                        .clickable { onChoose(choice.createRoute) },
                )
            }
        }
    }
}

@Preview
@Composable
private fun ProtocolPickerScreenPreview() {
    MotdTheme {
        ProtocolPickerScreen(
            choices = listOf(
                object : ProtocolAccountUi {
                    override val protocol = ProtocolId("irc")
                    override val labelRes = R.string.add_network_kind_network
                    override val createRoute: Any = Unit
                    override fun editRoute(networkId: Long): Any = Unit
                },
                object : ProtocolAccountUi {
                    override val protocol = ProtocolId("xmpp")
                    override val labelRes = R.string.protocol_xmpp_label
                    override val createRoute: Any = Unit
                    override fun editRoute(networkId: Long): Any = Unit
                },
            ),
            onBack = {},
            onChoose = {},
        )
    }
}
