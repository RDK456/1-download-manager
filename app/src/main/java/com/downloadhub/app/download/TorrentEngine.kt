package com.downloadhub.app.download

import android.content.Context
import com.downloadhub.app.data.local.DownloadEntity
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus

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
    val error: String?
)

class TorrentEngine(context: Context) {
    private val appContext = context.applicationContext
    private val sessionManager = SessionManager()
    private val handles = ConcurrentHashMap<String, TorrentHandle>()
    private val lock = Any()

    fun start(item: DownloadEntity): TorrentSnapshot? {
        val hash = ensureSession(item)
        val handle = findHandle(item, hash) ?: return null
        handles[item.id] = handle
        return snapshot(item, handle)
    }

    fun poll(item: DownloadEntity): TorrentSnapshot? {
        val hash = item.torrentInfoHash ?: runCatching {
            val params = AddTorrentParams.parseMagnetUri(item.url)
            params.infoHashes.getBest().toHex()
        }.getOrNull() ?: return handles[item.id]?.let { snapshot(item, it) }
        val handle = sessionManager.find(Sha1Hash.parseHex(hash)) ?: return handles[item.id]?.let { snapshot(item, it) }
        handles[item.id] = handle
        return snapshot(item, handle)
    }

    fun pause(item: DownloadEntity) {
        handles[item.id]?.pause()
    }

    fun resume(item: DownloadEntity) {
        val handle = handles[item.id] ?: run {
            start(item)
            handles[item.id]
        }
        handle?.resume()
    }

    fun remove(item: DownloadEntity, deleteFiles: Boolean) {
        val handle = handles[item.id] ?: item.torrentInfoHash?.let {
            runCatching { sessionManager.find(Sha1Hash.parseHex(it)) }.getOrNull()
        }
        if (handle != null) {
            sessionManager.remove(
                handle,
                if (deleteFiles) SessionHandle.DELETE_FILES else org.libtorrent4j.swig.remove_flags_t()
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

    private fun ensureSession(item: DownloadEntity): String {
        synchronized(lock) {
            if (!sessionManager.isRunning) {
                val params = SessionParams()
                params.setPosixDiskIO()
                sessionManager.start(params)
            }
        }

        val torrentFile = item.torrentFilePath?.let(::File)?.takeIf { it.exists() }
        val saveDirectory = File(item.outputPath ?: DownloadStorage(appContext).torrentDirectory(item.fileName).path)
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

    private fun findHandle(item: DownloadEntity, hash: String): TorrentHandle? {
        val parsed = runCatching { Sha1Hash.parseHex(hash) }.getOrNull() ?: return null
        repeat(20) {
            sessionManager.find(parsed)?.let { return it }
            Thread.sleep(100)
        }
        return sessionManager.find(parsed)
    }

    private fun snapshot(item: DownloadEntity, handle: TorrentHandle): TorrentSnapshot? {
        if (!handle.isValid) return null
        val status = handle.status()
        val infoHash = runCatching { handle.infoHash().toHex() }.getOrDefault(item.torrentInfoHash.orEmpty())
        val total = status.total()
        val done = status.totalDone()
        val metadataName = runCatching { handle.torrentFile()?.name() }.getOrNull()
        val error = status.errorCode().takeIf { it.isError }?.message
        return TorrentSnapshot(
            infoHash = infoHash,
            bytesDownloaded = done,
            totalBytes = total,
            percent = if (total > 0) (done * 100 / total).coerceIn(0, 100).toInt() else status.progress().let { (it * 100).toInt() },
            downloadRate = status.downloadPayloadRate().toLong().coerceAtLeast(0),
            uploadRate = status.uploadPayloadRate().toLong().coerceAtLeast(0),
            peers = status.numSeeds(),
            seeds = status.listSeeds(),
            isPaused = false,
            isFinished = status.isFinished,
            hasMetadata = status.hasMetadata(),
            name = metadataName,
            error = error
        )
    }
}
