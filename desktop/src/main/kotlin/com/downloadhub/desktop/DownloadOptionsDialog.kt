package com.downloadhub.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import com.downloadhub.core.DisplayFormat
import com.downloadhub.core.DownloadItem
import com.downloadhub.core.DownloadPriority

/**
 * One download's own settings.
 *
 * Modelled on the same two apps the rest of this came from: AB Download Manager lets a
 * single download carry its own speed limit and start time rather than only inheriting
 * the global ones, and qBittorrent orders its queue by a per-item priority and stops a
 * finished torrent sharing once it has given back enough.
 *
 * All of it was missing here, and the cost was concrete: one large file could take every
 * slot and saturate the connection with nothing the user could do about it, the queue
 * could not be reordered, and a finished torrent seeded for ever with no way to say
 * otherwise.
 */
@Composable
fun DownloadOptionsDialog(
    item: DownloadItem,
    globalSpeedLimitBytesPerSecond: Long,
    onSave: (priorityRank: Int, speedLimit: Long, startAfter: Long, ratio: Double, minutes: Int) -> Unit,
    onDismiss: () -> Unit,
    /** Headers, cookies and login for an ordinary download; not asked for otherwise. */
    onSaveRequest: (com.downloadhub.core.HttpRequestOptions) -> Unit = {}
) {
    val isPlainLink = item.source == com.downloadhub.core.DownloadSource.HTTP
    var headersText by remember(item.id) {
        mutableStateOf(com.downloadhub.core.HttpRequestOptions.formatHeaders(item.request.headers))
    }
    var cookies by remember(item.id) { mutableStateOf(item.request.cookies) }
    var username by remember(item.id) { mutableStateOf(item.request.username) }
    var password by remember(item.id) { mutableStateOf(item.request.password) }
    var priority by remember(item.id) { mutableStateOf(item.priority) }
    var speedLimitText by remember(item.id) {
        mutableStateOf(
            if (item.speedLimitBytesPerSecond > 0) {
                (item.speedLimitBytesPerSecond / 1024).toString()
            } else {
                ""
            }
        )
    }
    var startAfter by remember(item.id) {
        mutableStateOf(startAfterPresetFor(item.startAfterEpochMillis))
    }
    var ratioText by remember(item.id) {
        mutableStateOf(if (item.shareRatioLimit > 0) trimDouble(item.shareRatioLimit) else "")
    }
    var minutesText by remember(item.id) {
        mutableStateOf(if (item.seedTimeLimitMinutes > 0) item.seedTimeLimitMinutes.toString() else "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = APP_DIALOG_PROPERTIES,
        title = {
            Column {
                Text("Download options", fontWeight = FontWeight.SemiBold)
                Text(
                    item.fileName,
                    fontSize = 11.sp,
                    color = AppTheme.Palette.muted,
                    maxLines = 1
                )
            }
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Section("Priority")
                Text(
                    "Higher priority downloads are started first when a slot frees up.",
                    fontSize = 11.sp,
                    color = AppTheme.Palette.muted
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DownloadPriority.entries.forEach { level ->
                        FilterChip(
                            selected = priority == level,
                            onClick = { priority = level },
                            label = { Text(shortLabel(level), fontSize = 11.sp) }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Section("Speed limit")
                OutlinedTextField(
                    value = speedLimitText,
                    onValueChange = { speedLimitText = it.filter(Char::isDigit).take(7) },
                    singleLine = true,
                    label = { Text("This download only, in KB/s") },
                    placeholder = {
                        Text(
                            if (globalSpeedLimitBytesPerSecond > 0) {
                                "App-wide: ${DisplayFormat.bytes(globalSpeedLimitBytesPerSecond)}/s"
                            } else {
                                "No limit"
                            },
                            fontSize = 11.sp
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Leave empty to use the app-wide limit. It can lower that limit, never " +
                        "raise past it.",
                    fontSize = 11.sp,
                    color = AppTheme.Palette.muted
                )

                Spacer(Modifier.height(16.dp))
                Section("Start")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StartPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = startAfter == preset,
                            onClick = { startAfter = preset },
                            label = { Text(preset.label, fontSize = 11.sp) }
                        )
                    }
                }
                if (startAfter != StartPreset.ASAP) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Starts at ${describeStart(startAfter)}.",
                        fontSize = 11.sp,
                        color = AppTheme.Palette.muted
                    )
                }

                if (item.isTorrent) {
                    Spacer(Modifier.height(16.dp))
                    Section("Seeding")
                    Text(
                        "A finished torrent keeps uploading to peers. These are the two ways " +
                            "qBittorrent offers to stop it. Leave both empty to seed for ever.",
                        fontSize = 11.sp,
                        color = AppTheme.Palette.muted
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = ratioText,
                            onValueChange = { ratioText = it.filter { c -> c.isDigit() || c == '.' }.take(5) },
                            singleLine = true,
                            label = { Text("Stop at ratio") },
                            placeholder = { Text("2.0", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = minutesText,
                            onValueChange = { minutesText = it.filter(Char::isDigit).take(5) },
                            singleLine = true,
                            label = { Text("Stop after minutes") },
                            placeholder = { Text("60", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (isPlainLink) {
                    Spacer(Modifier.height(16.dp))
                    HttpRequestFields(
                        headers = headersText,
                        onHeaders = { headersText = it },
                        cookies = cookies,
                        onCookies = { cookies = it },
                        username = username,
                        onUsername = { username = it },
                        password = password,
                        onPassword = { password = it }
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(
                onClick = {
                    if (isPlainLink) {
                        onSaveRequest(
                            com.downloadhub.core.HttpRequestOptions(
                                headers = com.downloadhub.core.HttpRequestOptions.parseHeaders(headersText),
                                cookies = cookies.trim(),
                                username = username.trim(),
                                password = password
                            )
                        )
                    }
                    onSave(
                        priority.rank,
                        // KB/s in the field, bytes per second stored.
                        (speedLimitText.toLongOrNull() ?: 0L).coerceAtLeast(0L) * 1024L,
                        startAfter.epochMillisFrom(now()),
                        ratioText.toDoubleOrNull() ?: 0.0,
                        minutesText.toIntOrNull() ?: 0
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun Section(label: String) {
    Text(
        label,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = AppTheme.Palette.muted,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

/**
 * The words on a priority chip.
 *
 * Five full labels do not fit across the dialog at a readable size, so the two middles
 * are abbreviated. The tooltip in the row still spells them out.
 */
private fun shortLabel(level: DownloadPriority): String = when (level) {
    DownloadPriority.LOW -> "Low"
    DownloadPriority.BELOW_NORMAL -> "Below"
    DownloadPriority.NORMAL -> "Normal"
    DownloadPriority.ABOVE_NORMAL -> "Above"
    DownloadPriority.HIGH -> "High"
}

/**
 * When a download should start, as four choices rather than a date picker.
 *
 * A full date-and-time picker is a lot of UI for something used as "not right now". These
 * cover the reasons somebody defers a download, and each one is unambiguous.
 */
enum class StartPreset(val label: String) {
    ASAP("Straight away"),
    HOUR("In 1 hour"),
    SIX_HOURS("In 6 hours"),
    TOMORROW("Tomorrow morning");

    /** Epoch milliseconds for this preset, or 0 for "as soon as possible". */
    fun epochMillisFrom(nowMillis: Long): Long = when (this) {
        ASAP -> 0L
        HOUR -> nowMillis + 60L * 60L * 1000L
        SIX_HOURS -> nowMillis + 6L * 60L * 60L * 1000L
        // 08:00 local time tomorrow.
        TOMORROW -> {
            val calendar = java.util.Calendar.getInstance()
            calendar.timeInMillis = nowMillis
            calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 8)
            calendar.set(java.util.Calendar.MINUTE, 0)
            calendar.set(java.util.Calendar.SECOND, 0)
            calendar.set(java.util.Calendar.MILLISECOND, 0)
            calendar.timeInMillis
        }
    }
}

/**
 * Which preset a stored time corresponds to, so reopening the dialog shows what is set.
 *
 * Anything that does not match a preset - a time set by an older build, or one that has
 * already passed - shows as "straight away" rather than pretending to be one of the
 * options. Showing a time in the past as "in 1 hour" would be worse than not matching.
 */
fun startAfterPresetFor(epochMillis: Long): StartPreset {
    if (epochMillis <= 0L) return StartPreset.ASAP
    val now = now()
    val remaining = epochMillis - now
    return when {
        remaining <= 0L -> StartPreset.ASAP
        else -> StartPreset.entries.firstOrNull { preset ->
            preset != StartPreset.ASAP &&
                kotlin.math.abs(preset.epochMillisFrom(now) - epochMillis) < PRESET_TOLERANCE_MILLIS
        } ?: StartPreset.ASAP
    }
}

/** How close a stored time has to be to count as a preset. */
private const val PRESET_TOLERANCE_MILLIS = 60_000L

private fun describeStart(preset: StartPreset): String = when (preset) {
    StartPreset.ASAP -> "straight away"
    StartPreset.HOUR -> "in an hour"
    StartPreset.SIX_HOURS -> "in six hours"
    StartPreset.TOMORROW -> "tomorrow at 8:00"
}

private fun trimDouble(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

/** Overridable so the dialog can be reasoned about without depending on the clock. */
internal var now: () -> Long = { System.currentTimeMillis() }
