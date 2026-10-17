package com.downloadhub.app.ui.expressive.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

/**
 * A stand-in for a row while the list is still loading: a disc and two bars in the row's
 * own segmented shape, breathing slowly. One announcement for the whole group (via the
 * caller), not one per placeholder.
 */
@Composable
fun SkeletonRow(index: Int, count: Int, modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "alpha"
    )
    val tone = MaterialTheme.colorScheme.surfaceContainerHighest
    val shape = ListItemDefaults.segmentedShapes(index, count).shape
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp)
            .alpha(pulse.value)
            .clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.size(48.dp).clip(CircleShape).background(tone)) {}
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.fillMaxWidth(0.7f).height(14.dp).clip(CircleShape).background(tone)) {}
            Column(Modifier.fillMaxWidth(0.45f).height(10.dp).clip(CircleShape).background(tone)) {}
        }
    }
}
