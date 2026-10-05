package com.downloadhub.app.data

import com.downloadhub.core.QueueRules
import com.downloadhub.core.QueueSchedule

/**
 * One named queue on the phone, as on the desktop: a name, how many it runs at once
 * (0 = only the app-wide limit), its Start/Stop state, and an optional schedule.
 */
data class AppQueue(
    val id: String,
    val name: String,
    val maxConcurrent: Int = 0,
    val started: Boolean = true,
    val scheduleEnabled: Boolean = false,
    val days: Set<Int> = (1..7).toSet(),
    val startMinute: Int = 0,
    val stopMinute: Int = QueueSchedule.NO_STOP
) {
    val schedule: QueueSchedule get() = QueueSchedule(scheduleEnabled, days, startMinute, stopMinute)

    companion object {
        fun main() = AppQueue(QueueRules.MAIN, "Main")
    }
}

/**
 * The queue list as one DataStore string: a line per queue, tab-separated fields.
 * Main is always present, first, whatever the string says.
 */
object QueueCodec {
    fun encode(queues: List<AppQueue>): String = queues.joinToString("\n") { q ->
        listOf(
            q.id,
            q.name.replace('\t', ' ').replace('\n', ' '),
            q.maxConcurrent,
            if (q.started) 1 else 0,
            if (q.scheduleEnabled) 1 else 0,
            q.days.sorted().joinToString(","),
            q.startMinute,
            q.stopMinute
        ).joinToString("\t")
    }

    fun decode(text: String?): List<AppQueue> {
        val parsed = text.orEmpty().lines().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 8 || f[0].isBlank()) return@mapNotNull null
            AppQueue(
                id = f[0],
                name = f[1],
                maxConcurrent = f[2].toIntOrNull() ?: 0,
                started = f[3] == "1",
                scheduleEnabled = f[4] == "1",
                days = f[5].split(',').mapNotNull { it.toIntOrNull() }.filter { it in 1..7 }.toSet(),
                startMinute = f[6].toIntOrNull() ?: 0,
                stopMinute = f[7].toIntOrNull() ?: QueueSchedule.NO_STOP
            )
        }
        val main = parsed.firstOrNull { it.id == QueueRules.MAIN } ?: AppQueue.main()
        return listOf(main) + parsed.filter { it.id != QueueRules.MAIN }
    }
}
