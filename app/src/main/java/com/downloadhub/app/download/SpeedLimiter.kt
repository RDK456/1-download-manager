package com.downloadhub.app.download

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared token bucket that caps the combined download rate of every transfer.
 *
 * One limiter is used for the whole app, so "2 MB/s" means 2 MB/s in total
 * rather than per file, which is what users expect from a global limit.
 */
class SpeedLimiter {
    private val mutex = Mutex()
    private val tokens = AtomicLong(0L)
    private var limitBytesPerSecond: Long = 0L
    private var lastRefillNanos: Long = System.nanoTime()

    /** Applies a new limit (0 disables throttling) and refills the bucket. */
    suspend fun setLimit(bytesPerSecond: Long) = mutex.withLock {
        limitBytesPerSecond = bytesPerSecond.coerceAtLeast(0L)
        if (limitBytesPerSecond == 0L) {
            tokens.set(Long.MAX_VALUE / 2)
        }
        lastRefillNanos = System.nanoTime()
    }

    /**
     * Suspends until [bytes] may be written. Call this after every chunk, so the
     * limit applies to the whole app rather than one connection.
     */
    suspend fun acquire(bytes: Int) {
        val requested = bytes.toLong().coerceAtLeast(1L)
        while (true) {
            val waitMillis = mutex.withLock {
                val limit = limitBytesPerSecond
                if (limit <= 0L) {
                    return@withLock 0L
                }
                refill(limit)
                val available = tokens.get()
                if (available >= requested) {
                    tokens.addAndGet(-requested)
                    0L
                } else {
                    val missing = requested - available
                    // Sleep just long enough for the missing tokens, with slack so
                    // busy transfers do not spin on the mutex.
                    (missing * 1000L / limit).coerceIn(25L, 2_000L)
                }
            }
            if (waitMillis <= 0L) return
            delay(waitMillis)
        }
    }

    private fun refill(limit: Long) {
        val now = System.nanoTime()
        val elapsedNanos = now - lastRefillNanos
        if (elapsedNanos <= 0L) return
        lastRefillNanos = now
        val added = (limit * elapsedNanos / 1_000_000_000L).toLong()
        if (added <= 0L) return
        val current = tokens.get()
        val next = (current + added).coerceAtMost(limit)
        tokens.set(next)
    }
}
