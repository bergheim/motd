package io.github.trevarj.motd.ui.firehose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.FirehoseRow
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.ui.components.EmptyState

/** ACTION rows read as "* nick text"; everything else as "nick: text". Pure so it is testable. */
fun firehoseBody(
    sender: String,
    text: String,
    kind: MessageKind,
): String = if (kind == MessageKind.ACTION) "* $sender $text" else "$sender: $text"

/** Read-only merged stream of conversation lines from every channel and DM, newest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirehoseScreen(
    onBack: () -> Unit = {},
    onOpenMessage: (bufferId: Long, eventId: Long, serverTime: Long) -> Unit = { _, _, _ -> },
    viewModel: FirehoseViewModel = hiltViewModel(),
) {
    val rows = viewModel.items.collectAsLazyPagingItems()
    val showNetwork by viewModel.showNetwork.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                title = { Text(stringResource(R.string.firehose_title)) },
            )
        },
    ) { padding ->
        FirehoseContent(
            rows = rows,
            showNetwork = showNetwork,
            onOpenMessage = onOpenMessage,
            modifier = Modifier.padding(padding),
        )
    }
}

/** The stream's three states: a failed refresh, a settled empty stream, and the lines themselves. */
@Composable
internal fun FirehoseContent(
    rows: LazyPagingItems<FirehoseRow>,
    showNetwork: Boolean,
    onOpenMessage: (bufferId: Long, eventId: Long, serverTime: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val refresh = rows.loadState.refresh
    when {
        // Only with nothing to show. Lines already on screen stay there: a Room-backed refresh
        // rarely fails at all, and when it does, keeping what was read beats blanking the stream.
        refresh is LoadState.Error && rows.itemCount == 0 -> {
            EmptyState(
                icon = Icons.Outlined.CloudOff,
                title = stringResource(R.string.firehose_error_title),
                message = stringResource(R.string.firehose_error_message),
                modifier = modifier,
                actionLabel = stringResource(R.string.firehose_retry),
                onAction = rows::retry,
            )
        }

        // Held back until the first page has settled, so the empty state never flashes over a
        // stream that is about to arrive.
        rows.itemCount == 0 && refresh is LoadState.NotLoading -> {
            EmptyState(
                icon = Icons.Outlined.Bolt,
                title = stringResource(R.string.firehose_empty_title),
                message = stringResource(R.string.firehose_empty_message),
                modifier = modifier,
                // An empty list, like search results and the chat list.
                ghostRows = true,
            )
        }

        else -> {
            FirehoseList(
                rows = rows,
                showNetwork = showNetwork,
                onOpenMessage = onOpenMessage,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun FirehoseList(
    rows: LazyPagingItems<FirehoseRow>,
    showNetwork: Boolean,
    onOpenMessage: (bufferId: Long, eventId: Long, serverTime: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(
            count = rows.itemCount,
            // The canonical row id: stable across invalidation, unlike a merged-stream position.
            key = rows.itemKey { it.message.id },
        ) { index ->
            rows[index]?.let { row ->
                FirehoseLineRow(row = row, showNetwork = showNetwork, onOpenMessage = onOpenMessage)
            }
        }
    }
}

@Composable
private fun FirehoseLineRow(
    row: FirehoseRow,
    showNetwork: Boolean,
    onOpenMessage: (bufferId: Long, eventId: Long, serverTime: Long) -> Unit,
) {
    val message = row.message
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onOpenMessage(message.bufferId, message.id, message.serverTime) }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(0.32f)) {
            Text(
                text = row.bufferDisplayName,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Only with more than one network: otherwise the room name already says everything.
            if (showNetwork) {
                Text(
                    text = row.networkName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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
