package com.downloadhub.desktop

import com.downloadhub.core.DownloadCategory
import com.downloadhub.core.DownloadSource
import com.downloadhub.core.DownloadStatus
import com.downloadhub.core.QueueRules
import com.downloadhub.core.QueueSchedule
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
    /**
     * Where in-flight and temporary files go.
     *
     * Settable, because a cache on the system drive is a real complaint: a user with a
     * small SSD wants partials on a spinning disk, and one with a slow system drive wants
     * them somewhere faster. A getter rather than a `by lazy`, because the folder is read
     * from settings that can change while the app runs.
     */
    @Volatile
    var cacheDirectory: File = File(home, "cache").apply { mkdirs() }

    /**
     * The scratch directory, one level under the chosen cache.
     *
     * Kept separate from the cache root so a user who points the cache at a shared drive
     * gets a folder of their own rather than writing partials loose beside other
     * applications' files.
     */
    val workDir: File
        get() = File(cacheDirectory, "work").apply { if (!isDirectory) mkdirs() }

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
    val cacheDir: File get() = cacheDirectory

    /** Where the libtorrent native library is unpacked to. */
    /**
     * Pinned to the app's own profile rather than the chosen cache: it is unpacked before
     * any settings are read, and a library that had to be re-extracted every time the
     * cache folder changed would be a slow way to learn a wrong path was typed.
     */
    val nativeLibDir: File by lazy { File(home, "cache/libtorrent4j-native").apply { mkdirs() } }

    /** Staging area for a downloaded update installer or portable zip. */
    val updateDir: File by lazy { File(home, "cache/update").apply { mkdirs() } }

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

/**
 * One named queue, as saved. [maxConcurrent] of 0 means only the app-wide limit applies.
 * [started] is the queue's Start/Stop button; the schedule presses it at its times.
 */
@Serializable
data class QueueConfig(
    val id: String,
    val name: String,
    val maxConcurrent: Int = 0,
    val started: Boolean = true,
    val scheduleEnabled: Boolean = false,
    val days: List<Int> = (1..7).toList(),
    val startMinute: Int = 0,
    val stopMinute: Int = QueueSchedule.NO_STOP
) {
    val schedule: QueueSchedule
        get() = QueueSchedule(scheduleEnabled, days.toSet(), startMinute, stopMinute)

    companion object {
        fun main() = QueueConfig(id = QueueRules.MAIN, name = "Main")
    }
}

/** One of the user's own categories, as saved. See [com.downloadhub.core.CategoryRule]. */
@Serializable
data class CategoryRuleConfig(
    val name: String,
    val extensions: List<String> = emptyList(),
    /** Absolute, or relative to the download folder; blank uses the name. */
    val folder: String = ""
) {
    fun toRule() = com.downloadhub.core.CategoryRule(name, extensions, folder)
}

/** One RSS feed, by its URL; [name] is what the panel shows. */
@Serializable
data class RssFeedConfig(val url: String, val name: String = "")

/** One auto-download rule, as saved. See [com.downloadhub.core.RssRule]. */
@Serializable
data class RssRuleConfig(
    val name: String,
    val mustContain: String,
    val mustNotContain: String = "",
    val useRegex: Boolean = false,
    val enabled: Boolean = true
) {
    fun toRule() = com.downloadhub.core.RssRule(name, mustContain, mustNotContain, useRegex, enabled)
}

/** User-facing settings, persisted as JSON. */
@Serializable
data class DesktopSettings(
    val downloadDir: String = AppPaths.defaultDownloadDir.absolutePath,
    val maxConcurrent: Int = 3,
    /** Parallel connections one HTTP file is split over, when its server takes ranges. */
    val connectionsPerDownload: Int = 4,
    val speedLimitBytesPerSecond: Long = 0L,
    val maxRetries: Int = 2,
    /** Shared secret the browser extension must present. Generated once. */
    val captureToken: String = "",
    val browserCaptureEnabled: Boolean = true,
    val closeToTray: Boolean = true,
    val startMinimised: Boolean = false,
    /**
     * Where in-flight and temporary files go. Blank means the app's own profile folder.
     *
     * A download is written here first and moved to [downloadDir] only when it is whole,
     * so this is the folder that fills up while a large download runs - which is why a
     * user with a small system drive needs to be able to move it.
     */
    val cacheDir: String = "",
    /**
     * Whether removing an unfinished download also deletes what it had fetched.
     *
     * True by default, because the usual reason for removing something is that it is
     * unwanted, and a half-downloaded file nobody wanted is not something anyone wants to
     * come back to. It is a setting rather than a rule because the other case is real: a
     * download abandoned to free up a slot and picked up again later.
     */
    val deleteCacheWhenRemoved: Boolean = true,
    /**
     * Whether the first-run setup has been seen.
     *
     * False by default, so an existing install upgrading into a version with the setup
     * sees it once rather than never - the lesser of the two mistakes. Defaulting to true
     * would mean every current user silently skipped it and the flag would stay true, so
     * a genuinely new install could never be told apart.
     */
    val setupComplete: Boolean = false,
    /**
     * Which of the nine themes, stored by name.
     *
     * Defaults to MINT, which is the green the desktop app has always been: a user
     * upgrading sees no change, and the choice is the same one the Android app offers so
     * that picking Ocean on a phone gives Ocean on the desktop.
     */
    val themePalette: String = "AURORA",
    /**
     * Light, dark, or AMOLED.
     *
     * Dark by default, again to match what was there. AMOLED is a mode rather than a
     * palette: it makes the background and surfaces true black and leaves the accent
     * alone, so it works with all nine colours rather than replacing the choice.
     */
    val themeMode: String = "DARK",
    /** Named queues. Main is always first and cannot be removed; see [queuesOrDefault]. */
    val queues: List<QueueConfig> = listOf(QueueConfig.main()),
    /** The proxy for HTTP downloads, by [ProxyType] name. Follows Windows by default. */
    val proxyType: String = "SYSTEM",
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    /** The user's own categories: by extension, to a folder. Checked before the built-in type folders. */
    val categoryRules: List<CategoryRuleConfig> = emptyList(),
    // --- qBittorrent's Speed page --------------------------------------------------
    /** App-wide upload cap for torrents, bytes per second; 0 is unlimited. */
    val uploadLimitBytesPerSecond: Long = 0L,
    /** The "turtle" limits, used instead of the normal ones while alternative speed is on. */
    val altDownloadLimitBytesPerSecond: Long = 512L * 1024,
    val altUploadLimitBytesPerSecond: Long = 128L * 1024,
    /** The turtle switch, pressed by hand. */
    val altSpeedEnabled: Boolean = false,
    /** Turns alternative speed on by itself inside this window. */
    val altScheduleEnabled: Boolean = false,
    val altScheduleDays: List<Int> = (1..7).toList(),
    val altScheduleStartMinute: Int = 8 * 60,
    val altScheduleStopMinute: Int = 20 * 60,
    // --- qBittorrent's Connection and BitTorrent pages --------------------------------
    /** 0 lets libtorrent pick. */
    val torrentListenPort: Int = 0,
    val torrentDht: Boolean = true,
    val torrentLocalPeerDiscovery: Boolean = true,
    val torrentPortForwarding: Boolean = true,
    /** A [com.downloadhub.core.TorrentEncryption] name. */
    val torrentEncryption: String = "ALLOWED",
    /** 0 leaves libtorrent's default. */
    val torrentMaxConnections: Int = 0,
    val torrentAnonymousMode: Boolean = false,
    /** qBittorrent's IP filter: an eMule .dat or PeerGuardian .p2p blocklist. */
    val ipFilterEnabled: Boolean = false,
    val ipFilterPath: String = "",
    /** qBittorrent's watched folder: .torrent files dropped here are added by themselves. */
    val watchFolderEnabled: Boolean = false,
    val watchFolder: String = "",
    // --- RSS -------------------------------------------------------------------
    val rssFeeds: List<RssFeedConfig> = emptyList(),
    val rssRules: List<RssRuleConfig> = emptyList(),
    /** How often feeds are re-read. */
    val rssRefreshMinutes: Int = 30
) {
    /** The queues, with Main put back if a hand-edited file lost it. */
    val queuesOrDefault: List<QueueConfig>
        get() = if (queues.any { it.id == QueueRules.MAIN }) queues else listOf(QueueConfig.main()) + queues

    /** Whether the turtle limits apply right now: switched on by hand, or inside the schedule. */
    fun altSpeedActive(now: java.time.LocalDateTime = java.time.LocalDateTime.now()): Boolean =
        altSpeedEnabled || com.downloadhub.core.QueueRules.isWithin(
            com.downloadhub.core.QueueSchedule(altScheduleEnabled, altScheduleDays.toSet(), altScheduleStartMinute, altScheduleStopMinute),
            now
        )

    fun effectiveDownloadLimit(): Long = if (altSpeedActive()) altDownloadLimitBytesPerSecond else speedLimitBytesPerSecond
    fun effectiveUploadLimit(): Long = if (altSpeedActive()) altUploadLimitBytesPerSecond else uploadLimitBytesPerSecond

    fun torrentSessionSettings(): com.downloadhub.core.TorrentSessionSettings = com.downloadhub.core.TorrentSessionSettings(
        downloadLimitBytesPerSecond = effectiveDownloadLimit(),
        uploadLimitBytesPerSecond = effectiveUploadLimit(),
        listenPort = torrentListenPort,
        dht = torrentDht,
        localPeerDiscovery = torrentLocalPeerDiscovery,
        portForwarding = torrentPortForwarding,
        encryption = com.downloadhub.core.TorrentEncryption.entries.firstOrNull { it.name == torrentEncryption }
            ?: com.downloadhub.core.TorrentEncryption.ALLOWED,
        maxConnections = torrentMaxConnections,
        anonymousMode = torrentAnonymousMode
    )

    fun proxySetting(): com.downloadhub.core.ProxySetting = com.downloadhub.core.ProxySetting(
        type = com.downloadhub.core.ProxyType.entries.firstOrNull { it.name == proxyType } ?: com.downloadhub.core.ProxyType.SYSTEM,
        host = proxyHost,
        port = proxyPort
    )

    fun queue(id: String): QueueConfig =
        queuesOrDefault.firstOrNull { it.id == id } ?: queuesOrDefault.first { it.id == QueueRules.MAIN }

    /**
     * Where temporary files go, with the default applied.
     *
     * Blank means "the app's own folder", never the process's working directory - which
     * on Windows is wherever the app was started from, so a user who ran it from a
     * downloads folder would silently fill that up with partials.
     */
    fun cacheDirFile(): File =
        cacheDir.takeIf { it.isNotBlank() }?.let(::File) ?: AppPaths.cacheDirectory
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
    /**
     * The streams the chooser named, as yt-dlp format ids.
     *
     * A height cannot express what the chooser offers - 1080p60 and 1080p are the same
     * height and not the same download - so the row carries the ids themselves. Without
     * them a row restored from disk would fall back to the old bestvideo[height<=N]
     * selector and quietly download something other than what was chosen.
     */
    val streamFormatId: String? = null,
    /** The audio half of the pair; empty for an audio-only row, which has just the one. */
    val streamAudioFormatId: String? = null,
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
    val seedingStoppedAtEpochMillis: Long = 0L,
    // --- choices made in the pre-download dialog -------------------------------

    /** Which files inside this torrent to fetch. Empty means all of them. */
    val torrentSelectedFiles: List<Int> = emptyList(),
    /** Fetch files in order. */
    val torrentSequential: Boolean = false,
    /** Fetch the first and last pieces first, so video can start playing sooner. */
    val torrentFirstLastPiecesFirst: Boolean = false,
    /** Folder under the save directory that the torrent's files go in. */
    val torrentContentFolder: String = "",
    // --- torrent readings, mirrored from the engine for the detail pane ------------

    /** Bytes per second being uploaded. */
    val uploadRate: Long = 0L,
    /** Peers that have the whole file. */
    val seeds: Int = 0,
    /** Peers that have some of it. */
    val peerCount: Int = 0,
    /** Bytes uploaded over the torrent's whole life. */
    val uploadedBytes: Long = 0L,
    /** When it finished downloading. */
    val completedAt: Long = 0L,

    /**
     * Bytes downloaded per file, keyed by file index.
     *
     * Transient, and deliberately so. It is a reading rather than a setting: it changes
     * every second and is worth nothing after a restart, whereas the queue file is read
     * once at launch and written on every change. Persisting it would put a few hundred
     * numbers per torrent into that file, rewritten continuously, for figures that are
     * all zero again by the time it is next read.
     */
    @Transient
    var torrentFileProgress: Map<Int, Long> = emptyMap(),

    /**
     * Per-file priority, keyed by file index, holding a [com.downloadhub.core.FilePriority]
     * ordinal. Persisted, because it is a choice and a choice survives a restart.
     */
    val torrentFilePriorities: Map<Int, Int> = emptyMap(),
    /** Which queue starts it. Missing in older queue files, which puts them in Main. */
    val queueId: String = QueueRules.MAIN,
    // --- what each request for this file carries (HTTP only) ---
    val requestHeaders: Map<String, String> = emptyMap(),
    val cookies: String = "",
    val username: String = "",
    /** Kept in queue.json in plain text, as AB Download Manager does; the file is in the user profile. */
    val password: String = ""
) {

    /**
     * The scratch name this item's bytes go under.
     *
     * Two kinds of transfer, two shapes: an HTTP download is a single `part-<id>` file,
     * while a torrent's pieces are spread across a folder of its own. Removing an item
     * has to know which, or it deletes a folder that was never there and leaves the real
     * one behind.
     */
    val cacheKey: String
        get() = if (source == DownloadSource.TORRENT) "torrent-$id" else id

    /**
     * Every scratch entry this item can have. An HTTP download's partial is `part-<id>`
     * (see DesktopWorkArea), plus its segment state when split - [cacheKey] alone named
     * neither, so removing an unfinished download left its partial behind.
     */
    val cacheKeys: List<String>
        get() = when (source) {
            DownloadSource.TORRENT -> listOf(cacheKey)
            DownloadSource.YOUTUBE -> listOf("yt-$id")
            DownloadSource.HTTP -> listOf("part-$id", "part-$id.segments", "part-$id.segments.tmp")
        }
}

/** JSON-backed queue, loaded once and written on change (debounced by the caller). */
class DesktopStore(initial: List<QueuedDownload> = emptyList()) {

    private val items = LinkedHashMap<String, QueuedDownload>()

    init {
        initial.forEach { items[it.id] = it }
    }

    /**
     * Every read of the queue takes the lock.
     *
     * This one did not, and it is the one that matters: it is the only way the screen
     * reads the queue, so it runs on the UI thread while the torrent poll is calling
     * [update] several times a second from its own thread. [items] is a LinkedHashMap
     * and [items.values].toList() walks it from beginning to end, so a single put
     * landing mid-walk threw ConcurrentModificationException out of [refresh] - on the
     * thread building the state, before it could be assigned, leaving the list showing
     * whatever the last build that survived happened to contain. That is a list that
     * comes and goes on its own.
     */
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
    fun remove(id: String, deleteFiles: Boolean, deleteCache: Boolean = true) {
        val item = items.remove(id) ?: return
        if (deleteFiles) {
            item.location?.let { path -> runCatching { File(path).deleteRecursively() } }
        }
        // The scratch copy follows the same choice as the finished file, and for an
        // unfinished download it *is* the file - there is nothing else on disk. Leaving it
        // behind is how a cache quietly fills up with partials nobody can account for.
        if (deleteCache) {
            item.cacheKeys.forEach { key -> runCatching { AppPaths.workDir.resolve(key).deleteRecursively() } }
        }
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
