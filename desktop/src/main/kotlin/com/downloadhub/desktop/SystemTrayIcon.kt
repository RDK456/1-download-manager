package com.downloadhub.desktop

import java.awt.Color
import java.awt.Polygon
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
    /**
     * The same artwork as the app icon: a folder with a download arrow on it.
     *
     * Drawn from the same 108-unit coordinates as
     * app/src/main/res/drawable/ic_launcher_foreground.xml, so the tray, the taskbar,
     * the Start Menu and the phone are recognisably one app. The 1DM wordmark is
     * dropped because at 16 px it is a grey smear, and a smear is worse than nothing.
     *
     * A hairline outline is drawn around the black square, which the app icon cannot
     * afford: a launcher puts the icon on a wallpaper of unknown brightness, but a
     * taskbar can be light or dark and a plain black square vanishes on the dark one.
     */
    private fun iconImage(size: Int): Image {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON
            )

            val corner = size / 4
            g.color = Color(0x66, 0x72, 0x76)
            g.fillRoundRect(0, 0, size - 1, size - 1, corner, corner)
            g.color = Color.BLACK
            g.fillRoundRect(1, 1, size - 3, size - 3, corner - 1, corner - 1)

            // The folder and its arrow, taken from the launcher vector and scaled into
            // the square with a margin. Only the folder is drawn, so the artwork is
            // re-centred rather than cropped.
            val scale = size / 62f
            fun polygon(vararg coords: Float): Polygon {
                val shape = Polygon()
                var index = 0
                while (index + 1 < coords.size) {
                    shape.addPoint(
                        ((coords[index] - 20f) * scale).toInt().coerceIn(0, size),
                        ((coords[index + 1] - 24f) * scale).toInt().coerceIn(0, size)
                    )
                    index += 2
                }
                return shape
            }

            g.color = Color.WHITE
            g.fillPolygon(
                polygon(
                    34f, 38f, 36f, 36f, 43f, 36f, 43f, 31f, 45f, 29f, 57f, 29f, 59f, 31f,
                    59f, 36f, 72f, 36f, 74f, 38f, 74f, 58f, 72f, 60f, 36f, 60f, 34f, 58f
                )
            )
            g.color = Color.BLACK
            g.fillPolygon(
                polygon(
                    49f, 39f, 59f, 39f, 59f, 48f, 66f, 48f, 54f, 58f, 42f, 48f, 49f, 48f
                )
            )
        } finally {
            g.dispose()
        }
        return image
    }
}