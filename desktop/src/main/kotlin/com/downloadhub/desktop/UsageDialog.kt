package com.downloadhub.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.downloadhub.core.UsageHistory
import java.time.LocalDate

/** The one history this copy of the app writes and Tools > Download history shows. */
internal val usageHistory by lazy { UsageHistory(java.io.File(AppPaths.home, "usage.txt")) }

/** Bytes downloaded and top speed, day by day. */
@Composable
internal fun UsageDialog(onDismiss: () -> Unit) {
    val today = remember { LocalDate.now().toEpochDay() }
    val month = remember { usageHistory.recent(today, 30) }
    val shown = month.take(14)
    val largest = shown.maxOf { it.bytes }.coerceAtLeast(1)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        properties = APP_DIALOG_PROPERTIES,
        title = { Text("Download history") },
        text = {
            Column {
                Text(
                    "Today ${formatBytes(month.first().bytes)}  ·  7 days ${formatBytes(month.take(7).sumOf { it.bytes })}" +
                        "  ·  30 days ${formatBytes(month.sumOf { it.bytes })}",
                    fontSize = 12.sp
                )
                Text(
                    "Fastest in 30 days: ${formatBytes(month.maxOf { it.peakSpeed })}/s",
                    fontSize = 12.sp, color = AppTheme.Palette.muted, modifier = Modifier.padding(bottom = 8.dp)
                )
                shown.forEach { day ->
                    Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(LocalDate.ofEpochDay(day.epochDay).toString(), fontSize = 11.sp, modifier = Modifier.width(80.dp))
                        Box(Modifier.weight(1f).height(8.dp)) {
                            Box(
                                Modifier.fillMaxHeight().fillMaxWidth(day.bytes.toFloat() / largest)
                                    .background(AppTheme.Palette.accent, RoundedCornerShape(4.dp))
                            )
                        }
                        Text(formatBytes(day.bytes), fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(80.dp))
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
