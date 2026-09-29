package com.downloadhub.desktop

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * Keeps one copy of the app running.
 *
 * There was no guard at all, so every click on the app's icon started another copy.
 * Each one opened its own window, its own connection to the same database and its own
 * session writing the same files - and clicking a magnet link started a second one
 * rather than showing the one already open.
 *
 * The lock is a file lock on the app's own profile folder, which Windows releases when
 * the process dies. That matters: a crashed copy leaves nothing behind to clean up, so
 * there is no "stale lock" state where the app refuses to start and the only cure is
 * deleting a file the user has never heard of.
 *
 * A second copy does not simply exit. It hands its command line to the first through
 * [IntakeChannel] - which is the whole point, since the reason Windows started it was
 * to open something - and exits.
 */
class SingleInstance(
    private val lockFile: File = AppPaths.instanceLockFile,
    /** How long to keep trying, so a copy that is just exiting gets out of the way. */
    private val acquireTimeoutMillis: Long = ACQUIRE_TIMEOUT_MILLIS
) {
    private var channel: FileChannel? = null
    private var held: FileLock? = null

    /** True when this process is the copy that owns the app. */
    val isPrimary: Boolean get() = held != null

    /**
     * Tries to become the one copy.
     *
     * Returns false when another copy already holds it. The wait is not optimism: two
     * clicks in quick succession genuinely race here, and the second should join the
     * first rather than report a phantom "already running" and then find nothing.
     */
    fun tryAcquire(): Boolean {
        if (held != null) return true
        runCatching { lockFile.parentFile?.mkdirs() }
        val opened = runCatching {
            RandomAccessFile(lockFile, "rw").channel.also { channel = it }
        }.getOrNull() ?: return false

        val deadline = System.currentTimeMillis() + acquireTimeoutMillis
        while (true) {
            val taken = runCatching { opened.tryLock() }
                .getOrElse { failure ->
                    // Thrown when *this* JVM already holds it, which cannot happen for a
                    // second process but is worth surviving rather than crashing on.
                    if (failure is OverlappingFileLockException) null else throw failure
                }
            if (taken != null) {
                // The channel is kept in a field: releasing it would release the lock,
                // and letting the local go out of scope closes it.
                held = taken
                return true
            }
            if (System.currentTimeMillis() >= deadline) {
                runCatching { opened.close() }
                channel = null
                return false
            }
            Thread.sleep(RETRY_INTERVAL_MILLIS)
        }
    }

    /** Gives the lock up, so a later launch can take over. */
    fun release() {
        runCatching { held?.release() }
        held = null
        runCatching { channel?.close() }
        channel = null
    }

    companion object {
        /**
         * Long enough to cover a copy that is on its way out, short enough that a
         * genuine second click is not left looking hung.
         */
        const val ACQUIRE_TIMEOUT_MILLIS = 4_000L
        private const val RETRY_INTERVAL_MILLIS = 120L
    }
}
