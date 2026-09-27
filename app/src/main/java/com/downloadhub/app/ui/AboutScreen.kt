package com.downloadhub.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.downloadhub.app.BuildConfig
import com.downloadhub.app.R
import com.downloadhub.app.update.ReleaseInfo

private val GITHUB_MARK = Color(0xFF181717)

/**
 * "About us" page: what the app is, which version is installed, where the source
 * lives (GitHub), and a shortcut to install the newest published release.
 */
@Composable
fun AboutScreen(
    appVersion: String,
    versionCode: Int,
    ytdlpVersion: String,
    repoUrl: String,
    update: UpdateSnapshot,
    autoCheckUpdates: Boolean,
    onAutoCheckChange: (Boolean) -> Unit,
    onCheckUpdates: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onOpenRepo: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // GitHub logo -> opens the project's git page.
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenRepo),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = GITHUB_MARK),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_github),
                    contentDescription = "Open the GitHub repository",
                    modifier = Modifier.size(52.dp)
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "1 download manager",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        repoUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB9B9B9),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "View source on GitHub",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            painter = painterResource(R.drawable.ic_open_in_new),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        AboutCard(title = "About us") {
            Text(
                "1 download manager is a native Android download manager. It keeps direct " +
                    "file transfers, YouTube media, and BitTorrent downloads in one persistent " +
                    "queue with pause and resume, category filters, and a folder you choose.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(10.dp))
            AboutRow("App version", "$appVersion ($versionCode)")
            AboutRow("Package", BuildConfig.APPLICATION_ID)
            AboutRow("Bundled yt-dlp", ytdlpVersion)
            AboutRow("Repository", "github.com/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}")
        }

        AboutCard(title = "Updates") {
            Text(
                updateStatusText(update, appVersion),
                style = MaterialTheme.typography.bodyMedium
            )
            // A stable progress row + one primary action keeps the card from
            // jumping between layouts while a check or download runs.
            val progress = update.progress
            if (progress != null) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress.percent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (progress.totalBytes > 0) {
                        "${progress.percent}% · ${formatBytes(progress.downloadedBytes)} of ${formatBytes(progress.totalBytes)}"
                    } else {
                        "${formatBytes(progress.downloadedBytes)} downloaded"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val action = primaryUpdateAction(
                update = update,
                isDownloading = progress != null,
                onCheck = onCheckUpdates,
                onDownload = onDownloadUpdate,
                onInstall = onInstallUpdate
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = action.onClick,
                enabled = action.enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (action.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(action.label)
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Check automatically", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Looks for a new GitHub release when the app starts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoCheckUpdates,
                    onCheckedChange = onAutoCheckChange,
                    colors = settingSwitchColors()
                )
            }
        }

        AboutCard(title = "Project") {
            AboutLinkRow("Source code", repoUrl) { onOpenRepo() }
            AboutLinkRow("Releases", "$repoUrl/releases") { onOpenUrl("$repoUrl/releases") }
            AboutLinkRow("Report an issue", "$repoUrl/issues") { onOpenUrl("$repoUrl/issues") }
        }

        Text(
            "Only download media you own or have permission to download.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AboutCard(title: String, content: @Composable () -> Unit) {
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
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            content()
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

@Composable
private fun AboutLinkRow(label: String, url: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = Color.Transparent,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_open_in_new),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** What the single primary button in the Updates card should do right now. */
private data class PrimaryUpdateAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val busy: Boolean = false
)

@Composable
private fun primaryUpdateAction(
    update: UpdateSnapshot,
    isDownloading: Boolean,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit
): PrimaryUpdateAction {
    val pending = update.pending
    return when {
        isDownloading -> PrimaryUpdateAction("Downloading…", {}, enabled = false, busy = true)
        update.status is UpdateStatus.Checking ->
            PrimaryUpdateAction("Checking for updates…", {}, enabled = false, busy = true)
        pending != null -> PrimaryUpdateAction(
            label = when {
                !pending.signatureMatches -> "Uninstall old build to update"
                pending.needsPermission -> "Allow installation"
                else -> "Install version ${pending.release.version}"
            },
            onClick = onInstall
        )
        update.status is UpdateStatus.Available -> PrimaryUpdateAction(
            label = "Download version ${(update.status as UpdateStatus.Available).release.version}",
            onClick = onDownload
        )
        else -> PrimaryUpdateAction("Check for updates", onCheck)
    }
}

/** Human-readable status line for the update card. */
fun updateStatusText(update: UpdateSnapshot, currentVersion: String): String {
    update.pending?.let { pending ->
        return when {
            !pending.signatureMatches ->
                "Version ${pending.release.version} uses a different signing key. Uninstall this " +
                    "app once, then install the new version."
            pending.needsPermission ->
                "Version ${pending.release.version} is downloaded. Allow installs to finish updating."
            else -> "Version ${pending.release.version} is downloaded and ready to install."
        }
    }
    update.progress?.let { progress ->
        return "Downloading version ${progress.release.version}…"
    }
    return when (val status = update.status) {
        UpdateStatus.Idle -> "Installed version $currentVersion. Check GitHub for newer releases."
        UpdateStatus.Checking -> "Checking GitHub for a newer release…"
        is UpdateStatus.UpToDate -> "Version ${status.version} is the latest available."
        is UpdateStatus.Available ->
            "Version ${status.release.version} is available. Installed: $currentVersion."
        is UpdateStatus.Failed -> "Update check failed: ${status.message}"
    }
}

/** Release dialog shown automatically when a newer version is found. */
@Composable
fun UpdateAvailableDialog(
    release: ReleaseInfo,
    currentVersion: String,
    onDownload: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit
) {
    val asset = release.installAsset()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update available") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${release.displayName} (you have $currentVersion)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (release.notes.isNotBlank()) {
                    Text(
                        release.notes.take(400),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (asset != null && asset.size > 0) {
                    Text(
                        "Download size: ${formatBytes(asset.size)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDownload) {
                Text("Download update")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onSkip) {
                Text("Skip this version")
            }
        }
    )
}

/** Progress / install hand-off shown while an update is being applied. */
@Composable
fun UpdateFlowDialog(
    update: UpdateSnapshot,
    onInstall: () -> Unit,
    onAllowInstalls: () -> Unit,
    onDismiss: () -> Unit
) {
    update.pending?.let { pending ->
        if (!pending.signatureMatches) {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Different signing key") },
                text = {
                    Text(
                        "Version ${pending.release.version} is signed with a different key than " +
                            "the build you are running, so Android cannot install it over the " +
                            "current app. Uninstall 1 download manager first, then install the " +
                            "new version."
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = onAllowInstalls) {
                        Text("Open app settings")
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = onDismiss) {
                        Text("Not now")
                    }
                }
            )
        } else if (pending.needsPermission) {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Allow installation") },
                text = {
                    Text(
                        "To install version ${pending.release.version}, allow this app to " +
                            "install packages, then continue."
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = onAllowInstalls) {
                        Text("Open settings")
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = onDismiss) {
                        Text("Not now")
                    }
                }
            )
        } else {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Install update?") },
                text = {
                    Text(
                        "Version ${pending.release.version} was downloaded. Android will show " +
                            "its package installer to finish the update."
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = onInstall) {
                        Text("Install now")
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = onDismiss) {
                        Text("Later")
                    }
                }
            )
        }
        return
    }

    update.progress?.let { progress ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading update") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Version ${progress.release.version}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    LinearProgressIndicator(
                        progress = { progress.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        if (progress.totalBytes > 0) {
                            "${progress.percent}% · ${formatBytes(progress.downloadedBytes)} of ${formatBytes(progress.totalBytes)}"
                        } else {
                            "${formatBytes(progress.downloadedBytes)} downloaded"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {}
        )
    }
}
