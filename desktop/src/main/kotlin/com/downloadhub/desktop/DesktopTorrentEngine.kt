package com.downloadhub.desktop

import com.downloadhub.core.DownloadItem
import com.downloadhub.core.DownloadSource
import com.downloadhub.core.DownloadStatus
import com.downloadhub.core.TorrentEngine
import com.downloadhub.core.TorrentSnapshot
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Desktop torrent transfer loop.
 *
 * The Android build drives libtorrent from inside a foreground service; there is no
 * equivalent here, so a coroutine polls the shared engine and mirrors each reading
 * into the queue. The engine itself is the same code the phone runs.
 */
class DesktopTorrentEngine(
    private val store: DesktopStore,
    private val area: DesktopWorkArea,
    private val settingsState: StateFlow<DesktopSettings>,
    private val onChange: () -> Unit,
    private val scope: CoroutineScope
) {
    private val engine = TorrentEngine(
        torrentRoot = { AppPaths.torrentRoot },
        // Unpack the native library into the app's own profile, not %TEMP%. On a
        // machine whose TEMP is a network share or a locked volume, the extraction
        // fails and libtorrent4j then reports a missing library, which points at the
        // wrong thing entirely.
        nativeLibDir = { AppPaths.nativeLibDir }
    )
    private val running = ConcurrentHashMap<String, Boolean>()
    private var loopStarted = false

    fun startLoop() {
        if (loopStarted) return
        loopStarted = true
        scope.launch {
            while (isActive) {
                driveOne()
                delay(1500)
            }
        }
    }

    private suspend fun driveOne() {
        val limit = settingsState.value.maxConcurrent.coerceIn(1, 16)
        val live = store.snapshot().filter { it.source == DownloadSource.TORRENT }
        if (live.size >= limit) return

        val next = live.firstOrNull { it.status == DownloadStatus.QUEUED } ?: return
        runCatching { transfer(next) }
            .onFailure { error ->
                store.update(next.id) {
                    it.copy(status = DownloadStatus.FAILED, errorMessage = error.message ?: "Torrent failed")
                }
                onChange()
            }
    }

    private suspend fun transfer(item: QueuedDownload) {
        if (running.put(item.id, true) == true) return
        try {
            val core = item.toCoreItem()
            store.update(item.id) { it.copy(status = DownloadStatus.RUNNING, errorMessage = null) }
            onChange()

            // A .torrent link has to be fetched before libtorrent can read it; a
            // magnet can go straight in.
            val withFile = if (item.torrentFilePath.isNullOrBlank() && item.url.startsWith("http", true)) {
                val saved = downloadTorrentFile(item)
                if (saved == null) {
                    store.update(item.id) {
                        it.copy(status = DownloadStatus.FAILED, errorMessage = "Could not read that torrent file")
                    }
                    onChange()
                    return
                }
                item.copy(torrentFilePath = saved.absolutePath)
            } else {
                item
            }

            val coreWithFile = withFile.toCoreItem()
            engine.start(coreWithFile)

            while (currentCoroutineContext().isActive) {
                val current = store.get(item.id) ?: return
                when (current.status) {
                    DownloadStatus.PAUSED -> {
                        engine.pause(current.toCoreItem())
                        delay(POLL_MILLIS)
                    }

                    DownloadStatus.COMPLETED, DownloadStatus.FAILED -> return

                    else -> {
                        // A magnet takes a moment to resolve, so a null reading is
                        // "not yet" rather than a failure.
                        val snapshot = engine.poll(current.toCoreItem())
                        if (snapshot == null) {
                            delay(POLL_MILLIS)
                        } else {
                            applySnapshot(item.id, snapshot)
                            if (snapshot.isFinished) {
                                publish(item.id, snapshot)
                                return
                            }
                            delay(POLL_MILLIS)
                        }
                    }
                }
            }
        } finally {
            running.remove(item.id)
        }
    }

    private fun applySnapshot(id: String, snapshot: TorrentSnapshot) {
        store.update(id) {
            it.copy(
                bytesDownloaded = snapshot.bytesDownloaded,
                totalBytes = snapshot.totalBytes,
                speedBytesPerSecond = snapshot.downloadRate,
                torrentInfoHash = snapshot.infoHash.takeIf { hash -> hash.isNotBlank() },
                fileName = snapshot.name?.takeIf { name -> name.isNotBlank() } ?: it.fileName,
                errorMessage = snapshot.error
            )
        }
        onChange()
    }

    /**
     * Moves a finished torrent out of the staging area and into the download folder.
     *
     * libtorrent writes a folder named after the torrent, so the whole tree is moved
     * and the item points at its largest media file for "open".
     */
    private fun publish(id: String, snapshot: TorrentSnapshot) {
        val item = store.get(id) ?: return
        val source = item.outputPath?.let(::File)?.takeIf { it.isDirectory }
            ?: largestMediaIn(torrentDirFor(item))
        if (source == null) {
            store.update(id) {
                it.copy(status = DownloadStatus.COMPLETED, speedBytesPerSecond = 0L)
            }
            onChange()
            return
        }
        val published = area.publishFile(source, source.name, null, item.category)
        val media = largestMediaIn(File(published.location))
        store.update(id) {
            it.copy(
                status = DownloadStatus.COMPLETED,
                speedBytesPerSecond = 0L,
                location = (media ?: File(published.location)).absolutePath,
                fileName = File(published.location).name
            )
        }
        onChange()
    }

    private fun torrentDirFor(item: QueuedDownload): File =
        item.outputPath?.let(::File)
            ?: File(AppPaths.torrentRoot, item.fileName.replace(Regex("""[\\/:*?"<>|]"""), "_"))

    /** Opens the largest video or audio file so "open" does something useful. */
    private fun largestMediaIn(directory: File): File? {
        if (!directory.isDirectory) return null
        return directory.walkTopDown()
            .filter { it.isFile }
            .filter { MEDIA_EXTENSIONS.any { ext -> it.name.lowercase().endsWith(ext) } }
            .maxByOrNull { it.length() }
    }

    /**
     * Fetches a .torrent to disk so libtorrent can read it from a file.
     *
     * A magnet needs no download; only an http(s) link to a .torrent file does,
     * and that is fetched once and reused if the transfer is retried.
     */
    private suspend fun downloadTorrentFile(item: QueuedDownload): File? = withContext(Dispatchers.IO) {
        val dir = File(AppPaths.workDir, "torrent-${item.id}").apply { mkdirs() }
        val target = File(dir, "meta.torrent")
        if (target.isFile && target.length() > 8L) return@withContext target

        runCatching {
            val connection = (URL(item.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "1-download-manager")
            }
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                val body = connection.inputStream.use { it.readBytes() }
                // An HTML error page is not a torrent; reject it rather than
                // handing libtorrent a file it will reject with a worse message.
                if (body.size < 8 || String(body, 0, minOf(16, body.size)).startsWith("<")) {
                    return@runCatching null
                }
                target.outputStream().use { it.write(body) }
                target
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    fun pause(id: String) {
        store.get(id)?.let { engine.pause(it.toCoreItem()) }
    }

    fun resume(id: String) {
        store.update(id) { it.copy(status = DownloadStatus.QUEUED, errorMessage = null) }
        onChange()
    }

    fun remove(id: String, deleteFiles: Boolean) {
        store.get(id)?.let { engine.remove(it.toCoreItem(), deleteFiles) }
    }

    fun close() {
        runCatching { engine.shutdown() }
    }

    private companion object {
        const val POLL_MILLIS = 1000L
        val MEDIA_EXTENSIONS = listOf(
            ".mp4", ".mkv", ".webm", ".mov", ".avi", ".m4v", ".flv",
            ".mp3", ".m4a", ".flac", ".wav", ".opus", ".aac"
        )
    }
}
