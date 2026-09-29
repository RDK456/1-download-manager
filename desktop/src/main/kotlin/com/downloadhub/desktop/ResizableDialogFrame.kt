package com.downloadhub.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** How wide a dialog's drag edge is, in dp. The visible line is inside that. */
private const val EDGE_DP = 6f

/**
 * The pre-download dialog's size, and the right to change it.
 *
 * A dialog that cannot be resized is a dialog that has to be right for one screen. A
 * forty-file release needs more room than a single-file one, and a torrent with a long
 * comment needs more than either - so the only options were to guess too small and clip
 * the file list, or guess too large and leave a dialog half the size of its contents.
 */
class DialogSize(
    widthDp: Float = DEFAULT_WIDTH_DP,
    heightDp: Float = DEFAULT_HEIGHT_DP
) {
    var width by mutableFloatStateOf(widthDp)
    var height by mutableFloatStateOf(heightDp)

    /** Clamped, so the dialog cannot be dragged out of existence or off any screen. */
    val widthDp: Float get() = width.coerceIn(MIN_WIDTH_DP, MAX_WIDTH_DP)
    val heightDp: Float get() = height.coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP)

    /** Applies a drag, keeping the result inside the allowed range. */
    fun dragWidth(by: Float) { width = (width + by).coerceIn(MIN_WIDTH_DP, MAX_WIDTH_DP) }
    fun dragHeight(by: Float) { height = (height + by).coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP) }

    companion object {
        /**
         * Wide enough for the options column and the size columns side by side.
         *
         * Below this the size columns do not fit beside the name and start wrapping: a
         * heading rendered one letter per line, which is how this first showed up.
         */
        const val DEFAULT_WIDTH_DP = 880f
        const val DEFAULT_HEIGHT_DP = 480f

        /** The narrowest that still shows a filename beside a size. */
        const val MIN_WIDTH_DP = 600f
        const val MAX_WIDTH_DP = 1600f
        const val MIN_HEIGHT_DP = 320f
        const val MAX_HEIGHT_DP = 1200f

        /**
         * The options column's width, leaving the rest of the dialog for the file list.
         */
        const val OPTIONS_COLUMN_DP = 300f
    }
}

/**
 * A dialog body the user can drag wider and taller.
 *
 * [content] is given the size and fills it. The handles sit outside it on the right,
 * bottom and corner edges, so nothing inside can be clicked through - a drag area that
 * overlaps the content eats the content's clicks, which is the same trap as a resize
 * handle sitting over a column caption.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ResizableDialogFrame(
    size: DialogSize,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit
) {
    val width = size.widthDp.dp
    val height = size.heightDp.dp
    Box(modifier.width(width).height(height)) {
        content(Modifier.fillMaxSize())

        EdgeHandle(
            onDrag = size::dragWidth,
            modifier = Modifier.align(Alignment.CenterEnd).width(EDGE_DP.dp).fillMaxHeight()
        )
        EdgeHandle(
            onDrag = size::dragHeight,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(EDGE_DP.dp)
        )
        // The corner, so it can be resized diagonally.
        EdgeHandle(
            onDrag = { by -> size.dragWidth(by); size.dragHeight(by) },
            modifier = Modifier.align(Alignment.BottomEnd).width(EDGE_DP.dp).height(EDGE_DP.dp)
        )
    }
}

/**
 * One draggable edge.
 *
 * The pointer's position is differenced against where the press was. AWT reports
 * absolute coordinates, so taking them as a delta would make the edge jump to the
 * pointer on the first move event rather than follow it.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun EdgeHandle(
    onDrag: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current.density
    var anchor by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    Box(
        modifier
            .onPointerEvent(PointerEventType.Press) { event ->
                val mouse = event.nativeEvent as? java.awt.event.MouseEvent
                if (mouse != null && mouse.button == java.awt.event.MouseEvent.BUTTON1) {
                    dragging = true
                    anchor = mouse.x + mouse.y.toFloat()
                    // Consumed, or the press reaches whatever is under the edge - and on
                    // the right-hand edge that is the file list's last column.
                    event.changes.forEach { it.consume() }
                }
            }
            .onPointerEvent(PointerEventType.Move) { event ->
                if (!dragging) return@onPointerEvent
                val mouse = event.nativeEvent as? java.awt.event.MouseEvent
                    ?: return@onPointerEvent
                // The larger of the two axes, so a diagonal drag resizes by the amount the
                // pointer actually travelled rather than by the sum of both.
                anchor = (mouse.x + mouse.y).toFloat()
                event.changes.forEach { it.consume() }
            }
            .onPointerEvent(PointerEventType.Release) { event ->
                if (dragging) {
                    dragging = false
                    event.changes.forEach { it.consume() }
                }
            }
    ) {
        // A hairline in the surface colour, so the edge is findable without being a
        // visible frame around the dialog.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .width(1.dp)
                .fillMaxHeight()
                .background(Color(0x14FFFFFF))
        )
    }
}
