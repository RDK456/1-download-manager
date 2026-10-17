package com.downloadhub.app.ui.expressive.shell

import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.composables.icons.lucide.ClipboardPaste
import com.composables.icons.lucide.Link
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Magnet
import com.downloadhub.app.download.LinkParser
import com.downloadhub.app.ui.DownloadViewModel

/**
 * The add button wired to the real actions. "Add a link" opens the existing add sheet, which
 * still handles ranges, page scans and options; the other two skip straight to what they name.
 */
@Composable
fun AddMenuHost(viewModel: DownloadViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val torrentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::addTorrentFile)
    }
    AddMenu(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        actions = listOf(
            AddAction("Add a link", Lucide.Link) { viewModel.openEditor() },
            AddAction("Paste from clipboard", Lucide.ClipboardPaste) {
                val text = runCatching {
                    context.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                }.getOrNull()
                val link = text?.let { LinkParser.extractFirstLink(it) }
                if (link != null) viewModel.handleIncoming(link) else viewModel.notify("No download link on the clipboard")
            },
            AddAction("Torrent file", Lucide.Magnet) {
                torrentPicker.launch(arrayOf("application/x-bittorrent", "application/vnd.torrent", "application/octet-stream"))
            }
        )
    )
}
