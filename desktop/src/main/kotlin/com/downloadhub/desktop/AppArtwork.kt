package com.downloadhub.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.vector.PathParser
import java.awt.Image

/**
 * The app's icon, drawn at runtime: the tray, and the window's title bar and taskbar.
 *
 * Material Symbols Rounded "download" (filled, weight 700) in white on the app's indigo.
 * The glyph's path is the one in app/src/main/res/drawable/ic_launcher_foreground.xml,
 * which desktop/dist-tools/Generate-AppIcon.ps1 also rasterises into the .ico;
 * AppIconTest checks all three carry it, so the phone, the installer and Windows stay
 * one app.
 */
object AppArtwork {

    /** Google's glyph exactly as published, in its 960-unit box (y from -960 to 0). */
    internal const val GLYPH = "M462.05-345.05Q453.1-349.09 446-356L284-518q-14-14.27-13.5-33.64Q271-571 284.61-585q14.79-14.15 34.09-13.58Q338-598 352-584l81 81v-275q0-19.63 13.68-33.81Q460.35-826 480.18-826q19.82 0 33.32 14.19Q527-797.63 527-778v275l82-81q13.8-14 32.25-14.58 18.45-.58 32.91 13.5Q689-571 688.5-551.18T674-517L514-356q-7.17 6.91-16.33 10.95-9.16 4.05-17.91 4.05-8.76 0-17.71-4.05ZM229-135q-39.05 0-66.52-27.48Q135-189.95 135-229v-96q0-19.75 13.68-33.38Q162.35-372 182.18-372q19.82 0 33.32 13.62Q229-344.75 229-325v96h502v-96q0-19.75 13.68-33.38Q758.35-372 778.09-372q19.73 0 33.82 13.62Q826-344.75 826-325v96q0 39.05-27.77 66.52Q770.46-135 731-135H229Z"

    private val TOP = Color(0xFF7480F0)
    private val BOTTOM = Color(0xFF3F4AB8)

    private val glyph by lazy { PathParser().parsePathString(GLYPH).toPath() }

    /**
     * The icon at [size] pixels square.
     *
     * Only the central 64 units of the launcher's 108-unit viewport (22 to 86) are drawn,
     * so the glyph fills the square: nothing masks a desktop icon, and at 16 px the
     * launcher's safe-zone margin would leave a speck.
     */
    fun icon(size: Int): Image {
        val bitmap = ImageBitmap(size, size)
        val canvas = Canvas(bitmap)
        val s = size.toFloat()
        val corner = s * 14f / 64f
        canvas.drawRoundRect(0f, 0f, s, s, corner, corner, Paint().apply {
            isAntiAlias = true
            shader = LinearGradientShader(Offset.Zero, Offset(s, s), listOf(TOP, BOTTOM))
        })
        canvas.save()
        // Launcher viewport -> icon: crop 22..86 into the square.
        canvas.scale(s / 64f, s / 64f)
        canvas.translate(-22f, -22f)
        // Glyph box -> launcher viewport: centred on (54,54) at 0.066, as in the vector.
        canvas.translate(54f, 54f)
        canvas.scale(0.066f, 0.066f)
        canvas.translate(-480f, 480f)
        canvas.drawPath(glyph, Paint().apply { isAntiAlias = true; color = Color.White })
        canvas.restore()
        return bitmap.toAwtImage()
    }

    /**
     * The same icon at the sizes Windows asks for.
     *
     * Set on the AWT window rather than left to the exe's resources, so a run from
     * `gradlew run`, or a copied app directory, looks the same as an installed one.
     */
    fun windowIcons(): List<Image> = listOf(16, 24, 32, 48, 64, 128, 256).map { icon(it) }
}
