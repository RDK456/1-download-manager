package com.downloadhub.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.data.model.DownloadCategory
import com.downloadhub.app.data.model.DownloadSource
import com.downloadhub.app.data.model.DownloadStatus
import com.downloadhub.app.data.model.label

@Composable
fun DownloadCard(
    item: DownloadEntity,
    onClick: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val progress = progressFor(item)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(46.dp),
                    shape = MaterialTheme.shapes.small,
                    color = categoryColor(item.category).copy(alpha = 0.16f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = iconFor(item),
                            contentDescription = null,
                            tint = categoryColor(item.category),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.size(2.dp))
                    Text(
                        "${item.category.label} • ${statusLabel(item.status)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor(item.status),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                when {
                    item.status.canPauseUi -> IconButton(onClick = onPause) {
                        Icon(Icons.Default.Pause, contentDescription = "Pause download")
                    }
                    item.status == DownloadStatus.PAUSED -> IconButton(onClick = onResume) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Resume download")
                    }
                    item.status == DownloadStatus.FAILED -> IconButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retry download")
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More actions")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (item.status.canPauseUi) {
                            DropdownMenuItem(
                                text = { Text("Pause") },
                                onClick = { menuOpen = false; onPause() },
                                leadingIcon = { Icon(Icons.Default.Pause, contentDescription = null) }
                            )
                        }
                        if (item.status == DownloadStatus.PAUSED) {
                            DropdownMenuItem(
                                text = { Text("Resume") },
                                onClick = { menuOpen = false; onResume() },
                                leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) }
                            )
                        }
                        if (item.status == DownloadStatus.FAILED) {
                            DropdownMenuItem(
                                text = { Text("Retry") },
                                onClick = { menuOpen = false; onRetry() },
                                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Details") },
                            onClick = { menuOpen = false; onClick() },
                            leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = { menuOpen = false; onDelete() },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) }
                        )
                    }
                }
            }
            Spacer(Modifier.size(11.dp))
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = statusColor(item.status),
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(Modifier.size(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    progressLabel(item),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    progressDetail(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun progressDetail(item: DownloadEntity): String = when (item.status) {
    DownloadStatus.RUNNING -> buildString {
        if (item.speedBytesPerSecond > 0) append(formatSpeed(item.speedBytesPerSecond))
        if (item.etaSeconds >= 0) {
            if (isNotEmpty()) append(" • ")
            append(formatEta(item.etaSeconds))
        }
        if (isEmpty()) append(formatBytes(item.bytesDownloaded))
    }
    DownloadStatus.RESOLVING -> "Finding the best stream"
    DownloadStatus.QUEUED -> "Waiting in queue"
    DownloadStatus.PAUSED -> "${progressLabel(item)} paused"
    DownloadStatus.FAILED -> item.errorMessage ?: "Retry available"
    DownloadStatus.COMPLETED -> formatBytes(item.bytesDownloaded)
}

fun sourceLabel(source: DownloadSource): String = when (source) {
    DownloadSource.HTTP -> "Direct file"
    DownloadSource.YOUTUBE -> "YouTube"
    DownloadSource.TORRENT -> "Torrent"
}

private fun iconFor(item: DownloadEntity): ImageVector = when {
    item.source == DownloadSource.TORRENT -> Icons.Default.Folder
    else -> when (item.category) {
        DownloadCategory.VIDEO -> Icons.Default.Movie
        DownloadCategory.AUDIO -> Icons.Default.MusicNote
        DownloadCategory.IMAGE -> Icons.Default.Image
        DownloadCategory.COMPRESSED, DownloadCategory.ARCHIVE -> Icons.Default.Archive
        DownloadCategory.DOCUMENT -> Icons.Default.Description
        DownloadCategory.PROGRAM -> Icons.Default.Apps
        DownloadCategory.FILE, DownloadCategory.OTHER -> Icons.Default.InsertDriveFile
    }
}

private fun categoryColor(category: DownloadCategory): Color = when (category) {
    DownloadCategory.VIDEO -> Color(0xFF7C3AED)
    DownloadCategory.AUDIO -> Color(0xFF00796B)
    DownloadCategory.DOCUMENT -> Color(0xFF1769E0)
    DownloadCategory.COMPRESSED, DownloadCategory.ARCHIVE -> Color(0xFFB45309)
    DownloadCategory.IMAGE -> Color(0xFFBE185D)
    DownloadCategory.PROGRAM -> Color(0xFF0F766E)
    DownloadCategory.FILE -> Color(0xFF334155)
    DownloadCategory.OTHER -> Color(0xFF64748B)
}

@Composable
private fun statusColor(status: DownloadStatus): Color = when (status) {
    DownloadStatus.RUNNING -> MaterialTheme.colorScheme.primary
    DownloadStatus.RESOLVING -> MaterialTheme.colorScheme.tertiary
    DownloadStatus.QUEUED -> MaterialTheme.colorScheme.onSurfaceVariant
    DownloadStatus.PAUSED -> MaterialTheme.colorScheme.tertiary
    DownloadStatus.COMPLETED -> Color(0xFF168A57)
    DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
}

private val DownloadStatus.canPauseUi: Boolean
    get() = this == DownloadStatus.QUEUED || this == DownloadStatus.RESOLVING || this == DownloadStatus.RUNNING
