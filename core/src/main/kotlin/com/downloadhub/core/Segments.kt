package com.downloadhub.core

import java.io.File

/**
 * One byte range of a multi-connection download.
 *
 * [end] is inclusive, as in an HTTP Range header. [done] is how many bytes of the range
 * are on disk, written by one worker and read by the progress ticker.
 */
class Segment(val start: Long, end: Long, done: Long = 0L) {
    @Volatile
    var done: Long = done

    /** Moves down when an idle worker takes the far half of this range ([SegmentState.steal]). */
    @Volatile
    var end: Long = end
        internal set

    /** A worker is fetching this range right now. */
    @Volatile
    internal var active: Boolean = false

    val length: Long get() = end - start + 1
    val isComplete: Boolean get() = done >= length
    /** The next byte this segment still needs. */
    val next: Long get() = start + done
}

/**
 * The resumable state of a segmented download, kept beside its work file.
 *
 * The work file of a segmented download is allocated at full size up front, so its
 * length says nothing about how much has arrived. Without this file a resume would read
 * a full-length file as a finished one.
 */
class SegmentState(val total: Long, segments: List<Segment>) {
    private val list = java.util.concurrent.CopyOnWriteArrayList(segments)
    val segments: List<Segment> get() = list

    /**
     * Hands an idle worker its next range: an unfinished one nobody is fetching, else the
     * far half of the largest range still being fetched - so the last bytes of a file are
     * shared out instead of waiting on the slowest connection. Null when nothing is worth
     * splitting. The victim's reader stops at its new, shorter end.
     */
    @Synchronized
    fun steal(minSplitBytes: Long = MIN_SPLIT_BYTES): Segment? {
        list.firstOrNull { !it.isComplete && !it.active }?.let { it.active = true; return it }
        val victim = list.filter { !it.isComplete }.maxByOrNull { it.end - it.next + 1 } ?: return null
        val remaining = victim.end - victim.next + 1
        if (remaining < 2 * minSplitBytes) return null
        // Rounded down to 4 KB; the victim may still be mid-read, so keep a wide margin.
        val mid = ((victim.next + remaining / 2) / 4096) * 4096
        if (mid <= victim.next || mid > victim.end) return null
        val taken = Segment(mid, victim.end).also { it.active = true }
        victim.end = mid - 1
        list.add(list.indexOf(victim) + 1, taken)
        return taken
    }
    val downloaded: Long get() = segments.sumOf { it.done.coerceAtMost(it.length) }
    val isComplete: Boolean get() = segments.all { it.isComplete }

    fun encode(): String = buildString {
        append(VERSION).append(' ').append(total).append('\n')
        segments.forEach { append(it.start).append(' ').append(it.end).append(' ').append(it.done).append('\n') }
    }

    /** Written to a temporary file and renamed, so a crash mid-write leaves the old state. */
    fun write(file: File) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(encode())
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    companion object {
        private const val VERSION = "v1"

        /** Where the state of [work] lives. */
        fun fileFor(work: File): File = File(work.parentFile, work.name + ".segments")

        /** Null for a missing, unreadable or inconsistent file: the caller starts over. */
        fun read(file: File): SegmentState? = runCatching {
            if (!file.isFile) return null
            decode(file.readText())
        }.getOrNull()

        fun decode(text: String): SegmentState? {
            val lines = text.lines().filter { it.isNotBlank() }
            val header = lines.firstOrNull()?.split(' ') ?: return null
            if (header.size != 2 || header[0] != VERSION) return null
            val total = header[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val segments = lines.drop(1).map { line ->
                val parts = line.split(' ').map { it.toLongOrNull() ?: return null }
                if (parts.size != 3) return null
                Segment(parts[0], parts[1], parts[2])
            }
            if (segments.isEmpty()) return null
            // The ranges have to tile the file exactly, or bytes would be skipped or doubled.
            var expected = 0L
            segments.forEach { segment ->
                if (segment.start != expected || segment.end < segment.start) return null
                if (segment.done !in 0..segment.length) return null
                expected = segment.end + 1
            }
            if (expected != total) return null
            return SegmentState(total, segments)
        }

        /**
         * Splits [total] bytes into at most [connections] contiguous ranges, none smaller
         * than [minSegmentBytes] - a 300 KB file is not worth eight connections.
         */
        fun plan(total: Long, connections: Int, minSegmentBytes: Long = MIN_SEGMENT_BYTES): SegmentState {
            require(total > 0) { "total must be positive" }
            val byMinimum = (total / minSegmentBytes).coerceAtLeast(1L)
            val count = connections.toLong().coerceIn(1L, byMinimum).toInt()
            val base = total / count
            val segments = (0 until count).map { index ->
                val start = index * base
                val end = if (index == count - 1) total - 1 else start + base - 1
                Segment(start, end)
            }
            return SegmentState(total, segments)
        }

        const val MIN_SEGMENT_BYTES = 1L * 1024 * 1024

        /** A range is only split when each half keeps at least this much. */
        const val MIN_SPLIT_BYTES = 1L * 1024 * 1024
    }
}
