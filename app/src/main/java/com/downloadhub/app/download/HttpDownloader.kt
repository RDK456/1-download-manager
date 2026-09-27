package com.downloadhub.app.download

import com.downloadhub.app.data.SettingsRepository
import com.downloadhub.app.data.local.DownloadDao
import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.data.model.DownloadStatus
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
    private val dao: DownloadDao,
    private val storage: DownloadStorage,
    private val settings: SettingsRepository,
    private val speedLimiter: SpeedLimiter
) {
    suspend fun download(item: DownloadEntity) = withContext(Dispatchers.IO) {
        val work = storage.workFile(item.id)
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
                    dao.updateValidators(item.id, responseEtag, responseLastModified, now)
                }
                if (responseMime != null && item.mimeType.isNullOrBlank()) {
                    dao.updateMetadata(
                        item.id,
                        item.fileName,
                        responseMime,
                        item.category,
                        total,
                        now
                    )
                } else if (total > 0 && total != item.totalBytes) {
                    dao.updateMetadata(
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
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            // Shared bucket: caps the whole app's download rate.
                            speedLimiter.acquire(count)
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
                                dao.updateProgress(
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
        item: DownloadEntity,
        work: File,
        downloaded: Long = work.length(),
        total: Long = if (item.totalBytes > 0) item.totalBytes else downloaded,
        percent: Int = 100
    ) {
        val now = System.currentTimeMillis()
        val published = storage.publishFile(
            source = work,
            preferredName = item.fileName,
            destinationTreeUri = settings.currentDestinationTreeUri()
        )
        dao.updateOutputPath(item.id, published.location, now)
        dao.updateProgress(item.id, downloaded, total, percent, 0, -1, now)
        dao.setStatus(item.id, DownloadStatus.COMPLETED, null, now)
        if (!published.location.startsWith("content:")) {
            storage.scan(File(published.location))
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
}
