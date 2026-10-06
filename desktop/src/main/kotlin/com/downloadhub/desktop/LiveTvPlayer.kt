package com.downloadhub.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.VolumeX
import com.downloadhub.core.IptvChannel
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/**
 * One live channel playing inside the app, with the ffmpeg the app already ships.
 *
 * A single ffmpeg reads the channel at its own pace (`-re`) and writes two things: raw
 * frames, scaled and letterboxed to [WIDTH] x [HEIGHT], to its standard output, and plain
 * 44.1 kHz stereo samples to a socket on this machine. One process for both is what keeps
 * sound and picture together. The channel's user agent and referrer go with every request.
 */
private class LiveStream(
    channel: IptvChannel,
    private val onFrame: (ImageBitmap) -> Unit,
    private val onEnded: (String) -> Unit
) {
    @Volatile var muted = false
    @Volatile private var stopped = false
    @Volatile private var lastError: String? = null
    private val audioServer = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).apply { soTimeout = 30_000 }
    private var line: SourceDataLine? = null
    private val process: Process

    init {
        val command = buildList {
            add(DesktopPlayer.ffmpeg.absolutePath)
            addAll(listOf("-hide_banner", "-loglevel", "error"))
            channel.userAgent?.let { addAll(listOf("-user_agent", it)) }
            channel.referrer?.let { addAll(listOf("-headers", "Referer: $it\r\n")) }
            addAll(listOf("-re", "-i", channel.url))
            addAll(
                listOf(
                    "-map", "0:v:0",
                    "-vf", "scale=$WIDTH:$HEIGHT:force_original_aspect_ratio=decrease,pad=$WIDTH:$HEIGHT:(ow-iw)/2:(oh-ih)/2",
                    "-pix_fmt", "bgra", "-f", "rawvideo", "pipe:1"
                )
            )
            // ponytail: a channel with no audio track fails here; split the outputs if that turns up.
            addAll(listOf("-map", "0:a:0", "-ac", "2", "-ar", "44100", "-f", "s16le", "tcp://127.0.0.1:${audioServer.localPort}"))
        }
        process = ProcessBuilder(command).start()
        thread(isDaemon = true, name = "tv-log") {
            process.errorStream.bufferedReader().forEachLine { if (it.isNotBlank()) lastError = it.trim() }
        }
        thread(isDaemon = true, name = "tv-audio") { playAudio() }
        thread(isDaemon = true, name = "tv-video") { readFrames() }
    }

    private fun readFrames() {
        val frame = ByteArray(WIDTH * HEIGHT * 4)
        val info = ImageInfo(WIDTH, HEIGHT, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
        val input = DataInputStream(process.inputStream.buffered(frame.size))
        try {
            while (!stopped) {
                input.readFully(frame)
                onFrame(org.jetbrains.skia.Image.makeRaster(info, frame, WIDTH * 4).toComposeImageBitmap())
            }
        } catch (_: Exception) {
            // End of stream, or stop() closed it.
        }
        if (!stopped) onEnded(friendly(lastError))
    }

    private fun playAudio() {
        runCatching {
            audioServer.accept().use { socket ->
                val format = AudioFormat(44_100f, 16, 2, true, false)
                val out = AudioSystem.getSourceDataLine(format).apply { open(format, 44_100); start() }
                line = out
                val buffer = ByteArray(8_192)
                val input = socket.getInputStream()
                while (!stopped) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (muted) buffer.fill(0, 0, read)
                    out.write(buffer, 0, read)
                }
            }
        }
    }

    fun stop() {
        stopped = true
        runCatching { process.destroyForcibly() }
        runCatching { audioServer.close() }
        runCatching { line?.stop(); line?.close() }
    }

    private fun friendly(error: String?): String = when {
        error == null -> "The channel stopped sending."
        "403" in error || "401" in error -> "The channel refused the connection. It may be blocked in your region."
        "404" in error -> "This channel is no longer at that address."
        "Connection" in error || "timed out" in error -> "Could not reach the channel. It may be offline."
        else -> "This channel could not be played: $error"
    }

    companion object {
        const val WIDTH = 960
        const val HEIGHT = 540
    }
}

/**
 * The channel being watched, filling the TV panel: the picture, a back arrow, mute, and -
 * when VLC or mpv is installed - an option to move it there.
 */
@Composable
fun LiveTvPlayer(
    channel: IptvChannel,
    onClose: () -> Unit,
    onOpenExternal: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    var frame by remember(channel.url) { mutableStateOf<ImageBitmap?>(null) }
    var problem by remember(channel.url) { mutableStateOf<String?>(null) }
    var muted by remember { mutableStateOf(false) }
    var stream by remember { mutableStateOf<LiveStream?>(null) }

    DisposableEffect(channel.url) {
        // One sound at a time: the music player pauses while a channel plays.
        if (DesktopPlayer.state.value.playing) DesktopPlayer.toggle()
        val started = runCatching { LiveStream(channel, { frame = it }, { problem = it }) }
            .onFailure { problem = "Could not start the player: ${it.message}" }
            .getOrNull()
        started?.muted = muted
        stream = started
        onDispose { started?.stop() }
    }

    Column(modifier.fillMaxSize().background(AppTheme.Palette.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = onClose) { Icon(Lucide.ArrowLeft, "Back to channels", Modifier.size(20.dp), tint = AppTheme.Palette.onSurface) }
            Text(
                channel.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppTheme.Palette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { muted = !muted; stream?.muted = muted }) {
                Icon(if (muted) Lucide.VolumeX else Lucide.Volume2, if (muted) "Unmute" else "Mute", Modifier.size(20.dp), tint = AppTheme.Palette.onSurface)
            }
            if (onOpenExternal != null) {
                TextButton(onClick = onOpenExternal) {
                    Icon(Lucide.ExternalLink, null, Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Open in VLC", fontSize = 12.sp)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            frame?.let { Image(it, channel.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            val message = problem
            when {
                message != null -> Text(message, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
                frame == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Connecting to ${channel.name}…", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                }
            }
        }
    }
}
