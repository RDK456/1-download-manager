package com.downloadhub.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.TravelExplore
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.downloadhub.app.data.model.AudioFormat
import com.downloadhub.app.data.model.DownloadCategory
import com.downloadhub.app.data.model.DownloadSource
import com.downloadhub.app.data.model.MediaQuality
import com.downloadhub.app.data.model.label
import com.downloadhub.app.download.LinkParser
import com.downloadhub.app.download.YouTubeFormatListing
import com.downloadhub.app.download.failedYouTubeFormats
import com.downloadhub.core.StreamChoice
import com.downloadhub.core.StreamFormat
import com.downloadhub.core.chooseStream
import kotlinx.coroutines.CancellationException

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
    onListFormats: suspend (String) -> YouTubeFormatListing,
    onPickTorrent: (Uri) -> Unit,
    onScanPage: (String) -> Unit
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
    val trimmedLink = link.trim()
    // Only a complete link is looked up: every keystroke before that would fire an
    // extraction that is guaranteed to fail, and a failed lookup reads as if the
    // video were at fault.
    val youTubeLookup = isYoutube && trimmedLink.startsWith("http", ignoreCase = true)

    // The real format list for the link, with real sizes. The fixed Best-to-360p menu
    // is gone: it offered 4K and 2K rows for a 1080p video, and picking one silently
    // downloaded 1080p. A 1080p video now shows rows up to 1080p and nothing above.
    var ytListing by remember { mutableStateOf<YouTubeFormatListing?>(null) }
    var ytLoading by remember { mutableStateOf(false) }
    var ytError by remember { mutableStateOf<String?>(null) }
    var ytAttempt by remember { mutableStateOf(0) }
    var ytVideo by remember { mutableStateOf<StreamFormat?>(null) }
    var ytAudioPick by remember { mutableStateOf<StreamFormat?>(null) }
    var ytAudioOnly by remember { mutableStateOf(false) }

    LaunchedEffect(trimmedLink, ytAttempt) {
        if (!youTubeLookup) {
            ytListing = null
            ytError = null
            ytLoading = false
            return@LaunchedEffect
        }
        ytLoading = true
        ytError = null
        val found = try {
            onListFormats(trimmedLink)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            failedYouTubeFormats(failed.message?.takeIf { it.isNotBlank() } ?: "Could not read that link.")
        }
        ytListing = found
        ytLoading = false
        if (found.error != null) {
            ytError = found.error
        } else {
            // The best row is pre-selected, so Add means the best unless the user
            // says otherwise. A selection that is no longer on offer - the link
            // changed underneath it - falls back the same way.
            if (found.videoFormats.none { it.formatId == ytVideo?.formatId }) {
                ytVideo = found.videoFormats.firstOrNull()
            }
            if (found.audioFormats.none { it.formatId == ytAudioPick?.formatId }) {
                ytAudioPick = found.audioFormats.firstOrNull()
            }
        }
    }

    /** The exact streams Add will queue, or null when there is nothing to queue. */
    val ytPair: StreamChoice? = remember(ytListing, ytVideo, ytAudioPick, ytAudioOnly) {
        val listing = ytListing?.takeIf { it.error == null } ?: return@remember null
        if (ytAudioOnly) {
            ytAudioPick?.let { StreamChoice(it, null) }
        } else {
            val video = ytVideo ?: return@remember null
            chooseStream(
                (listing.videoFormats + listing.audioFormats).distinctBy { it.formatId },
                video.height ?: 0,
                listing.audioFormats
            )
        }
    }

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
                YouTubeFormatPicker(
                    listing = ytListing,
                    loading = ytLoading,
                    error = ytError,
                    video = ytVideo,
                    audioPick = ytAudioPick,
                    audioOnly = ytAudioOnly,
                    onVideo = { ytVideo = it },
                    onAudioPick = { ytAudioPick = it },
                    onAudioOnly = { ytAudioOnly = it },
                    onRetry = { ytAttempt++ }
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
                            Icon(Icons.Default.TravelExplore, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Check page for media")
                        }
                    }
                }
                CategorySelector(category) { category = it }
            }

            Button(
                onClick = {
                    if (isYoutube) {
                        // The row names its streams, so the queue carries the ids rather
                        // than a height: 1080p60 and 1080p are the same height and not
                        // the same download. The quality column keeps the height for the
                        // list to show, and audio-only keeps the AUDIO value the rest of
                        // the app already reads.
                        val pair = ytPair ?: return@Button
                        val audioOnlyRow = ytAudioOnly
                        onAdd(
                            link,
                            fileName.takeIf { it.isNotBlank() },
                            if (audioOnlyRow) DownloadCategory.AUDIO else DownloadCategory.VIDEO,
                            effectiveSource,
                            seed.userAgent,
                            seed.contentDisposition,
                            if (audioOnlyRow) {
                                MediaQuality.AUDIO
                            } else {
                                MediaQuality.entries.firstOrNull { it.maxHeight == pair.video.height }
                                    ?: MediaQuality.BEST
                            },
                            if (audioOnlyRow) {
                                // The row already names the codec; fromValue falls back
                                // to M4A for anything else, which is the safe target.
                                AudioFormat.fromValue(pair.video.ext)
                            } else {
                                audioFormat
                            },
                            pair.video.formatId,
                            pair.audio?.formatId
                        )
                    } else {
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
                },
                enabled = if (isYoutube) ytPair != null && !ytLoading else link.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Add to queue")
            }
        }
    }
}

/**
 * The video's real qualities, with real sizes - one row per height, best first.
 *
 * Each row is a format the extractor actually offered, so a 1080p video ends at
 * 1080p: there is no 4K row to tap and no silent downgrade afterwards. The number
 * beside a row is video plus audio, because both transfer, and every video row says
 * it needs joining, because on YouTube that is now true of all of them.
 */
@Composable
private fun YouTubeFormatPicker(
    listing: YouTubeFormatListing?,
    loading: Boolean,
    error: String?,
    video: StreamFormat?,
    audioPick: StreamFormat?,
    audioOnly: Boolean,
    onVideo: (StreamFormat) -> Unit,
    onAudioPick: (StreamFormat) -> Unit,
    onAudioOnly: (Boolean) -> Unit,
    onRetry: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Quality", style = MaterialTheme.typography.titleSmall)
        if (loading && listing == null && error == null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
            Text(
                "Reading what this video offers...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }
        val failure = error ?: listing?.error
        if (failure != null) {
            Text(
                failure,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(if (loading) "Retrying..." else "Retry")
            }
            return@Column
        }
        val found = listing ?: return@Column
        if (found.title.isNotBlank()) {
            Text(
                found.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        if (found.bestTotalBytes > 0L) {
            Text(
                "${formatBytes(found.bestTotalBytes)} for the largest option. " +
                    "Every quality is two files joined together.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = !audioOnly,
                onClick = { onAudioOnly(false) },
                label = { Text("Video") }
            )
            FilterChip(
                selected = audioOnly,
                onClick = { onAudioOnly(true) },
                label = { Text("Audio only") }
            )
        }
        if (audioOnly) {
            if (found.audioFormats.isEmpty()) {
                Text(
                    "This video offers no audio-only stream.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                found.audioFormats.forEach { format ->
                    val bytes = format.sizeBytes
                    FormatRow(
                        title = "${format.qualityLabel} · ${format.ext}",
                        subtitle = buildString {
                            format.totalBitrate?.let { append(it).append(" kbps") }
                            if (bytes != null && bytes > 0L && isNotEmpty()) append("  ·  ")
                        },
                        sizeBytes = bytes,
                        selected = audioPick?.formatId == format.formatId,
                        onClick = { onAudioPick(format) }
                    )
                }
            }
        } else {
            val all = (found.videoFormats + found.audioFormats).distinctBy { it.formatId }
            found.videoFormats.forEach { format ->
                val total = chooseStream(all, format.height ?: 0, found.audioFormats)
                    ?.totalBytes ?: format.sizeBytes ?: 0L
                FormatRow(
                    title = format.fullLabel,
                    subtitle = buildString {
                        append(format.ext)
                        append("  ·  needs joining")
                    },
                    sizeBytes = total.takeIf { it > 0L },
                    selected = video?.formatId == format.formatId,
                    onClick = { onVideo(format) }
                )
            }
        }
    }
}

@Composable
private fun FormatRow(
    title: String,
    subtitle: String,
    /** Null renders as "size unknown": the extractor did not say. */
    sizeBytes: Long?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            if (sizeBytes != null && sizeBytes > 0L) formatBytes(sizeBytes) else "size unknown",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
