package com.downloadhub.app.ui.expressive.shape

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.MotionDurationScale
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import kotlinx.coroutines.isActive
import com.downloadhub.app.ui.expressive.model.StateShapeKind
import com.downloadhub.app.ui.expressive.model.spins
import com.downloadhub.app.ui.expressive.model.tone

/**
 * The only file that touches `MaterialShapes`, which is still experimental in
 * material3 1.5.0-beta01. If the library changes shape again, this is the one place to fix.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun StateShapeKind.polygon(): RoundedPolygon = when (this) {
    StateShapeKind.COOKIE -> MaterialShapes.Cookie9Sided
    StateShapeKind.CIRCLE -> MaterialShapes.Circle
    StateShapeKind.SQUIRCLE -> MaterialShapes.Square
    StateShapeKind.BURST -> MaterialShapes.SoftBurst
    StateShapeKind.PENTAGON -> MaterialShapes.Pentagon
}

/** The badge's fill and the icon colour that sits on it, from the theme, for a state. */
@Composable
fun badgeColors(kind: StateShapeKind, selected: Boolean = false): Pair<Color, Color> {
    val s = MaterialTheme.colorScheme
    if (selected) return s.primary to s.onPrimary
    return when (kind.tone) {
        com.downloadhub.app.ui.expressive.model.BadgeTone.ACTIVE -> s.primary to s.onPrimary
        com.downloadhub.app.ui.expressive.model.BadgeTone.REST -> s.secondaryContainer to s.onSecondaryContainer
        com.downloadhub.app.ui.expressive.model.BadgeTone.DONE -> s.tertiaryContainer to s.onTertiaryContainer
        com.downloadhub.app.ui.expressive.model.BadgeTone.ERROR -> s.errorContainer to s.onErrorContainer
    }
}

private class MorphPair(val from: StateShapeKind, val to: StateShapeKind)

/**
 * A badge that morphs from one state's shape to the next on the theme's default spatial
 * spring, so a pause or a finish is something you see happen. [content] is drawn on top,
 * centred (usually the file-type icon).
 *
 * [spin] turns the shape slowly while it is the transferring one. The caller decides, so
 * the list can cap how many turn at once.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StateShape(
    kind: StateShapeKind,
    color: Color,
    modifier: Modifier = Modifier,
    spin: Boolean = kind.spins,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    var pair by remember { mutableStateOf(MorphPair(kind, kind)) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(kind) {
        if (kind != pair.to) {
            pair = MorphPair(pair.to, kind)
            progress.snapTo(0f)
            progress.animateTo(1f, spec)
        }
    }
    // The turn is a loop, so it is skipped when the system has animations switched off:
    // a zero duration scale would finish every lap instantly and spin the loop flat out.
    val angle = remember { Animatable(0f) }
    LaunchedEffect(spin) {
        if (!spin || coroutineContext[MotionDurationScale]?.scaleFactor == 0f) return@LaunchedEffect
        while (isActive) {
            angle.animateTo(angle.value + 360f, tween(TURN_MILLIS, easing = LinearEasing))
            angle.snapTo(angle.value % 360f)
        }
    }
    Box(
        modifier = modifier
            .drawWithCache {
                val morph = Morph(pair.from.polygon(), pair.to.polygon())
                val path = Path()
                onDrawBehind {
                    morph.toPath(progress.value, path)
                    rotate(if (spin) angle.value else 0f, pivot = center) {
                        withTransform({ scale(size.width, size.height, Offset.Zero) }) {
                            drawPath(path, color)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** One slow turn every 14 s. */
private const val TURN_MILLIS = 14_000
