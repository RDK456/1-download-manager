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

    fun load(context: Context) {
        expressive = context.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean(KEY, true)
    }

    fun set(context: Context, on: Boolean) {
        expressive = on
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }
}
