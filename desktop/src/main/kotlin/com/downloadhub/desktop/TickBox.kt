package com.downloadhub.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A tick box, drawn rather than themed.
 *
 * Material's own Checkbox animated its tick, and on this Compose version the animation did
 * not always settle - which is how a settings row ended up showing two ticks in one box.
 * Drawn directly there is nothing to animate, and a flat square tick is the look the rest
 * of the app is going for anyway.
 *
 * It lays out at exactly [size]. An earlier version added 8 dp to make a larger target,
 * and that is what put every label in the settings a few pixels below its own tick box: a
 * row that centres its text against a 21 dp control centres it against the padding, not
 * against the box. The larger target is the row's job - see [TickRow] - not the box's,
 * because a control that grows its own height cannot sit in a row beside text.
 */
@Composable
fun TickBox(
    checked: Boolean,
    onChange: ((Boolean) -> Unit)? = null,
    enabled: Boolean = true,
    size: Dp = 13.dp
) {
    val shape = RoundedCornerShape(2.dp)
    Box(
        Modifier
            .size(size)
            .clickable(enabled = enabled && onChange != null) { onChange?.invoke(!checked) },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(size)
                .background(
                    if (checked && enabled) Color(0xFF34D399) else Color(0xFF0E1416),
                    shape
                )
                .border(
                    width = 1.dp,
                    color = when {
                        !enabled -> Color(0xFF1E2629)
                        checked -> Color(0xFF34D399)
                        else -> Color(0xFF3A4749)
                    },
                    shape = shape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (checked && enabled) TickMark(Color(0xFF07110D))
        }
    }
}

/**
 * A labelled tick box, with the label on the same line as the box.
 *
 * Every tick box in the app goes through here rather than through a bare `Row`, because
 * the alignment is the thing that is easy to get wrong: the row centres the text against
 * whatever height the box occupies, so a box that is taller than it looks - because it is
 * padding itself out to be easier to hit - drags the text down with it.
 *
 * The whole row is the target, which is the reason a 13 dp box is still easy to tick.
 */
@Composable
fun TickRow(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fontSize: androidx.compose.ui.unit.TextUnit = 12.sp,
    detail: String? = null,
    /**
     * Last, so the usual call is `TickRow("Label", checked) { ... }`. A handler in the
     * middle of a parameter list with optional parameters after it cannot be passed as a
     * trailing lambda, which is the only way anybody writes this.
     */
    onChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 3.dp)
    ) {
        TickBox(checked = checked, onChange = if (enabled) onChange else null, enabled = enabled)
        Column(Modifier.padding(start = 8.dp)) {
            Text(
                label,
                fontSize = fontSize,
                color = if (enabled) {
                    androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                } else {
                    androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            if (detail != null) {
                Text(
                    detail,
                    fontSize = 10.sp,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** A checkmark, drawn as two strokes rather than fetched as an icon. */
@Composable
private fun TickMark(colour: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(9.dp)) {
        val w = size.width
        val h = size.height
        drawLine(
            colour,
            start = androidx.compose.ui.geometry.Offset(w * 0.18f, h * 0.52f),
            end = androidx.compose.ui.geometry.Offset(w * 0.42f, h * 0.76f),
            strokeWidth = 1.6.dp.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
        drawLine(
            colour,
            start = androidx.compose.ui.geometry.Offset(w * 0.42f, h * 0.76f),
            end = androidx.compose.ui.geometry.Offset(w * 0.84f, h * 0.22f),
            strokeWidth = 1.6.dp.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }
}
