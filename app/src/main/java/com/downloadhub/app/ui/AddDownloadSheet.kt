package com.downloadhub.app.ui

import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ClipboardPaste
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.ScanSearch
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.downloadhub.app.data.model.AudioFormat
import com.downloadhub.app.data.model.DownloadCategory
import com.downloadhub.app.data.model.DownloadSource
import com.downloadhub.app.data.model.MediaQuality
import com.downloadhub.app.data.model.label
import com.downloadhub.app.download.LinkParser

/** MIME types offered to the system document picker for torrents. */
private val TORRENT_MIME_TYPES = arrayOf(
    "application/x-bittorrent",
    "application/vnd.torrent",
    "application/octet-stream"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDownloadSheet(
    seed: EditorSeed,
    allowTorrentFile: Boolean,
    scanning: Boolean,
    onDismiss: () -> Unit,
    onAdd: (
        rawLink: String,
        fileName: String?,
        category: DownloadCategory?,
        sourceOverride: DownloadSource?,
        userAgent: String?,
        contentDisposition: String?,
        quality: MediaQuality,
        audioFormat: AudioFormat,
        streamFormatId: String?,
        streamAudioFormatId: String?
    ) -> Unit,
    /**
     * Hands a YouTube link to the YouTube tab, which owns everything about it:
     * the listing, the full quality choice, the queueing. The sheet used to pick
     * qualities inline, which split YouTube handling across two places and hid
     * options the tab shows.
     */
    onOpenYouTubeTab: (String) -> Unit,
    onPickTorrent: (Uri) -> Unit,
    onScanPage: (String) -> Unit,
    /** Headers, cookies and login typed for this link; sent just before onAdd. */
    onRequestOptions: (com.downloadhub.core.HttpRequestOptions) -> Unit = {},
    /** Several links, or a numbered range, pasted at once: queued together. */
    onAddBatch: (List<String>) -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current
    var link by rememberSaveable(seed.link) { mutableStateOf(seed.link) }
    var fileName by rememberSaveable(seed.fileName) { mutableStateOf(seed.fileName.orEmpty()) }
    var quality by rememberSaveable(seed.link) { mutableStateOf(seed.quality) }
    var audioFormat by rememberSaveable(seed.link) { mutableStateOf(seed.audioFormat) }
    var request by remember(seed.link) { mutableStateOf(com.downloadhub.core.HttpRequestOptions()) }
    var showRequest by remember(seed.link) { mutableStateOf(false) }
    var category by remember(seed.source) {
        mutableStateOf(
            seed.category ?: when (seed.quality) {
                MediaQuality.AUDIO -> DownloadCategory.AUDIO
                else -> if (seed.source == DownloadSource.YOUTUBE) {
                    DownloadCategory.VIDEO
                } else {
                    null
                }
            }
        )
    }

    // A pasted magnet/YouTube/torrent link is detected even when the sheet was
    // opened generically, so the right options are offered immediately.
    val effectiveSource = remember(link, seed.source) {
        val trimmed = link.trim()
        if (trimmed.isEmpty()) seed.source else LinkParser.sourceFor(trimmed)
    }
    // More than one link (a list, or a [1-50] range) is a batch: no per-file options,
    // everything is queued as it comes.
    val batch = remember(link) { com.downloadhub.core.BatchLinks.expand(link) }.takeIf { it.size > 1 }
    val isYoutube = batch == null && effectiveSource == DownloadSource.YOUTUBE

    // What Add does, shared with the keyboard's Go key in either field.
    fun submit() {
        if (link.isBlank()) return
        when {
            batch != null -> onAddBatch(batch)
            isYoutube -> onOpenYouTubeTab(link.trim())
            else -> {
                if (!request.isEmpty) onRequestOptions(request)
                onAdd(
                    link,
                    fileName.takeIf { it.isNotBlank() },
                    category,
                    effectiveSource,
                    seed.userAgent,
                    seed.contentDisposition,
                    quality,
                    audioFormat,
                    null,
                    null
                )
            }
        }
    }
    val goAction = androidx.compose.foundation.text.KeyboardActions(onGo = { submit() })

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onPickTorrent)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Add download", style = MaterialTheme.typography.headlineSmall)
            Text(
                sourceTitle(effectiveSource),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(if (effectiveSource == DownloadSource.TORRENT) "Magnet or .torrent URL" else "URL")
                },
                maxLines = 5,
                supportingText = { Text("One link, several (one per line), or a range like file[01-20].jpg") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                keyboardActions = goAction,
                trailingIcon = {
                    IconButton(onClick = {
                        clipboard.getText()?.text?.let { link = it }
                    }) {
                        Icon(Lucide.ClipboardPaste, contentDescription = "Paste link")
                    }
                }
            )

            // Picking a .torrent file is a Torrents-tab action, so it is only
            // offered there (or when the sheet was opened for a torrent source).
            if (allowTorrentFile || effectiveSource == DownloadSource.TORRENT) {
                OutlinedButton(
                    onClick = { filePicker.launch(TORRENT_MIME_TYPES) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Lucide.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Choose .torrent file from device")
                }
            }

            if (batch != null) {
                Text(
                    "${batch.size} links will be queued" + if (batch.size == com.downloadhub.core.BatchLinks.MAX) " (the most one batch takes)." else ".",
                    style = MaterialTheme.typography.bodyMedium
                )
                batch.take(3).forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                if (batch.size > 3) Text("…", style = MaterialTheme.typography.bodySmall)
            } else if (isYoutube) {
                // YouTube lives in its own tab: the listing, every quality the
                // extractor offers, the queueing. Picking qualities here split the
                // handling across two places and hid options the tab shows, so the
                // sheet hands the link over instead of downloading from it.
                Text(
                    "YouTube links open in the YouTube tab, where every quality " +
                        "and format the video offers is listed to choose from.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { onOpenYouTubeTab(link.trim()) },
                    enabled = link.trim().isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open in YouTube tab")
                }
            } else if (effectiveSource == DownloadSource.TORRENT) {
                Text(
                    "Paste a magnet link or a .torrent URL, or pick a torrent file from this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("File name (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                    keyboardActions = goAction
                )
                // Offer to look inside a pasted web page for video, audio or files.
                if (link.trim().startsWith("http")) {
                    OutlinedButton(
                        onClick = { onScanPage(link) },
                        enabled = !scanning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (scanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Checking the page...")
                        } else {
                            Icon(Lucide.ScanSearch, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Check page for media")
                        }
                    }
                }
                CategorySelector(category) { category = it }
                // What the link sends: only some sites need it, so it stays folded away.
                if (effectiveSource == DownloadSource.HTTP) {
                    if (showRequest) {
                        RequestFields(
                            initial = request,
                            actionLabel = null,
                            onAction = {},
                            onChange = { request = it }
                        )
                    } else {
                        TextButton(onClick = { showRequest = true }) { Text("Headers, cookies and login...") }
                    }
                }
            }

            Button(
                onClick = { submit() },
                enabled = !isYoutube && link.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (batch != null) "Add ${batch.size} to queue" else "Add to queue")
            }
        }
    }
}

@Composable
private fun CategorySelector(selected: DownloadCategory?, onSelect: (DownloadCategory) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Category", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // ARCHIVE is a legacy stored value that already reads as "Compressed".
            DownloadCategory.entries
                .filterNot { it == DownloadCategory.ARCHIVE }
                .forEach { option ->
                    FilterChip(
                        selected = selected == option,
                        onClick = { onSelect(option) },
                        label = { Text(option.label) }
                    )
                }
        }
    }
}

private fun sourceTitle(source: DownloadSource): String = when (source) {
    DownloadSource.HTTP -> "Direct download"
    DownloadSource.YOUTUBE -> "YouTube video or audio"
    DownloadSource.TORRENT -> "BitTorrent download"
}
