package com.downloadhub.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.downloadhub.core.QueueRules
import com.downloadhub.core.QueueSchedule

internal val DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/**
 * Creates or edits one queue: its name, how many it runs at once, and when it starts and
 * stops by itself. A blank stop time means it runs until it is empty.
 */
@Composable
internal fun QueueEditorDialog(
    queue: QueueConfig,
    isNew: Boolean,
    onSave: (QueueConfig) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(queue.name) }
    var limit by remember { mutableStateOf(if (queue.maxConcurrent > 0) queue.maxConcurrent.toString() else "") }
    var scheduled by remember { mutableStateOf(queue.scheduleEnabled) }
    var start by remember { mutableStateOf(QueueRules.formatTime(queue.startMinute)) }
    var stop by remember {
        mutableStateOf(if (queue.schedule.hasStop) QueueRules.formatTime(queue.stopMinute) else "")
    }
    var days by remember { mutableStateOf(queue.days.toSet()) }

    val startMinute = QueueRules.parseTime(start)
    val stopMinute = if (stop.isBlank()) QueueSchedule.NO_STOP else QueueRules.parseTime(stop)
    val valid = name.isNotBlank() && (!scheduled || (startMinute != null && stopMinute != null && days.isNotEmpty()))

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = APP_DIALOG_PROPERTIES,
        title = { Text(if (isNew) "New queue" else "Edit queue") },
        text = {
            Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = limit,
                    onValueChange = { limit = it.filter(Char::isDigit).take(2) },
                    label = { Text("Downloads at once (blank = app setting)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                TickRow(
                    "Start and stop on a schedule",
                    scheduled,
                    detail = "The queue starts itself at the start time and stops at the stop time."
                ) { scheduled = it }
                if (scheduled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = start,
                            onValueChange = { start = it.take(5) },
                            label = { Text("Start (HH:mm)") },
                            isError = startMinute == null,
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = stop,
                            onValueChange = { stop = it.take(5) },
                            label = { Text("Stop (optional)") },
                            isError = stopMinute == null,
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DAY_LABELS.forEachIndexed { index, label ->
                            val day = index + 1
                            DayChip(label, day in days) { days = if (day in days) days - day else days + day }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    onSave(
                        queue.copy(
                            name = name.trim(),
                            maxConcurrent = limit.toIntOrNull() ?: 0,
                            scheduleEnabled = scheduled,
                            days = days.sorted(),
                            startMinute = startMinute ?: queue.startMinute,
                            stopMinute = stopMinute ?: QueueSchedule.NO_STOP
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("Delete queue", color = AppTheme.Palette.error) }
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
internal fun DayChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Text(
        label,
        fontSize = 11.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) AppTheme.Palette.onAccent else AppTheme.Palette.muted,
        modifier = Modifier
            .background(if (selected) AppTheme.Palette.accent else AppTheme.Palette.surface, shape)
            .border(1.dp, if (selected) AppTheme.Palette.accent else AppTheme.Palette.outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp)
    )
}

/** Picks the queue the selected downloads move to. */
@Composable
internal fun QueuePickerDialog(
    queues: List<QueueConfig>,
    current: String?,
    count: Int,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = APP_DIALOG_PROPERTIES,
        title = { Text(if (count == 1) "Move to queue" else "Move $count downloads to queue") },
        text = {
            Column(Modifier.width(300.dp)) {
                queues.forEach { queue ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(queue.id) }
                            .padding(vertical = 9.dp, horizontal = 4.dp)
                    ) {
                        Text(
                            queue.name,
                            fontSize = 13.sp,
                            fontWeight = if (queue.id == current) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (queue.id == current) AppTheme.Palette.accent else AppTheme.Palette.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Text(queueSummary(queue), fontSize = 11.sp, color = AppTheme.Palette.muted)
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** "Stopped", "Running", or the schedule - what a queue is going to do, in a few words. */
internal fun queueSummary(queue: QueueConfig): String {
    val state = if (queue.started) "Running" else "Stopped"
    if (!queue.scheduleEnabled) return state
    val stop = if (queue.schedule.hasStop) "-" + QueueRules.formatTime(queue.stopMinute) else ""
    return "$state · ${QueueRules.formatTime(queue.startMinute)}$stop"
}
