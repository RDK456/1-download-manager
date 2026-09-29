package com.downloadhub.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
 * The hit area is larger than the box. A control that has to be aimed at is a control
 * people skip, and the same argument applies to the row buttons that were too small to
 * hit.
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
        // A 21 dp target around a 13 dp box.
        Modifier
            .size(size + 8.dp)
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
