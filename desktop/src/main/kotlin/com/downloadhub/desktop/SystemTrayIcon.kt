package com.downloadhub.desktop

import java.awt.Color
import java.awt.Image
import java.awt.Menu
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/**
 * System tray presence.
 *
 * A download manager has to keep running after its window is dismissed, otherwise
 * closing the tab kills a transfer. The window is hidden instead, which also takes
 * it off the taskbar, and the tray icon is the only way back to it.
 *
 * The icon is drawn in code rather than loaded from a resource so it always matches
 * the accent colour the app is using and cannot go missing from a package.
 */
class SystemTrayIcon(
    private val onShow: () -> Unit,
    private val onPauseAll: () -> Unit,
    private val onResumeAll: () -> Unit,
    private val onExit: () -> Unit
) {
    private var trayIcon: java.awt.TrayIcon? = null

    val available: Boolean get() = SystemTray.isSupported()

    fun install() {
        if (!available || trayIcon != null) return
        runCatching {
            val tray = SystemTray.getSystemTray()
            val menu = PopupMenu()

            val show = MenuItem("Open 1 download manager").apply {
                addActionListener { onShow() }
            }
            val resume = MenuItem("Resume all").apply {
                addActionListener { onResumeAll() }
            }
            val pause = MenuItem("Pause all").apply {
                addActionListener { onPauseAll() }
            }
            val exit = MenuItem("Exit").apply {
                addActionListener { onExit() }
            }
            menu.add(show)
            menu.add(separator())
            menu.add(resume)
            menu.add(pause)
            menu.add(separator())
            menu.add(exit)

            val icon = TrayIcon(iconImage(32), "1 download manager")
            icon.setImage(autoSized(icon.image))
            icon.setPopupMenu(menu)
            // Left click is the fastest way back to a hidden window.
            icon.addActionListener { onShow() }
            tray.add(icon)
            trayIcon = icon
        }
    }

    fun update(activeCount: Int, totalSpeed: Long) {
        val icon = trayIcon ?: return
        val text = when {
            activeCount > 0 -> {
                val speed = com.downloadhub.core.DisplayFormat.speed(totalSpeed)
                if (speed.isBlank()) "$activeCount downloading" else "$activeCount • $speed"
            }

            else -> "1 download manager"
        }
        runCatching {

            icon.toolTip = "1 download manager - $text"
        }
    }

    fun remove() {
        trayIcon?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
        trayIcon = null
    }

    /** A disabled, blank item used as a divider; AWT has no real separator. */
    private fun separator(): MenuItem = MenuItem("-").apply { isEnabled = false }

    private fun autoSized(image: Image): Image = runCatching {
        val tray = SystemTray.getSystemTray()
        val size = tray.trayIconSize
        image.getScaledInstance(size.width, size.height, Image.SCALE_SMOOTH)
    }.getOrDefault(image)

    /** A rounded green tile with a download arrow, drawn at [size] pixels. */
    private fun iconImage(size: Int): Image {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON
            )
            val pad = (size * 0.12f).toInt()
            g.color = Color(0x1A, 0x21, 0x24)
            g.fillRoundRect(0, 0, size, size, size / 3, size / 3)

            g.color = Color(0x34, 0xD3, 0x99)
            val stroke = (size * 0.11f).toInt().coerceAtLeast(2)
            g.stroke = java.awt.BasicStroke(stroke.toFloat())

            // Down arrow: a vertical shaft and a chevron.
            val cx = size / 2f
            g.drawLine(cx.toInt(), (size * 0.24f).toInt(), cx.toInt(), (size * 0.58f).toInt())
            g.drawLine(
                (size * 0.34f).toInt(), (size * 0.48f).toInt(),
                cx.toInt(), (size * 0.62f).toInt()
            )
            g.drawLine(
                cx.toInt(), (size * 0.62f).toInt(),
                (size * 0.66f).toInt(), (size * 0.48f).toInt()
            )
            // The tray line the file is landing on.
            g.drawLine(
                (size * 0.28f).toInt(), (size * 0.76f).toInt(),
                (size * 0.72f).toInt(), (size * 0.76f).toInt()
            )
        } finally {
            g.dispose()
        }
        return image
    }
}