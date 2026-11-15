package com.downloadhub.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp

/**
 * An approximation of iOS 26's Liquid Glass: a translucent body that is brighter at the top
 * left, a thin specular edge that fades toward the bottom right, and a soft lift. Compose
 * Desktop cannot blur what is behind a surface, so this reads as glass over a gradient
 * backdrop rather than refracting live content.
 */
fun Modifier.liquidGlass(shape: Shape): Modifier = this
    .shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
    .clip(shape)
    .background(
        Brush.linearGradient(
            colors = listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.05f)),
            start = Offset.Zero,
            end = Offset(600f, 600f)
        )
    )
    .border(
        1.dp,
        Brush.linearGradient(
            colors = listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.25f)),
            start = Offset.Zero,
            end = Offset(600f, 600f)
        ),
        shape
    )

/** The backdrop glass sits on: a soft two-tone wash of the accent. */
fun glassBackdrop(base: Color, tint: Color): Brush = Brush.linearGradient(
    colors = listOf(tint.copy(alpha = 0.22f).compositeOver(base), base, tint.copy(alpha = 0.10f).compositeOver(base)),
    start = Offset.Zero,
    end = Offset(1400f, 1400f)
)
