package com.downloadhub.desktop

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The update flow, in one dialog.
 *
 * Android installs by handing an APK to the system package installer. Windows has no
 * equivalent, so the .msi is downloaded and then started, and Windows Installer
 * takes over from there.
 */
@Composable
fun UpdateDialog(
    state: DesktopUiState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    // Nothing to show until a check has actually produced something.
    if (state.update is UpdateCheck.Idle) return

    val title = when (val update = state.update) {
        is UpdateCheck.Checking -> "Checking for updates"
        is UpdateCheck.Available -> "Update available"
        is UpdateCheck.UpToDate -> "No update needed"
        is UpdateCheck.Failed -> "Could not check"
        else -> "Update"
    }

    val body = when (val update = state.update) {
        is UpdateCheck.Checking -> "Asking GitHub for the newest release…"
        is UpdateCheck.Available -> {
            val size = if (update.asset.size > 0) {
                " (${com.downloadhub.core.DisplayFormat.bytes(update.asset.size)})"
            } else {
                ""
            }
            "${update.release.displayName} is available.$size\n\n" +
                "Download it, then the installer will replace this copy and keep your settings."
        }

        is UpdateCheck.UpToDate -> "You are on the latest version (${update.version})."
        is UpdateCheck.Failed -> "${update.message}\n\nCheck your internet connection and try again."
        else -> ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        text = {
            if (state.updateProgress in 0..100) {
                LinearProgressIndicator(
                    progress = { state.updateProgress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text(body)
        },
        confirmButton = {
            when {
                state.downloadedInstaller != null ->
                    Button(onClick = onInstall) { Text("Install now") }

                state.update is UpdateCheck.Available && state.updateProgress < 0 ->
                    Button(onClick = onDownload) { Text("Download") }

                state.update is UpdateCheck.Failed ->
                    Button(onClick = onCheck) { Text("Try again") }

                else -> Unit
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
