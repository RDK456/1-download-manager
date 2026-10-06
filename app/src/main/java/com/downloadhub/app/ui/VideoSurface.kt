package com.downloadhub.app.ui

import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.FastForward
import com.composables.icons.lucide.Rewind
import com.composables.icons.lucide.X
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

/** The two video views a video moves between: the one in the page and the full-screen one. */
private class VideoViews {
    var inline: PlayerView? = null
    var full: PlayerView? = null
}

/**
 * The video. In the page it has button controls - tap shows them - with a close button;
 * full screen it adds the touch gestures: double-tap a side skips 10 seconds, holding plays
 * at 2x, and dragging up or down sets brightness on the left and volume on the right.
 *
 * The page's view stays alive while full screen is open, and the player is handed between
 * the two with [PlayerView.switchTargetView]. Destroying and recreating the view was what
 * made the switch flash black and stutter both ways.
 */
@Composable
fun VideoSurface(modifier: Modifier = Modifier) {
    var fullscreen by remember { mutableStateOf(false) }
    var wantsLandscape by remember { mutableStateOf(false) }
    val views = remember { VideoViews() }
    val activity = LocalContext.current.findActivity()
    val orientation = androidx.compose.ui.platform.LocalConfiguration.current.orientation
    GestureVideo(
        fullscreen = false,
        onFullscreen = {
            // Turn first, open after: a full-screen window opened mid-turn was laid out at
            // the old size and the page showed round it until the turn finished.
            val size = activity?.let { AppPlayer.player(it).videoSize }
            wantsLandscape = !(size != null && size.height > size.width)
            activity?.requestedOrientation = if (wantsLandscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            fullscreen = true
        },
        modifier = modifier,
        views = views
    )
    val turned = !wantsLandscape || orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    if (fullscreen && turned) FullscreenVideo(onExit = { fullscreen = false }, views = views)
}

@Composable
private fun FullscreenVideo(onExit: () -> Unit, views: VideoViews) {
    val activity = LocalContext.current.findActivity()
    Dialog(
        onDismissRequest = onExit,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            // The turn itself was requested before this opened; see VideoSurface.
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
        // The activity rotates in place (it handles orientation itself), so the dialog is not
        // rebuilt when it turns sideways and kept its portrait size: a black box in the middle
        // with the page showing round it. The size and the hidden bars are applied again
        // whenever the screen's size changes.
        val config = androidx.compose.ui.platform.LocalConfiguration.current
        LaunchedEffect(window, config.screenWidthDp, config.screenHeightDp) {
            window?.let { w ->
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                WindowCompat.getInsetsController(w, w.decorView).hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        GestureVideo(fullscreen = true, onFullscreen = onExit, modifier = Modifier.fillMaxSize(), window = window, views = views)
    }
}

@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun GestureVideo(fullscreen: Boolean, onFullscreen: () -> Unit, modifier: Modifier, window: Window? = null, views: VideoViews) {
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

    // Gestures only full screen. In the page a tap just shows the buttons: the gestures
    // fought the page's own scrolling and taps, and the buttons are what a small video
    // needs anyway.
    val gestures = if (!fullscreen) {
        Modifier.pointerInput(Unit) { detectTapGestures(onTap = { controls = !controls }) }
    } else Modifier
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
    Box(modifier.background(Color.Black).then(gestures)) {
        AndroidView(
            factory = { ctx ->
                // From XML, the only way to pick a TextureView; see video_player.xml.
                (LayoutInflater.from(ctx).inflate(R.layout.video_player, null) as PlayerView).also { view ->
                    val player = AppPlayer.player(ctx)
                    if (fullscreen) {
                        views.full = view
                        PlayerView.switchTargetView(player, views.inline, view)
                    } else {
                        views.inline = view
                        if (views.full == null) view.player = player
                    }
                }
            },
            onRelease = { view ->
                if (fullscreen) {
                    views.full = null
                    PlayerView.switchTargetView(AppPlayer.player(view.context), view, views.inline)
                } else {
                    views.inline = null
                    view.player = null
                }
            },
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
                // In the page, a close button: a live channel otherwise had no way to stop.
                if (!fullscreen) {
                    Box(Modifier.align(Alignment.TopEnd)) { VideoButton(Lucide.X, "Close player", AppPlayer::stop) }
                }
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                    if (state.queue.size > 1) VideoButton(Lucide.SkipBack, "Previous", AppPlayer::previous)
                    // The page has no double-tap, so skipping is a pair of buttons there.
                    if (!fullscreen && !live) VideoButton(Lucide.Rewind, "Back 10 seconds") { AppPlayer.seekBy(-10_000) }
                    IconButton(onClick = AppPlayer::toggle, modifier = Modifier.size(64.dp)) {
                        Icon(
                            if (state.playing) Lucide.Pause else Lucide.Play,
                            if (state.playing) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(44.dp)
                        )
                    }
                    if (!fullscreen && !live) VideoButton(Lucide.FastForward, "Forward 10 seconds") { AppPlayer.seekBy(10_000) }
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
