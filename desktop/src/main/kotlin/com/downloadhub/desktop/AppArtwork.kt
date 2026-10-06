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
 * Material Symbols Rounded "download" with a "counter_1" badge (filled, weight 700), in
 * white on the app's indigo. Both paths, and where each sits, are the ones in
 * app/src/main/res/drawable/ic_launcher_foreground.xml, which
 * desktop/dist-tools/Generate-AppIcon.ps1 also renders into the .ico; AppIconTest checks
 * all three carry them, so the phone, the installer and Windows stay one app.
 */
object AppArtwork {

    /** Google's glyphs exactly as published, in their 960-unit box (y from -960 to 0). */
    internal const val GLYPH = "M462.05-345.05Q453.1-349.09 446-356L284-518q-14-14.27-13.5-33.64Q271-571 284.61-585q14.79-14.15 34.09-13.58Q338-598 352-584l81 81v-275q0-19.63 13.68-33.81Q460.35-826 480.18-826q19.82 0 33.32 14.19Q527-797.63 527-778v275l82-81q13.8-14 32.25-14.58 18.45-.58 32.91 13.5Q689-571 688.5-551.18T674-517L514-356q-7.17 6.91-16.33 10.95-9.16 4.05-17.91 4.05-8.76 0-17.71-4.05ZM229-135q-39.05 0-66.52-27.48Q135-189.95 135-229v-96q0-19.75 13.68-33.38Q162.35-372 182.18-372q19.82 0 33.32 13.62Q229-344.75 229-325v96h502v-96q0-19.75 13.68-33.38Q758.35-372 778.09-372q19.73 0 33.82 13.62Q826-344.75 826-325v96q0 39.05-27.77 66.52Q770.46-135 731-135H229Z"
    internal const val ONE = "M480.4-55q-88.87 0-166.12-33.08-77.25-33.09-135.18-91.02-57.93-57.93-91.02-135.12Q55-391.41 55-480.36q0-88.96 33.08-166.29 33.09-77.32 90.86-134.81 57.77-57.48 135.03-91.01Q391.24-906 480.28-906t166.49 33.45q77.44 33.46 134.85 90.81t90.89 134.87Q906-569.34 906-480.27q0 89.01-33.53 166.25t-91.01 134.86q-57.49 57.62-134.83 90.89Q569.28-55 480.4-55ZM461-612v300q0 14.87 10.57 24.94Q482.14-277 497.05-277t24.93-10.35Q532-297.7 532-313v-323q0-19.75-13.62-33.38Q504.75-683 485-683h-72q-14.87 0-24.94 10.09-10.06 10.09-10.06 25t10.35 25.41Q398.7-612 414-612h47Z"

    private val TOP = Color(0xFF7480F0)
    private val BOTTOM = Color(0xFF3F4AB8)

    private val arrow by lazy { PathParser().parsePathString(GLYPH).toPath() }
    private val badge by lazy { PathParser().parsePathString(ONE).toPath() }

    /**
     * The icon at [size] pixels square.
     *
     * Only the central 64 units of the launcher's 108-unit viewport (22 to 86) are drawn,
     * so the glyphs fill the square: nothing masks a desktop icon, and at 16 px the
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
        val white = Paint().apply { isAntiAlias = true; color = Color.White }
        // Glyph box -> launcher viewport (as the vector's groups) -> the 22..86 crop.
        fun place(path: androidx.compose.ui.graphics.Path, centreX: Float, centreY: Float, scale: Float) {
            canvas.save()
            canvas.scale(s / 64f, s / 64f)
            canvas.translate(centreX - 22f, centreY - 22f)
            canvas.scale(scale, scale)
            canvas.translate(-480f, 480f)
            canvas.drawPath(path, white)
            canvas.restore()
        }
        place(arrow, 50f, 58f, 0.058f)
        place(badge, 68f, 38f, 0.0247f)
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
