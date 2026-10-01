package com.downloadhub.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.downloadhub.core.YouTubeEntry
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The YouTube section: paste anything, take the lot.
 *
 * A playlist, an album, a channel, an artist page, a likes list, or one video -
 * the panel lists what the link holds with a checkbox per row, and downloads the
 * checked ones. Modelled on the way soundcli treats a link as the start of a
 * library rather than as one file: almost any link works, the same song is
 * never queued twice, and an audio download keeps its artwork and details
 * inside the file.
 *
 * One quality for the batch, chosen up front. Per-video exact streams would need
 * a lookup per row, which is a playlist that lists in minutes; the ceiling is
 * the honest choice for many, and a single video keeps the exact chooser via
 * [onSingle].
 */
@Composable
fun YouTubePanel(
    fetch: (String, (YtDlpEngine.PlaylistFetch) -> Unit) -> Unit,
    /** A link handed over by New Download, loaded once then forgotten. */
    prefill: String? = null,
    onPrefillConsumed: () -> Unit = {},
    knownIds: Set<String>,
    onQueue: (List<YouTubeEntry>, Boolean, Int?, String) -> Int,
    /** Reads one video's full format list, with every height, frame rate and codec. */
    fetchFormats: (String, (YtDlpEngine.FormatListing) -> Unit) -> Unit,
    /**
     * Queues one video at its exact streams, then returns to the Downloads list.
     *
     * The title travels with it. Without it the row is named from the URL, and a
     * video URL's last segment is the route - so the row read "watch" until the
     * extractor got far enough to rename it.
     */
    onPickExact: (String, com.downloadhub.core.StreamChoice, Boolean, String) -> Unit,
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
    var message by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    fun run() {        val trimmed = link.trim()
        if (trimmed.isEmpty()) {
            message = "Paste a link first: a video, playlist, album or channel."
            return
        }
        job?.cancel()
        message = null
        busy = true
        entries = emptyList()
        checked = emptySet()
        title = ""
        single = false
        job = scope.launch {
            // The callback may never come if the panel leaves mid-lookup; the
            // state writes below are harmless then, and the job is cancelled
            // with the scope.
            var answered = false
            fetch(trimmed) { found ->
                answered = true
                busy = false
                if (found.error != null) {
                    message = found.error
                } else {
                    title = found.title
                    single = found.single
                    entries = found.entries
                    // Everything checkable starts checked, minus what is already
                    // queued: the common case is "the whole playlist", and
                    // re-queuing it should take no clicks at all.
                    checked = found.entries
                        .map { it.id }
                        .filterNot { knownIds.contains(it) }
                        .toSet()
                    if (found.entries.isEmpty()) message = "That link held nothing downloadable."
                }
            }
            if (!answered) busy = false
        }
    }

    // A handed-over link loads at once, then is forgotten: later recompositions
    // must not re-fetch it over what was typed here since.
    LaunchedEffect(prefill) {
        val incoming = prefill?.trim().orEmpty()
        if (incoming.isNotEmpty()) {
            link = incoming
            onPrefillConsumed()
            run()
        }
    }

    Column(modifier.fillMaxSize().background(AppTheme.Palette.background)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text("YouTube link", fontSize = 12.sp) },
                placeholder = {
                    Text(
                        "Video, playlist, album or channel",
                        fontSize = 12.sp,
                        color = AppTheme.Palette.faint
                    )
                },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { run() }, enabled = !busy) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(14.dp).height(14.dp),
                        strokeWidth = 2.dp,
                        color = AppTheme.Palette.onAccent
                    )
                } else {
                    Text("Fetch")
                }
            }
        }

        if (message != null) {
            Text(
                message!!,
                fontSize = 12.sp,
                color = AppTheme.Palette.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        if (title.isNotBlank() && entries.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.Palette.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${entries.size} ${if (entries.size == 1) "video" else "videos"}",
                    fontSize = 11.sp,
                    color = AppTheme.Palette.muted
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
                fetchFormats = fetchFormats,
                onPick = { choice, audioOnly -> onPickExact(entry.url, choice, audioOnly, entry.title) }
            )
        }

        if (entries.size > 1) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        val selectable = entries.map { it.id }.filterNot { knownIds.contains(it) }.toSet()
                        checked = if (checked.size >= selectable.size) emptySet() else selectable
                    }
                    .padding(horizontal = 16.dp, vertical = 4.dp),
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
                    fontSize = 12.sp,
                    color = AppTheme.Palette.muted
                )
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(entries, key = { it.id }) { entry ->
                val known = knownIds.contains(entry.id)
                val on = checked.contains(entry.id)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !known) {
                            checked = if (on) checked - entry.id else checked + entry.id
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
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
                            fontSize = 13.sp,
                            color = if (known) AppTheme.Palette.faint else AppTheme.Palette.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val uploader = entry.uploader
                            if (entry.durationSeconds > 0L) {
                                Text(
                                    formatDuration(entry.durationSeconds),
                                    fontSize = 11.sp,
                                    color = AppTheme.Palette.faint
                                )
                            }
                            if (uploader != null) {
                                if (entry.durationSeconds > 0L) {
                                    Text("  ·  ", fontSize = 11.sp, color = AppTheme.Palette.faint)
                                }
                                Text(
                                    uploader,
                                    fontSize = 11.sp,
                                    color = AppTheme.Palette.faint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (known) {
                                Text("  ·  In queue", fontSize = 11.sp, color = AppTheme.Palette.accent)
                            }
                        }
                    }
                }
            }
        }

        // Only for a batch. On a single video the block above already has its own
        // Download and its own Video/Audio-only, so this footer was a second
        // Download button and a second row of chips for the same one download -
        // and the ceiling chips on top of a list of exact rows contradicts them.
        if (entries.size > 1) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(AppTheme.Palette.surface)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                // The action first, then what it acts on. The button was below the
                // chips and its label was long enough to clip at this width, which
                // left the one control that does the thing as the least visible.
                Button(
                    onClick = {
                        val chosen = entries.filter { checked.contains(it.id) }
                        if (chosen.isEmpty()) {
                            message = "Tick at least one video first."
                            return@Button
                        }
                        val queued = onQueue(chosen, audioOnly, ceiling, "m4a")
                        val skipped = chosen.size - queued
                        message = buildString {
                            append("Queued $queued")
                            if (skipped > 0) append(", $skipped already in the queue")
                            append(".")
                        }
                        // Queued rows join the known ones, so a second press queues
                        // nothing twice.
                        checked = emptySet()
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
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = !audioOnly,
                        onClick = { audioOnly = false },
                        label = { Text("Video", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = audioOnly,
                        onClick = { audioOnly = true },
                        label = { Text("Audio only", fontSize = 12.sp) }
                    )
                    if (!audioOnly) {
                        listOf(null to "Best", 1080 to "1080p", 720 to "720p", 480 to "480p").forEach { (height, label) ->
                            FilterChip(
                                selected = ceiling == height,
                                onClick = { ceiling = height },
                                label = { Text(label, fontSize = 12.sp) }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (audioOnly) {
                        "Audio with artwork and details kept inside the file."
                    } else {
                        "The best stream at or below the ceiling, joined with audio."
                    },
                    fontSize = 11.sp,
                    color = AppTheme.Palette.muted
                )
            }
        }
    }
}

/** 125 -> "2:05", 3661 -> "1:01:01". Zero is not a duration and never reaches here. */
private fun formatDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

/**
 * Every quality and format one video offers, each with its real size.
 *
 * The raw format list, not one row per height: a 4K video shows its 4K rows
 * because nothing collapsed them away, and a row's number is the video plus
 * the best audio, because both transfer. Picking a row queues exactly those
 * streams and returns to the Downloads list.
 */
@Composable
private fun YouTubeSingleQuality(
    url: String,
    fetchFormats: (String, (YtDlpEngine.FormatListing) -> Unit) -> Unit,
    onPick: (com.downloadhub.core.StreamChoice, Boolean) -> Unit
) {
    var listing by remember(url) { mutableStateOf<YtDlpEngine.FormatListing?>(null) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    var busy by remember(url) { mutableStateOf(true) }
    var attempt by remember(url) { mutableStateOf(0) }
    var audioOnly by remember(url) { mutableStateOf(false) }
    var chosen by remember(url) { mutableStateOf<com.downloadhub.core.StreamFormat?>(null) }

    LaunchedEffect(url, attempt) {
        busy = true
        error = null
        fetchFormats(url) { found ->
            if (found.error != null) {
                error = found.error
            } else {
                listing = found
                if (found.allFormats.none { it.formatId == chosen?.formatId }) {
                    chosen = found.videoFormats.firstOrNull()
                }
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("Quality", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.Palette.onSurface)
        if (busy && listing == null && error == null) {
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AppTheme.Palette.accent, modifier = Modifier.size(22.dp))
            }
            return@Column
        }
        val failure = error ?: listing?.error
        if (failure != null) {
            Text(failure, fontSize = 12.sp, color = AppTheme.Palette.error)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { attempt++ }) { Text("Retry") }
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
                label = { Text("Video", fontSize = 12.sp) }
            )
            FilterChip(
                selected = audioOnly,
                onClick = { audioOnly = true },
                label = { Text("Audio only", fontSize = 12.sp) }
            )
        }
        Spacer(Modifier.height(4.dp))
        // Its own scroll. Dumped straight into the panel's column the rows ran past
        // the bottom of the window with no way to reach them - and the Download
        // button, which came after them, was off-screen entirely.
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
                    fontSize = 12.sp,
                    color = AppTheme.Palette.muted
                )
            } else {
                audios.forEach { format ->
                    val bytes = format.sizeBytes
                    QualityRow(
                        title = "${format.qualityLabel} · ${format.ext}",
                        subtitle = buildString {
                            format.totalBitrate?.let { append(it).append(" kbps") }
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
                QualityRow(
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
                    onPick(com.downloadhub.core.StreamChoice(selected, null), true)
                } else {
                    val pair = com.downloadhub.core.chooseStream(
                        (videos + audios).distinctBy { it.formatId },
                        selected.height ?: 0,
                        audios
                    )
                    // The tapped row, not the height's best: chooseStream answers
                    // the height, and the tap named the stream.
                    onPick(
                        com.downloadhub.core.StreamChoice(
                            pair?.video?.takeIf { it.formatId == selected.formatId } ?: selected,
                            pair?.audio
                        ),
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
private fun QualityRow(
    title: String,
    subtitle: String,
    /** Null renders as "size unknown": the extractor did not say. */
    sizeBytes: Long?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .background(
                if (selected) AppTheme.Palette.accentContainer else AppTheme.Palette.surface,
                RoundedCornerShape(4.dp)
            )
            .clickable(onClick = onClick)
            // Tight: one line of title, one of detail, and nothing else. The rows
            // were a third taller than their content, so a video with twenty formats
            // needed three screens of scrolling to see six of them.
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) AppTheme.Palette.accent else AppTheme.Palette.onSurface
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        "  $subtitle",
                        fontSize = 10.sp,
                        color = AppTheme.Palette.faint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (sizeBytes != null && sizeBytes > 0L) {
                com.downloadhub.core.DisplayFormat.bytes(sizeBytes)
            } else {
                "unknown"
            },
            fontSize = 11.sp,
            color = AppTheme.Palette.muted
        )
    }
}
