package com.downloadhub.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.downloadhub.core.DisplayFormat
import com.downloadhub.core.DownloadItem
import com.downloadhub.core.DownloadStatus

/**
 * The right-click menu on a download.
 *
 * Modelled on the same two apps the rest of this came from. qBittorrent's torrent menu is
 * the shape to follow, because it is the one a torrent user already has in their hands.
 *
 * What is here is what this app can actually do. An item that appears and then reports
 * "not supported" is worse than one that is absent, because the list is the app's own
 * description of itself - see [contextActions].
 */
sealed interface ContextAction {
    /** Stops a running or queued download. */
    data object Pause : ContextAction

    /** Puts a paused download back in the queue. */
    data object Resume : ContextAction

    /** Clears the error and starts again. */
    data object ForceStart : ContextAction

    /**
     * Asks whether to remove the row, and whether to delete what is on disk.
     *
     * A menu that deleted immediately on click would be the worst possible thing to put
     * behind a right-click, so removal always asks.
     */
    data object Remove : ContextAction

    /** Opens this download's own settings. */
    data object Options : ContextAction

    /** Picks a different folder, for a download that has not finished. */
    data object SetLocation : ContextAction

    /** Renames the finished file, on disk and in the list. */
    data object Rename : ContextAction

    /** Opens the folder it sits in, with it selected. */
    data object OpenFolder : ContextAction

    /** Copies the magnet link to the clipboard. */
    data object CopyMagnet : ContextAction

    /** Saves the .torrent file somewhere else. */
    data object ExportTorrent : ContextAction

    /**
     * Always offered and never enabled.
     *
     * It is in qBittorrent's menu and users look for it, so leaving it out reads as an
     * oversight rather than as "not supported". A tick box that cannot be ticked is a lie
     * about what the app does with the files.
     */
    data object AutomaticManagement : ContextAction
}

/**
 * What this download's menu should offer, and in what order.
 *
 * Decided from the item's own state rather than shown-and-greyed, because a list of things
 * that are all unavailable says nothing, while a list of the things that apply to *this*
 * download says exactly what can be done to it.
 */
fun contextActions(item: DownloadItem, hasContentFiles: Boolean): List<ContextAction> = buildList {
    when (item.status) {
        DownloadStatus.RUNNING, DownloadStatus.RESOLVING, DownloadStatus.QUEUED ->
            add(ContextAction.Pause)

        DownloadStatus.PAUSED -> add(ContextAction.Resume)
        DownloadStatus.FAILED -> {
            // Force start and resume differ only for something that has already failed.
            add(ContextAction.ForceStart)
            add(ContextAction.Resume)
        }
        else -> Unit
    }
    add(ContextAction.Options)
    if (!item.isTorrent && item.status != DownloadStatus.COMPLETED) {
        add(ContextAction.SetLocation)
    }
    if (item.status == DownloadStatus.COMPLETED && hasContentFiles) {
        add(ContextAction.Rename)
    }
    if (hasContentFiles) add(ContextAction.OpenFolder)
    if (item.isTorrent) {
        add(ContextAction.CopyMagnet)
        add(ContextAction.ExportTorrent)
    }
    add(ContextAction.AutomaticManagement)
    // Last, and always present: it is the only destructive one, and the dialog it opens
    // is the confirmation.
    add(ContextAction.Remove)
}

/** The label for a menu item. */
fun contextActionLabel(action: ContextAction): String = when (action) {
    ContextAction.Pause -> "Pause"
    ContextAction.Resume -> "Resume"
    ContextAction.ForceStart -> "Force Start"
    ContextAction.Remove -> "Remove"
    ContextAction.Options -> "Torrent options..."
    ContextAction.SetLocation -> "Set location..."
    ContextAction.Rename -> "Rename..."
    ContextAction.OpenFolder -> "Open destination folder"
    ContextAction.CopyMagnet -> "Copy magnet link"
    ContextAction.ExportTorrent -> "Export .torrent..."
    ContextAction.AutomaticManagement -> "Automatic Torrent Management"
}

/**
 * The text "Copy magnet link" puts on the clipboard.
 *
 * A magnet for a torrent that was added as a magnet. One for a torrent added as a file
 * cannot be built - the tracker list lives in the file, not in anything the app kept - so
 * the file's own path is copied instead, which is something the user can actually paste
 * somewhere useful.
 */
fun magnetLinkFor(item: DownloadItem): String = when {
    item.url.startsWith("magnet:", ignoreCase = true) -> item.url
    !item.torrentFilePath.isNullOrBlank() -> item.torrentFilePath.orEmpty()
    else -> item.url
}

/**
 * The menu itself.
 *
 * A dialog rather than a popup anchored to the pointer: an anchored menu needs a position
 * in screen coordinates that Compose Desktop does not hand out here, and getting it wrong
 * puts the menu off screen with no way to dismiss it.
 */
@Composable
fun DownloadContextMenu(
    item: DownloadItem,
    hasContentFiles: Boolean,
    onAction: (ContextAction) -> Unit,
    onDismiss: () -> Unit
) {
    val actions = remember(item.id, item.status, hasContentFiles) {
        contextActions(item, hasContentFiles)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = APP_DIALOG_PROPERTIES,
        title = {
            Column {
                Text(
                    item.fileName,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    "${DisplayFormat.bytes(item.bytesDownloaded)} of " +
                        DisplayFormat.bytes(item.totalBytes) + "  ·  " +
                        item.status.name.lowercase().replaceFirstChar { it.uppercase() },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(Modifier.width(240.dp).verticalScroll(rememberScrollState())) {
                actions.forEach { action ->
                    ContextRow(
                        label = contextActionLabel(action),
                        // Only removal is destructive, and only removal is coloured. A menu
                        // where everything is red says nothing about what is dangerous.
                        destructive = action == ContextAction.Remove,
                        muted = action == ContextAction.AutomaticManagement,
                        onClick = { onAction(action) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun ContextRow(label: String, destructive: Boolean = false, muted: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 12.sp,
        color = when {
            destructive -> AppTheme.Palette.error
            muted -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 7.dp)
    )
}

/** Asks for a new name for a finished file. */
@Composable
fun RenameDownloadDialog(currentName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = APP_DIALOG_PROPERTIES,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("File name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.trim().isNotEmpty()
            ) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Asks where an unfinished download should be written. */
@Composable
fun SetLocationDialog(
    currentDirectory: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var path by remember { mutableStateOf(currentDirectory) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = APP_DIALOG_PROPERTIES,
        title = { Text("Set location") },
        text = {
            Column {
                Text(
                    "Where this download is written. It has not finished, so nothing is on " +
                        "disk yet to move.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("Folder") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(path.trim()) },
                enabled = path.trim().isNotEmpty()
            ) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
