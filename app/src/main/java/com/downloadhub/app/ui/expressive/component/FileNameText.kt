package com.downloadhub.app.ui.expressive.component

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.downloadhub.app.ui.expressive.model.splitFileName

/** Past this system font scale the name wraps over two lines instead of being cut short. */
private const val WRAP_FROM_FONT_SCALE = 1.5f

/**
 * A file name that stays recognisable when it is too long: the middle gives way, the start
 * and the end (with the extension) stay. With large system text there is room to spare
 * vertically and none sideways, so it wraps instead.
 */
@Composable
fun FileNameText(
    name: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMediumEmphasized
) {
    if (LocalDensity.current.fontScale >= WRAP_FROM_FONT_SCALE) {
        Text(name, modifier, style = style, maxLines = 2, overflow = TextOverflow.Ellipsis)
        return
    }
    val parts = splitFileName(name)
    Row(modifier) {
        if (parts.head.isNotEmpty()) {
            Text(parts.head, Modifier.weight(1f, fill = false), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(parts.tail, style = style, maxLines = 1, softWrap = false)
    }
}
