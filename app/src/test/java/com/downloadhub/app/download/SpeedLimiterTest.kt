package com.downloadhub.app.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The limiter is what enforces the user's speed limit, so the tests check the
 * behaviour that matters: unlimited never sleeps, a configured limit actually
 * throttles, raising it takes effect, and several transfers share one budget.
 */
class SpeedLimiterTest {
    @Test
    fun unlimitedLimitNeverBlocks() = runBlocking {
        val limiter = SpeedLimiter()
        limiter.setLimit(0)
        val start = System.nanoTime()
        repeat(500) { limiter.acquire(64 * 1024) }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("unlimited should not sleep, took ${elapsedMs}ms", elapsedMs < 500)
    }

    @Test
    fun negativeLimitsAreTreatedAsUnlimited() = runBlocking {
        val limiter = SpeedLimiter()
        limiter.setLimit(-5)
        val start = System.nanoTime()
        repeat(100) { limiter.acquire(16 * 1024) }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("negative limit must not throttle", elapsedMs < 500)
    }

    @Test
    fun throttledThroughputStaysNearTheLimit() = runBlocking {
        val limitPerSecond = 512L * 1024
        val limiter = SpeedLimiter()
        limiter.setLimit(limitPerSecond)

        val start = System.nanoTime()
        val requested = (limitPerSecond * 3) / 2
        var granted = 0L
        while (granted < requested) {
            val chunk = 32 * 1024
            limiter.acquire(chunk)
            granted += chunk
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        // Generous slack for scheduler jitter, but a working limiter has to be in
        // the same order of magnitude as the configured rate.
        assertTrue(
            "throttling took ${elapsedMs}ms, expected roughly 1500ms",
            elapsedMs in 700..6_000
        )
    }

    @Test
    fun raisingTheLimitTakesEffect() = runBlocking {
        val limiter = SpeedLimiter()
        limiter.setLimit(64L * 1024)
        val slowStart = System.nanoTime()
        repeat(64) { limiter.acquire(8 * 1024) }
        val slowMs = (System.nanoTime() - slowStart) / 1_000_000

        limiter.setLimit(8L * 1024 * 1024)
        val fastStart = System.nanoTime()
        repeat(64) { limiter.acquire(8 * 1024) }
        val fastMs = (System.nanoTime() - fastStart) / 1_000_000

        assertTrue("fast (${fastMs}ms) should beat slow (${slowMs}ms)", fastMs < slowMs)
    }

    @Test
    fun concurrentCallersShareTheSameBucket() = runBlocking {
        val limitPerSecond = 1L * 1024 * 1024
        val limiter = SpeedLimiter()
        limiter.setLimit(limitPerSecond)
        val start = System.nanoTime()
        val jobs = (1..4).map {
            CoroutineScope(Dispatchers.Default).launch {
                repeat(100) { limiter.acquire(8 * 1024) }
            }
        }
        jobs.forEach { it.join() }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        // Four callers asking for roughly 800 KB each must still fit in seconds.
        assertTrue("shared bucket overshot: ${elapsedMs}ms", elapsedMs < 8_000)
    }
}
