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
@Composable
fun AddDownloadDialog(
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
fun SettingsDialog(
    settings: DesktopSettings,
    ytDlp: String,
    captureActive: Boolean,
    capturePort: Int,
    onDismiss: () -> Unit,
    onSave: (DesktopSettings) -> Unit,
    onChooseFolder: () -> File?,
    onToggleCapture: (Boolean) -> Unit,
    extensionReady: Boolean,
    extensionPath: String,
    onOpenExtensionFolder: () -> Unit
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
                        Icon(DlmIcons.Folder, contentDescription = "Choose a folder")
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

                Spacer(Modifier.height(16.dp))
                Text(
                    "Browser downloads",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = settings.browserCaptureEnabled,
                        onCheckedChange = onToggleCapture
                    )
                    Text(
                        "Catch downloads from the browser",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    if (captureActive) {
                        "Listening on 127.0.0.1:$capturePort. Install the browser extension " +
                            "and paste the code below to pair it."
                    } else {
                        "Turn this on, then install the browser extension."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (settings.browserCaptureEnabled && settings.captureToken.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Pairing code",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SelectionContainerCompat(settings.captureToken)
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    "1. Install the extension",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "Open the folder, then in Chrome or Edge choose Extensions, turn on " +
                        "Developer mode, and pick that folder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SelectionContainerCompat(extensionPath)
                OutlinedButton(
                    onClick = onOpenExtensionFolder,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                ) {
                    Text(if (extensionReady) "Open extension folder" else "Extract and open folder")
                }

                Text(
                    "2. Paste the pairing code above into the extension",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 10.dp)
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

/** Read-only, selectable token so it can be copied into the extension popup. */
@Composable
private fun SelectionContainerCompat(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(10.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
        )
    }
}
