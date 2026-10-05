package com.downloadhub.core

import java.io.File
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.TorrentInfo

/**
 * Reads what a magnet is about, before anything is queued.
 *
 * A magnet does not carry a file list. That is not the same as there being none: the list
 * lives in the torrent's metadata, and libtorrent fetches it from the swarm in a few
 * seconds' worth of a few kilobytes. Everything else about the metadata is already in the
 * magnet, so what arrives is exactly the file list, the sizes, the piece size and the
 * name.
 *
 * This is what makes the pre-download dialog useful for a magnet. Without it the dialog
 * says "a magnet has no file list until it connects" and offers no choice at all - which
 * is true and also means a torrent is the one kind of download where you cannot see what
 * you are about to get, or take three files out of forty, or say no.
 *
 * Its own session rather than the download session's, so a lookup never appears in the
 * list or counts towards the connection budget.
 */
object TorrentMetadataReader {

    /**
     * How long to wait before giving up.
     *
     * A minute: a magnet with no trackers is found through DHT alone, and on a quiet swarm
     * the first peer can take most of that. The dialog stays usable meanwhile.
     */
    const val DEFAULT_TIMEOUT_MILLIS = 60_000L

    /**
     * One lookup session for the life of the app, rather than one per magnet.
     *
     * A new session starts with an empty DHT routing table, and filling it takes 10-30
     * seconds - which is why lookups used to time out at 20 seconds without ever reaching
     * a peer. Kept running, the table stays warm and later lookups start straight away.
     * It never downloads: [SessionManager.fetchMagnet] fetches only the metadata and
     * removes the torrent again.
     */
    private var shared: SessionManager? = null

    @Synchronized
    private fun session(): SessionManager {
        shared?.takeIf { it.isRunning }?.let { return it }
        LibtorrentNative.ensureReady()
        val manager = SessionManager()
        val params = SessionParams()
        params.setPosixDiskIO()
        manager.start(params)
        shared = manager
        return manager
    }

    /** Stops the lookup session, so its threads do not keep the process alive on exit. */
    @Synchronized
    fun shutdown() {
        shared?.let { runCatching { it.stop() } }
        shared = null
    }

    /** Starts the lookup session early, so the first magnet does not wait on DHT bootstrap. */
    fun warmUp() {
        Thread({ runCatching { session() } }, "magnet-lookup-warmup").apply { isDaemon = true }.start()
    }

    /**
     * The file list behind a magnet, or a failure saying why not.
     *
     * Public trackers are added to a magnet that names none (search results never do), so
     * peers are found through trackers as well as DHT.
     */
    fun read(magnet: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): Result<TorrentMetainfo> {
        val link = magnet.trim()
        if (!link.startsWith("magnet:", ignoreCase = true)) {
            return Result.failure(
                IllegalArgumentException("That is not a magnet link, so it needs no lookup")
            )
        }
        // Loaded first: parsing the magnet is itself a native call, and fails without it.
        runCatching { LibtorrentNative.ensureReady() }
        runCatching { AddTorrentParams.parseMagnetUri(link).infoHashes.getBest() }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("The magnet link has no info hash"))

        val scratch = File(System.getProperty("java.io.tmpdir") ?: ".", "dlm-metadata").apply { mkdirs() }
        return runCatching {
            val seconds = (timeoutMillis / 1000).toInt().coerceAtLeast(1)
            val bytes = session().fetchMagnet(withPublicTrackers(link), seconds, scratch)
                ?: error("No peers sent the file list in time")
            metainfoFrom(TorrentInfo(bytes))
        }
    }

    /**
     * Turns libtorrent's reading of a torrent into ours.
     *
     * The same shape [TorrentParser] produces from a file, so the pre-download dialog's
     * file list, the folder name and the totals all work for a magnet without knowing
     * where the metadata came from.
     *
     * Comment, date and creator are blank: libtorrent does not carry them on a
     * `TorrentInfo`, and inventing them would be worse than an honest blank. The info
     * hashes are the ones the magnet is identified by, so they are exact.
     */
    fun metainfoFrom(info: TorrentInfo): TorrentMetainfo {
        val storage = runCatching { info.files() }.getOrNull()
        val count = runCatching { info.numFiles() }.getOrDefault(0)
        val files = (0 until count).mapNotNull { position ->
            val length = runCatching { storage?.fileSize(position) }.getOrNull() ?: return@mapNotNull null
            // `filePath` gives the whole path within the torrent with forward slashes,
            // which is the form the tree builder and the filter both expect. A torrent
            // with a single file has a one-segment path, and so is a top-level row.
            val path = runCatching { storage?.filePath(position) }.getOrNull()
                ?.replace('\\', '/')
                ?.trim('/')
                ?.takeIf { it.isNotBlank() }
                ?: runCatching { storage?.fileName(position) }.getOrNull() ?: return@mapNotNull null
            TorrentFile(
                index = position,
                path = path,
                size = length,
                pieceLength = runCatching { info.pieceLength().toLong() }.getOrDefault(0L),
                pieceCount = runCatching { info.numPieces() }.getOrDefault(0)
            )
        }
        val name = runCatching { info.name() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: files.firstOrNull()?.name
            ?: "Unnamed torrent"
        return TorrentMetainfo(
            name = name,
            files = files,
            comment = "",
            createdAtEpochMillis = 0L,
            createdBy = "",
            infoHashV1 = runCatching { info.infoHash().toString() }.getOrDefault(""),
            infoHashV2 = runCatching { info.infoHashes().getBest().toString() }.getOrDefault(""),
            isSingleFile = files.size <= 1
        )
    }

    /** Reads a torrent already on disk, through the same path a magnet takes. */
    fun readFile(file: File): Result<TorrentMetainfo> = runCatching {
        metainfoFrom(TorrentInfo(file.readBytes()))
    }
}
