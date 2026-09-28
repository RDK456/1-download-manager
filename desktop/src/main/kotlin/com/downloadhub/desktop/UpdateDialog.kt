package com.downloadhub.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.downloadhub.core.DisplayFormat

/**
 * The update flow, in one dialog.
 *
 * Android installs by handing an APK to the system package installer. Windows has no
 * equivalent, so the .msi is downloaded and then started, and Windows Installer
 * takes over from there.
 *
 * What this tries to get right, because each of these was missing:
 *
 * - **What is changing.** The release notes are shown. A dialog that only says
 *   "1.4.9 is available" makes the person go and look it up.
 * - **How far along the download is.** Bytes, not just a bar - a bar with no number
 *   cannot be compared against the size of the thing.
 * - **What to do when the installer will not run.** Windows Installer fails on some
 *   machines with "Could not set file security ... Error: 5", and nothing in the app
 *   can fix that. So the portable zip is offered as a real alternative, and a failed
 *   launch says so instead of closing the dialog.
 */
@Composable
fun UpdateDialog(
    state: DesktopUiState,
    onCheck: () -> Unit,
    onDownload: (Boolean) -> Unit,
    onInstall: () -> Unit,
    onReveal: () -> Unit,
    onDismiss: () -> Unit
) {
    // Nothing to show until a check has actually produced something.
    if (state.update is UpdateCheck.Idle) return

    val downloading = state.updateProgress in 0..100
    val hasZip = (state.update as? UpdateCheck.Available)?.release?.portableZip() != null

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text(titleFor(state.update), fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    summaryFor(state),
                    fontSize = 12.sp,
                    color = AppTheme.Palette.onSurface
                )

                if (downloading) {
                    Spacer(Modifier.height(12.dp))
                    val done = DisplayFormat.bytes(state.updateBytes)
                    val total = if (state.updateTotalBytes > 0) {
                        DisplayFormat.bytes(state.updateTotalBytes)
                    } else {
                        "an unknown size"
                    }
                    Text("Downloading  $done of $total", fontSize = 11.sp, color = AppTheme.Palette.muted)
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { state.updateProgress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                val notes = (state.update as? UpdateCheck.Available)?.release?.readableNotes
                if (!notes.isNullOrBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "What's new",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppTheme.Palette.muted
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(notes, fontSize = 12.sp, color = AppTheme.Palette.onSurface)
                }
            }
        },
        confirmButton = {
            when {
                state.downloadedInstaller != null ->
                    TextButton(onClick = onInstall) { Text("Install now") }

                downloading -> Spacer(Modifier.width(8.dp))

                state.update is UpdateCheck.Available ->
                    TextButton(onClick = { onDownload(false) }) { Text("Download installer") }

                state.update is UpdateCheck.Failed ->
                    TextButton(onClick = onCheck) { Text("Try again") }

                else -> Unit
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.downloadedInstaller != null) {
                    // A download that is sitting on disk can be run later, and the user
                    // may want to move it or unblock it first.
                    TextButton(onClick = onReveal) { Text("Show file") }
                } else if (!downloading && hasZip &&
                    (state.update is UpdateCheck.Available || state.update is UpdateCheck.Failed)
                ) {
                    TextButton(onClick = { onDownload(true) }) { Text("Portable zip") }
                }
                if (!downloading) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    )
}

private fun titleFor(update: UpdateCheck): String = when (update) {
    is UpdateCheck.Checking -> "Checking for updates"
    is UpdateCheck.Available -> "Update available"
    is UpdateCheck.UpToDate -> "Up to date"
    is UpdateCheck.Failed -> "Could not update"
    else -> "Update"
}

/** One or two lines saying what the app found, before the detail. */
private fun summaryFor(state: DesktopUiState): String = when (val update = state.update) {
    is UpdateCheck.Checking -> "Asking GitHub for the newest release…"
    is UpdateCheck.Available -> {
        val size = if (update.asset.size > 0) {
            " · ${DisplayFormat.bytes(update.asset.size)}"
        } else {
            ""
        }
        "${update.release.displayName} is available.$size\n" +
            "You are on ${state.appVersion}. The installer keeps your settings and downloads."
    }

    is UpdateCheck.UpToDate -> "You are on ${update.version}, which is the newest release."
    is UpdateCheck.Failed -> update.message
    // Idle returns above; the dialog is not shown for it, but a `when` that could
    // stop compiling the moment a state is added is not worth the risk of guessing.
    else -> ""
}
