package com.downloadhub.app.ui.expressive.screen.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Trash2
import com.downloadhub.app.data.AppQueue
import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.data.model.DownloadSource
import com.downloadhub.app.data.model.DownloadStatus
import com.downloadhub.app.download.DownloadService
import com.downloadhub.app.ui.QueueChips
import com.downloadhub.app.ui.RequestSection
import com.downloadhub.app.ui.TorrentContentView
import com.downloadhub.app.ui.TorrentExtras
import com.downloadhub.app.ui.expressive.component.FileNameText
import com.downloadhub.app.ui.expressive.component.RowBar
import com.downloadhub.app.ui.expressive.model.PrimaryAction
import com.downloadhub.app.ui.expressive.model.RowConditions
import com.downloadhub.app.ui.expressive.model.facts
import com.downloadhub.app.ui.expressive.model.friendlyError
import com.downloadhub.app.ui.expressive.model.primaryActionFor
import com.downloadhub.app.ui.expressive.model.toRowModel
import com.downloadhub.app.ui.expressive.shape.StateShape
import com.downloadhub.app.ui.theme.Mono
import com.downloadhub.core.HttpRequestOptions
import com.downloadhub.core.PeerRow
import com.downloadhub.core.TrackerRow

/**
 * The details of one download in a bottom sheet: its state shape and full name, a bar,
 * the facts that are known, the reason if it failed, and the actions that make sense for
 * its status. Dragging the sheet hands the release velocity to the theme's spring.
 *
 * The torrent pieces (files, peers, trackers) and the per-link request options are the
 * existing ones; they take the new theme but are not rebuilt.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailsSheet(
    item: DownloadEntity,
    conditions: RowConditions,
    onDismiss: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onOpenWith: () -> Unit,
    onShare: () -> Unit,
    trackersOf: () -> List<TrackerRow>,
    peersOf: () -> List<PeerRow>,
    contentOf: () -> TorrentContentView?,
    onFilesWanted: (List<Int>, Boolean) -> Unit,
    onForceRecheck: () -> Unit,
    onForceReannounce: () -> Unit,
    queues: List<AppQueue>,
    onMoveToQueue: (String) -> Unit,
    onSaveRequest: (HttpRequestOptions) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmDelete by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val model = remember(item, conditions) { item.toRowModel(conditions) }
    val facts = remember(item) { item.facts() }
    val context = LocalContext.current
    val finished = item.status == DownloadStatus.COMPLETED

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = Modifier.navigationBarsPadding()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                StateShape(
                    kind = model.shape,
                    color = if (model.isError) scheme.errorContainer else scheme.primaryContainer,
                    modifier = Modifier.size(56.dp),
                    spin = false
                ) {}
                Column(Modifier.weight(1f)) {
                    SelectionContainer { FileNameText(item.fileName, style = MaterialTheme.typography.titleLargeEmphasized) }
                    Text(
                        listOf(model.headline, model.detail).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (model.isError) scheme.error else scheme.onSurfaceVariant
                    )
                }
            }

            if (model.bar != com.downloadhub.app.ui.expressive.model.BarStyle.NONE) RowBar(model.bar, model.progress)

            if (item.status == DownloadStatus.FAILED) {
                val error = friendlyError(item.errorMessage)
                Surface(shape = MaterialTheme.shapes.extraLarge, color = scheme.errorContainer, contentColor = scheme.onErrorContainer) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(error.short, style = MaterialTheme.typography.titleMediumEmphasized)
                        Text(error.detail, style = MaterialTheme.typography.bodyMedium)
                        if (error.raw != null && error.raw != error.detail) {
                            SelectionContainer { Text(error.raw, style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono)) }
                        }
                    }
                }
            }
            if (model.isWaiting) {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer) {
                    Text(
                        if (conditions.lowStorage) "Held because there is not enough space." else "Held until the phone is back online. It carries on by itself.",
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                facts.forEach { fact ->
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = scheme.surfaceContainerHigh,
                        modifier = if (fact.wide) Modifier.fillMaxWidth() else Modifier
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(fact.label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                            SelectionContainer {
                                Text(
                                    fact.value,
                                    style = MaterialTheme.typography.bodyMedium.let { if (fact.mono) it.copy(fontFamily = Mono) else it },
                                    maxLines = if (fact.wide) 3 else 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            if (item.source == DownloadSource.TORRENT) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Download in order", style = MaterialTheme.typography.titleSmallEmphasized)
                        Text(
                            "Fetch from the start so videos play while downloading. Can be slower overall.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = item.torrentSequential, onCheckedChange = { on -> DownloadService.requestSequential(context, item.id, on) })
                }
                TorrentExtras(item.id, contentOf, trackersOf, peersOf, onFilesWanted, onForceRecheck, onForceReannounce)
            }
            if (!finished) QueueChips(item.queueId, queues, onMoveToQueue)
            if (item.source == DownloadSource.HTTP && !finished) RequestSection(item, onSaveRequest)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (primaryActionFor(item.status)) {
                    PrimaryAction.PAUSE -> FilledTonalButton(onPause, Modifier.weight(1f)) {
                        Icon(Lucide.Pause, null); Spacer(Modifier.width(8.dp)); Text("Pause")
                    }
                    PrimaryAction.RESUME -> Button(onResume, Modifier.weight(1f)) {
                        Icon(Lucide.Play, null); Spacer(Modifier.width(8.dp)); Text("Resume")
                    }
                    PrimaryAction.RETRY -> Button(onRetry, Modifier.weight(1f)) {
                        Icon(Lucide.RotateCw, null); Spacer(Modifier.width(8.dp)); Text("Retry")
                    }
                    PrimaryAction.OPEN -> Button(onOpen, Modifier.weight(1f)) {
                        Icon(Lucide.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Open")
                    }
                }
                if (finished) {
                    OutlinedButton(onShare, Modifier.weight(1f)) {
                        Icon(Lucide.Share2, null); Spacer(Modifier.width(8.dp)); Text("Share")
                    }
                }
            }
            if (finished) TextButton(onOpenWith, Modifier.fillMaxWidth()) { Text("Open with another app") }
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Lucide.Trash2, null, tint = scheme.error)
                Spacer(Modifier.width(8.dp))
                Text("Delete", color = scheme.error)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this download?") },
            text = { Text("The file is removed from this phone too. You can undo for a few seconds.") },
            confirmButton = { TextButton({ confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Keep") } }
        )
    }
}
