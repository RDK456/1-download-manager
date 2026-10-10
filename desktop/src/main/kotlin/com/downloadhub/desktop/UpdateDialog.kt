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
import androidx.compose.ui.window.DialogProperties
import com.downloadhub.core.DisplayFormat

/**
 * What the dialog is offering, decided from the state rather than read off it.
 *
 * Pulled out of the composable so the decisions can be tested, and because they were
 * where this went wrong: the confirm button was chosen from "has anything downloaded"
 * alone, so a portable zip - which cannot be run as an installer - got the same
 * "Install now" button as an .msi and the portable path dead-ended at the last step.
 */
data class UpdateActions(
    /** The main button's caption, or null for no main button. */
    val confirm: String?,
    /** What the main button does. */
    val onConfirm: UpdateAction,
    /** The secondary button's caption, or null when there is nothing to offer. */
    val secondary: String?,
    val onSecondary: UpdateAction,
    val canClose: Boolean
)

/** What a button in this dialog does, so the choice can be tested without a window. */
enum class UpdateAction {
    NONE,
    DOWNLOAD_INSTALLER,
    DOWNLOAD_PORTABLE,
    INSTALL,
    SWITCH_TO_NEW_VERSION,
    REVEAL,
    RETRY,
    CLOSE
}

/**
 * Works out what the dialog should show.
 *
 * The order of preference for the main button is: whatever has been downloaded and
 * can actually be used, then a download. What has been downloaded decides which,
 * because that is the difference between an installer and a zip.
 */
fun updateActionsFor(
    update: UpdateCheck,
    downloaded: DownloadedUpdate?,
    progress: Int,
    hasPortable: Boolean
): UpdateActions {
    val downloading = progress in 0..100

    // Something has been fetched. What can be done with it depends entirely on what
    // it is, so this is decided before anything else.
    if (downloaded != null) {
        return UpdateActions(
            confirm = if (downloaded.isInstaller) "Install and restart" else "Switch to this version",
            onConfirm = if (downloaded.isInstaller) UpdateAction.INSTALL else UpdateAction.SWITCH_TO_NEW_VERSION,
            secondary = "Show file",
            onSecondary = UpdateAction.REVEAL,
            canClose = true
        )
    }

    if (downloading) {
        // No buttons at all while the transfer runs: dismissing mid-download would
        // leave the user with no idea whether it carried on.
        return UpdateActions(null, UpdateAction.NONE, null, UpdateAction.NONE, canClose = false)
    }

    return when (update) {
        is UpdateCheck.Available -> UpdateActions(
            confirm = "Update now",
            onConfirm = UpdateAction.DOWNLOAD_INSTALLER,
            secondary = if (hasPortable) "Portable zip" else null,
            onSecondary = UpdateAction.DOWNLOAD_PORTABLE,
            canClose = true
        )

        // The portable zip stays on offer after a failure. It is the answer for the
        // one failure that cannot be retried into working - a security product that
        // will not let Windows Installer run - and it used to vanish exactly then.
        is UpdateCheck.Failed -> UpdateActions(
            confirm = "Try again",
            onConfirm = UpdateAction.RETRY,
            secondary = if (hasPortable) "Portable zip" else null,
            onSecondary = UpdateAction.DOWNLOAD_PORTABLE,
            canClose = true
        )

        else -> UpdateActions(null, UpdateAction.NONE, null, UpdateAction.NONE, canClose = true)
    }
}

/**
 * The update flow, in one dialog.
 *
 * Android installs by handing an APK to the system package installer. Windows has no
 * equivalent, so the .msi is downloaded and then started, and Windows Installer
 * takes over from there. The portable zip is the alternative for machines where
 * Windows Installer cannot run at all; it is unpacked and started instead, which is a
 * different operation and is labelled as one.
 *
 * What this tries to get right, because each of these was missing:
 *
 * - **What is changing.** The release notes are shown. A dialog that only says
 *   "1.4.9 is available" makes the person go and look it up.
 * - **How far along the download is.** Bytes, not just a bar - a bar with no number
 *   cannot be compared against the size of the thing.
 * - **What to do when the installer will not run.** Windows Installer fails on some
 *   machines with "Could not set file security ... Error: 5", and nothing in the app
 *   can fix that. So the portable zip is offered as a real alternative that can
 *   actually be used, and a failed launch says so instead of closing the dialog.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun UpdateDialog(
    state: DesktopUiState,
    onCheck: () -> Unit,
    onDownload: (Boolean) -> Unit,
    onInstall: () -> Unit,
    onSwitchToNewVersion: () -> Unit,
    onReveal: () -> Unit,
    onDismiss: () -> Unit
) {
    // Nothing to show until a check has actually produced something.
    if (state.update is UpdateCheck.Idle) return

    val hasPortable = portableOffered(state)
    val actions = updateActionsFor(
        update = state.update,
        downloaded = state.downloadedUpdate,
        progress = state.updateProgress,
        hasPortable = hasPortable
    )

    AlertDialog(
        onDismissRequest = { if (actions.canClose) onDismiss() },
        // Without this the dialog is a flat rectangle on an undimmed background, which
        // reads as a panel painted onto the window rather than something in front of
        // it. Desktop draws no scrim of its own.
        properties = DialogProperties(scrimColor = androidx.compose.ui.graphics.Color(0xCC000000)),
        title = { Text(titleFor(state), fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    summaryFor(state),
                    fontSize = 12.sp,
                    color = AppTheme.Palette.onSurface
                )

                if (state.updateProgress in 0..100) {
                    Spacer(Modifier.height(12.dp))
                    val done = DisplayFormat.bytes(state.updateBytes)
                    val total = if (state.updateTotalBytes > 0) {
                        DisplayFormat.bytes(state.updateTotalBytes)
                    } else {
                        "an unknown size"
                    }
                    Text(
                        "${verbFor(state.updateProgress)}  $done of $total",
                        fontSize = 11.sp,
                        color = AppTheme.Palette.muted
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { state.updateProgress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                val notes = readableNotesFor(state)
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
            if (actions.confirm != null) {
                TextButton(
                    onClick = {
                        run(
                            actions.onConfirm, onCheck, onDownload, onInstall,
                            onSwitchToNewVersion, onReveal, onDismiss
                        )
                    }
                ) { Text(actions.confirm) }
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (actions.secondary != null) {
                    TextButton(
                        onClick = {
                            run(
                                actions.onSecondary, onCheck, onDownload, onInstall,
                                onSwitchToNewVersion, onReveal, onDismiss
                            )
                        }
                    ) { Text(actions.secondary) }
                }
                if (actions.canClose) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    )
}

private fun run(
    action: UpdateAction,
    onCheck: () -> Unit,
    onDownload: (Boolean) -> Unit,
    onInstall: () -> Unit,
    onSwitch: () -> Unit,
    onReveal: () -> Unit,
    onDismiss: () -> Unit
) = when (action) {
    UpdateAction.DOWNLOAD_INSTALLER -> onDownload(false)
    UpdateAction.DOWNLOAD_PORTABLE -> onDownload(true)
    UpdateAction.INSTALL -> onInstall()
    UpdateAction.SWITCH_TO_NEW_VERSION -> onSwitch()
    UpdateAction.REVEAL -> onReveal()
    UpdateAction.RETRY -> onCheck()
    UpdateAction.CLOSE -> onDismiss()
    UpdateAction.NONE -> Unit
}

/**
 * Whether the portable zip can be offered.
 *
 * Read from the remembered release rather than from the current state, so it survives
 * a failed download.
 */
private fun portableOffered(state: DesktopUiState): Boolean =
    (state.update as? UpdateCheck.Available)?.release?.portableZip() != null ||
        (state.update is UpdateCheck.Failed && state.updateReleaseName != null)

/** Unpacking a zip and starting it is not "downloading", and saying so is clearer. */
private fun verbFor(progress: Int): String = if (progress == 0) "Starting" else "Downloading"

private fun readableNotesFor(state: DesktopUiState): String? =
    (state.update as? UpdateCheck.Available)?.release?.readableNotes

/**
 * The title follows what has happened.
 *
 * It used to stay on "Update available" after a 78 MB download had finished, which
 * reads as though nothing had been done.
 */
private fun titleFor(state: DesktopUiState): String {
    val downloaded = state.downloadedUpdate
    return when {
        state.updateProgress in 0..100 && downloaded == null -> "Working on it"
        downloaded?.isInstaller == true -> "Ready to install"
        downloaded != null -> "Ready to switch"
        else -> when (state.update) {
            is UpdateCheck.Checking -> "Checking for updates"
            is UpdateCheck.Available -> "Update available"
            is UpdateCheck.UpToDate -> "Up to date"
            is UpdateCheck.Failed -> "Could not update"
            else -> "Update"
        }
    }
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
        // Once something has been fetched, saying "is available" is out of date.
        val headline = if (state.downloadedUpdate == null) {
            "${update.release.displayName} is available.$size"
        } else {
            "${update.release.displayName} has been downloaded."
        }
        "$headline\nYou are on ${state.appVersion}. One click downloads it, installs it and restarts the app; your settings and downloads are kept."
    }

    is UpdateCheck.UpToDate -> "You are on ${update.version}, which is the newest release."
    is UpdateCheck.Failed -> update.message
    // Idle returns above; the dialog is not shown for it, but a `when` that could
    // stop compiling the moment a state is added is not worth the risk of guessing.
    else -> ""
}
