package com.downloadhub.app.ui.expressive.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.downloadhub.app.ui.expressive.model.BarStyle

/**
 * The bar under a row. Wavy while bytes are moving, flat when they are not, so the wave
 * itself says "alive". Nothing at all when there is nothing to show.
 *
 * The value is animated and handed to the indicator as a lambda, so a progress tick redraws
 * the bar and does not recompose the row around it.
 */
@Composable
fun RowBar(style: BarStyle, progress: Float, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = WavyProgressIndicatorDefaults.ProgressAnimationSpec,
        label = "progress"
    )
    val bar = modifier.fillMaxWidth()
    when (style) {
        BarStyle.NONE -> Unit
        BarStyle.FLAT -> LinearProgressIndicator(progress = { animated }, modifier = bar)
        BarStyle.WAVY -> LinearWavyProgressIndicator(progress = { animated }, modifier = bar)
        BarStyle.WAVY_INDETERMINATE -> LinearWavyProgressIndicator(modifier = bar)
    }
}
