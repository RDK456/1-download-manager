package com.downloadhub.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Explicit switch colours.
 *
 * Material 3 paints an unchecked switch with `surfaceVariant` for the track and
 * `outline` for the thumb. In a dark or AMOLED palette those two are both dark
 * greys, so the toggle renders as an empty pill with no visible knob. Deriving
 * the thumb from `onSurface` keeps it high-contrast in every palette.
 */
@Composable
fun settingSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    checkedBorderColor = MaterialTheme.colorScheme.primary,
    checkedIconColor = MaterialTheme.colorScheme.onPrimary,
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurface,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
    uncheckedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    disabledCheckedThumbColor = Color.White.copy(alpha = 0.6f),
    disabledCheckedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
    disabledUncheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
    disabledUncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
    disabledUncheckedBorderColor = MaterialTheme.colorScheme.outline
)
