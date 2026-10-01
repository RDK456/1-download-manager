package com.downloadhub.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The six icons this app needs that are not in the core Material set.
 *
 * `material-icons-extended` carries every icon Google has ever drawn, which is a
 * 36 MB jar for the six below. It was the single largest thing in the Windows build
 * and it was paid for by every download, on a platform where the install had already
 * failed once because the payload was too large to unpack cleanly.
 *
 * These are the standard Material paths for the same glyphs, so they look identical
 * to the rest of the UI. Anything added later should first check whether it is in
 * `compose.material3`'s core set - which ships with the app at no cost - before
 * reaching for the extended jar again.
 */
object DlmIcons {

    private fun icon(
        name: String,
        block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit
    ): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // A vector with no path is invisible, so every icon here sets one.
            path(fill = SolidColor(Color.Black)) { block() }
        }.build()

    /** Two vertical bars. Pausing a transfer, not a media player. */
    val Pause: ImageVector by lazy {
        icon("Pause") {
            moveTo(6f, 19f); horizontalLineToRelative(4f); verticalLineToRelative(-14f)
            horizontalLineToRelative(-4f); close()
            moveTo(14f, 5f); verticalLineToRelative(14f); horizontalLineToRelative(4f)
            verticalLineToRelative(-14f); close()
        }
    }

    /** A filled square. */
    val Stop: ImageVector by lazy {
        icon("Stop") {
            moveTo(6f, 6f); horizontalLineToRelative(12f); verticalLineToRelative(12f)
            horizontalLineToRelative(-12f); close()
        }
    }

    /** A closed folder. */
    val Folder: ImageVector by lazy {
        icon("Folder") {
            moveTo(10f, 4f)
            horizontalLineToRelative(-6f)
            curveTo(2.9f, 4f, 2.01f, 4.9f, 2.01f, 6f)
            lineTo(2f, 18f)
            curveTo(2f, 19.1f, 2.9f, 20f, 4f, 20f)
            horizontalLineToRelative(16f)
            curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f)
            lineTo(22f, 8f)
            curveTo(22f, 6.9f, 21.1f, 6f, 20f, 6f)
            horizontalLineToRelative(-8f)
            close()
        }
    }

    /** An open folder, for the "show in folder" action. */
    val FolderOpen: ImageVector by lazy {
        icon("FolderOpen") {
            moveTo(20f, 6f)
            horizontalLineToRelative(-8f)
            lineTo(10f, 4f)
            horizontalLineToRelative(-6f)
            curveTo(2.9f, 4f, 2.01f, 4.9f, 2.01f, 6f)
            lineTo(2f, 18f)
            curveTo(2f, 19.1f, 2.9f, 20f, 4f, 20f)
            horizontalLineToRelative(16f)
            curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f)
            lineTo(22f, 8f)
            curveTo(22f, 6.9f, 21.1f, 6f, 20f, 6f)
            close()
            moveTo(20f, 18f)
            horizontalLineToRelative(-16f)
            lineTo(4f, 8f)
            horizontalLineToRelative(16f)
            close()
        }
    }

    /** Up chevron, for ascending sort. */
    val ArrowUpward: ImageVector by lazy {
        icon("ArrowUpward") {
            moveTo(4f, 12f)
            lineToRelative(1.41f, 1.41f)
            lineTo(11f, 7.83f)
            verticalLineTo(20f)
            horizontalLineToRelative(2f)
            verticalLineTo(7.83f)
            lineToRelative(5.58f, 5.59f)
            lineTo(20f, 12f)
            lineTo(12f, 4f)
            close()
        }
    }

    /** Down chevron, for descending sort. */
    val ArrowDownward: ImageVector by lazy {
        icon("ArrowDownward") {
            moveTo(20f, 12f)
            lineToRelative(-1.41f, 1.41f)
            lineTo(13f, 16.17f)
            verticalLineTo(4f)
            horizontalLineToRelative(-2f)
            verticalLineTo(12.17f)
            lineTo(5.41f, 6.58f)
            lineTo(4f, 12f)
            lineTo(12f, 20f)
            close()
        }
    }

    // --- category icons ------------------------------------------------------
    // One per sidebar category, so the rail can be read without reading it. Same
    // reasoning as the six above: each is a plain Material path, drawn here because
    // the extended jar they all live in is 36 MB.

    /** An archive box: a band across a crate. */
    val Compressed: ImageVector by lazy {
        icon("Compressed") {
            moveTo(20f, 4f)
            horizontalLineTo(4f)
            verticalLineTo(4f)
            close()
            moveTo(21f, 6f)
            verticalLineTo(20f)
            horizontalLineTo(3f)
            verticalLineTo(6f)
            close()
            moveTo(5f, 8f)
            horizontalLineTo(19f)
            verticalLineToRelative(-2f)
            horizontalLineTo(5f)
            close()
        }
    }

    /** A window with a title bar: an application. */
    val Programs: ImageVector by lazy {
        icon("Programs") {
            moveTo(19f, 4f)
            horizontalLineTo(5f)
            curveTo(4.45f, 4f, 4.01f, 4.45f, 4.01f, 5f)
            lineTo(4f, 19f)
            curveTo(4f, 19.55f, 4.45f, 20f, 5f, 20f)
            horizontalLineTo(19f)
            curveTo(19.55f, 20f, 20f, 19.55f, 20f, 19f)
            verticalLineTo(5f)
            curveTo(20f, 4.45f, 19.55f, 4f, 19f, 4f)
            close()
            moveTo(18f, 13f)
            horizontalLineTo(6f)
            verticalLineToRelative(-2f)
            horizontalLineTo(18f)
            close()
        }
    }

    /** A strip of film. */
    val Videos: ImageVector by lazy {
        icon("Videos") {
            moveTo(18f, 4f)
            lineTo(6f, 4f)
            curveTo(4.9f, 4f, 4f, 4.9f, 4f, 6f)
            verticalLineTo(18f)
            curveTo(4f, 19.1f, 4.9f, 20f, 6f, 20f)
            horizontalLineTo(18f)
            curveTo(19.1f, 20f, 20f, 19.1f, 20f, 18f)
            verticalLineTo(6f)
            curveTo(20f, 4.9f, 19.1f, 4f, 18f, 4f)
            close()
            moveTo(10f, 16.5f)
            verticalLineToRelative(-9f)
            lineTo(16f, 12f)
            close()
            moveTo(7.5f, 4f)
            horizontalLineTo(9f)
            verticalLineToRelative(16f)
            horizontalLineTo(7.5f)
            close()
        }
    }

    /** A quaver. */
    val Music: ImageVector by lazy {
        icon("Music") {
            moveTo(20f, 3f)
            verticalLineTo(13.55f)
            curveTo(20f, 14.37f, 20f, 16.28f, 20f, 17.5f)
            curveTo(20f, 19.43f, 18.43f, 21f, 16.5f, 21f)
            curveTo(14.57f, 21f, 13f, 19.43f, 13f, 17.5f)
            curveTo(13f, 15.57f, 14.57f, 14f, 16.5f, 14f)
            curveTo(17.03f, 14f, 17.5f, 14.2f, 18f, 14.63f)
            verticalLineTo(5f)
            horizontalLineTo(4f)
            verticalLineTo(3f)
            close()
        }
    }

    /** A framed picture with a sun. */
    val Pictures: ImageVector by lazy {
        icon("Pictures") {
            moveTo(21f, 19f)
            verticalLineTo(5f)
            curveTo(21f, 4.45f, 20.55f, 4f, 20f, 4f)
            horizontalLineTo(3f)
            curveTo(2.45f, 4f, 2f, 4.45f, 2f, 5f)
            verticalLineTo(19f)
            curveTo(2f, 19.55f, 2.45f, 20f, 3f, 20f)
            horizontalLineTo(5f)
            verticalLineTo(18f)
            horizontalLineTo(20f)
            verticalLineTo(11f)
            lineToRelative(-2.5f, -2.5f)
            lineToRelative(-6.5f, 6.5f)
            lineToRelative(-2f, -2f)
            verticalLineTo(18f)
            close()
            moveTo(8.5f, 11.5f)
            curveTo(8.5f, 10.67f, 8.83f, 9.92f, 9.41f, 9.41f)
            curveTo(9.99f, 8.9f, 10.74f, 8.5f, 11.5f, 8.5f)
            curveTo(12.33f, 8.5f, 13.08f, 8.9f, 13.6f, 9.41f)
            curveTo(14.18f, 9.92f, 14.5f, 10.67f, 14.5f, 11.5f)
            curveTo(14.5f, 12.33f, 14.18f, 13.08f, 13.6f, 13.6f)
            curveTo(13.08f, 14.18f, 12.33f, 14.5f, 11.5f, 14.5f)
            curveTo(10.74f, 14.5f, 9.99f, 14.18f, 9.41f, 13.6f)
            curveTo(8.83f, 13.08f, 8.5f, 12.33f, 8.5f, 11.5f)
            close()
        }
    }

    /** A sheet of paper with lines. */
    val Documents: ImageVector by lazy {
        icon("Documents") {
            moveTo(14f, 2f)
            verticalLineTo(6f)
            curveTo(5.45f, 6f, 5.01f, 6.45f, 5.01f, 7f)
            verticalLineTo(19f)
            curveTo(5.01f, 19.55f, 5.45f, 20f, 6f, 20f)
            horizontalLineTo(18f)
            curveTo(18.55f, 20f, 19f, 19.55f, 19f, 19f)
            verticalLineTo(8f)
            close()
            moveTo(16f, 18f)
            horizontalLineTo(8f)
            verticalLineToRelative(-2f)
            horizontalLineTo(16f)
            close()
            moveTo(16f, 14f)
            horizontalLineTo(8f)
            verticalLineToRelative(-2f)
            horizontalLineTo(16f)
            close()
            moveTo(13f, 9f)
            verticalLineTo(2f)
            horizontalLineTo(8f)
            verticalLineToRelative(7f)
            close()
        }
    }

    /**
     * A play triangle in a circle: the video section.
     *
     * Its own glyph rather than the filled triangle, which already means an
     * active download in the rail. Two paths because one stroke and one fill
     * cannot share a path: the ring is drawn, the triangle is filled.
     */
    val YouTube: ImageVector by lazy {
        ImageVector.Builder(
            name = "YouTube",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f) {
                // This version's arcTo is the SVG form, so the ring is two
                // half-circles: top one way, bottom one back.
                moveTo(3f, 12f)
                arcTo(9f, 9f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 21f, y1 = 12f)
                arcTo(9f, 9f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 3f, y1 = 12f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(10f, 8.5f)
                lineTo(16f, 12f)
                lineTo(10f, 15.5f)
                close()
            }
        }.build()
    }
}
