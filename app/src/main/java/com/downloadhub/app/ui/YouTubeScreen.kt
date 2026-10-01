package com.downloadhub.app.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.downloadhub.app.download.AppPlaylistFetch
import com.downloadhub.core.YouTubeEntry
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
    onFetch: suspend (String) -> AppPlaylistFetch,
    onQueue: (List<YouTubeEntry>, Boolean, Int?) -> Unit,
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
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    // The link the list belongs to. Typing after a fetch must not silently
    // reassign the rows to a link they did not come from.
    var fetchedLink by remember { mutableStateOf("") }

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
            OutlinedButton(
                onClick = { onSingle(entries.single().url) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Choose exact quality")
            }
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

        if (entries.isNotEmpty()) {
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
            Button(
                onClick = {
                    val chosen = entries.filter { checked.contains(it.id) }
                    if (chosen.isEmpty()) {
                        error = "Tick at least one video first."
                        return@Button
                    }
                    onQueue(chosen, audioOnly, ceiling)
                    checked = emptySet()
                },
                enabled = checked.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (checked.isEmpty()) "Download selected" else "Download ${checked.size} selected")
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
