package com.downloadhub.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    knownIds: Set<String>,
    onQueue: (List<YouTubeEntry>, Boolean, Int?, String) -> Int,
    onSingle: (String) -> Unit,
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

    fun run() {
        val trimmed = link.trim()
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
            // One video keeps the exact chooser: heights, frame rates and real
            // sizes, rather than a ceiling that cannot tell 1080p60 from 1080p.
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                OutlinedButton(onClick = { onSingle(entries.single().url) }) {
                    Text("Choose exact quality")
                }
            }
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

        if (entries.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(AppTheme.Palette.surface)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
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
                Spacer(Modifier.height(8.dp))
                Text(
                    if (audioOnly) {
                        "Audio with artwork and details kept inside the file."
                    } else {
                        "The best stream at or below the ceiling, joined with audio."
                    },
                    fontSize = 11.sp,
                    color = AppTheme.Palette.muted
                )
                Spacer(Modifier.height(8.dp))
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
                    Text("Download ${if (checked.isEmpty()) "" else "${checked.size} "}selected")
                }
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
