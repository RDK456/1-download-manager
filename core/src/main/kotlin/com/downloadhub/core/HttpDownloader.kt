package com.downloadhub.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HttpDownloader(
    private val store: DownloadStore,
    private val area: WorkArea,
    private val policies: TransferPolicyProvider,
    /**
     * The app-wide cap, shared by every transfer.
     *
     * Must be a single instance held by the caller. One was being created per download
     * inside the engine, which meant each transfer had a private bucket with no limit
     * ever set on it - so the "speed limit" setting did nothing on the desktop at all,
     * while looking like it worked on the phone.
     */
    private val speedLimiter: SpeedLimiter,
    /**
     * This one file's own cap, if it has one.
     *
     * Separate from [speedLimiter] because they answer different questions: the global
     * one stops every download together, this stops one file crowding out the rest.
     */
    private val itemSpeedLimiter: SpeedLimiter? = null,
    /** Resolved destination folder; null means the platform default. */
    private val destinationTreeUri: () -> String? = { null }
) {
    suspend fun download(item: DownloadItem) = withContext(Dispatchers.IO) {
        val work = area.workFile(item.id)
        val policy = runCatching { policies.policyFor(item.id) }.getOrDefault(TransferPolicy())
        val connections = policy.connections
        proxy = policy.proxy
        val segments = SegmentState.fileFor(work)
        // A segment file means this download was started split, and its work file is
        // full-length already - the single-stream path below would read that as done.
        if ((connections > 1 || segments.isFile) && downloadSegmented(item, work, segments, connections)) {
            return@withContext
        }
        var attempt = 0
        var restart = false

        while (true) {
            ensureActive()
            var existing = if (restart) 0L else work.length()
            if (restart) work.delete()

            val connection = open(item, if (existing > 0) "bytes=$existing-" else null)

            try {
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                    val rangeStart = parseContentRangeStart(connection.getHeaderField("Content-Range"))
                    if (existing > 0 && rangeStart != existing) {
                        restart = true
                        attempt++
                        if (attempt < 2) continue
                    }
                } else if (responseCode == HttpURLConnection.HTTP_OK && existing > 0) {
                    existing = 0
                    work.delete()
                } else if (responseCode == 416 && existing > 0 && attempt == 0) {
                    val remoteTotal = parseUnsatisfiedTotal(connection.getHeaderField("Content-Range"))
                    if (remoteTotal == existing) {
                        promoteCompleted(item, work, downloaded = existing, total = remoteTotal)
                        return@withContext
                    }
                    restart = true
                    attempt++
                    if (attempt < 2) continue
                }

                if (responseCode !in 200..299) {
                    throw IOException("Server returned HTTP $responseCode")
                }

                val responseEtag = connection.getHeaderField("ETag")
                val responseLastModified = connection.getHeaderField("Last-Modified")
                val responseMime = connection.contentType?.substringBefore(';')?.trim()
                val total = parseTotal(connection, responseCode, existing)
                val now = System.currentTimeMillis()
                if (responseEtag != null || responseLastModified != null) {
                    store.updateValidators(item.id, responseEtag, responseLastModified, now)
                }
                if (responseMime != null && item.mimeType.isNullOrBlank()) {
                    store.updateMetadata(
                        item.id,
                        item.fileName,
                        responseMime,
                        item.category,
                        total,
                        now
                    )
                } else if (total > 0 && total != item.totalBytes) {
                    store.updateMetadata(
                        item.id,
                        item.fileName,
                        item.mimeType,
                        item.category,
                        total,
                        now
                    )
                }

                val append = responseCode == HttpURLConnection.HTTP_PARTIAL && existing > 0
                var downloaded = existing
                var windowStarted = System.currentTimeMillis()
                var windowBytes = 0L
                var lastUpdate = 0L

                connection.inputStream.use { input ->
                    FileOutputStream(work, append).use { output ->
                        // A larger read buffer keeps the socket busy on fast links.
                        val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            // Two gates in series: the app-wide cap, then this file's
                            // own if it has one.
                            speedLimiter.acquire(count)
                            itemSpeedLimiter?.acquire(count)
                            output.write(buffer, 0, count)
                            downloaded += count
                            windowBytes += count
                            val current = System.currentTimeMillis()
                            if (current - lastUpdate >= 400) {
                                val elapsed = (current - windowStarted).coerceAtLeast(1L)
                                val speed = windowBytes * 1000L / elapsed
                                val percent = if (total > 0) {
                                    (downloaded * 100L / total).coerceIn(0, 100).toInt()
                                } else {
                                    0
                                }
                                store.updateProgress(
                                    item.id,
                                    downloaded,
                                    total,
                                    percent,
                                    speed,
                                    estimateEta(total, downloaded, speed),
                                    current
                                )
                                lastUpdate = current
                                windowStarted = current
                                windowBytes = 0
                            }
                        }
                        output.fd.sync()
                    }
                }

                val finalSize = work.length()
                val finalTotal = if (total > 0) total else finalSize
                val finalPercent = if (finalTotal > 0) {
                    (finalSize * 100L / finalTotal).coerceIn(0, 100).toInt()
                } else {
                    100
                }
                promoteCompleted(
                    item,
                    work,
                    downloaded = finalSize,
                    total = finalTotal,
                    percent = finalPercent
                )
                return@withContext
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                connection.disconnect()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        Unit
    }

    /** Set from the policy when a download starts; null follows the system proxy. */
    @Volatile
    private var proxy: java.net.Proxy? = null

    /** One request, with [range] and its If-Range validator when resuming. */
    private fun open(item: DownloadItem, range: String?): HttpURLConnection {
        val url = URL(item.url)
        val raw = proxy?.let { url.openConnection(it) } ?: url.openConnection()
        return (raw as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 35_000
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", item.userAgent ?: "DownloadHub/1.0")
            // After the engine's own headers, so one typed for this download wins.
            item.request.applyTo(this)
            if (range != null) {
                setRequestProperty("Range", range)
                item.etag?.let { setRequestProperty("If-Range", it) }
                    ?: item.lastModified?.let { setRequestProperty("If-Range", it) }
            }
        }
    }

    /** The server stopped answering byte ranges, so the split copy cannot be finished. */
    private class RangesRefused(message: String) : IOException(message)

    /**
     * Fetches the file over several connections at once, one byte range each.
     *
     * Returns false - having left nothing behind - when the server does not take ranges or
     * will not say how big the file is, and the caller falls back to one stream.
     */
    private suspend fun downloadSegmented(
        item: DownloadItem,
        work: File,
        segmentFile: File,
        connections: Int
    ): Boolean = coroutineScope {
        var state = SegmentState.read(segmentFile)
        if (state == null) {
            segmentFile.delete()
            // A partial from one stream - started before the connection count was raised -
            // is finished the way it was begun rather than thrown away.
            if (work.length() > 0L) return@coroutineScope false
            val total = probeRangeTotal(item) ?: return@coroutineScope false
            // Below two segments' worth the split costs more than it saves.
            if (total < 2 * SegmentState.MIN_SEGMENT_BYTES) return@coroutineScope false
            state = SegmentState.plan(total, connections)
            work.delete()
            RandomAccessFile(work, "rw").use { it.setLength(total) }
            state.write(segmentFile)
        }
        val plan = state
        val total = plan.total

        try {
            FileChannel.open(work.toPath(), StandardOpenOption.WRITE).use { channel ->
                val ticker = launch { reportProgress(item.id, plan, segmentFile) }
                try {
                    // Its own scope, so a worker's failure comes out of it as that failure
                    // rather than as the cancellation of everything around it.
                    coroutineScope {
                        plan.segments.filter { !it.isComplete }
                            .forEach { segment -> launch(Dispatchers.IO) { fetchSegment(item, segment, channel) } }
                    }
                } finally {
                    ticker.cancel()
                    // Whatever happened - paused, failed, done - the next resume starts
                    // from what is actually on disk.
                    channel.force(false)
                    plan.write(segmentFile)
                }
            }
        } catch (refused: RangesRefused) {
            // The file changed on the server or it stopped taking ranges: the pieces
            // already fetched cannot be trusted together, so start again in one stream.
            segmentFile.delete()
            work.delete()
            return@coroutineScope false
        }

        segmentFile.delete()
        promoteCompleted(item, work, downloaded = total, total = total)
        true
    }

    /** The full size, if the server answers a one-byte range with a 206 that says it. */
    private fun probeRangeTotal(item: DownloadItem): Long? {
        val connection = open(item, "bytes=0-0")
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_PARTIAL) return null
            val total = parseUnsatisfiedTotal(connection.getHeaderField("Content-Range"))?.takeIf { it > 0 }
                ?: return null
            val now = System.currentTimeMillis()
            val etag = connection.getHeaderField("ETag")
            val lastModified = connection.getHeaderField("Last-Modified")
            if (etag != null || lastModified != null) store.updateValidators(item.id, etag, lastModified, now)
            val mime = connection.contentType?.substringBefore(';')?.trim()
            store.updateMetadata(item.id, item.fileName, item.mimeType ?: mime, item.category, total, now)
            total
        } catch (error: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /** One worker: fills [segment] from where it got to, retrying a dropped connection. */
    private suspend fun fetchSegment(item: DownloadItem, segment: Segment, channel: FileChannel) {
        val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
        var failures = 0
        while (!segment.isComplete) {
            currentCoroutineContext().ensureActive()
            val from = segment.next
            val connection = open(item, "bytes=$from-${segment.end}")
            try {
                val code = connection.responseCode
                val start = parseContentRangeStart(connection.getHeaderField("Content-Range"))
                if (code != HttpURLConnection.HTTP_PARTIAL || start != from) {
                    throw RangesRefused("Server answered a range with HTTP $code")
                }
                connection.inputStream.use { input ->
                    while (!segment.isComplete) {
                        currentCoroutineContext().ensureActive()
                        val wanted = (segment.length - segment.done).coerceAtMost(buffer.size.toLong()).toInt()
                        val count = input.read(buffer, 0, wanted)
                        if (count < 0) break
                        speedLimiter.acquire(count)
                        itemSpeedLimiter?.acquire(count)
                        val bytes = ByteBuffer.wrap(buffer, 0, count)
                        var position = segment.next
                        while (bytes.hasRemaining()) position += channel.write(bytes, position)
                        segment.done += count
                    }
                }
                failures = 0
            } catch (refused: RangesRefused) {
                throw refused
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                if (++failures > SEGMENT_RETRIES) throw error
                delay(1_000L * failures)
            } finally {
                connection.disconnect()
            }
        }
    }

    /** Publishes speed and progress every 400 ms, and saves the segment file every few seconds. */
    private suspend fun reportProgress(id: String, plan: SegmentState, segmentFile: File) {
        var lastBytes = plan.downloaded
        var lastTime = System.currentTimeMillis()
        var lastSaved = lastTime
        while (true) {
            delay(400)
            val now = System.currentTimeMillis()
            val bytes = plan.downloaded
            val speed = (bytes - lastBytes) * 1000L / (now - lastTime).coerceAtLeast(1L)
            val percent = (bytes * 100L / plan.total).coerceIn(0, 100).toInt()
            store.updateProgress(id, bytes, plan.total, percent, speed, estimateEta(plan.total, bytes, speed), now)
            lastBytes = bytes
            lastTime = now
            if (now - lastSaved >= 3_000L) {
                runCatching { plan.write(segmentFile) }
                lastSaved = now
            }
        }
    }

    private suspend fun promoteCompleted(
        item: DownloadItem,
        work: File,
        downloaded: Long = work.length(),
        total: Long = if (item.totalBytes > 0) item.totalBytes else downloaded,
        percent: Int = 100
    ) {
        val now = System.currentTimeMillis()
        val published = area.publishFile(
            source = work,
            preferredName = item.fileName,
            destinationTreeUri = destinationTreeUri(),
            // The engine already worked out what kind of file this is from the
            // response, so the platform files it without re-reading the extension.
            category = item.category
        )
        store.updateOutputPath(item.id, published.location, now)
        store.updateProgress(item.id, downloaded, total, percent, 0, -1, now)
        store.setStatus(item.id, DownloadStatus.COMPLETED, null, now)
        if (!published.location.startsWith("content:")) {
            area.scan(File(published.location))
        }
    }

    private fun parseContentRangeStart(value: String?): Long? =
        value?.substringAfter("bytes ", "")?.substringBefore('-')?.trim()?.toLongOrNull()

    private fun parseUnsatisfiedTotal(value: String?): Long? =
        value?.substringAfter('/')?.trim()?.toLongOrNull()

    private fun parseTotal(connection: HttpURLConnection, responseCode: Int, existing: Long): Long {
        val contentRangeTotal = connection.getHeaderField("Content-Range")
            ?.substringAfter('/', "")
            ?.trim()
            ?.toLongOrNull()
        if (contentRangeTotal != null && contentRangeTotal > 0) return contentRangeTotal
        val length = connection.contentLengthLong
        return if (length > 0) {
            if (responseCode == HttpURLConnection.HTTP_PARTIAL) existing + length else length
        } else {
            0
        }
    }

    private fun estimateEta(total: Long, downloaded: Long, speed: Long): Long {
        if (total <= 0 || speed <= 0 || downloaded >= total) return -1
        return (total - downloaded) / speed
    }

    private companion object {
        const val TRANSFER_BUFFER_BYTES = 64 * 1024
        /** Dropped connections one segment survives before the download fails. */
        const val SEGMENT_RETRIES = 4
    }
}
