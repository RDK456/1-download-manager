package com.downloadhub.app.ui

import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize
import com.composables.icons.lucide.Minimize
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.SkipBack
import com.composables.icons.lucide.SkipForward
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.provider.Settings
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.PlayerView
import com.downloadhub.app.R
import kotlinx.coroutines.delay

/**
 * The video with touch controls: tap shows the controls, double-tap a side skips 10 seconds,
 * holding plays at 2x, and dragging up or down sets brightness on the left and volume on the
 * right. The full-screen button opens the same thing over the whole screen.
 */
@Composable
fun VideoSurface(modifier: Modifier = Modifier) {
    var fullscreen by remember { mutableStateOf(false) }
    if (fullscreen) {
        // A stand-in keeps the page layout while the video plays full screen.
        Box(modifier.background(Color.Black))
        FullscreenVideo(onExit = { fullscreen = false })
    } else {
        GestureVideo(fullscreen = false, onFullscreen = { fullscreen = true }, modifier = modifier)
    }
}

@Composable
private fun FullscreenVideo(onExit: () -> Unit) {
    val activity = LocalContext.current.findActivity()
    Dialog(
        onDismissRequest = onExit,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            val size = activity?.let { AppPlayer.player(it).videoSize }
            // Turn sideways for wide video; tall video stays upright.
            activity?.requestedOrientation = if (size != null && size.height > size.width) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            window?.let { w ->
                // Edge to edge, over the camera cutout too: otherwise the dialog stops short of
                // the cutout side in landscape and the page underneath shows through.
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                w.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
                w.setDimAmount(0f)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    w.attributes = w.attributes.apply {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
                WindowCompat.getInsetsController(w, w.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
        GestureVideo(fullscreen = true, onFullscreen = onExit, modifier = Modifier.fillMaxSize(), window = window)
    }
}

@Composable
private fun GestureVideo(fullscreen: Boolean, onFullscreen: () -> Unit, modifier: Modifier, window: Window? = null) {
    val context = LocalContext.current
    val state by AppPlayer.state.collectAsState()
    val live = state.current?.isLive == true
    val targetWindow = window ?: context.findActivity()?.window
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }

    var controls by remember { mutableStateOf(true) }
    var hint by remember { mutableStateOf<String?>(null) }
    var hintAt by remember { mutableLongStateOf(0L) }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume) }
    var brightness by remember { mutableFloatStateOf(currentBrightness(targetWindow, context)) }
    var position by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }

    fun show(text: String) {
        hint = text
        hintAt = System.currentTimeMillis()
    }

    LaunchedEffect(hintAt) {
        delay(800)
        if (System.currentTimeMillis() - hintAt >= 800) hint = null
    }
    LaunchedEffect(controls, state.playing) {
        if (controls && state.playing) {
            delay(3_000)
            controls = false
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            position = AppPlayer.position()
            delay(250)
        }
    }

    Box(
        modifier
            .background(Color.Black)
            .pointerInput(live) {
                detectTapGestures(
                    onTap = { controls = !controls },
                    onDoubleTap = { offset ->
                        if (!live) {
                            val forward = offset.x > size.width / 2
                            AppPlayer.seekBy(if (forward) 10_000 else -10_000)
                            show(if (forward) "+10 s" else "-10 s")
                        }
                    },
                    onLongPress = {
                        if (!live) {
                            AppPlayer.setSpeed(2f)
                            hint = "2x speed"
                            hintAt = Long.MAX_VALUE
                        }
                    },
                    onPress = {
                        tryAwaitRelease()
                        if (hintAt == Long.MAX_VALUE) {
                            AppPlayer.setSpeed(1f)
                            show("1x")
                        }
                    }
                )
            }
            .pointerInput(targetWindow) {
                var leftSide = false
                detectVerticalDragGestures(
                    onDragStart = { offset -> leftSide = offset.x < size.width / 2 },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        val step = -dy / size.height
                        if (leftSide) {
                            brightness = (brightness + step).coerceIn(0.01f, 1f)
                            targetWindow?.let { w -> w.attributes = w.attributes.apply { screenBrightness = brightness } }
                            show("Brightness ${(brightness * 100).toInt()}%")
                        } else {
                            volume = (volume + step).coerceIn(0f, 1f)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (volume * maxVolume).toInt(), 0)
                            show("Volume ${(volume * 100).toInt()}%")
                        }
                    }
                )
            }
    ) {
        AndroidView(
            factory = { ctx ->
                // From XML, the only way to pick a TextureView; see video_player.xml.
                (LayoutInflater.from(ctx).inflate(R.layout.video_player, null) as PlayerView).apply {
                    player = AppPlayer.player(ctx)
                }
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize()
        )

        if (state.buffering && state.error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(40.dp), color = Color.White, strokeWidth = 3.dp)
        }
        state.error?.let {
            Text(
                it,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
            )
        }

        hint?.let {
            Text(
                it,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }

        if (controls) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                    if (state.queue.size > 1) VideoButton(Lucide.SkipBack, "Previous", AppPlayer::previous)
                    IconButton(onClick = AppPlayer::toggle, modifier = Modifier.size(64.dp)) {
                        Icon(
                            if (state.playing) Lucide.Pause else Lucide.Play,
                            if (state.playing) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(44.dp)
                        )
                    }
                    if (state.queue.size > 1) VideoButton(Lucide.SkipForward, "Next", AppPlayer::next)
                }
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (live) {
                        Text("LIVE", color = Color.Red, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp).weight(1f))
                    } else {
                        val duration = state.durationMillis.coerceAtLeast(1L)
                        val shown = dragging ?: (position.toFloat() / duration).coerceIn(0f, 1f)
                        Text(clock((shown * duration).toLong()), color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Slider(
                            value = shown,
                            onValueChange = { dragging = it },
                            onValueChangeFinished = {
                                dragging?.let { AppPlayer.seek((it * duration).toLong()) }
                                dragging = null
                            },
                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White),
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        )
                        Text(clock(state.durationMillis), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                    VideoButton(
                        if (fullscreen) Lucide.Minimize else Lucide.Maximize,
                        if (fullscreen) "Exit full screen" else "Full screen",
                        onFullscreen
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, label, tint = Color.White) }
}

/** The window's brightness override, or the system brightness when there is none. */
private fun currentBrightness(window: Window?, context: Context): Float {
    val own = window?.attributes?.screenBrightness ?: -1f
    if (own >= 0f) return own
    return runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
}

private fun clock(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
