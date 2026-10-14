package com.downloadhub.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlinx.coroutines.delay

/**
 * Brings a freshly opened dialog in front of the browser that caught the download.
 *
 * Windows only lets the foreground process take focus, so a plain toFront() from an app that
 * sits in the tray leaves the window behind the browser (it only flashes in the taskbar).
 * Joining the foreground window's input queue for a moment makes SetForegroundWindow
 * honoured; no key presses are sent to the browser.
 */
object ForegroundWindow {
    @Suppress("FunctionName")
    private interface User32 : Library {
        fun GetForegroundWindow(): Pointer?
        fun GetWindowThreadProcessId(hwnd: Pointer?, pid: Pointer?): Int
        fun AttachThreadInput(from: Int, to: Int, attach: Boolean): Boolean
        fun SetForegroundWindow(hwnd: Pointer): Boolean
        fun BringWindowToTop(hwnd: Pointer): Boolean
        fun ShowWindow(hwnd: Pointer, cmd: Int): Boolean
        fun IsIconic(hwnd: Pointer): Boolean
    }

    @Suppress("FunctionName")
    private interface Kernel32 : Library {
        fun GetCurrentThreadId(): Int
    }

    private val user32 by lazy {
        runCatching {
            if (System.getProperty("os.name").orEmpty().startsWith("Windows")) Native.load("user32", User32::class.java) else null
        }.getOrNull()
    }
    private val kernel32 by lazy { runCatching { Native.load("kernel32", Kernel32::class.java) }.getOrNull() }

    private const val SW_RESTORE = 9

    /** Waits a moment for the native window to exist, then raises it; falls back to plain AWT. */
    suspend fun raise(window: java.awt.Window) {
        delay(150)
        window.isAlwaysOnTop = true
        window.toFront()
        window.requestFocus()
        runCatching { nativeRaise(window) }
        window.isAlwaysOnTop = false
    }

    private fun nativeRaise(window: java.awt.Window) {
        val user = user32 ?: return
        val hwnd = Pointer(Native.getWindowID(window))
        if (user.IsIconic(hwnd)) user.ShowWindow(hwnd, SW_RESTORE)
        val me = kernel32?.GetCurrentThreadId() ?: 0
        val theirs = user.GetWindowThreadProcessId(user.GetForegroundWindow(), null)
        val joined = theirs != 0 && theirs != me && user.AttachThreadInput(me, theirs, true)
        try {
            user.BringWindowToTop(hwnd)
            user.SetForegroundWindow(hwnd)
        } finally {
            if (joined) user.AttachThreadInput(me, theirs, false)
        }
    }
}
