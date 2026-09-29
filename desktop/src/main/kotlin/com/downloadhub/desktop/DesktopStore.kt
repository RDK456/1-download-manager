package com.downloadhub.desktop

import com.downloadhub.core.DownloadCategory
import com.downloadhub.core.DownloadSource
import com.downloadhub.core.DownloadStatus
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Where the desktop app keeps its state.
 *
 * Settings and the download queue live under the user's profile rather than beside
 * the executable, so replacing or upgrading the install never loses the queue.
 */
object AppPaths {
    private const val APP_DIR = "1DownloadManager"

    val home: File by lazy {
        val base = System.getProperty("user.home") ?: "."
        File(base, "AppData" + File.separator + "Roaming" + File.separator + APP_DIR).apply { mkdirs() }
    }

    val settingsFile: File get() = File(home, "settings.json")
    val queueFile: File get() = File(home, "queue.json")

    /**
     * The lock that keeps one copy of the app running.
     *
     * Kept in the profile rather than beside the executable, so it is the same file
     * whether the app was installed or unzipped - two copies started from different
     * folders are still two copies of one app.
     */
    val instanceLockFile: File get() = File(home, "instance.lock")

    /**
     * Where a second copy leaves what Windows asked it to open.
     *
     * The copy that already owns the lock collects from here, so a magnet link or a
     * .torrent opened while it was running is queued rather than lost.
     */
    val pendingIntakeFile: File get() = File(home, "pending-intake.txt")

    /** Scratch space for in-flight transfers, kept off the destination folder. */
    val workDir: File by lazy { File(home, "work").apply { mkdirs() } }

    val defaultDownloadDir: File by lazy {
        File(System.getProperty("user.home") ?: ".", "Downloads" + File.separator + "DownloadHub")
            .apply { mkdirs() }
    }

    val toolsDir: File by lazy { File(home, "tools").apply { mkdirs() } }

    /** Staging area for torrents; libtorrent writes here before publication. */
    val torrentRoot: File by lazy { File(home, "torrents").apply { mkdirs() } }

    /**
     * Scratch space for things that have to be unpacked or staged.
     *
     * Deliberately under the app's own profile rather than `java.io.tmpdir`. TEMP is
     * not reliably a local writable folder: it is routinely pointed at a network
     * share or a second volume, and on a machine whose security software locks files
     * down, writing there fails with an error that names nothing the user recognises.
     * The profile folder is where the app already keeps everything else, so it is
     * known to work.
     */
    val cacheDir: File by lazy { File(home, "cache").apply { mkdirs() } }

    /** Where the libtorrent native library is unpacked to. */
    val nativeLibDir: File by lazy { File(cacheDir, "libtorrent4j-native").apply { mkdirs() } }

    /** Staging area for a downloaded update installer or portable zip. */
    val updateDir: File by lazy { File(cacheDir, "update").apply { mkdirs() } }

    /**
     * Where an unpacked portable build goes before it is started.
     *
     * Its own folder rather than over the running install: Windows will not let a
     * running .exe be replaced, and the launcher executing right now is the one that
     * would have to be overwritten. Beside it, the previous version is still there if
     * the new one will not start.
     */
    val updateNextDir: File by lazy { File(cacheDir, "update-next").apply { mkdirs() } }
}

object DesktopJson {
    val format = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
}

/** User-facing settings, persisted as JSON. */
@Serializable
data class DesktopSettings(
    val downloadDir: String = AppPaths.defaultDownloadDir.absolutePath,
    val maxConcurrent: Int = 3,
    val speedLimitBytesPerSecond: Long = 0L,
    val maxRetries: Int = 2,
    /** Shared secret the browser extension must present. Generated once. */
    val captureToken: String = "",
    val browserCaptureEnabled: Boolean = true,
    val closeToTray: Boolean = true,
    val startMinimised: Boolean = false
) {
    val speedLimitEnabled: Boolean get() = speedLimitBytesPerSecond > 0L

    fun downloadDirFile(): File = File(downloadDir)

    companion object {
        fun load(): DesktopSettings {
            val file = AppPaths.settingsFile
            val loaded = if (file.isFile) {
                runCatching { DesktopJson.format.decodeFromString<DesktopSettings>(file.readText()) }
                    .getOrDefault(DesktopSettings())
            } else {
                DesktopSettings()
            }
            // A token generated on first run means the extension can be paired
            // immediately, with nothing for the user to copy out of a file.
            if (loaded.captureToken.isBlank()) {
                return loaded.copy(captureToken = CaptureServer.newToken())
            }
            return loaded
        }

        fun save(settings: DesktopSettings) {
            runCatching {
                AppPaths.settingsFile.writeText(DesktopJson.format.encodeToString(settings))
            }
        }
    }
}

/** Persisted queue entry. */
@Serializable
data class QueuedDownload(
    val id: String,
    val url: String,
    val fileName: String,
    val source: DownloadSource = DownloadSource.HTTP,
    val category: DownloadCategory = DownloadCategory.OTHER,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSecond: Long = 0L,
    val errorMessage: String? = null,
    val location: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val mimeType: String? = null,
    val quality: String? = null,
    val audioFormat: String? = null,
    val playlist: Boolean = false,
    val torrentFilePath: String? = null,
    val torrentInfoHash: String? = null,
    val outputPath: String? = null,
    /** When the item was queued; drives Date Added and the default sort. */
    val createdAt: Long = System.currentTimeMillis(),
    // --- per-download settings; the rules live in core's TransferRules -------

    /** Queue order. Higher goes first. */
    val priorityRank: Int = 2,
    /** This item's own speed cap in bytes per second. Zero means use the global one. */
    val speedLimitBytesPerSecond: Long = 0L,
    /** Do not start before this time, in epoch milliseconds. */
    val startAfterEpochMillis: Long = 0L,
    /** Stop seeding once this much has been uploaded per byte downloaded. Zero is off. */
    val shareRatioLimit: Double = 0.0,
    /** Stop seeding this many minutes after finishing. Zero is off. */
    val seedTimeLimitMinutes: Int = 0,
    /** When it began seeding, so a time limit has something to count from. */
    val seedingSinceEpochMillis: Long = 0L,
    /** When sharing stopped, if it did. Zero means it has not. */
    val seedingStoppedAtEpochMillis: Long = 0L
)

/** JSON-backed queue, loaded once and written on change (debounced by the caller). */
class DesktopStore(initial: List<QueuedDownload> = emptyList()) {

    private val items = LinkedHashMap<String, QueuedDownload>()

    init {
        initial.forEach { items[it.id] = it }
    }

    @Synchronized
    fun snapshot(): List<QueuedDownload> = items.values.toList()

    @Synchronized
    fun get(id: String): QueuedDownload? = items[id]

    @Synchronized
    fun add(item: QueuedDownload) {
        items[item.id] = item
    }

    @Synchronized
    fun remove(id: String) {
        items.remove(id)
    }

    @Synchronized
    fun update(id: String, transform: (QueuedDownload) -> QueuedDownload) {
        items[id]?.let { items[id] = transform(it) }
    }

    @Synchronized
    fun clearFinished() {
        items.values
            .filter { it.status == DownloadStatus.COMPLETED }
            .forEach { items.remove(it.id) }
    }

    /**
     * Removes a download from the queue, optionally taking the file with it.
     *
     * [deleteFiles] is a question the user is asked rather than a default, because the
     * two answers are both reasonable and the wrong one is not undoable. The scratch
     * copy always goes either way: it is the app's own working file, and leaving it
     * behind would strand a partial download nobody asked for.
     */
    @Synchronized
    fun remove(id: String, deleteFiles: Boolean) {
        val item = items.remove(id) ?: return
        if (deleteFiles) {
            item.location?.let { path -> runCatching { File(path).deleteRecursively() } }
        }
        runCatching { AppPaths.workDir.resolve(id).deleteRecursively() }
    }

    /** Deletes the saved file and the scratch copy. */
    @Synchronized
    fun delete(id: String) = remove(id, deleteFiles = true)

    fun persist() {
        runCatching {
            AppPaths.queueFile.writeText(
                DesktopJson.format.encodeToString(snapshot())
            )
        }
    }

    companion object {
        fun load(): DesktopStore {
            val file = AppPaths.queueFile
            if (!file.isFile) return DesktopStore()
            val restored = runCatching {
                DesktopJson.format.decodeFromString<List<QueuedDownload>>(file.readText())
            }.getOrDefault(emptyList())
            // Nothing can be running yet after a restart, so anything mid-flight
            // comes back paused rather than claiming to transfer.
            return DesktopStore(
                restored.map {
                    if (it.status.isRunning()) it.copy(status = DownloadStatus.PAUSED) else it
                }
            )
        }

        private fun DownloadStatus.isRunning() =
            this == DownloadStatus.RUNNING || this == DownloadStatus.QUEUED ||
                this == DownloadStatus.RESOLVING
    }
}
