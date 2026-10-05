package com.downloadhub.core

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueRulesTest {
    // 2026-10-05 is a Monday.
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute)

    private val nightly = QueueSchedule(enabled = true, startMinute = 2 * 60, stopMinute = 6 * 60)

    @Test
    fun startAndStopFireWhenTheirTimePasses() {
        assertEquals(QueueSwitch.START, QueueRules.switchBetween(nightly, at(5, 1, 59), at(5, 2, 0)))
        assertEquals(QueueSwitch.STOP, QueueRules.switchBetween(nightly, at(5, 5, 59), at(5, 6, 1)))
        assertNull(QueueRules.switchBetween(nightly, at(5, 3), at(5, 4)))
    }

    @Test
    fun aMachineThatSleptThroughBothDoesTheLastOne() {
        assertEquals(QueueSwitch.STOP, QueueRules.switchBetween(nightly, at(5, 1), at(5, 7)))
    }

    @Test
    fun anOvernightWindowStopsTheNextMorning() {
        val overnight = QueueSchedule(enabled = true, days = setOf(1), startMinute = 23 * 60, stopMinute = 7 * 60)
        // Monday 23:00 starts; Tuesday 07:00 stops, though Tuesday is not a scheduled day.
        assertEquals(QueueSwitch.START, QueueRules.switchBetween(overnight, at(5, 22), at(5, 23, 30)))
        assertEquals(QueueSwitch.STOP, QueueRules.switchBetween(overnight, at(6, 6), at(6, 8)))
    }

    @Test
    fun onlyScheduledDaysStartAndDisabledNeverDoes() {
        val weekends = nightly.copy(days = setOf(6, 7))
        assertNull(QueueRules.switchBetween(weekends, at(5, 1), at(5, 3)))
        assertEquals(QueueSwitch.START, QueueRules.switchBetween(weekends, at(10, 1), at(10, 3)))
        assertNull(QueueRules.switchBetween(nightly.copy(enabled = false), at(5, 1), at(5, 3)))
    }

    @Test
    fun noStopTimeMeansItOnlyEverStarts() {
        val startOnly = nightly.copy(stopMinute = QueueSchedule.NO_STOP)
        assertEquals(QueueSwitch.START, QueueRules.switchBetween(startOnly, at(5, 1), at(5, 23)))
    }

    @Test
    fun admitRespectsStoppedQueuesPerQueueLimitsAndTheTotal() {
        val items = listOf("a1" to "a", "a2" to "a", "a3" to "a", "b1" to "b", "c1" to "c")
        val admitted = QueueRules.admit(
            ordered = items,
            queueOf = { it.second },
            runningPerQueue = mapOf("a" to 1),
            isStarted = { it != "c" },
            limitOf = { if (it == "a") 2 else 0 },
            free = 3
        ).map { it.first }
        assertEquals(listOf("a1", "b1"), admitted)
        assertEquals(emptyList<String>(), QueueRules.admit(items, { it.second }, emptyMap(), { true }, { 0 }, 0))
    }

    @Test
    fun aWindowContainsItsTimesAndCrossesMidnight() {
        assertEquals(true, QueueRules.isWithin(nightly, at(5, 3)))
        assertEquals(false, QueueRules.isWithin(nightly, at(5, 6)))
        val overnight = QueueSchedule(enabled = true, days = setOf(1), startMinute = 23 * 60, stopMinute = 7 * 60)
        assertEquals(true, QueueRules.isWithin(overnight, at(5, 23, 30)))
        assertEquals("Tuesday morning belongs to Monday's window", true, QueueRules.isWithin(overnight, at(6, 6)))
        assertEquals(false, QueueRules.isWithin(overnight, at(6, 23, 30)))
    }

    @Test
    fun timesParseAndFormat() {
        assertEquals(125, QueueRules.parseTime("2:05"))
        assertNull(QueueRules.parseTime("25:00"))
        assertEquals("02:05", QueueRules.formatTime(125))
    }
}
