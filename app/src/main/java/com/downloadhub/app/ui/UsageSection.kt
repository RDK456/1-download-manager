package com.downloadhub.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.downloadhub.app.DownloadHubApplication
import java.time.LocalDate

/** Bytes downloaded and top speed, day by day. */
@Composable
fun UsageSection() {
    val app = LocalContext.current.applicationContext as? DownloadHubApplication ?: return
    val today = remember { LocalDate.now().toEpochDay() }
    val month = remember { app.container.usageHistory.recent(today, 30) }
    val shown = month.take(14)
    val largest = shown.maxOf { it.bytes }.coerceAtLeast(1)
    SettingsSection("Download history") {
        Text(
            "Today ${formatBytes(month.first().bytes)} · 7 days ${formatBytes(month.take(7).sumOf { it.bytes })}" +
                " · 30 days ${formatBytes(month.sumOf { it.bytes })}",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "Fastest in 30 days: ${formatBytes(month.maxOf { it.peakSpeed })}/s",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column {
            shown.forEach { day ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        LocalDate.ofEpochDay(day.epochDay).toString().substring(5),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(48.dp)
                    )
                    Box(Modifier.weight(1f).height(8.dp)) {
                        Box(
                            Modifier.fillMaxHeight().fillMaxWidth(day.bytes.toFloat() / largest)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                        )
                    }
                    Text(
                        formatBytes(day.bytes),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(72.dp)
                    )
                }
            }
        }
    }
}
