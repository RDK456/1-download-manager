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
    private val settings: SettingsRepository,
    private val thumbnailCache: ThumbnailCache
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

    /**
     * Lists what a pasted link holds: a playlist, album, channel, or one video.
     *
     * `--dump-single-json` answers collections and single videos with the same
     * shape, and `--flat-playlist` keeps each entry small - a full extraction
     * downloads a hundred kilobytes per video before the first row can show.
     * The shared parser reads both, so this never branches on link shape.
     */
    suspend fun fetchPlaylist(url: String): AppPlaylistFetch = withContext(Dispatchers.IO) {
        updateYtDlpIfNeeded()
        ensureInitialized()
        try {
            val request = YoutubeDLRequest(url).apply {
                addOption("--dump-single-json")
                addOption("--flat-playlist")
                addOption("--no-warnings")
                addOption("--no-playlist")
                addOption("--socket-timeout", "30")
            }
            val out = YoutubeDL.getInstance().execute(request, null, null).out
            val listing = out?.let { com.downloadhub.core.parseYouTubeListing(it) }
            if (listing == null || listing.isEmpty) {
                return@withContext AppPlaylistFetch(
                    "", emptyList(), false,
                    "That link held nothing downloadable."
                )
            }
            AppPlaylistFetch(listing.title, listing.entries, listing.single, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = (e as? YoutubeDLException)?.message
                ?.substringAfter("ERROR: ")?.trim()?.takeIf { it.isNotBlank() }
                ?: e.message?.trim()?.takeIf { it.isNotBlank() }
                ?: "yt-dlp could not read that link"
            AppPlaylistFetch("", emptyList(), false, explainAvailability(url, reason))
        }
    }

    /**
     * What a link actually offers, for the quality picker.
     *
     * Runs the same extraction the download will run, but stops at the format list:
     * `getInfo` reads the dump-json the wrapper already fetches, so this costs one
     * lookup and no bytes of video. A failure carries the extractor's message rather
     * than a generic one, because "this video is private" and "sign in to confirm
     * your age" need different responses from the user.
     */
    suspend fun listFormats(url: String): YouTubeFormatListing = withContext(Dispatchers.IO) {
        updateYtDlpIfNeeded()
        ensureInitialized()
        try {
            val info = YoutubeDL.getInstance().getInfo(url)
            val title = info.title?.takeIf { it.isNotBlank() }
                ?: info.fulltitle?.takeIf { it.isNotBlank() }.orEmpty()
            val all = info.formats.orEmpty().mapNotNull { it.toStreamFormat() }
            if (all.isEmpty()) {
                return@withContext failedYouTubeFormats(
                    "No downloadable streams were offered for that link."
                )
            }
            youTubeFormatListing(all, title)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = (e as? YoutubeDLException)?.message
                ?.substringAfter("ERROR: ")?.trim()?.takeIf { it.isNotBlank() }
                ?: e.message?.trim()?.takeIf { it.isNotBlank() }
                ?: "yt-dlp could not read that link"
            failedYouTubeFormats(explainAvailability(url, reason))
        }
    }

    /**
     * Says what an extraction failure means when it claims unavailability.
     *
     * "This video is not available" is what the extractor says both for a video
     * that is gone and for a public video it was refused - rate-limiting or
     * bot-checking on this network. YouTube's oEmbed endpoint answers for any
     * public video with no login, so if it answers, the video is there and the
     * extractor was refused. Anything else keeps the original message: a wrong
     * guess would be worse than an unadorned one.
     */
    private fun explainAvailability(url: String, reason: String): String {
        if (!reason.contains("not available", ignoreCase = true) &&
            !reason.contains("private", ignoreCase = true)
        ) {
            return reason
        }
        val id = youTubeVideoId(url) ?: return reason
        return when (youTubeVideoLooksPublic(id)) {
            true -> "$reason. The video itself looks public, so this is YouTube " +
                "refusing the extractor rather than a removed video - usually " +
                "rate-limiting or bot-checking on this network. Waiting a while " +
                "and trying again is what fixes it."
            else -> reason
        }
    }

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
        // Grab the artwork as soon as the stream is resolved so the queue card can
        // show a thumbnail while the transfer is still running.
        storeArtwork(item.id, info)

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
            destinationTreeUri = settings.currentDestinationTreeUri(),
            // The item already knows whether this was asked for as audio or video,
            // which is what decides the folder; the file's extension is the weaker
            // signal because yt-dlp can deliver m4a from a video URL.
            category = item.category.toCoreCategory()
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
     * Saves the resolved thumbnail URL, its cached copy, and the media duration.
     * Failures are ignored: artwork is a nicety, never a reason to fail a download.
     */
    private suspend fun storeArtwork(id: String, info: com.yausername.youtubedl_android.mapper.VideoInfo) {
        val url = runCatching {
            info.thumbnail?.takeIf { it.isNotBlank() }
                ?: info.thumbnails?.lastOrNull()?.url?.takeIf { it.isNotBlank() }
        }.getOrNull()
        val duration = runCatching { info.duration }.getOrNull()?.takeIf { it > 0 }?.toLong()
        if (url == null && duration == null) return

        val cached = url?.let { runCatching { thumbnailCache.load(null, it) }.getOrNull() }
        val cachedPath = cached?.let { bitmap ->
            runCatching {
                val target = File(File(context.filesDir, "thumbnails"), "$id.jpg")
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
                }
                target.absolutePath
            }.getOrNull()
        }
        runCatching {
            dao.updateThumbnail(
                id = id,
                thumbnailUrl = url,
                thumbnailPath = cachedPath,
                durationSeconds = duration,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    /**
     * Builds the yt-dlp format selection from the user's choice.
     *
     * A row picked from the real format list names its streams outright, and those
     * ids are used verbatim: a height ceiling cannot express the difference between
     * 1080p60 and 1080p, and falling back to it here would download something other
     * than the row that was tapped. The ceiling remains for everything queued before
     * the picker existed, and for anything added without it.
     */
    private fun applyFormatSelection(request: YoutubeDLRequest, item: DownloadEntity) {
        val videoId = item.streamFormatId?.takeIf { it.isNotBlank() }
        val audioId = item.streamAudioFormatId?.takeIf { it.isNotBlank() }
        if (videoId != null) {
            val audioOnly = item.quality == MediaQuality.AUDIO.value ||
                item.category == DownloadCategory.AUDIO
            if (audioOnly) {
                val target = AudioFormat.fromValue(item.audioFormat)
                request.addOption("-f", videoId)
                request.addOption("-x")
                request.addOption("--audio-format", target.value)
                request.addOption("--audio-quality", if (target == AudioFormat.OPUS) "5" else "0")
                request.addOption("--embed-metadata")
                request.addOption("--embed-thumbnail")
            } else {
                request.addOption("-f", videoId + (audioId?.let { "+$it" } ?: "+bestaudio"))
                request.addOption("--merge-output-format", "mp4")
                request.addOption("--embed-metadata")
            }
            return
        }
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
            // Artwork and details travel inside the file, which is what makes a
            // downloaded playlist a library rather than a folder of numbered files.
            request.addOption("--embed-thumbnail")
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
        request.addOption("--embed-metadata")
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
