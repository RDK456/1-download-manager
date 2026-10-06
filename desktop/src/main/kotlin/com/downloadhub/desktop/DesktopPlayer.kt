package com.downloadhub.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.downloadhub.core.DownloadItem
import com.downloadhub.core.DownloadStatus
import com.downloadhub.core.Lyrics
import com.downloadhub.core.LyricsSource
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The music player.
 *
 * The app already ships ffmpeg, which reads every audio format there is, so it decodes the
 * file to plain 44.1 kHz stereo samples and Java's own sound output plays them - no player
 * library, no codecs to install. Pausing stops the output; seeking starts ffmpeg again at
 * the new position. Video goes to a real video player (see [openVideo]).
 */
object DesktopPlayer {
    enum class Repeat { OFF, ALL, ONE }

    data class State(
        val queue: List<File> = emptyList(),
        val index: Int = -1,
        val playing: Boolean = false,
        val durationMillis: Long = 0L,
        val volume: Float = 0.8f,
        val repeat: Repeat = Repeat.ALL,
        val error: String? = null
    ) {
        val track: File? get() = queue.getOrNull(index)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private const val RATE = 44_100
    private val format = AudioFormat(RATE.toFloat(), 16, 2, true, false)

    private class Session(val process: Process, val line: SourceDataLine, val startMillis: Long) {
        @Volatile var stopped = false
    }

    @Volatile
    private var session: Session? = null

    private val ffmpeg: File get() = File(AppPaths.toolsDir, "ffmpeg.exe")

    fun play(queue: List<File>, index: Int) {
        _state.update { it.copy(queue = queue, index = index, error = null, durationMillis = 0L) }
        startAt(0L)
    }

    fun toggle() {
        val current = session
        when {
            current == null -> if (_state.value.track != null) startAt(0L)
            _state.value.playing -> { current.line.stop(); _state.update { it.copy(playing = false) } }
            else -> { current.line.start(); _state.update { it.copy(playing = true) } }
        }
    }

    fun seek(millis: Long) = startAt(millis.coerceAtLeast(0L))

    fun next() = move(+1)

    fun previous() = if (position() > 3_000) startAt(0L) else move(-1)

    fun cycleRepeat() = _state.update { it.copy(repeat = Repeat.entries[(it.repeat.ordinal + 1) % Repeat.entries.size]) }

    fun setVolume(volume: Float) {
        _state.update { it.copy(volume = volume.coerceIn(0f, 1f)) }
        session?.line?.let(::applyVolume)
    }

    fun stop() {
        stopSession()
        _state.update { it.copy(playing = false, index = -1) }
    }

    /** Where playback is, from the samples the sound card has actually played. */
    fun position(): Long = session?.let { it.startMillis + it.line.longFramePosition * 1000 / RATE } ?: 0L

    private fun move(step: Int) {
        val state = _state.value
        if (state.queue.isEmpty()) return
        var next = state.index + step
        if (next !in state.queue.indices) {
            if (state.repeat != Repeat.ALL) return stop()
            next = (next + state.queue.size) % state.queue.size
        }
        _state.update { it.copy(index = next, durationMillis = 0L) }
        startAt(0L)
    }

    private fun startAt(millis: Long) {
        stopSession()
        val track = _state.value.track ?: return
        if (!ffmpeg.isFile) {
            _state.update { it.copy(playing = false, error = "The bundled ffmpeg is missing, so audio cannot play") }
            return
        }
        runCatching {
            if (_state.value.durationMillis == 0L) _state.update { it.copy(durationMillis = probeDuration(track)) }
            val process = ProcessBuilder(
                ffmpeg.absolutePath, "-v", "error", "-nostdin",
                "-ss", "%.3f".format(java.util.Locale.US, millis / 1000.0),
                "-i", track.absolutePath, "-vn", "-f", "s16le", "-acodec", "pcm_s16le",
                "-ac", "2", "-ar", RATE.toString(), "-"
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val line = AudioSystem.getSourceDataLine(format).apply {
                open(format, RATE) // a quarter of a second of buffer, so pausing feels immediate
                start()
            }
            applyVolume(line)
            val current = Session(process, line, millis)
            session = current
            _state.update { it.copy(playing = true, error = null) }
            Thread({ pump(current) }, "player-audio").apply { isDaemon = true }.start()
        }.onFailure { failure ->
            _state.update { it.copy(playing = false, error = failure.message ?: "Could not play this file") }
        }
    }

    private fun pump(current: Session) {
        val buffer = ByteArray(16_384)
        runCatching {
            current.process.inputStream.use { input ->
                while (!current.stopped) {
                    val read = input.readNBytes(buffer, 0, buffer.size)
                    if (read <= 0) break
                    current.line.write(buffer, 0, read - read % 4)
                }
            }
        }
        if (current.stopped) return
        runCatching { current.line.drain(); current.line.close() }
        current.process.destroy()
        if (session === current) {
            session = null
            if (_state.value.repeat == Repeat.ONE) startAt(0L) else move(+1)
        }
    }

    private fun stopSession() {
        val current = session ?: return
        session = null
        current.stopped = true
        runCatching { current.line.stop(); current.line.flush(); current.line.close() }
        runCatching { current.process.destroy() }
    }

    private fun applyVolume(line: SourceDataLine) {
        runCatching {
            val gain = line.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
            val volume = _state.value.volume
            gain.value = if (volume <= 0.001f) gain.minimum else (20 * log10(volume)).coerceIn(gain.minimum, gain.maximum)
        }
    }

    /** The track's length, from what ffmpeg prints when asked about it. */
    private fun probeDuration(track: File): Long = runCatching {
        val process = ProcessBuilder(ffmpeg.absolutePath, "-hide_banner", "-nostdin", "-i", track.absolutePath)
            .redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor()
        val match = Regex("""Duration: (\d+):(\d+):(\d+(?:\.\d+)?)""").find(text) ?: return@runCatching 0L
        val (h, m, s) = match.destructured
        (h.toLong() * 3600 + m.toLong() * 60) * 1000 + (s.toDouble() * 1000).toLong()
    }.getOrDefault(0L)

    private val AUDIO = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "aiff", "mka")
    private val VIDEO = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "flv", "wmv", "ts", "mpg", "mpeg")

    fun isAudio(file: File) = file.extension.lowercase() in AUDIO
    fun isVideo(file: File) = file.extension.lowercase() in VIDEO

    /**
     * Opens a video in a real video player: VLC or mpv when installed, Windows' own player
     * otherwise. Drawing video frame by frame inside the app would mean shipping a player.
     */
    fun openVideo(file: File) {
        runCatching {
            val player = externalPlayer()
            if (player != null) ProcessBuilder(player.absolutePath, file.absolutePath).start()
            else java.awt.Desktop.getDesktop().open(file)
        }
    }

    /** VLC, or mpv on the PATH; null when neither is installed. */
    fun externalPlayer(): File? =
        listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"))
            .map { File(it, "VideoLAN/VLC/vlc.exe") }.firstOrNull { it.isFile }
            ?: System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "mpv.exe") }.firstOrNull { it.isFile }

    /** Opens a live stream in VLC or mpv; false when neither is installed to play it. */
    /** Opens a stream, with the user agent and referrer its broadcaster asks for, if any. */
    fun openStream(url: String, userAgent: String? = null, referrer: String? = null): Boolean {
        val player = externalPlayer() ?: return false
        val mpv = player.name.startsWith("mpv", ignoreCase = true)
        val command = buildList {
            add(player.absolutePath)
            userAgent?.let { add(if (mpv) "--user-agent=$it" else "--http-user-agent=$it") }
            referrer?.let { add(if (mpv) "--referrer=$it" else "--http-referrer=$it") }
            add(url)
        }
        return runCatching { ProcessBuilder(command).start() }.isSuccess
    }

    /** The music and videos among finished downloads, including the files inside torrent folders. */
    fun libraryFiles(items: List<DownloadItem>): List<File> = items
        .filter { it.status == DownloadStatus.COMPLETED }
        .mapNotNull { it.location?.let(::File) }
        .flatMap { location ->
            when {
                location.isFile -> listOf(location)
                location.isDirectory -> location.walkTopDown().maxDepth(4).filter { it.isFile }.take(300).toList()
                else -> emptyList()
            }
        }
        .filter { isAudio(it) || isVideo(it) }
        .distinctBy { it.absolutePath.lowercase() }
}

private fun clock(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}

/** The position, read off the player four times a second while something is loaded. */
@Composable
private fun rememberPosition(): Long {
    val state by DesktopPlayer.state.collectAsState()
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.track, state.playing) {
        while (true) {
            position = DesktopPlayer.position()
            delay(250)
        }
    }
    return position
}

/**
 * The bar along the bottom while something is loaded: what is playing, the controls, where
 * it is, and the volume - on every screen, as a music app keeps it.
 */
@Composable
fun MiniPlayerBar(onOpenPlayer: () -> Unit) {
    val state by DesktopPlayer.state.collectAsState()
    val track = state.track ?: return
    val position = rememberPosition()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val (title, artist) = remember(track) { LyricsSource.guessTitleArtist(track.name) }
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(shape)
            .background(AppTheme.Palette.surface)
            .border(1.dp, AppTheme.Palette.outline.copy(alpha = 0.35f), shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.width(200.dp).clickable(onClick = onOpenPlayer)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.Palette.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                state.error ?: artist.ifBlank { "Now playing" },
                fontSize = 11.sp,
                color = if (state.error != null) AppTheme.Palette.error else AppTheme.Palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        PlayerButton(Icons.Default.KeyboardArrowLeft, "Previous") { DesktopPlayer.previous() }
        PlayerButton(if (state.playing) DlmIcons.Pause else Icons.Default.PlayArrow, if (state.playing) "Pause" else "Play", big = true) { DesktopPlayer.toggle() }
        PlayerButton(Icons.Default.KeyboardArrowRight, "Next") { DesktopPlayer.next() }
        Text(clock(position), fontSize = 11.sp, color = AppTheme.Palette.muted, modifier = Modifier.padding(start = 8.dp))
        Slider(
            value = dragging ?: if (state.durationMillis > 0) (position.toFloat() / state.durationMillis).coerceIn(0f, 1f) else 0f,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { DesktopPlayer.seek((it * state.durationMillis).toLong()) }
                dragging = null
            },
            enabled = state.durationMillis > 0,
            colors = SliderDefaults.colors(thumbColor = AppTheme.Palette.accent, activeTrackColor = AppTheme.Palette.accent),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
        )
        Text(clock(state.durationMillis), fontSize = 11.sp, color = AppTheme.Palette.muted)
        PlayerButton(
            Icons.Default.Refresh,
            "Repeat: " + state.repeat.name.lowercase(),
            tint = if (state.repeat == DesktopPlayer.Repeat.OFF) AppTheme.Palette.faint else AppTheme.Palette.accent
        ) { DesktopPlayer.cycleRepeat() }
        if (state.repeat == DesktopPlayer.Repeat.ONE) Text("1", fontSize = 10.sp, color = AppTheme.Palette.accent)
        PlayerButton(Icons.Default.List, "Lyrics and library") { onOpenPlayer() }
        Slider(
            value = state.volume,
            onValueChange = DesktopPlayer::setVolume,
            colors = SliderDefaults.colors(thumbColor = AppTheme.Palette.accent, activeTrackColor = AppTheme.Palette.accent),
            modifier = Modifier.width(110.dp)
        )
    }
}

@Composable
private fun PlayerButton(icon: ImageVector, label: String, big: Boolean = false, tint: Color = AppTheme.Palette.onSurface, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(if (big) 40.dp else 32.dp)) {
        Box(
            Modifier
                .size(if (big) 34.dp else 28.dp)
                .clip(RoundedCornerShape(50))
                .background(if (big) AppTheme.Palette.accent else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, Modifier.size(if (big) 20.dp else 18.dp), tint = if (big) AppTheme.Palette.onAccent else tint)
        }
    }
}

/**
 * The Player section: your music and videos on the left, the lyrics of what is playing on
 * the right, following the song line by line when LRCLIB has timed lyrics.
 */
@Composable
fun PlayerPanel(items: List<DownloadItem>, modifier: Modifier = Modifier) {
    val state by DesktopPlayer.state.collectAsState()
    val files = remember(items) { DesktopPlayer.libraryFiles(items) }
    val audio = files.filter(DesktopPlayer::isAudio)
    Row(modifier.fillMaxSize().background(AppTheme.Palette.background)) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(12.dp)) {
            Text("Your music and videos", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.Palette.onSurface)
            Text("${audio.size} songs · ${files.size - audio.size} videos", fontSize = 11.sp, color = AppTheme.Palette.muted)
            Spacer(Modifier.height(8.dp))
            if (files.isEmpty()) {
                Text("Finished music and video downloads appear here.", fontSize = 12.sp, color = AppTheme.Palette.muted, modifier = Modifier.padding(top = 24.dp))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(files, key = { _, f -> f.absolutePath }) { _, file ->
                    val current = file == state.track
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (current) AppTheme.Palette.selection else Color.Transparent)
                            .clickable {
                                if (DesktopPlayer.isVideo(file)) DesktopPlayer.openVideo(file)
                                else DesktopPlayer.play(audio, audio.indexOf(file))
                            }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (DesktopPlayer.isVideo(file)) DlmIcons.Videos else DlmIcons.Music,
                            null,
                            Modifier.size(16.dp),
                            tint = if (current) AppTheme.Palette.accent else AppTheme.Palette.muted
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(file.nameWithoutExtension, fontSize = 12.sp, color = AppTheme.Palette.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(file.extension.uppercase(), fontSize = 10.sp, color = AppTheme.Palette.faint)
                    }
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(AppTheme.Palette.outline.copy(alpha = 0.3f)))
        LyricsPane(state.track, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun LyricsPane(track: File?, modifier: Modifier) {
    var lyrics by remember(track) { mutableStateOf<Lyrics?>(null) }
    var looked by remember(track) { mutableStateOf(false) }
    LaunchedEffect(track) {
        if (track == null) return@LaunchedEffect
        val (title, artist) = LyricsSource.guessTitleArtist(track.name)
        lyrics = LyricsSource.find(title, artist)
        looked = true
    }
    val position = rememberPosition()
    val listState = rememberLazyListState()
    val current = lyrics?.takeIf { it.synced }?.let { LyricsSource.lineAt(it.lines, position) } ?: -1
    LaunchedEffect(current) { if (current > 2) listState.animateScrollToItem(current - 2) }
    Column(modifier.padding(16.dp)) {
        Text("Lyrics", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.Palette.onSurface)
        Spacer(Modifier.height(8.dp))
        val lines = lyrics?.lines.orEmpty()
        when {
            track == null -> Text("Play a song to see its lyrics.", fontSize = 12.sp, color = AppTheme.Palette.muted)
            !looked -> Text("Looking for lyrics...", fontSize = 12.sp, color = AppTheme.Palette.muted)
            lines.isEmpty() -> Text(
                "No lyrics found for this song. Names like \"Artist - Title\" match best.",
                fontSize = 12.sp,
                color = AppTheme.Palette.muted
            )
            else -> LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(lines) { index, line ->
                    Text(
                        line.text.ifBlank { "♪" },
                        fontSize = if (index == current) 16.sp else 14.sp,
                        fontWeight = if (index == current) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            index == current -> AppTheme.Palette.accent
                            index < current -> AppTheme.Palette.faint
                            else -> AppTheme.Palette.onSurface
                        },
                        modifier = Modifier.clickable(enabled = lyrics?.synced == true) { DesktopPlayer.seek(line.atMillis) }
                    )
                }
            }
        }
    }
}
