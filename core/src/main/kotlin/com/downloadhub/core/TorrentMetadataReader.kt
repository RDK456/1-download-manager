package com.downloadhub.core

import java.io.File
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.TorrentFlags
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
 * A throwaway session rather than the download session's. The torrent must not appear in
 * the list, must not count towards the connection budget, and must be gone the moment the
 * metadata has been read; giving it its own session means all three follow from stopping
 * it, with no removal call whose overload might not be the one meant.
 */
object TorrentMetadataReader {

    /**
     * How long to wait before giving up.
     *
     * Long enough for a normal swarm and short enough that a magnet with nobody on it
     * does not leave the user watching a dialog. The fallback is a dialog with no file
     * list, which is what it always was.
     */
    const val DEFAULT_TIMEOUT_MILLIS = 20_000L

    /**
     * The file list behind a magnet, or null.
     *
     * Null means "could not be read", which is three different things that all look the
     * same from here and are worth telling apart in the message rather than in the type:
     * nothing is seeding it, the swarm is too slow, or it is not a magnet at all.
     */
    fun read(magnet: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): Result<TorrentMetainfo> {
        val link = magnet.trim()
        if (!link.startsWith("magnet:", ignoreCase = true)) {
            return Result.failure(
                IllegalArgumentException("That is not a magnet link, so it needs no lookup")
            )
        }
        val hash = runCatching { AddTorrentParams.parseMagnetUri(link).infoHashes.getBest() }
            .getOrNull()
            ?: return Result.failure(IllegalArgumentException("The magnet link has no info hash"))

        val manager = SessionManager()
        // Somewhere harmless to point a paused torrent at. Nothing is written: the
        // torrent never starts, so this is only here because the call insists on a path.
        val scratch = File(System.getProperty("java.io.tmpdir") ?: ".", "dlm-metadata").apply {
            mkdirs()
        }
        try {
            val params = SessionParams()
            params.setPosixDiskIO()
            manager.start(params)

            // Paused, and not auto-managed: this is a lookup, not the beginning of a
            // download. Without PAUSED the swarm's first few pieces start arriving, which
            // is data nobody asked for and which then has to be deleted.
            manager.download(link, scratch, TorrentFlags.PAUSED)

            val handle = awaitHandle(manager, hash, timeoutMillis)
                ?: return Result.failure(
                    IllegalStateException("No peers answered, so the file list is not available yet")
                )
            val info = awaitMetadata(handle, timeoutMillis)
                ?: return Result.failure(
                    IllegalStateException("The swarm did not send the file list in time")
                )
            return Result.success(metainfoFrom(info))
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            // Stopping the session is the cleanup. There is no torrent to remove and no
            // data to delete, because there was never a download.
            runCatching { manager.stop() }
            runCatching { scratch.deleteRecursively() }
        }
    }

    private fun awaitHandle(
        manager: SessionManager,
        hash: org.libtorrent4j.Sha1Hash,
        timeoutMillis: Long
    ): org.libtorrent4j.TorrentHandle? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val handle = runCatching { manager.find(hash) }.getOrNull()
            if (handle != null && handle.isValid) return handle
            Thread.sleep(POLL_MILLIS)
        }
        return null
    }

    private fun awaitMetadata(
        handle: org.libtorrent4j.TorrentHandle,
        timeoutMillis: Long
    ): TorrentInfo? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { handle.status().hasMetadata() }.getOrDefault(false)) {
                val info = runCatching { handle.torrentFile() }.getOrNull()
                if (info != null && info.isValid) return info
            }
            Thread.sleep(POLL_MILLIS)
        }
        return null
    }

    private const val POLL_MILLIS = 250L

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
