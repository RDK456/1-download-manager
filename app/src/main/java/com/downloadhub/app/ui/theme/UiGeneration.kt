package com.downloadhub.app.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Which look the app wears: the redesign (Material 3 Expressive) or the classic one. A user
 * option, kept on the device and read before the first frame, like the other display
 * settings. Changing it takes effect at once, without restarting.
 */
object UiGeneration {
    private const val KEY = "ui_expressive"

    /** True for the redesign. It is the default. */
    var expressive by mutableStateOf(true)
        private set

    /** False until the user has picked a look once (new install or first run after update). */
    var chosen by mutableStateOf(true)
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
        expressive = prefs.getBoolean(KEY, true)
        chosen = prefs.contains(KEY)
    }

    fun set(context: Context, on: Boolean) {
        expressive = on
        chosen = true
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }
}
