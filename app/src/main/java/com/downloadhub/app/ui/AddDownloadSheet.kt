package com.downloadhub.app.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
    onDismiss: () -> Unit,
    onAdd: (
        rawLink: String,
        fileName: String?,
        category: DownloadCategory?,
        sourceOverride: DownloadSource?,
        userAgent: String?,
        contentDisposition: String?,
        quality: MediaQuality,
        audioFormat: AudioFormat
    ) -> Unit,
    onPickTorrent: (Uri) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current
    var link by rememberSaveable(seed.link) { mutableStateOf(seed.link) }
    var fileName by rememberSaveable(seed.fileName) { mutableStateOf(seed.fileName.orEmpty()) }
    var quality by rememberSaveable(seed.link) { mutableStateOf(seed.quality) }
    var audioFormat by rememberSaveable(seed.link) { mutableStateOf(seed.audioFormat) }
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
    val isYoutube = effectiveSource == DownloadSource.YOUTUBE

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
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                trailingIcon = {
                    IconButton(onClick = {
                        clipboard.getText()?.text?.let { link = it }
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste link")
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
                    Icon(Icons.Default.FileOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Choose .torrent file from device")
                }
            }

            if (isYoutube) {
                MediaQualitySelector(
                    selected = quality,
                    onSelect = {
                        quality = it
                        category = if (it.isAudioOnly) {
                            DownloadCategory.AUDIO
                        } else {
                            DownloadCategory.VIDEO
                        }
                    }
                )
                if (quality.isAudioOnly) {
                    AudioFormatSelector(selected = audioFormat, onSelect = { audioFormat = it })
                }
                Text(
                    if (quality.isAudioOnly) {
                        "Saves the audio track only, converted to ${audioFormat.label}."
                    } else {
                        "yt-dlp picks the best stream at or below the selected resolution."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                    singleLine = true
                )
                CategorySelector(category) { category = it }
            }

            Button(
                onClick = {
                    onAdd(
                        link,
                        fileName.takeIf { it.isNotBlank() },
                        category,
                        effectiveSource,
                        seed.userAgent,
                        seed.contentDisposition,
                        quality,
                        audioFormat
                    )
                },
                enabled = link.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Add to queue")
            }
        }
    }
}

@Composable
private fun MediaQualitySelector(selected: MediaQuality, onSelect: (MediaQuality) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Quality", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MediaQuality.entries.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    label = { Text(option.label) },
                    leadingIcon = if (option == selected) {
                        {
                            Icon(
                                if (option.isAudioOnly) Icons.Default.GraphicEq else Icons.Default.Movie,
                                contentDescription = null,
                                modifier = Modifier.width(16.dp)
                            )
                        }
                    } else {
                        null
                    }
                )
            }
        }
    }
}

@Composable
private fun AudioFormatSelector(selected: AudioFormat, onSelect: (AudioFormat) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Audio type", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AudioFormat.entries.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    label = { Text(option.label) }
                )
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
