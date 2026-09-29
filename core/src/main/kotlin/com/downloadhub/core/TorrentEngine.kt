package com.downloadhub.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.swig.remove_flags_t

/**
 * A point-in-time reading of one torrent.
 *
 * libtorrent reports everything through alerts, but the app polls instead: a poll
 * is cheap, cannot be missed, and keeps the Android service and the desktop loop
 * driving the engine the same way.
 */
data class TorrentSnapshot(
    val infoHash: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val percent: Int,
    val downloadRate: Long,
    val uploadRate: Long,
    val peers: Int,
    val seeds: Int,
    val isPaused: Boolean,
    val isFinished: Boolean,
    val hasMetadata: Boolean,
    val name: String?,
    val error: String?,
    /**
     * Bytes uploaded since the torrent was added, ever.
     *
     * What a share ratio is measured against. [uploadRate] is bytes per second and
     * forgets everything the moment seeding pauses, which is no use for "stop at 2.0".
     */
    val uploadedBytes: Long = 0L,
    /** Finished: it has everything and is willing to upload to peers. */
    val isSeeding: Boolean = false,
    /**
     * When it was first seen finished, or zero when it has not finished.
     *
     * A share limit like "stop seeding after 30 minutes" needs somewhere for the clock
     * to start, and libtorrent has no timestamp for it, so the first poll that sees the
     * torrent complete sets it.
     */
    val seedingSinceEpochMillis: Long = 0L
)

/**
 * Torrent engine, shared by Android and Windows.
 *
 * libtorrent4j's JVM artifact is platform neutral, so the only thing that differed
 * per platform was where files live. [torrentRoot] supplies that, which is why this
 * class carries no Android reference and compiles into both builds.
 *
 * [nativeLibDir] is where the native library is unpacked to before it is loaded. It
 * is a parameter rather than a hard-coded `%TEMP%` for the same reason: TEMP is
 * routinely a network share or a locked-down volume, and extracting there fails in a
 * way that looks like a missing library rather than an unwritable folder.
 */
class TorrentEngine(
    private val torrentRoot: () -> File,
    private val nativeLibDir: (() -> File)? = null
) {

    private val sessionManager: SessionManager
    private val handles = ConcurrentHashMap<String, TorrentHandle>()
    private val lock = Any()

    /** When each torrent was first seen complete, keyed by info hash. */
    private val seedingStarted = HashMap<String, Long>()

    init {
        // Must happen before SessionManager is constructed, because that class's
        // initialiser performs the native load that fails on desktop without this.
        nativeLibDir?.let { LibtorrentNative.useDirectory(it()) }
        LibtorrentNative.ensureReady()
        sessionManager = SessionManager()
    }

    fun start(item: DownloadItem): TorrentSnapshot? {
        val hash = ensureSession(item)
        val handle = findHandle(hash) ?: return null
        // Applied after the handle exists, not before: libtorrent needs the piece layout
        // to know which pieces a file covers, and only has that once the torrent is in
        // the session.
        applyFileSelection(item, handle)
        handles[item.id] = handle
        return snapshot(item, handle)
    }

    /**
     * Applies the dialog's file selection to a started torrent.
     *
     * A deselected file is set to priority IGNORE, which is how libtorrent is told to
     * leave its pieces alone. This is the empty path for every magnet and every torrent
     * added without the dialog.
     */
    private fun applyFileSelection(item: DownloadItem, handle: TorrentHandle) {
        val selection = item.torrentSelectedFiles
        if (selection.isEmpty()) return
        val info = runCatching { handle.torrentFile() }.getOrNull() ?: return
        val fileCount = runCatching { info.numFiles() }.getOrDefault(0)
        if (fileCount <= 0) return
        repeat(fileCount) { index ->
            val wanted = selection.contains(index)
            runCatching {
                handle.filePriority(
                    index,
                    if (wanted) Priority.DEFAULT else Priority.IGNORE
                )
            }
        }
        if (item.torrentSequential) {
            runCatching { handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) }
        }
        if (item.torrentFirstLastPiecesFirst) {
            // The first and last pieces are what a player needs before it can start, so
            // asking for them turns a video from "nothing plays for an hour" into
            // "playable almost immediately".
            runCatching {
                val pieceCount = runCatching { info.numPieces() }.getOrDefault(0)
                if (pieceCount > 0) {
                    val priorities = Priority.array(Priority.DEFAULT, pieceCount)
                    priorities[0] = Priority.TOP_PRIORITY
                    priorities[pieceCount - 1] = Priority.TOP_PRIORITY
                    handle.prioritizePieces(priorities)
                }
            }
        }
    }

    fun poll(item: DownloadItem): TorrentSnapshot? {
        val hash = item.torrentInfoHash ?: runCatching {
            AddTorrentParams.parseMagnetUri(item.url).infoHashes.getBest().toHex()
        }.getOrNull() ?: return handles[item.id]?.let { snapshot(item, it) }
        val handle = sessionManager.find(Sha1Hash.parseHex(hash))
            ?: return handles[item.id]?.let { snapshot(item, it) }
        handles[item.id] = handle
        return snapshot(item, handle)
    }

    fun pause(item: DownloadItem) {
        handles[item.id]?.pause()
    }

    fun resume(item: DownloadItem) {
        val handle = handles[item.id] ?: run {
            start(item)
            handles[item.id]
        }
        handle?.resume()
    }

    fun remove(item: DownloadItem, deleteFiles: Boolean) {
        val handle = handles[item.id] ?: item.torrentInfoHash?.let {
            runCatching { sessionManager.find(Sha1Hash.parseHex(it)) }.getOrNull()
        }
        if (handle != null) {
            sessionManager.remove(
                handle,
                if (deleteFiles) SessionHandle.DELETE_FILES else remove_flags_t()
            )
        }
        handles.remove(item.id)
    }

    fun shutdown() {
        synchronized(lock) {
            if (sessionManager.isRunning) sessionManager.stop()
            handles.clear()
        }
    }

    private fun ensureSession(item: DownloadItem): String {
        synchronized(lock) {
            if (!sessionManager.isRunning) {
                val params = SessionParams()
                params.setPosixDiskIO()
                sessionManager.start(params)
            }
        }

        val torrentFile = item.torrentFilePath?.let(::File)?.takeIf { it.exists() }
        // The folder the user chose in the dialog sits under the save directory. Without
        // it the files land loose beside everything else already downloaded, which is
        // what made multi-file torrents hard to find afterwards.
        val base = item.outputPath?.let(::File) ?: defaultSaveDirectory(item)
        val saveDirectory = if (item.torrentContentFolder.isNotBlank()) {
            File(base, item.torrentContentFolder)
        } else {
            base
        }
        saveDirectory.mkdirs()
        val flags = TorrentFlags.SEQUENTIAL_DOWNLOAD
            .or_(TorrentFlags.UPDATE_SUBSCRIBE)
            .or_(TorrentFlags.NEED_SAVE_RESUME)

        return if (torrentFile != null) {
            val info = TorrentInfo(torrentFile)
            val hash = info.infoHash().toHex()
            sessionManager.download(info, saveDirectory, null, null, null, flags)
            hash
        } else {
            val params = AddTorrentParams.parseMagnetUri(item.url)
            val hash = params.infoHashes.getBest().toHex()
            sessionManager.download(item.url, saveDirectory, flags)
            hash
        }
    }

    private fun defaultSaveDirectory(item: DownloadItem): File =
        File(torrentRoot(), sanitise(item.fileName))

    private fun findHandle(hash: String): TorrentHandle? {
        val parsed = runCatching { Sha1Hash.parseHex(hash) }.getOrNull() ?: return null
        // libtorrent resolves a magnet asynchronously, so give the handle a moment
        // to appear rather than reporting "unknown torrent" on the first poll.
        repeat(20) {
            sessionManager.find(parsed)?.let { return it }
            Thread.sleep(100)
        }
        return sessionManager.find(parsed)
    }

    private fun snapshot(item: DownloadItem, handle: TorrentHandle): TorrentSnapshot? {
        if (!handle.isValid) return null
        val status = handle.status()
        val infoHash = runCatching { handle.infoHash().toHex() }
            .getOrDefault(item.torrentInfoHash.orEmpty())
        val total = status.total()
        val done = status.totalDone()
        val metadataName = runCatching { handle.torrentFile()?.name() }.getOrNull()
        val error = status.errorCode().takeIf { it.isError }?.message
        val finished = status.isFinished
        return TorrentSnapshot(
            infoHash = infoHash,
            bytesDownloaded = done,
            totalBytes = total,
            percent = if (total > 0) {
                (done * 100 / total).coerceIn(0, 100).toInt()
            } else {
                (status.progress() * 100).toInt()
            },
            downloadRate = status.downloadPayloadRate().toLong().coerceAtLeast(0),
            uploadRate = status.uploadPayloadRate().toLong().coerceAtLeast(0),
            peers = status.numSeeds(),
            seeds = status.listSeeds(),
            isPaused = false,
            isFinished = finished,
            hasMetadata = status.hasMetadata(),
            name = metadataName,
            error = error,
            // Cumulative, unlike uploadRate. A share ratio is measured against
            // everything uploaded over the whole life of the torrent, and a rate cannot
            // answer that: it forgets everything the moment seeding pauses.
            uploadedBytes = status.allTimeUpload().coerceAtLeast(0),
            isSeeding = finished,
            // When it started seeding, recorded the first time it was seen finished. A
            // share-limit clock needs a start, and libtorrent exposes no timestamp for
            // one - so it is kept here rather than guessed at on the UI side.
            seedingSinceEpochMillis = seedingSince(infoHash, finished)
        )
    }

    private fun sanitise(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "torrent" }

    /**
     * When a torrent started seeding, recorded once and then kept.
     *
     * Kept per info hash rather than per item id so the clock survives the app being
     * closed and reopened: a torrent that finished at 3am and is re-checked at 9am has
     * been seeding for six hours, not zero.
     */
    private fun seedingSince(infoHash: String, finished: Boolean): Long = synchronized(lock) {
        if (!finished || infoHash.isBlank()) {
            seedingStarted.remove(infoHash)
            0L
        } else {
            seedingStarted.getOrPut(infoHash) { System.currentTimeMillis() }
        }
    }
}
