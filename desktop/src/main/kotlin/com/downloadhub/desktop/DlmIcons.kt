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
}
