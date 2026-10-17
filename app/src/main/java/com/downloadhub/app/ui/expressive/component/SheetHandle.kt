package com.downloadhub.app.ui.expressive.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/** The grab bar at the top of a sheet: a plain rounded bar, no box behind it. */
@Composable
fun SheetHandle(modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(vertical = 14.dp)
            .size(width = 36.dp, height = 4.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
    )
}

/**
 * Dims the page behind the open add menu so the choices read, and closes the menu when it is
 * tapped. Fades on the theme's effects spring. Draws nothing and takes no touches when shut.
 */
@Composable
fun AddMenuScrim(visible: Boolean, onDismiss: () -> Unit) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "scrim"
    )
    if (alpha <= 0.001f && !visible) return
    Box(
        Modifier
            .fillMaxSize()
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .clearAndSetSemantics { }
    )
}
