package com.downloadhub.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * When a queue starts and stops by itself.
 *
 * Modelled on AB Download Manager: the schedule presses the queue's Start and Stop
 * buttons at the given times, nothing more. Between those moments the user can start or
 * stop the queue by hand and the schedule does not fight them.
 *
 * [days] are ISO day numbers, 1 = Monday to 7 = Sunday. Times are minutes after
 * midnight. A [stopMinute] of -1 means "start, and run until the queue is empty"; a stop
 * time at or before the start time belongs to the next morning.
 */
data class QueueSchedule(
    val enabled: Boolean = false,
    val days: Set<Int> = (1..7).toSet(),
    val startMinute: Int = 0,
    val stopMinute: Int = NO_STOP
) {
    val hasStop: Boolean get() = stopMinute in 0 until MINUTES_PER_DAY

    companion object {
        const val NO_STOP = -1
        const val MINUTES_PER_DAY = 24 * 60
    }
}

enum class QueueSwitch { START, STOP }

object QueueRules {
    /** The queue every download is in unless moved. It cannot be deleted. */
    const val MAIN = "main"

    /**
     * The last start or stop time that fell in (from, to], if any.
     *
     * Checked on a timer rather than at exact moments, so a machine that slept through a
     * start time starts the queue when it wakes - and one that slept through both the
     * start and the stop does whichever came last.
     */
    fun switchBetween(schedule: QueueSchedule, from: LocalDateTime, to: LocalDateTime): QueueSwitch? {
        if (!schedule.enabled || schedule.days.isEmpty() || !to.isAfter(from)) return null
        var latest: Pair<LocalDateTime, QueueSwitch>? = null
        fun consider(at: LocalDateTime, switch: QueueSwitch) {
            val current = latest
            if (at.isAfter(from) && !at.isAfter(to) && (current == null || !at.isBefore(current.first))) {
                latest = at to switch
            }
        }
        // From the day before: an overnight stop belongs to the previous day's window.
        var day: LocalDate = from.toLocalDate().minusDays(1)
        val last = to.toLocalDate()
        var guard = 0
        while (!day.isAfter(last) && guard++ < 9) {
            if (day.dayOfWeek.value in schedule.days) {
                consider(day.atTime(timeOf(schedule.startMinute)), QueueSwitch.START)
                if (schedule.hasStop) {
                    val stopDay = if (schedule.stopMinute <= schedule.startMinute) day.plusDays(1) else day
                    consider(stopDay.atTime(timeOf(schedule.stopMinute)), QueueSwitch.STOP)
                }
            }
            day = day.plusDays(1)
        }
        return latest?.second
    }

    /**
     * Which of [ordered] may start now, in order.
     *
     * A stopped queue admits nothing; a queue with a limit admits up to it, counting what
     * of that queue is already running; and [free] caps the total. A limit of zero means
     * the queue has none of its own.
     */
    fun <T> admit(
        ordered: List<T>,
        queueOf: (T) -> String,
        runningPerQueue: Map<String, Int>,
        isStarted: (String) -> Boolean,
        limitOf: (String) -> Int,
        free: Int
    ): List<T> {
        if (free <= 0) return emptyList()
        val running = runningPerQueue.toMutableMap()
        val admitted = mutableListOf<T>()
        for (item in ordered) {
            if (admitted.size >= free) break
            val queue = queueOf(item)
            if (!isStarted(queue)) continue
            val limit = limitOf(queue)
            val now = running[queue] ?: 0
            if (limit > 0 && now >= limit) continue
            running[queue] = now + 1
            admitted += item
        }
        return admitted
    }

    /**
     * Whether [now] falls inside the schedule's window: from the start time to the stop
     * time on a scheduled day, with a stop at or before the start meaning the next morning.
     * This is qBittorrent's "alternative speed limits from ... to ..., on these days".
     */
    fun isWithin(schedule: QueueSchedule, now: LocalDateTime): Boolean {
        if (!schedule.enabled || !schedule.hasStop || schedule.days.isEmpty()) return false
        val minute = now.hour * 60 + now.minute
        val today = now.dayOfWeek.value
        val yesterday = now.minusDays(1).dayOfWeek.value
        return if (schedule.stopMinute > schedule.startMinute) {
            today in schedule.days && minute >= schedule.startMinute && minute < schedule.stopMinute
        } else {
            (today in schedule.days && minute >= schedule.startMinute) ||
                (yesterday in schedule.days && minute < schedule.stopMinute)
        }
    }

    fun timeOf(minute: Int): LocalTime =
        LocalTime.of((minute / 60).coerceIn(0, 23), (minute % 60).coerceIn(0, 59))

    /** "HH:mm" to minutes after midnight, or null if it is not a time. */
    fun parseTime(text: String): Int? {
        val match = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""").find(text) ?: return null
        val hours = match.groupValues[1].toInt()
        val minutes = match.groupValues[2].toInt()
        if (hours > 23 || minutes > 59) return null
        return hours * 60 + minutes
    }

    fun formatTime(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
}
