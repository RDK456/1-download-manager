package com.downloadhub.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.data.model.AudioFormat
import com.downloadhub.app.data.model.DownloadSource
import com.downloadhub.app.data.model.DownloadStatus
import com.downloadhub.app.data.model.MediaQuality
import com.downloadhub.app.data.model.label

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadDetailsSheet(
    item: DownloadEntity,
    onDismiss: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onOpenWith: () -> Unit,
    onShare: () -> Unit,
    // A torrent's trackers and peers, and qBittorrent's two force actions.
    trackersOf: () -> List<com.downloadhub.core.TrackerRow> = { emptyList() },
    peersOf: () -> List<com.downloadhub.core.PeerRow> = { emptyList() },
    contentOf: () -> TorrentContentView? = { null },
    onFilesWanted: (List<Int>, Boolean) -> Unit = { _, _ -> },
    onForceRecheck: () -> Unit = {},
    onForceReannounce: () -> Unit = {},
    // Which queue it is in, and what an ordinary link sends with its requests.
    queues: List<com.downloadhub.app.data.AppQueue> = emptyList(),
    onMoveToQueue: (String) -> Unit = {},
    onSaveRequest: (com.downloadhub.core.HttpRequestOptions) -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(item.fileName, style = MaterialTheme.typography.titleLarge)
            Text(
                // A finished download states its status in the Completed block
                // below, so the header only shows where it came from.
                if (item.status == DownloadStatus.COMPLETED) {
                    sourceLabel(item.source)
                } else {
    "${sourceLabel(item.source)} \u00B7 ${statusLabel(item.status)}"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            if (item.source == com.downloadhub.app.data.model.DownloadSource.TORRENT) {
                TorrentExtras(item.id, contentOf, trackersOf, peersOf, onFilesWanted, onForceRecheck, onForceReannounce)
            }
            if (item.status != DownloadStatus.COMPLETED) {
                QueueChips(item.queueId, queues, onMoveToQueue)
            }
            if (item.source == com.downloadhub.app.data.model.DownloadSource.HTTP && item.status != DownloadStatus.COMPLETED) {
                RequestSection(item, onSaveRequest)
            }
            if (item.status == DownloadStatus.COMPLETED) {
                // Finished downloads show a state instead of a progress bar.
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF168A57).copy(alpha = 0.12f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF168A57),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Completed",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF168A57)
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            formatBytes(item.bytesDownloaded),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LinearProgressIndicator(
                    progress = progressFor(item),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(progressLabel(item), style = MaterialTheme.typography.labelLarge)
                    Text(
                        if (item.totalBytes > 0) "${formatBytes(item.bytesDownloaded)} / ${formatBytes(item.totalBytes)}" else progressLabel(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider()
            DetailRow("Source", sourceLabel(item.source))
            DetailRow("Category", item.category.label)
            if (item.source == DownloadSource.YOUTUBE) {
                val quality = MediaQuality.fromValue(item.quality)
                DetailRow(
                    "Quality",
                    if (quality.isAudioOnly) {
    "Audio only \u00B7 ${AudioFormat.fromValue(item.audioFormat).label}"
                    } else {
                        quality.label
                    }
                )
            }
            item.mimeType?.let { DetailRow("Format", it) }
            item.outputPath?.let { DetailRow("Location", it, maxLines = 2) }
            if (item.status == DownloadStatus.FAILED) {
                Text(
                    item.errorMessage ?: "The download could not be completed.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (item.status.canPauseDetails) {
                    OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Pause, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Pause")
                    }
                }
                if (item.status == DownloadStatus.PAUSED) {
                    Button(onClick = onResume, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Resume")
                    }
                }
                if (item.status == DownloadStatus.FAILED) {
                    Button(onClick = onRetry, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Retry")
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpen,
                    enabled = item.status == DownloadStatus.COMPLETED,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Open")
                }
                OutlinedButton(
                    onClick = onShare,
                    enabled = item.status == DownloadStatus.COMPLETED,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Share")
                }
            }
            TextButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Delete download", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete download?") },
            text = { Text("The downloaded file and its queue entry will be removed.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String, maxLines: Int = 1) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

private val DownloadStatus.canPauseDetails: Boolean
    get() = this == DownloadStatus.QUEUED || this == DownloadStatus.RESOLVING || this == DownloadStatus.RUNNING
