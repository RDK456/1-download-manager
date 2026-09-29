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
import java.io.File
import java.util.Locale

@Composable
fun SettingsDialog(
    settings: DesktopSettings,
    ytDlp: String,
    captureActive: Boolean,
    capturePort: Int,
    onDismiss: () -> Unit,
    onSave: (DesktopSettings) -> Unit,
    onChooseFolder: () -> File?,
    onChooseCacheFolder: () -> File? = onChooseFolder,
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
    // Shown as the folder itself rather than "the app's own folder", because a user
    // looking at this wants to know where the bytes are going.
    var cache by remember { mutableStateOf(settings.cacheDir) }
    var deleteCache by remember { mutableStateOf(settings.deleteCacheWhenRemoved) }

    AlertDialog(
        onDismissRequest = onDismiss,
            properties = APP_DIALOG_PROPERTIES,
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
                Spacer(Modifier.height(14.dp))
                Text(
                    "Temporary files",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "A download is written here first and moved to the download folder " +
                        "only when it is whole, so this is the folder that fills up during " +
                        "a transfer. Leave it empty to use the app's own folder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = cache,
                        onValueChange = { cache = it },
                        label = { Text("Cache folder") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onChooseCacheFolder()?.let { cache = it.absolutePath } }) {
                        Icon(DlmIcons.Folder, contentDescription = "Choose a cache folder")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TickBox(checked = deleteCache, onChange = { deleteCache = it })
                    Text(
                        "Delete the cache when an unfinished download is removed",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TickBox(checked = closeToTray, onChange = { closeToTray = it })
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
                        closeToTray = closeToTray,
                        // Trimmed: a trailing space in a path is a folder that does not exist.
                        cacheDir = cache.trim(),
                        deleteCacheWhenRemoved = deleteCache
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
/**
 * Opens a finished download's folder in Explorer, with the file selected.
 *
 * This is what the row's folder button does, and the icon says folder, so the
 * folder is what it opens. It used to hand the file to `java.awt.Desktop.open`,
 * which is two problems at once: the icon promises a folder and the code launched
 * whatever program handles the file, and the whole thing was wrapped in
 * `runCatching`, so on a machine where Desktop is unavailable it did nothing and
 * said nothing. Explorer is called directly and the answer is returned.
 *
 * Returns false rather than throwing, so the caller can tell the user instead of
 * leaving a button that appears broken.
 */
internal fun revealInFolder(path: String?): Boolean {
    val file = path?.let { File(it) } ?: return false
    // A finished download whose file was moved or deleted still has a folder worth
    // opening, so this does not give up when the file itself is gone.
    val folder = file.parentFile ?: return false
    if (!folder.isDirectory) return false
    val explorer = File("C:/Windows/explorer.exe")
    if (!explorer.isFile) return false
    return runCatching {
        val process = if (file.exists()) {
            // /select, is Explorer's own "show me this file" switch.
            ProcessBuilder(explorer.absolutePath, "/select,", file.absolutePath)
                .redirectErrorStream(true).start()
        } else {
            ProcessBuilder(explorer.absolutePath, folder.absolutePath)
                .redirectErrorStream(true).start()
        }
        // Explorer is a single instance: a second launch just hands over to the
        // running one, so the process is left to exit on its own.
        process
        true
    }.getOrDefault(false)
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
