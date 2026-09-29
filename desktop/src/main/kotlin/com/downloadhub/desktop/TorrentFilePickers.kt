package com.downloadhub.desktop

import androidx.compose.runtime.Composable
import com.downloadhub.core.TorrentParser
import java.awt.Component
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Choosing a `.torrent` from disk.
 *
 * AWT's own dialog rather than Compose's, for one reason: Compose Desktop's file chooser
 * shells out to a native Windows dialog that has to be located at runtime and silently
 * fails to appear when it is not. AWT's is the same dialog Windows shows everywhere else
 * and always appears.
 */
fun chooseTorrentFile(parent: Component?): File? {
    val chooser = JFileChooser()
    chooser.dialogTitle = "Choose a torrent file"
    chooser.fileSelectionMode = JFileChooser.FILES_ONLY
    chooser.isMultiSelectionEnabled = false
    chooser.fileFilter = FileNameExtensionFilter("Torrent files (*.torrent)", "torrent")
    return if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.takeIf { TorrentParser.looksLikeTorrent(it) }
    } else {
        null
    }
}

/** Choosing a folder, for the dialog's save-in field. */
internal fun pickFolder(startIn: File?): File? {
    val chooser = JFileChooser(startIn?.takeIf { it.isDirectory })
    chooser.dialogTitle = "Choose where to save"
    chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile
    } else {
        null
    }
}

/**
 * Accepts a `.torrent` dropped on the window.
 *
 * Attached to the AWT window rather than to a Compose modifier, and the reason is version
 * specific: the pointer events in this Compose build have no drop event at all, so there is
 * nothing for a modifier to listen to. Windows' drag-and-drop goes through AWT's
 * TransferHandler regardless, so this is the same path a file explorer's drop takes.
 */
fun installTorrentDropTarget(
    window: Component,
    onTorrent: (File) -> Unit,
    onProblem: (String) -> Unit
): DropTarget {
    // The listener is passed to the constructor rather than set afterwards: a no-argument
    // DropTarget followed by setDropListener leaves a window with two drop targets, and
    // the second silently wins.
    val listener = object : DropTargetAdapter() {
        override fun drop(event: DropTargetDropEvent) {
            // Accept first, then decide. Refusing at dragOver means the cursor shows "no"
            // over the whole window, which stops a user ever finding out it works.
            event.acceptDrop(DnDConstants.ACTION_COPY)
            val files = droppedFiles(event)
            val torrent = files.firstOrNull { TorrentParser.looksLikeTorrent(it) }
            if (torrent != null) {
                onTorrent(torrent)
                event.dropComplete(true)
                return
            }
            onProblem(
                when {
                    files.isEmpty() -> "That drop carried no file this app can use."
                    // The most common wrong file: a browser saving its "page not found"
                    // under the name of the link that was clicked.
                    else -> "${files.first().name} is not a torrent file. If it came from " +
                        "a browser, it may be an error page saved with the wrong name."
                }
            )
            event.dropComplete(false)
        }
    }
    return DropTarget(window, DnDConstants.ACTION_COPY, listener, true)
}

/**
 * The files in a drop, read the way Windows offers them.
 *
 * Each entry is turned into a File by its own path rather than cast wholesale: some sources
 * hand over a list of strings, and a cast that assumes otherwise throws and loses the drop.
 */
private fun droppedFiles(event: DropTargetDropEvent): List<File> =
    runCatching {
        event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
    }.getOrNull()?.mapNotNull { entry ->
        when (entry) {
            is File -> entry
            is String -> File(entry)
            else -> null
        }
    }.orEmpty()

/** Why a dropped or picked file could not be used, said rather than swallowed. */
@Composable
fun TorrentProblemDialog(message: String, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        // Inside the Window, like every other dialog: a Dialog outside one has no Compose
        // scene and takes the JVM down with it.
        properties = APP_DIALOG_PROPERTIES,
        title = { androidx.compose.material3.Text("That cannot be added") },
        text = { androidx.compose.material3.Text(message) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text("Close")
            }
        }
    )
}
