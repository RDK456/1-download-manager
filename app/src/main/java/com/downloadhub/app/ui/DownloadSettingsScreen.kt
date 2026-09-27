package com.downloadhub.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.downloadhub.app.data.DownloadSettings

/**
 * Download settings: where files go, how many run at once, how fast, and how
 * failures are handled. Every control here is read live by the download service.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DownloadSettingsScreen(
    settings: DownloadSettings,
    destinationTreeUri: String?,
    onMaxConcurrentChange: (Int) -> Unit,
    onSpeedLimitChange: (Long) -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    onMaxRetriesChange: (Int) -> Unit,
    onAutoRemoveChange: (Boolean) -> Unit,
    onDestinationChange: (String?) -> Unit
) {
    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            onDestinationChange(uri.toString())
        }
    }
    val folderName = destinationTreeUri?.let { uriString ->
        runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uriString))?.name }.getOrNull()
    } ?: "Download/DownloadHub"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsSection("Storage") {
            Text("Saved folder", style = MaterialTheme.typography.bodyMedium)
            Text(
                folderName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            OutlinedButton(
                onClick = { folderPicker.launch(null) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Change folder")
            }
            if (destinationTreeUri != null) {
                androidx.compose.material3.TextButton(
                    onClick = { onDestinationChange(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Use default Download/DownloadHub")
                }
            }
        }

        SettingsSection("Transfers") {
            StepperRow(
                title = "Downloads at once",
                subtitle = "How many items may transfer simultaneously",
                value = settings.maxConcurrent,
                range = DownloadSettings.MIN_MAX_CONCURRENT..DownloadSettings.MAX_MAX_CONCURRENT,
                onChange = onMaxConcurrentChange
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Speed limit", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Applies to all downloads combined",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DownloadSettings.SPEED_PRESETS.forEach { (bytes, label) ->
                        FilterChip(
                            selected = settings.speedLimitBytesPerSecond == bytes,
                            onClick = { onSpeedLimitChange(bytes) },
                            label = { Text(label) }
                        )
                    }
                }
            }
            SettingSwitch(
                title = "Download on Wi-Fi only",
                subtitle = "Pause transfers on mobile data",
                checked = settings.wifiOnly,
                onChange = onWifiOnlyChange
            )
        }

        SettingsSection("Reliability") {
            StepperRow(
                title = "Automatic retries",
                subtitle = "Retry a failed transfer this many times",
                value = settings.maxRetries,
                range = 0..DownloadSettings.MAX_RETRIES_LIMIT,
                onChange = onMaxRetriesChange
            )
            SettingSwitch(
                title = "Clear finished downloads",
                subtitle = "Remove a download from the queue once it completes",
                checked = settings.autoRemoveCompleted,
                onChange = onAutoRemoveChange
            )
            Text(
                "Downloads run as a foreground service with an ongoing notification, " +
                    "so they keep going when you leave the app or lock the screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            content()
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StepperRow(
    title: String,
    subtitle: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = { onChange((value - 1).coerceIn(range)) }, enabled = value > range.first) {
            Icon(Icons.Default.Remove, contentDescription = "Decrease $title")
        }
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(28.dp)
        )
        IconButton(onClick = { onChange((value + 1).coerceIn(range)) }, enabled = value < range.last) {
            Icon(Icons.Default.Add, contentDescription = "Increase $title")
        }
    }
}
