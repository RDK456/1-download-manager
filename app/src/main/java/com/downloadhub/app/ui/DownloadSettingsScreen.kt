package com.downloadhub.app.ui

import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.BatteryWarning
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Plus
import android.content.Intent
import com.downloadhub.app.update.YtDlpUpdateState
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
    isBatteryExempt: Boolean,
    onMaxConcurrentChange: (Int) -> Unit,
    onConnectionsChange: (Int) -> Unit = {},
    onSpeedLimitChange: (Long) -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    onAutoQueueChange: (Boolean) -> Unit = {},
    onMaxRetriesChange: (Int) -> Unit,
    onAutoRemoveChange: (Boolean) -> Unit,
    onDestinationChange: (String?) -> Unit,
    onRequestBatteryExemption: () -> Unit,
    downloaderVersion: String,
    ytdlpUpdate: YtDlpUpdateState,
    onRetryYtDlp: () -> Unit
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
                Icon(Lucide.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
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
            StepperRow(
                title = "Connections per download",
                subtitle = "Splits a file into parts fetched at once, when the server allows it",
                value = settings.connectionsPerDownload,
                range = 1..DownloadSettings.MAX_CONNECTIONS,
                onChange = onConnectionsChange
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
            SettingSwitch(
                title = "Add links from the browser straight away",
                subtitle = "Links shared or opened from a browser, and download links you copy, go into the queue without asking",
                checked = settings.autoQueueIncoming,
                onChange = onAutoQueueChange
            )
        }

        SettingsSection("Background reliability") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Battery optimisation", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (isBatteryExempt) {
                        "Exempt. Downloads keep running in the background."
                    } else {
                        "Not exempt. Android may pause or throttle transfers while the " +
                            "app is in the background, which is the usual cause of a " +
                            "download that only speeds up when the screen is on."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isBatteryExempt) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
            if (!isBatteryExempt) {
                OutlinedButton(
                    onClick = onRequestBatteryExemption,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Lucide.BatteryWarning,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Allow background downloads")
                }
            }
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

        SettingsSection("YouTube downloader") {
            YtDlpStatusRow(
                installed = downloaderVersion,
                state = ytdlpUpdate,
                onRetry = onRetryYtDlp
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
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = settingSwitchColors()
        )
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
            Icon(Lucide.Minus, contentDescription = "Decrease $title")
        }
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(28.dp)
        )
        IconButton(onClick = { onChange((value + 1).coerceIn(range)) }, enabled = value < range.last) {
            Icon(Lucide.Plus, contentDescription = "Increase $title")
        }
    }
}
