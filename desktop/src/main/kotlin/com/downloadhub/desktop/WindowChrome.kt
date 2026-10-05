package com.downloadhub.desktop

import com.downloadhub.core.ThemeColors
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference

/**
 * Paints the native Windows title bar in the app's own colours.
 *
 * AB Download Manager draws its own title bar so the window reads as one surface. Drawing
 * one here would mean re-implementing dragging, edge resizing and snap, all of which the
 * native frame already does correctly. Windows 11 lets an app colour that frame instead,
 * which gets the same look and keeps every native behaviour. On Windows 10 only the dark
 * flag is honoured, and the rest is ignored rather than failing.
 */
internal object WindowChrome {
    private interface Dwm : Library {
        fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntByReference, size: Int): Int
    }

    private val dwm: Dwm? by lazy {
        if (!System.getProperty("os.name", "").startsWith("Windows")) null
        else runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull()
    }

    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_BORDER_COLOR = 34
    private const val DWMWA_CAPTION_COLOR = 35
    private const val DWMWA_TEXT_COLOR = 36

    fun apply(window: java.awt.Window, colors: ThemeColors) {
        val api = dwm ?: return
        runCatching {
            if (!window.isDisplayable) return
            val hwnd = Pointer(Native.getWindowID(window))
            fun set(attribute: Int, value: Int) {
                api.DwmSetWindowAttribute(hwnd, attribute, IntByReference(value), 4)
            }
            set(DWMWA_USE_IMMERSIVE_DARK_MODE, if (colors.isDark) 1 else 0)
            // The caption matches the menu strip under it, so the two read as one bar.
            set(DWMWA_CAPTION_COLOR, colorRef(colors.surface))
            set(DWMWA_TEXT_COLOR, colorRef(colors.onSurface))
            set(DWMWA_BORDER_COLOR, colorRef(colors.outlineVariant))
        }
    }

    /** ARGB to a Win32 COLORREF, which is 0x00BBGGRR. */
    internal fun colorRef(argb: Long): Int {
        val r = (argb shr 16 and 0xFF).toInt()
        val g = (argb shr 8 and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return (b shl 16) or (g shl 8) or r
    }
}
