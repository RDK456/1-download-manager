package com.downloadhub.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.downloadhub.core.DownloadItem
import com.downloadhub.core.DownloadStatus
import com.downloadhub.core.DisplayFormat

/**
 * A fill that appears under the pointer, the way AB Download Manager's rows and buttons
 * answer a hover. A row that does not react until it is clicked feels slower than one
 * that does, even when both take the same time to respond.
 *
 * [selected] wins over hover, so the hover never hides which rows are ticked.
 */
internal fun Modifier.hoverFill(
    selected: Boolean = false,
    shape: Shape = RoundedCornerShape(0.dp)
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val fill = when {
        selected -> AppTheme.Palette.accentContainer
        // The theme's own faint wash. copy(alpha = ...) set an absolute alpha, which turned
        // a 10% white into a 55% white block on dark themes.
        hovered -> AppTheme.Palette.selection
        else -> Color.Transparent
    }
    this.hoverable(source).clip(shape).background(fill, shape)
}

/**
 * The status column: a bar with the percentage on it while a download has a size to
 * measure against, and a word otherwise. AB Download Manager shows progress here rather
 * than under the name, so the name keeps its line.
 */
@Composable
internal fun StatusCell(item: DownloadItem, width: Dp, colour: Color) {
    val showsBar = item.totalBytes > 0 && item.status != DownloadStatus.COMPLETED &&
        item.status != DownloadStatus.FAILED
    Box(Modifier.width(width).padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
        if (!showsBar) {
            Text(
                DisplayFormat.status(item),
                fontSize = 11.sp,
                color = colour,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            return@Box
        }
        val fraction = (item.progressPercent / 100f).coerceIn(0f, 1f)
        val running = item.status == DownloadStatus.RUNNING
        val shape = RoundedCornerShape(4.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .height(16.dp)
                .clip(shape)
                .background(AppTheme.Palette.raised, shape)
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(if (running) AppTheme.Palette.accent else AppTheme.Palette.faint)
            )
            Text(
                if (running) "${item.progressPercent}%" else "${DisplayFormat.status(item)} · ${item.progressPercent}%",
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = if (running && fraction > 0.45f) AppTheme.Palette.onAccent else AppTheme.Palette.onSurface,
                maxLines = 1,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}
