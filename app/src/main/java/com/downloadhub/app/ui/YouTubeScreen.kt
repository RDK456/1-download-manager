package com.downloadhub.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.downloadhub.app.data.model.AudioFormat
import com.downloadhub.app.data.model.DownloadCategory
import com.downloadhub.app.data.model.DownloadSource
import com.downloadhub.app.data.model.MediaQuality
import com.downloadhub.app.download.AppPlaylistFetch
import com.downloadhub.app.download.YouTubeFormatListing
import com.downloadhub.core.StreamFormat
import com.downloadhub.core.YouTubeEntry
import com.downloadhub.core.chooseStream
import kotlinx.coroutines.CancellationException

/**
 * The YouTube section: paste anything, take the lot.
 *
 * A playlist, an album, a channel, or one video - the screen lists what the
 * link holds with a checkbox per row, and queues the checked ones. The same
 * deal as the desktop section: almost any link works, the same song is never
 * queued twice, and an audio download keeps its artwork and details inside the
 * file. One quality for the batch, chosen up front; a single video keeps the
 * exact chooser via [onSingle], which opens the add sheet for it.
 */
@Composable
fun YouTubeScreen(
    knownIds: Set<String>,
    /** A link handed over by the add sheet, loaded once then forgotten. */
    prefill: String?,
    onPrefillConsumed: () -> Unit,
    onFetch: suspend (String) -> AppPlaylistFetch,
    onFetchFormats: suspend (String) -> YouTubeFormatListing,
    onQueue: (List<YouTubeEntry>, Boolean, Int?) -> Unit,
    /**
     * Queues one video at its exact streams, the ids the rows named.
     *
     * The same ten arguments as the add sheet's Add, because it is the same
     * queueing: the section lists, but it must not queue by its own rules.
     */
    onAddExact: (
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
    /** Called after anything is queued, so the app returns to the Downloads tab. */
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    var link by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<YouTubeEntry>>(emptyList()) }
    var single by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var audioOnly by remember { mutableStateOf(false) }
    var ceiling by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    // The link the list belongs to. Typing after a fetch must not silently
    // reassign the rows to a link they did not come from.
    var fetchedLink by remember { mutableStateOf("") }

    // A link handed over by the add sheet: loaded once, then forgotten, so a
    // later recomposition does not re-fetch it over what the user typed since.
    LaunchedEffect(prefill) {
        val incoming = prefill?.trim().orEmpty()
        if (incoming.isNotEmpty()) {
            link = incoming
            fetchedLink = incoming
            onPrefillConsumed()
        }
    }

    val trimmed = link.trim()
    // Fetch is explicit: every keystroke firing an extraction is how a
    // half-typed link reads as a broken video. The lookup below runs on the
    // submitted link, not the text field.

    fun run() {
        if (trimmed.isEmpty()) {
            error = "Paste a link first: a video, playlist, album or channel."
            return
        }
        fetchedLink = trimmed
    }

    // The actual lookup, keyed on the submitted link rather than the text field.
    LaunchedEffect(fetchedLink, attempt) {
        if (fetchedLink.isEmpty()) return@LaunchedEffect
        busy = true
        error = null
        entries = emptyList()
        checked = emptySet()
        title = ""
        single = false
        val found = try {
            onFetch(fetchedLink)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            AppPlaylistFetch(
                "", emptyList(), false,
                failed.message?.takeIf { it.isNotBlank() } ?: "Could not read that link."
            )
        }
        busy = false
        if (found.error != null) {
            error = found.error
        } else {
            title = found.title
            single = found.single
            entries = found.entries
            checked = found.entries.map { it.id }.filterNot { knownIds.contains(it) }.toSet()
            if (found.entries.isEmpty()) error = "That link held nothing downloadable."
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("YouTube", style = MaterialTheme.typography.headlineSmall)
        Text(
            "A video, playlist, album or channel - the section lists what the " +
                "link holds, and downloads the rows you tick.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("YouTube link") },
            placeholder = { Text("Video, playlist, album or channel") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )
        Button(
            onClick = { run() },
            enabled = !busy && trimmed.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
                Text("Reading...")
            } else {
                Text("Fetch")
            }
        }

        if (busy && entries.isEmpty() && error == null) {
            Text(
                "Reading what this link holds...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val failure = error
        if (failure != null && !busy) {
            Text(
                failure,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            OutlinedButton(
                onClick = { attempt++ },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Retry")
            }
        }

        if (title.isNotBlank() && entries.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${entries.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (single && entries.size == 1) {
            // One video lists every quality and format the extractor offers -
            // every height, frame rate and codec, each with its real size - so
            // there is no row to tap that silently downloads something else.
            val entry = entries.single()
            YouTubeSingleQuality(
                url = entry.url,
                onFetchFormats = onFetchFormats,
                onDownload = { video, audio, audioOnlyRow ->
                    if (audioOnlyRow) {
                        onAddExact(
                            entry.url,
                            entry.title,
                            DownloadCategory.AUDIO,
                            DownloadSource.YOUTUBE,
                            null,
                            null,
                            MediaQuality.AUDIO,
                            AudioFormat.fromValue(video.ext),
                            video.formatId,
                            null
                        )
                    } else {
                        onAddExact(
                            entry.url,
                            entry.title,
                            DownloadCategory.VIDEO,
                            DownloadSource.YOUTUBE,
                            null,
                            null,
                            MediaQuality.entries.firstOrNull { it.maxHeight == video.height }
                                ?: MediaQuality.BEST,
                            AudioFormat.M4A,
                            video.formatId,
                            audio?.formatId
                        )
                    }
                    onDone()
                }
            )
        }

        if (entries.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val selectable = entries.map { it.id }
                            .filterNot { knownIds.contains(it) }.toSet()
                        checked = if (checked.size >= selectable.size) emptySet() else selectable
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = checked.isNotEmpty() &&
                        checked.size >= entries.count { !knownIds.contains(it.id) },
                    onCheckedChange = null
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (checked.isEmpty()) "Select all" else "${checked.size} selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(entries, key = { it.id }) { entry ->
                val known = knownIds.contains(entry.id)
                val on = checked.contains(entry.id)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !known) {
                            checked = if (on) checked - entry.id else checked + entry.id
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = on || known,
                        enabled = !known,
                        onCheckedChange = null
                    )
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = if (known) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                        val uploader = entry.uploader
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (entry.durationSeconds > 0L) {
                                Text(
                                    formatDuration(entry.durationSeconds),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (uploader != null) {
                                if (entry.durationSeconds > 0L) {
                                    Text(
                                        "  ·  ",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    uploader,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (known) {
                                Text(
                                    "  ·  In queue",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }

        // Only for a batch. On a single video the quality block above already has
        // its own Download and its own Video/Audio-only, so this footer was a
        // second Download button and a second row of chips for the same one
        // download - and the ceiling chips on top of a list of exact rows
        // contradict them.
        if (entries.size > 1) {
            // The action first, then what it acts on, matching the desktop panel.
            Button(
                onClick = {
                    val chosen = entries.filter { checked.contains(it.id) }
                    if (chosen.isEmpty()) {
                        error = "Tick at least one video first."
                        return@Button
                    }
                    onQueue(chosen, audioOnly, ceiling)
                    checked = emptySet()
                    onDone()
                },
                enabled = checked.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (checked.isEmpty()) "Download selected" else "Download ${checked.size} selected",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = !audioOnly,
                    onClick = { audioOnly = false },
                    label = { Text("Video") }
                )
                FilterChip(
                    selected = audioOnly,
                    onClick = { audioOnly = true },
                    label = { Text("Audio only") }
                )
                if (!audioOnly) {
                    listOf(null to "Best", 1080 to "1080p", 720 to "720p", 480 to "480p")
                        .forEach { (height, label) ->
                            FilterChip(
                                selected = ceiling == height,
                                onClick = { ceiling = height },
                                label = { Text(label) }
                            )
                        }
                }
            }
            Text(
                if (audioOnly) {
                    "Audio with artwork and details kept inside the file."
                } else {
                    "The best stream at or below the ceiling, joined with audio."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * Every quality and format one video offers, each with its real size.
 *
 * The raw format list, not one row per height: a 4K video shows its 4K rows
 * because nothing collapsed them away, and a row's number is the video plus
 * the best audio, because both transfer. Tapping a row downloads exactly those
 * streams - 1080p60 and 1080p are different rows and different downloads.
 */
@Composable
private fun YouTubeSingleQuality(
    url: String,
    onFetchFormats: suspend (String) -> YouTubeFormatListing,
    onDownload: (video: StreamFormat, audio: StreamFormat?, audioOnly: Boolean) -> Unit
) {
    var listing by remember(url) { mutableStateOf<YouTubeFormatListing?>(null) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    var busy by remember(url) { mutableStateOf(true) }
    var attempt by remember(url) { mutableStateOf(0) }
    var audioOnly by remember(url) { mutableStateOf(false) }
    var chosen by remember(url) { mutableStateOf<StreamFormat?>(null) }

    LaunchedEffect(url, attempt) {
        busy = true
        error = null
        try {
            val found = onFetchFormats(url)
            if (found.error != null) {
                error = found.error
            } else {
                listing = found
                if (found.allFormats.none { it.formatId == chosen?.formatId }) {
                    chosen = found.videoFormats.firstOrNull()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            error = failed.message?.takeIf { it.isNotBlank() } ?: "Could not read that video."
        }
        busy = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Quality", style = MaterialTheme.typography.titleSmall)
        if (busy && listing == null && error == null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
            return@Column
        }
        val failure = error ?: listing?.error
        if (failure != null) {
            Text(
                failure,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            OutlinedButton(onClick = { attempt++ }, modifier = Modifier.fillMaxWidth()) {
                Text("Retry")
            }
            return@Column
        }
        val found = listing ?: return@Column
        // offerVideoRows, not the raw list: the raw one carried previews wearing a
        // real row's label - five rows at five heights all reporting 3.34 MB on one
        // video - which is what made this look like noise rather than a list.
        val videos = com.downloadhub.core.offerVideoRows(found.allFormats)
        val audios = com.downloadhub.core.offerAudioRows(found.allFormats)
        val bestAudio = audios.firstOrNull()
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = !audioOnly,
                onClick = { audioOnly = false },
                label = { Text("Video") }
            )
            FilterChip(
                selected = audioOnly,
                onClick = { audioOnly = true },
                label = { Text("Audio only") }
            )
        }
        // Its own scroll. Dumped straight into the screen's column the rows ran
        // past the bottom with no way to reach them - and the Download button,
        // which came after them, was off-screen entirely.
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
        if (audioOnly) {
            if (audios.isEmpty()) {
                Text(
                    "This video offers no audio-only stream.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                audios.forEach { format ->
                    val bytes = format.sizeBytes
                    FormatRow(
                        title = "${format.qualityLabel} · ${format.ext}",
                        subtitle = buildString {
                            format.totalBitrate?.let { append(it).append(" kbps") }
                            if (bytes != null && bytes > 0L && isNotEmpty()) append("  ·  ")
                        },
                        sizeBytes = bytes,
                        selected = chosen?.formatId == format.formatId,
                        onClick = { chosen = format }
                    )
                }
            }
        } else {
            videos.forEach { format ->
                val total = (format.sizeBytes ?: 0L) + (bestAudio?.sizeBytes ?: 0L)
                FormatRow(
                    // "4K", not "1772p": a row nobody can compare against another
                    // row is not a choice, and the exact height is in the subtitle.
                    title = format.displayLabel,
                    subtitle = buildString {
                        val rate = format.fps
                        if (rate != null && rate >= 50) {
                            append(rate).append(" fps")
                            append("  ·  ")
                        }
                        append(format.ext)
                        format.videoCodec?.let { append("  ·  ").append(it.substringBefore('.')) }
                        append("  ·  needs joining")
                    },
                    sizeBytes = total.takeIf { it > 0L },
                    selected = chosen?.formatId == format.formatId,
                    onClick = { chosen = format }
                )
            }
        }
        }
        Spacer(Modifier.height(8.dp))
        val pick = chosen
        Button(
            onClick = {
                val selected = pick ?: return@Button
                if (audioOnly) {
                    onDownload(selected, null, true)
                } else {
                    val pair = chooseStream(
                        (videos + audios).distinctBy { it.formatId },
                        selected.height ?: 0,
                        audios
                    )
                    // The tapped row, not the height's best: chooseStream answers
                    // the height, and the tap named the stream.
                    onDownload(
                        pair?.video?.takeIf { it.formatId == selected.formatId } ?: selected,
                        pair?.audio,
                        false
                    )
                }
            },
            enabled = pick != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Download")
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
            .padding(vertical = 1.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
            // Tight: one line of title, the detail beside it, nothing else. The
            // rows were a third taller than their content, so a video with twenty
            // formats needed three screens of scrolling to see six of them.
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                if (subtitle.isNotBlank()) {
                    Text(
                        "  $subtitle",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (sizeBytes != null && sizeBytes > 0L) formatBytes(sizeBytes) else "unknown",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
