package com.downloadhub.desktop

import java.awt.Color
import java.awt.Image
import java.awt.Polygon
import java.awt.image.BufferedImage

/**
 * The app's icon, drawn at runtime.
 *
 * There is one drawing and one place it lives. Previously the tray drew the folder and
 * arrow itself while the exe, the Start Menu entry and the desktop shortcut got the
 * .ico, and the window's title-bar icon came from wherever the exe's resources
 * happened to point. Three places to be right about an icon is two too many: a report
 * that "the icon did not change" is almost always one of them still showing the old
 * artwork, and nothing in the build says which.
 *
 * The coordinates are the ones in app/src/main/res/drawable/ic_launcher_foreground.xml,
 * which is also what desktop/dist-tools/Generate-AppIcon.ps1 rasterises into the .ico.
 * AppIconTest checks all three against each other, so the phone, the installer and
 * Windows itself stay one app.
 */
object AppArtwork {

    /**
     * The icon at [size] pixels square.
     *
     * A hairline outline is drawn around the black square. The Android launcher icon
     * cannot afford that, because it is dropped onto a wallpaper of unknown brightness,
     * but a taskbar can be light or dark and a plain black square vanishes on the dark
     * one - as does a title-bar icon on a dark window.
     */
    fun icon(size: Int): Image {
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

    /**
     * The same icon at the sizes Windows asks for.
     *
     * Set on the AWT window rather than left to the exe's resources. The exe's icon is
     * only right if the launcher is the jpackage one and the .ico made it into the
     * build; saying it here means a run from `gradlew run`, or a copied app directory,
     * looks the same as an installed one.
     */
    fun windowIcons(): List<Image> = listOf(16, 24, 32, 48, 64, 128, 256).map { icon(it) }
}
