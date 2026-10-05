package com.downloadhub.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.downloadhub.app.data.AppQueue
import com.downloadhub.core.QueueRules
import com.downloadhub.core.QueueSchedule

private val DAYS = listOf("M", "T", "W", "T", "F", "S", "S")

/** Named queues, each with a Start/Stop switch and an optional schedule, as on the desktop. */
@Composable
fun QueuesScreen(
    queues: List<AppQueue>,
    onSave: (AppQueue) -> Unit,
    onDelete: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit
) {
    var editing by remember { mutableStateOf<Pair<AppQueue, Boolean>?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                "A stopped queue starts nothing. A schedule starts and stops a queue by itself; " +
                    "Android checks it about every 15 minutes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        items(queues, key = { it.id }) { queue ->
            Card(Modifier.fillMaxWidth().clickable { editing = queue to false }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(queue.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            summary(queue),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = queue.started, onCheckedChange = { onToggle(queue.id, it) })
                }
            }
        }
        item {
            OutlinedButton(
                onClick = { editing = AppQueue("q" + System.currentTimeMillis().toString(36), "") to true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("New queue") }
        }
    }

    editing?.let { (queue, isNew) ->
        QueueEditor(
            queue = queue,
            isNew = isNew,
            onSave = { onSave(it); editing = null },
            onDelete = if (isNew || queue.id == QueueRules.MAIN) null else { { onDelete(queue.id); editing = null } },
            onDismiss = { editing = null }
        )
    }
}

private fun summary(queue: AppQueue): String {
    val state = if (queue.started) "Running" else "Stopped"
    val limit = if (queue.maxConcurrent > 0) " · ${queue.maxConcurrent} at once" else ""
    if (!queue.scheduleEnabled) return state + limit
    val stop = if (queue.schedule.hasStop) "-" + QueueRules.formatTime(queue.stopMinute) else ""
    return "$state$limit · ${QueueRules.formatTime(queue.startMinute)}$stop"
}

@Composable
private fun QueueEditor(
    queue: AppQueue,
    isNew: Boolean,
    onSave: (AppQueue) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(queue.name) }
    var limit by remember { mutableStateOf(if (queue.maxConcurrent > 0) queue.maxConcurrent.toString() else "") }
    var scheduled by remember { mutableStateOf(queue.scheduleEnabled) }
    var start by remember { mutableStateOf(QueueRules.formatTime(queue.startMinute)) }
    var stop by remember { mutableStateOf(if (queue.schedule.hasStop) QueueRules.formatTime(queue.stopMinute) else "") }
    var days by remember { mutableStateOf(queue.days) }
    val startMinute = QueueRules.parseTime(start)
    val stopMinute = if (stop.isBlank()) QueueSchedule.NO_STOP else QueueRules.parseTime(stop)
    val valid = name.isNotBlank() && (!scheduled || (startMinute != null && stopMinute != null && days.isNotEmpty()))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New queue" else "Edit queue") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    limit,
                    { limit = it.filter(Char::isDigit).take(2) },
                    label = { Text("Downloads at once (blank = app setting)") },
                    singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Start and stop on a schedule", Modifier.weight(1f))
                    Switch(checked = scheduled, onCheckedChange = { scheduled = it })
                }
                if (scheduled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            start, { start = it.take(5) }, label = { Text("Start HH:mm") },
                            singleLine = true, isError = startMinute == null, modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            stop, { stop = it.take(5) }, label = { Text("Stop (optional)") },
                            singleLine = true, isError = stopMinute == null, modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DAYS.forEachIndexed { index, label ->
                            val day = index + 1
                            FilterChip(
                                selected = day in days,
                                onClick = { days = if (day in days) days - day else days + day },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = {
                onSave(
                    queue.copy(
                        name = name.trim(),
                        maxConcurrent = limit.toIntOrNull() ?: 0,
                        scheduleEnabled = scheduled,
                        days = days,
                        startMinute = startMinute ?: queue.startMinute,
                        stopMinute = stopMinute ?: QueueSchedule.NO_STOP
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
