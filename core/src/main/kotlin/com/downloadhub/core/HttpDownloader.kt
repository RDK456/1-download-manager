package com.downloadhub.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
        var attempt = 0
        var restart = false

        while (true) {
            ensureActive()
            var existing = if (restart) 0L else work.length()
            if (restart) work.delete()

            val connection = (URL(item.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 35_000
                instanceFollowRedirects = true
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", item.userAgent ?: "DownloadHub/1.0")
                if (existing > 0) {
                    setRequestProperty("Range", "bytes=$existing-")
                    item.etag?.let { setRequestProperty("If-Range", it) }
                        ?: item.lastModified?.let { setRequestProperty("If-Range", it) }
                }
            }

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
    }
}
