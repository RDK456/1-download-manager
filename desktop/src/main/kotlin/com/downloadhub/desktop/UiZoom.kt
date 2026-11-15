package com.downloadhub.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** Interface size, changed with Ctrl +, Ctrl - and Ctrl 0 as in a browser; remembered between runs. */
object UiZoom {
    private val prefs = java.util.prefs.Preferences.userRoot().node("com/downloadhub/desktop")
    private val steps = listOf(0.8f, 0.9f, 1.0f, 1.1f, 1.25f, 1.5f, 1.75f, 2.0f)

    var scale by mutableFloatStateOf(runCatching { prefs.getFloat("uiZoom", 1f) }.getOrDefault(1f))
        private set

    private fun set(value: Float) {
        scale = value
        runCatching { prefs.putFloat("uiZoom", value) }
    }

    /** Handles the zoom keys; false when [key] is not one of them. */
    fun onKey(key: Key): Boolean = when (key) {
        Key.Equals, Key.Plus, Key.NumPadAdd -> { set(steps.firstOrNull { it > scale + 0.001f } ?: steps.last()); true }
        Key.Minus, Key.NumPadSubtract -> { set(steps.lastOrNull { it < scale - 0.001f } ?: steps.first()); true }
        Key.Zero, Key.NumPad0 -> { set(1f); true }
        else -> false
    }
}

@Composable
fun ZoomHost(content: @Composable () -> Unit) {
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density * UiZoom.scale, base.fontScale), content = content)
}
