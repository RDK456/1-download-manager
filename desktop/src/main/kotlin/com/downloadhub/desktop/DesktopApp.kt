package com.downloadhub.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.downloadhub.core.DownloadStatus
import java.awt.Desktop
import java.io.File
import java.util.Locale

/**
 * The Windows app.
 *
 * Same shape as the Android screens - two list tabs and a settings page - so the
 * two builds stay recognisable, but everything Android specific (foreground
 * service, SAF tree, WorkManager recovery) has no desktop equivalent and is
 * simply absent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadHubDesktopApp(
    state: DesktopUiState,
    actions: DesktopActions
) {
    var tab by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var exitConfirm by remember { mutableStateOf(false) }

    val visible = state.items.filter {
        val matchesTab = if (tab == 0) !it.isTorrent() else it.isTorrent()
        val matchesQuery = query.isBlank() ||
            it.fileName.contains(query, ignoreCase = true) ||
            it.url.contains(query, ignoreCase = true)
        matchesTab && matchesQuery
    }

    MaterialTheme(colorScheme = state.palette) {
        Scaffold { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(state.palette.background)
            ) {
                // Top bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "1 download manager",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(230.dp)
                    )
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, Modifier.size(18.dp))
                        },
                        placeholder = { Text("Search", fontSize = 13.sp) },
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add a download")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }

                TabRow(selectedTabIndex = tab) {
                    Tab(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        text = { Text("Downloads") }
                    )
                    Tab(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        text = { Text("Torrents") }
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${visible.size} shown",
                        style = MaterialTheme.typography.bodySmall,
                        color = state.palette.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    if (state.busyCount > 0) {
                        OutlinedButton(onClick = actions.pauseAll) {
                            Icon(Icons.Default.Pause, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Pause all")
                        }
                    } else {
                        OutlinedButton(onClick = actions.resumeAll) {
                            Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Resume all")
                        }
                    }
                }

                if (visible.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (query.isBlank()) "Nothing here yet. Use + to add a download." else "No match.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = state.palette.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 16.dp, end = 16.dp, bottom = 24.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(visible, key = { it.id }) { item ->
                            DownloadRow(item, state, actions)
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddDownloadDialog(
            onDismiss = { showAdd = false },
            onAdd = { link, audioOnly, format, height, playlist ->
                actions.addDownload(link, audioOnly, format, height, playlist)
                showAdd = false
            }
        )
    }

    if (showSettings) {
        SettingsDialog(
            settings = state.settings,
            ytDlp = state.ytDlpStatus,
            onDismiss = { showSettings = false },
            onSave = { updated ->
                actions.updateSettings(updated)
                showSettings = false
            },
            onChooseFolder = actions.chooseFolder
        )
    }

    if (exitConfirm) {
        AlertDialog(
            onDismissRequest = { exitConfirm = false },
            title = { Text("Close 1 download manager?") },
            text = {
                Text(
                    if (state.busyCount > 0) {
                        "${state.busyCount} transfer(s) are still running and will be cancelled."
                    } else {
                        "You can reopen it from the Start menu."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { exitConfirm = false; actions.quit() }) { Text("Close") }
            },
            dismissButton = {
                TextButton(onClick = { exitConfirm = false }) { Text("Keep open") }
            }
        )
    }
}

@Composable
private fun DownloadRow(
    item: QueuedDownload,
    state: DesktopUiState,
    actions: DesktopActions
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = state.palette.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    statusLine(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.status == DownloadStatus.FAILED) {
                        state.palette.error
                    } else {
                        state.palette.onSurfaceVariant
                    }
                )
                if (item.status == DownloadStatus.RUNNING) {
                    LinearProgressIndicator(
                        progress = {
                            if (item.totalBytes > 0) {
                                (item.bytesDownloaded.toFloat() / item.totalBytes).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    )
                }
            }

            IconButton(onClick = { openOutput(item.location) }) {
                Icon(Icons.Default.FolderOpen, contentDescription = "Open the file", Modifier.size(18.dp))
            }
            when (item.status) {
                DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.RESOLVING ->
                    IconButton(onClick = { actions.pause(item.id) }) {
                        Icon(Icons.Default.Pause, contentDescription = "Pause", Modifier.size(18.dp))
                    }

                DownloadStatus.PAUSED ->
                    IconButton(onClick = { actions.resume(item.id) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Resume", Modifier.size(18.dp))
                    }

                DownloadStatus.FAILED ->
                    IconButton(onClick = { actions.retry(item.id) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retry", Modifier.size(18.dp))
                    }

                DownloadStatus.COMPLETED -> Unit
            }
            IconButton(onClick = { actions.remove(item.id) }) {
                Icon(Icons.Default.Delete, contentDescription = "Remove", Modifier.size(18.dp))
            }
        }
    }
}

private fun statusLine(item: QueuedDownload): String {
    val size = "${formatBytes(item.bytesDownloaded)} of ${formatBytes(item.totalBytes)}"
    return when (item.status) {
        DownloadStatus.QUEUED -> "Waiting in the queue"
        DownloadStatus.RESOLVING -> "Working out the link"
        DownloadStatus.RUNNING -> if (item.totalBytes > 0) {
            "$size - ${item.speedBytesPerSecond.asSpeed()}"
        } else {
            "$size downloaded"
        }

        DownloadStatus.PAUSED -> "Paused - $size"
        DownloadStatus.COMPLETED -> "Completed - ${formatBytes(item.bytesDownloaded)}"
        DownloadStatus.FAILED -> item.errorMessage ?: "Failed"
    }
}

@Composable
private fun AddDownloadDialog(
    onDismiss: () -> Unit,
    onAdd: (String, Boolean, String, Int?, Boolean) -> Unit
) {
    var link by remember { mutableStateOf("") }
    var audioOnly by remember { mutableStateOf(false) }
    var format by remember { mutableStateOf("m4a") }
    var height by remember { mutableStateOf("1080") }
    var playlist by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a download") },
        text = {
            Column {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text("Link, magnet or .torrent URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("AUDIO", "720", "1080", "2160", "best").forEach { preset ->
                        val selected = if (preset == "AUDIO") audioOnly else (!audioOnly && height == preset)
                        FilterChip(
                            selected = selected,
                            onClick = {
                                if (preset == "AUDIO") audioOnly = !audioOnly
                                else {
                                    audioOnly = false
                                    height = preset
                                }
                            },
                            label = { Text(if (preset == "AUDIO") "Audio only" else preset, fontSize = 12.sp) }
                        )
                    }
                }
                if (audioOnly) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("m4a", "mp3", "opus", "wav").forEach { option ->
                            FilterChip(
                                selected = format == option,
                                onClick = { format = option },
                                label = { Text(option, fontSize = 12.sp) }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = playlist, onCheckedChange = { playlist = it })
                    Text("Whole playlist (YouTube)", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsed = height.toIntOrNull()
                    onAdd(
                        link.trim(),
                        audioOnly,
                        format,
                        if (audioOnly) null else parsed,
                        playlist
                    )
                },
                enabled = link.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SettingsDialog(
    settings: DesktopSettings,
    ytDlp: String,
    onDismiss: () -> Unit,
    onSave: (DesktopSettings) -> Unit,
    onChooseFolder: () -> File?
) {
    var folder by remember { mutableStateOf(settings.downloadDir) }
    var concurrent by remember { mutableStateOf(settings.maxConcurrent.toString()) }
    var speed by remember { mutableStateOf(settings.speedLimitBytesPerSecond.toString()) }
    var retries by remember { mutableStateOf(settings.maxRetries.toString()) }
    var closeToTray by remember { mutableStateOf(settings.closeToTray) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = folder,
                        onValueChange = { folder = it },
                        label = { Text("Download folder") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onChooseFolder()?.let { folder = it.absolutePath } }) {
                        Icon(Icons.Default.Folder, contentDescription = "Choose a folder")
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = concurrent,
                    onValueChange = { concurrent = it.filter(Char::isDigit) },
                    label = { Text("Downloads at once (1-8)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = speed,
                    onValueChange = { speed = it.filter(Char::isDigit) },
                    label = { Text("Speed limit in KB/s (0 = unlimited)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = retries,
                    onValueChange = { retries = it.filter(Char::isDigit) },
                    label = { Text("Automatic retries (0-5)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = closeToTray, onCheckedChange = { closeToTray = it })
                    Text("Close to the system tray", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    ytDlp,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    settings.copy(
                        downloadDir = folder,
                        maxConcurrent = concurrent.toIntOrNull()?.coerceIn(1, 8) ?: 3,
                        speedLimitBytesPerSecond = (speed.toLongOrNull() ?: 0L) * 1024L,
                        maxRetries = retries.toIntOrNull()?.coerceIn(0, 5) ?: 2,
                        closeToTray = closeToTray
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// --- helpers ----------------------------------------------------------------

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
    else -> "$bytes B"
}

private fun Long.asSpeed(): String = if (this <= 0) {
    "starting"
} else {
    "${formatBytes(this)}/s"
}

private fun QueuedDownload.isTorrent() = source == com.downloadhub.core.DownloadSource.TORRENT

/** Opens the finished file, or reveals it in Explorer when that fails. */
internal fun openOutput(path: String?) {
    val file = path?.let { File(it) } ?: return
    if (!file.exists()) return
    runCatching {
        val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
        if (desktop != null && desktop.isSupported(Desktop.Action.OPEN)) desktop.open(file)
        else if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) desktop.browse(file.parentFile.toURI())
    }
}
