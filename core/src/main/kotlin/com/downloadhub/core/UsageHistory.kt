package com.downloadhub.core

import java.io.File

/**
 * How much was downloaded each day, and the fastest speed seen that day.
 *
 * Fed with the queue as it changes: a download's new bytes count when it was already running
 * the last time it was seen, so bytes already on disk at a start or a resume are not counted.
 * shortcut: a file that starts and ends between two sightings is missed, and a torrent
 * re-check counts what it verifies; count inside the transfer loops if exact figures matter.
 */
class UsageHistory(private val file: File) {
    data class Sample(val id: String, val bytes: Long, val speed: Long, val running: Boolean)
    data class Day(val epochDay: Long, val bytes: Long = 0L, val peakSpeed: Long = 0L)

    private val seen = HashMap<String, Sample>()
    private val days = HashMap<Long, Day>()
    private var dirty = false
    private var savedAt = 0L

    init {
        runCatching {
            if (file.isFile) file.readLines().forEach { line ->
                val f = line.split(' ').mapNotNull { it.toLongOrNull() }
                if (f.size == 3) days[f[0]] = Day(f[0], f[1], f[2])
            }
        }
    }

    @Synchronized
    fun observe(samples: List<Sample>, today: Long, nowMillis: Long) {
        var added = 0L
        var speed = 0L
        for (sample in samples) {
            val before = seen.put(sample.id, sample)
            if (before != null && before.running) added += (sample.bytes - before.bytes).coerceAtLeast(0L)
            if (sample.running) speed += sample.speed
        }
        val day = days[today] ?: Day(today)
        if (added > 0 || speed > day.peakSpeed) {
            days[today] = day.copy(bytes = day.bytes + added, peakSpeed = maxOf(day.peakSpeed, speed))
            dirty = true
        }
        // Written a few seconds apart while moving, and at once when everything has stopped.
        if (dirty && (nowMillis - savedAt >= SAVE_EVERY_MILLIS || samples.none { it.running })) {
            save(today)
            savedAt = nowMillis
        }
    }

    /** The last [count] days ending [today], newest first, with empty days filled in. */
    @Synchronized
    fun recent(today: Long, count: Int): List<Day> = (0 until count).map { days[today - it] ?: Day(today - it) }

    private fun save(today: Long) {
        days.keys.removeAll { it <= today - KEEP_DAYS }
        runCatching {
            val temp = File(file.path + ".tmp")
            temp.writeText(days.values.sortedBy { it.epochDay }.joinToString("\n") { "${it.epochDay} ${it.bytes} ${it.peakSpeed}" })
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
            dirty = false
        }
    }

    companion object {
        const val KEEP_DAYS = 90
        private const val SAVE_EVERY_MILLIS = 5_000L
    }
}
