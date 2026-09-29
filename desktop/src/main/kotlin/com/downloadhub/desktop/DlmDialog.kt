package com.downloadhub.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * A dialog the app draws itself.
 *
 * Material's `AlertDialog` sizes its own content and will not be told otherwise: it lays
 * the `text` slot out at a fixed maximum, so a dialog that asked for 880 dp got 560 and
 * squeezed the file list into the remainder. The five size columns then did not fit
 * beside the name, and their headings wrapped one letter per line - "Size" as S, i, z, e
 * down the page.
 *
 * Drawing the panel is the only way to get a width the app decides. It is also cheaper:
 * no second window, so there is no `Dialog` to be composed inside the `Window` for the
 * sake of a `LocalComposeScene`, and nothing to go wrong about z-order or focus.
 *
 * Composed inside the `Window` like every other dialog here, for the same reason as the
 * rest: a `Dialog` composed at the application level throws "CompositionLocal
 * LocalComposeScene not provided" the instant it appears, and takes the JVM with it.
 */
@Composable
fun DlmDialog(
    title: String,
    subtitle: String? = null,
    width: Dp = 460.dp,
    onDismiss: () -> Unit,
    actions: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            // The scrim. A click on it dismisses, which is the convention and also the
            // only way out for a dialog too large to fit on a small screen.
            .background(Color(0xB3000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .width(width)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                // The clicks inside must not reach the scrim, or every tick box and
                // button in the dialog also dismisses it.
                .clickable(enabled = false) { }
                .padding(20.dp)
        ) {
            Text(
                title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            Spacer(Modifier.height(14.dp))
            content()
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                actions()
            }
        }
    }
}
