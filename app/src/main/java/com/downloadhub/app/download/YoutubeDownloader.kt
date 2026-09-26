package com.downloadhub.app.download

import android.content.Context
import com.downloadhub.app.data.SettingsRepository
import com.downloadhub.app.data.local.DownloadDao
import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.data.model.AudioFormat
import com.downloadhub.app.data.model.DownloadCategory
import com.downloadhub.app.data.model.DownloadStatus
import com.downloadhub.app.data.model.MediaQuality
import com.downloadhub.app.update.YtDlpUpdateChecker
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class YoutubeDownloader(
    private val context: Context,
    private val dao: DownloadDao,
    private val storage: DownloadStorage,
    private val settings: SettingsRepository
) {
    private val initMutex = Mutex()
    private val updateMutex = Mutex()
    private val updatePreferences = context.getSharedPreferences("downloader_state", Context.MODE_PRIVATE)

    @Volatile
    private var initialized = false

    suspend fun ensureInitialized() = withContext(Dispatchers.IO) {
        if (initialized) return@withContext
        initMutex.withLock {
            if (initialized) return@withLock
            YoutubeDL.getInstance().init(context)
            FFmpeg.getInstance().init(context)
            initialized = true
        }
    }

    /**
     * The Android wrapper artifact is intentionally stable, while yt-dlp itself
     * releases independently. Check the stable yt-dlp channel once a day so a
     * fresh install does not stay on the wrapper's older bundled script.
     */
    suspend fun updateYtDlpIfNeeded(force: Boolean = false): String? {
        val now = System.currentTimeMillis()
        val lastCheck = updatePreferences.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        if (!force && now - lastCheck < UPDATE_INTERVAL_MILLIS) {
            return currentVersion()
        }
        ensureInitialized()
        return withContext(Dispatchers.IO) {
            updateMutex.withLock {
                val checkedAt = System.currentTimeMillis()
                val previousCheck = updatePreferences.getLong(KEY_LAST_UPDATE_CHECK, 0L)
                if (!force && checkedAt - previousCheck < UPDATE_INTERVAL_MILLIS) {
                    return@withLock currentVersion()
                }

                runCatching {
                    YoutubeDL.getInstance().updateYoutubeDL(
                        context,
                        YoutubeDL.UpdateChannel.STABLE
                    )
                }
                updatePreferences.edit().putLong(KEY_LAST_UPDATE_CHECK, checkedAt).apply()
                currentVersion()
            }
        }
    }

    fun currentVersion(): String = runCatching { YoutubeDL.version(context) }
        .getOrNull()
        ?: BUNDLED_YTDLP_VERSION

    /**
     * Newest stable yt-dlp release according to the same endpoint the wrapper's
     * STABLE channel uses, or null when the check could not complete. Read-only:
     * nothing is installed here.
     */
    suspend fun latestStableVersion(): String? = YtDlpUpdateChecker().latestStableVersion()

    suspend fun download(item: DownloadEntity, serviceScope: CoroutineScope) = withContext(Dispatchers.IO) {
        updateYtDlpIfNeeded()
        ensureInitialized()
        val info = YoutubeDL.getInstance().getInfo(item.url)
        val title = info.title?.takeIf { it.isNotBlank() } ?: "youtube-video"
        val videoId = info.id?.takeIf { it.isNotBlank() }
        val baseName = if (videoId == null) title else "$title [$videoId]"
        val fileName = LinkParser.sanitizeFileName(baseName)
        val estimatedTotal = estimateTotal(info.fileSize, info.fileSizeApproximate)
        dao.updateMetadata(
            item.id,
            fileName,
            item.mimeType,
            item.category,
            estimatedTotal,
            System.currentTimeMillis()
        )

        val workDirectory = storage.workDirectory(item.id)
        workDirectory.mkdirs()
        val request = YoutubeDLRequest(item.url).apply {
            addOption("--no-playlist")
            addOption("--newline")
            addOption("--continue")
            addOption("--no-overwrites")
            addOption("--retries", "10")
            addOption("--fragment-retries", "10")
            addOption("--concurrent-fragments", "4")
            addOption("--socket-timeout", "30")
            addOption("--paths", workDirectory.absolutePath)
            item.userAgent?.let { addOption("--user-agent", it) }
            applyFormatSelection(this, item)
        }

        val lastCallback = AtomicLong(0L)
        try {
            YoutubeDL.getInstance().execute(
                request = request,
                processId = item.id,
                callback = { progress, eta, _ ->
                    val now = System.currentTimeMillis()
                    if (now - lastCallback.get() >= 400L) {
                        lastCallback.set(now)
                        val safeProgress = progress.coerceIn(0f, 100f)
                        val estimatedBytes = if (estimatedTotal > 0) {
                            (estimatedTotal * safeProgress / 100f).toLong()
                        } else {
                            0L
                        }
                        serviceScope.launch {
                            dao.updateProgress(
                                item.id,
                                estimatedBytes,
                                estimatedTotal,
                                safeProgress.toInt(),
                                0,
                                eta,
                                System.currentTimeMillis()
                            )
                        }
                    }
                }
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cancelled: YoutubeDL.CanceledException) {
            throw CancellationException("YouTube download paused", cancelled)
        } catch (interrupted: InterruptedException) {
            throw CancellationException("YouTube download paused", interrupted)
        }

        ensureActive()
        val completedFile = storage.largestDownloadableFile(workDirectory)
            ?: throw YoutubeDLException("The YouTube download did not produce a file")
        val size = completedFile.length()
        val published = storage.publishFile(
            source = completedFile,
            preferredName = completedFile.name,
            destinationTreeUri = settings.currentDestinationTreeUri()
        )
        workDirectory.deleteRecursively()
        val mime = mimeFor(completedFile)
        val now = System.currentTimeMillis()
        dao.updateOutputPath(item.id, published.location, now)
        dao.updateMetadata(item.id, completedFile.name, mime, item.category, size, now)
        dao.updateProgress(item.id, size, size, 100, 0, -1, now)
        dao.setStatus(item.id, DownloadStatus.COMPLETED, null, now)
        if (!published.location.startsWith("content:")) {
            storage.scan(File(published.location))
        }
    }

    /**
     * Builds the yt-dlp format selection from the user's choice. The explicit
     * height ceiling keeps the request honest, and falls back to the best
     * available stream when YouTube does not offer that height.
     */
    private fun applyFormatSelection(request: YoutubeDLRequest, item: DownloadEntity) {
        val quality = MediaQuality.fromValue(item.quality)
        val audioFormat = AudioFormat.fromValue(item.audioFormat)
        if (quality.isAudioOnly || item.category == DownloadCategory.AUDIO) {
            val target = if (item.category == DownloadCategory.AUDIO && item.quality == null) {
                AudioFormat.M4A
            } else {
                audioFormat
            }
            request.addOption("-f", "bestaudio/best")
            request.addOption("-x")
            request.addOption("--audio-format", target.value)
            request.addOption("--audio-quality", if (target == AudioFormat.OPUS) "5" else "0")
            request.addOption("--embed-metadata")
            return
        }

        val height = quality.maxHeight
        val selector = if (height == null) {
            "bestvideo+bestaudio/best"
        } else {
            "bestvideo[height<=$height]+bestaudio/best[height<=$height]/best"
        }
        request.addOption("-f", selector)
        request.addOption("--merge-output-format", "mp4")
    }

    private fun estimateTotal(exact: Long, approximate: Long): Long = when {
        exact > 0 -> exact
        approximate > 0 -> approximate
        else -> 0
    }

    private fun mimeFor(file: File): String = when (file.extension.lowercase(Locale.US)) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "opus" -> "audio/opus"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "aiff" -> "audio/aiff"
        else -> "application/octet-stream"
    }

    private companion object {
        const val BUNDLED_YTDLP_VERSION = "2026.08.19"
        const val KEY_LAST_UPDATE_CHECK = "yt_dlp_last_update_check"
        const val UPDATE_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L
    }
}
