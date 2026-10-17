package com.downloadhub.app.ui.expressive.screen.details

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.downloadhub.app.data.AppQueue
import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.ui.DownloadViewModel
import com.downloadhub.app.ui.expressive.model.RowConditions

/**
 * Wires the details view to the view model, as a bottom sheet or as a side pane. Both ask
 * for delete confirmation the same way and both hide the row for an undoable delete.
 */
@Composable
fun DetailsHost(
    item: DownloadEntity,
    viewModel: DownloadViewModel,
    queues: List<AppQueue>,
    conditions: RowConditions,
    asSheet: Boolean,
    modifier: Modifier = Modifier
) {
    if (asSheet) {
        DetailsSheet(
            item = item,
            conditions = conditions,
            onDismiss = { viewModel.select(null) },
            onPause = { viewModel.pause(item.id) },
            onResume = { viewModel.resume(item.id) },
            onRetry = { viewModel.retry(item.id) },
            onDelete = { viewModel.deleteWithUndo(listOf(item.id)) },
            onOpen = { viewModel.launchOpen(item, open = true) },
            onOpenWith = { viewModel.launchOpen(item, open = false) },
            onShare = { viewModel.launchShare(item) },
            trackersOf = { viewModel.torrentTrackers(item) },
            peersOf = { viewModel.torrentPeers(item) },
            contentOf = { viewModel.torrentContent(item) },
            onFilesWanted = { indices, wanted -> viewModel.setTorrentFilesWanted(item.id, indices, wanted) },
            onForceRecheck = { viewModel.forceRecheck(item) },
            onForceReannounce = { viewModel.forceReannounce(item) },
            queues = queues,
            onMoveToQueue = { viewModel.moveToQueue(item.id, it) },
            onSaveRequest = { viewModel.setItemRequest(item.id, it) }
        )
        return
    }
    var confirmDelete by remember(item.id) { mutableStateOf(false) }
    Surface(modifier.fillMaxHeight(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(Modifier.padding(start = 20.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Details", style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.weight(1f))
                IconButton({ viewModel.select(null) }) { Icon(Lucide.X, contentDescription = "Close details") }
            }
            DetailsBody(
                item = item,
                conditions = conditions,
                onPause = { viewModel.pause(item.id) },
                onResume = { viewModel.resume(item.id) },
                onRetry = { viewModel.retry(item.id) },
                onOpen = { viewModel.launchOpen(item, open = true) },
                onOpenWith = { viewModel.launchOpen(item, open = false) },
                onShare = { viewModel.launchShare(item) },
                trackersOf = { viewModel.torrentTrackers(item) },
                peersOf = { viewModel.torrentPeers(item) },
                contentOf = { viewModel.torrentContent(item) },
                onFilesWanted = { indices, wanted -> viewModel.setTorrentFilesWanted(item.id, indices, wanted) },
                onForceRecheck = { viewModel.forceRecheck(item) },
                onForceReannounce = { viewModel.forceReannounce(item) },
                queues = queues,
                onMoveToQueue = { viewModel.moveToQueue(item.id, it) },
                onSaveRequest = { viewModel.setItemRequest(item.id, it) },
                onAskDelete = { confirmDelete = true }
            )
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this download?") },
            text = { Text("The file is removed from this phone too. You can undo for a few seconds.") },
            confirmButton = { TextButton({ confirmDelete = false; viewModel.deleteWithUndo(listOf(item.id)) }) { Text("Delete") } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Keep") } }
        )
    }
}
