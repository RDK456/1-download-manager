package com.downloadhub.app.ui.expressive.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.downloadhub.app.ui.theme.UiGeneration

/*
 * Drop-in replacements for the Material pieces the older screens use. Each takes the same
 * arguments as the original and, with the new look on, draws the expressive version: cards
 * are large-cornered and tonal, buttons spring to a new shape while pressed, switches carry
 * an icon in the thumb, progress bars are wavy. With the classic look they call the original
 * unchanged. A screen adopts all of it by changing its imports, so its logic is never touched.
 */

/** A card. In the new look: large corners, a tonal fill, no border and no shadow. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    colors: CardColors? = null,
    elevation: CardElevation? = null,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    if (UiGeneration.expressive) {
        androidx.compose.material3.Card(
            modifier = modifier,
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            elevation = CardDefaults.cardElevation(0.dp),
            content = content
        )
    } else {
        androidx.compose.material3.Card(
            modifier = modifier,
            shape = shape ?: CardDefaults.shape,
            colors = colors ?: CardDefaults.cardColors(),
            elevation = elevation ?: CardDefaults.cardElevation(),
            border = border,
            content = content
        )
    }
}

/** A card that can be pressed. */
@Composable
fun Card(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: CardColors? = null,
    elevation: CardElevation? = null,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    if (UiGeneration.expressive) {
        androidx.compose.material3.Card(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            elevation = CardDefaults.cardElevation(0.dp),
            content = content
        )
    } else {
        androidx.compose.material3.Card(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = shape ?: CardDefaults.shape,
            colors = colors ?: CardDefaults.cardColors(),
            elevation = elevation ?: CardDefaults.cardElevation(),
            border = border,
            content = content
        )
    }
}

/** A filled button. In the new look it springs to a rounder or squarer shape while pressed. */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    if (UiGeneration.expressive) {
        androidx.compose.material3.Button(
            onClick = onClick,
            shapes = ButtonDefaults.shapes(),
            modifier = modifier,
            enabled = enabled,
            colors = colors ?: ButtonDefaults.buttonColors(),
            contentPadding = contentPadding,
            content = content
        )
    } else {
        androidx.compose.material3.Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = colors ?: ButtonDefaults.buttonColors(),
            contentPadding = contentPadding,
            content = content
        )
    }
}

/** An outlined button, with the same pressed-shape behaviour. */
@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    if (UiGeneration.expressive) {
        androidx.compose.material3.OutlinedButton(
            onClick = onClick,
            shapes = ButtonDefaults.shapes(),
            modifier = modifier,
            enabled = enabled,
            colors = colors ?: ButtonDefaults.outlinedButtonColors(),
            contentPadding = contentPadding,
            content = content
        )
    } else {
        androidx.compose.material3.OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = colors ?: ButtonDefaults.outlinedButtonColors(),
            border = border ?: ButtonDefaults.outlinedButtonBorder(enabled),
            contentPadding = contentPadding,
            content = content
        )
    }
}

/** A switch. In the new look a tick sits in the thumb while it is on, so state is not colour alone. */
@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SwitchColors? = null
) {
    androidx.compose.material3.Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        thumbContent = if (UiGeneration.expressive && checked) {
            { Icon(Lucide.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
        } else null,
        enabled = enabled,
        colors = colors ?: SwitchDefaults.colors()
    )
}

/** A determinate progress bar: wavy in the new look, flat in the classic one. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LinearProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    trackColor: Color = Color.Unspecified
) {
    val c = if (color == Color.Unspecified) ProgressColor.indicator() else color
    val t = if (trackColor == Color.Unspecified) ProgressColor.track() else trackColor
    if (UiGeneration.expressive) {
        LinearWavyProgressIndicator(progress = progress, modifier = modifier, color = c, trackColor = t)
    } else {
        androidx.compose.material3.LinearProgressIndicator(progress = progress, modifier = modifier, color = c, trackColor = t)
    }
}

/** An indeterminate progress bar. */
@Composable
fun LinearProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    trackColor: Color = Color.Unspecified
) {
    val c = if (color == Color.Unspecified) ProgressColor.indicator() else color
    val t = if (trackColor == Color.Unspecified) ProgressColor.track() else trackColor
    if (UiGeneration.expressive) {
        LinearWavyProgressIndicator(modifier = modifier, color = c, trackColor = t)
    } else {
        androidx.compose.material3.LinearProgressIndicator(modifier = modifier, color = c, trackColor = t)
    }
}

private object ProgressColor {
    @Composable fun indicator(): Color = MaterialTheme.colorScheme.primary
    @Composable fun track(): Color = MaterialTheme.colorScheme.secondaryContainer
}
