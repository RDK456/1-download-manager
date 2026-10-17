package com.downloadhub.app.ui.expressive.shape

import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.isSpecified

/**
 * The library's loading indicator, for waits of unknown length. Confined here beside
 * `StateShape` because it is still experimental in material3 1.5.0-beta01. Screens call
 * these two and never the library directly.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Loader(modifier: Modifier = Modifier) = LoadingIndicator(modifier = modifier)

/** The same on a tonal disc, for use over content or in an empty area. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContainedLoader(modifier: Modifier = Modifier) = ContainedLoadingIndicator(modifier = modifier)

/**
 * Drop-in for the indeterminate `CircularProgressIndicator` the older screens call. With the
 * redesign on, every one of those spinners becomes the morphing loading indicator; with it
 * off they are the plain Material spinner they always were. Screens not rebuilt yet change
 * by changing one import.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    strokeWidth: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Unspecified
) {
    if (com.downloadhub.app.BuildConfig.EXPRESSIVE_UI) {
        if (color.isSpecified) LoadingIndicator(modifier = modifier, color = color) else LoadingIndicator(modifier = modifier)
    } else {
        androidx.compose.material3.CircularProgressIndicator(
            modifier = modifier,
            color = if (color.isSpecified) color else androidx.compose.material3.ProgressIndicatorDefaults.circularColor,
            strokeWidth = if (strokeWidth.isSpecified) strokeWidth else androidx.compose.material3.ProgressIndicatorDefaults.CircularStrokeWidth
        )
    }
}
