package com.downloadhub.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.downloadhub.core.Lyrics
import com.downloadhub.core.LyricsSource
import kotlinx.coroutines.delay

@Composable
private fun rememberPlayerPosition(): Long {
    val state by AppPlayer.state.collectAsState()
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.current, state.playing) {
        while (true) {
            position = AppPlayer.position()
            delay(300)
        }
    }
    return position
}

/** The bar above the tabs while something is loaded: what it is, play/pause, next, and progress. */
@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val state by AppPlayer.state.collectAsState()
    val current = state.current ?: return
    val position = rememberPlayerPosition()
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column {
            if (!current.isLive && state.durationMillis > 0) {
                LinearProgressIndicator(
                    progress = { (position.toFloat() / state.durationMillis).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(2.dp)
                )
            }
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (current.isVideo) Icons.Default.Movie else Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(current.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        state.error ?: if (current.isLive) "Live" else if (state.buffering) "Loading..." else "Now playing",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = AppPlayer::toggle) {
                    Icon(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.playing) "Pause" else "Play")
                }
                if (state.queue.size > 1) IconButton(onClick = AppPlayer::next) { Icon(Icons.Default.SkipNext, "Next") }
            }
        }
    }
}

/**
 * The Player: the video (or the song's lyrics, following along), the controls, and every
 * finished music and video download to pick from.
 */
@Composable
fun PlayerScreen(library: List<AppPlayer.Item>) {
    val context = LocalContext.current
    val state by AppPlayer.state.collectAsState()
    val current = state.current
    // The video stays above the list rather than in it: a video surface scrolled inside a
    // lazy list flickers and drops frames.
    Column(Modifier.fillMaxSize()) {
    if (current?.isVideo == true) {
        VideoSurface(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
    }
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (current != null) {
            item(key = "now") {
                Column(Modifier.padding(16.dp)) {
                    Text(current.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (!current.isVideo) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = AppPlayer::previous) { Icon(Icons.Default.SkipPrevious, "Previous") }
                            FilledIconButton(onClick = AppPlayer::toggle, modifier = Modifier.size(56.dp)) {
                                Icon(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(30.dp))
                            }
                            IconButton(onClick = AppPlayer::next) { Icon(Icons.Default.SkipNext, "Next") }
                        }
                        LyricsCard(current.title)
                    }
                }
            }
        }
        item(key = "heading") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Your music and videos", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                if (library.isEmpty()) {
                    Text("Finished music and video downloads appear here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        itemsIndexed(library, key = { _, item -> item.uri }) { index, item ->
            val playing = item.uri == current?.uri
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (playing) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent)
                    .clickable {
                        // Songs play as a list, so next and previous move through them; a video plays alone.
                        if (item.isVideo) AppPlayer.play(context, listOf(item), 0)
                        else {
                            val songs = library.filter { !it.isVideo }
                            AppPlayer.play(context, songs, songs.indexOf(item))
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (item.isVideo) Icons.Default.Movie else Icons.Default.MusicNote,
                    null,
                    tint = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(12.dp))
                Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (index < library.lastIndex) {
                HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
    }
}

/** The song's lyrics from LRCLIB, following the song line by line when they are timed. */
@Composable
private fun LyricsCard(title: String) {
    var lyrics by remember(title) { mutableStateOf<Lyrics?>(null) }
    var looked by remember(title) { mutableStateOf(false) }
    LaunchedEffect(title) {
        val (song, artist) = LyricsSource.guessTitleArtist(title)
        lyrics = LyricsSource.find(song, artist)
        looked = true
    }
    val position = rememberPlayerPosition()
    val current = lyrics?.takeIf { it.synced }?.let { LyricsSource.lineAt(it.lines, position) } ?: -1
    val listState = rememberLazyListState()
    LaunchedEffect(current) { if (current > 1) listState.animateScrollToItem(current - 1) }
    Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        val lines = lyrics?.lines.orEmpty()
        when {
            !looked -> Text("Looking for lyrics...", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
            lines.isEmpty() -> Text("No lyrics found. Names like \"Artist - Title\" match best.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
            else -> LazyColumn(state = listState, modifier = Modifier.heightIn(max = 280.dp).padding(16.dp)) {
                itemsIndexed(lines) { index, line ->
                    Text(
                        line.text.ifBlank { "♪" },
                        style = if (index == current) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                        fontWeight = if (index == current) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            index == current -> MaterialTheme.colorScheme.primary
                            index < current -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.padding(vertical = 3.dp).clickable(enabled = lyrics?.synced == true) { AppPlayer.seek(line.atMillis) }
                    )
                }
            }
        }
    }
}
