package com.downloadhub.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageHistoryTest {
    private fun sample(bytes: Long, running: Boolean = true, speed: Long = 0) =
        UsageHistory.Sample("a", bytes, speed, running)

    @Test
    fun countsOnlyBytesThatArrivedWhileRunningAndSurvivesAReload() {
        val file = File.createTempFile("usage", ".txt").apply { delete(); deleteOnExit() }
        val usage = UsageHistory(file)
        usage.observe(listOf(sample(500, running = false)), 100, 0)   // already on disk
        usage.observe(listOf(sample(500, speed = 40)), 100, 1_000)    // resumed
        usage.observe(listOf(sample(800, speed = 90)), 100, 2_000)
        usage.observe(listOf(sample(100, speed = 10)), 100, 3_000)    // restarted from less
        usage.observe(listOf(sample(150, running = false)), 100, 4_000) // finished

        val reloaded = UsageHistory(file).recent(101, 3)
        assertEquals(listOf(101L, 100L, 99L), reloaded.map { it.epochDay })
        assertEquals(listOf(0L, 350L, 0L), reloaded.map { it.bytes })
        assertEquals(90L, reloaded[1].peakSpeed)
    }

    @Test
    fun oldDaysAreDropped() {
        val file = File.createTempFile("usage", ".txt").apply { deleteOnExit(); writeText("1 5 5\njunk line\n") }
        UsageHistory(file).observe(listOf(sample(0, speed = 1)), 500, 10_000)
        assertEquals("500 0 1", file.readText())
    }
}
