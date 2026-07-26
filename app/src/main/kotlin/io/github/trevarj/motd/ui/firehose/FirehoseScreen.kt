package io.github.trevarj.motd.ui.firehose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.FirehoseRow
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.ui.components.EmptyState

/** ACTION rows read as "* nick text"; everything else as "nick: text". Pure so it is testable. */
fun firehoseBody(sender: String, text: String, kind: MessageKind): String =
    if (kind == MessageKind.ACTION) "* $sender $text" else "$sender: $text"

/** Read-only merged stream of conversation lines across every network, newest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirehoseScreen(
    onBack: () -> Unit = {},
    onOpenMessage: (bufferId: Long, eventId: Long, serverTime: Long) -> Unit = { _, _, _ -> },
    viewModel: FirehoseViewModel = hiltViewModel(),
) {
    val rows = viewModel.items.collectAsLazyPagingItems()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.firehose_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        // Only show the empty state once the initial load has settled, so it never flashes while
        // the first page is still loading.
        if (rows.itemCount == 0 && rows.loadState.refresh is LoadState.NotLoading) {
            EmptyState(
                icon = Icons.Outlined.Bolt,
                title = stringResource(R.string.firehose_empty_title),
                message = stringResource(R.string.firehose_empty_message),
                modifier = Modifier.padding(padding),
            )
        } else {
            FirehoseList(rows = rows, onOpenMessage = onOpenMessage, modifier = Modifier.padding(padding))
        }
    }
}

@Composable
private fun FirehoseList(
    rows: LazyPagingItems<FirehoseRow>,
    onOpenMessage: (bufferId: Long, eventId: Long, serverTime: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(
            count = rows.itemCount,
            key = rows.itemKey { it.message.id },
        ) { index ->
            val row = rows[index] ?: return@items
            FirehoseLineRow(row = row, onOpenMessage = onOpenMessage)
        }
    }
}

@Composable
private fun FirehoseLineRow(
    row: FirehoseRow,
    onOpenMessage: (bufferId: Long, eventId: Long, serverTime: Long) -> Unit,
) {
    val message = row.message
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenMessage(message.bufferId, message.id, message.serverTime) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = row.bufferDisplayName,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.32f),
        )
        Text(
            text = firehoseBody(message.sender, message.text, message.kind),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
