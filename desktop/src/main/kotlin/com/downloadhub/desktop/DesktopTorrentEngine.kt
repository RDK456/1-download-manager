package com.downloadhub.desktop

import com.downloadhub.core.DownloadItem
import com.downloadhub.core.DownloadSource
import com.downloadhub.core.DownloadStatus
import com.downloadhub.core.TorrentEngine
import com.downloadhub.core.TorrentParser
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
    // Torrent bytes go into the chosen cache folder, not straight into the download
    // folder. A torrent is written in place and reassembled from many peers, so it cannot
    // be fetched to a scratch file and moved at the end the way an HTTP download is - and
    // putting it in the download folder would scatter half-written files through the
    // user's finished ones.
    private val engine = TorrentEngine(
        torrentRoot = { area.torrentWorkDir("staging") },
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
        val torrents = store.snapshot().filter { it.source == DownloadSource.TORRENT }
        // Only what is actually moving counts against the limit. Counting every
        // torrent ever queued does, so after three downloads had finished no new
        // one could ever start - the limit was spent on history.
        val active = torrents.count { it.isTransferring() || running.containsKey(it.id) }
        if (active >= limit) return

        val next = torrents.firstOrNull { it.status == DownloadStatus.QUEUED } ?: return
        // Started, not awaited.
        //
        // transfer() polls for the whole life of the torrent, so awaiting it here
        // meant this loop sat inside the first download for its entire duration and
        // never came back to start a second one: the first torrent showed progress
        // and everything added after it sat on "Queued" for ever, whatever the
        // concurrency setting said.
        scope.launch {
            runCatching { transfer(next) }
                .onFailure { error ->
                    store.update(next.id) {
                        it.copy(status = DownloadStatus.FAILED, errorMessage = error.message ?: "Torrent failed")
                    }
                    onChange()
                }
        }
    }

    /** Whether this row is one that is moving, or about to be. */
    private fun QueuedDownload.isTransferring(): Boolean =
        status == DownloadStatus.RESOLVING || status == DownloadStatus.RUNNING

    /**
     * Saves a magnet's file list the first time the swarm sends it, and never again.
     *
     * Once per item, not once per poll: this sits in a loop that runs every few
     * hundred milliseconds, and reading the metadata costs a second libtorrent
     * session and a trip to the swarm.
     */
    private fun saveFileListOnce(item: QueuedDownload) {
        if (!item.url.startsWith("magnet:", ignoreCase = true)) return
        if (com.downloadhub.core.TorrentMetainfoStore.read(AppPaths.home, item.id) != null) return
        if (fileListSaves.putIfAbsent(item.id, true) != null) return
        scope.launch(Dispatchers.IO) {
            val fetched = com.downloadhub.core.TorrentMetadataReader.read(item.url).getOrNull()
            com.downloadhub.core.TorrentMetainfoStore.write(AppPaths.home, item.id, fetched)
            onChange()
        }
    }

    /** Items whose file list has already been looked for, so it is looked for once. */
    private val fileListSaves = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

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
                            // A magnet that resolved without passing through the
                            // pre-download dialog - from the browser extension, or a
                            // magnet opened in Explorer - has just been handed its file
                            // list by the swarm and has nowhere to keep it. This is the
                            // only moment it can be saved, so it is taken once.
                            if (snapshot.hasMetadata) saveFileListOnce(item)
                            if (snapshot.isFinished) {
                                publish(item.id, snapshot)
                                // Keep watching only while there is a limit to enforce.
                                // The old behaviour stopped here, which meant a finished
                                // torrent kept seeding for ever and the app never heard
                                // about it again - so "stop at ratio 2" had nothing to
                                // act on. With no limits set there is nothing to watch
                                // for, so this ends as it used to.
                                if (!enforceShareLimits(item.id, snapshot)) return
                                delay(SEEDING_POLL_MILLIS)
                            } else {
                                delay(POLL_MILLIS)
                            }
                        }
                    }
                }
            }
        } finally {
            running.remove(item.id)
        }
    }

    /**
     * Applies this torrent's share limits, and says whether to keep watching it.
     *
     * Returns false once the torrent has been stopped, or when there are no limits to
     * apply, which ends this torrent's polling loop.
     */
    private fun enforceShareLimits(id: String, snapshot: TorrentSnapshot): Boolean {
        val item = store.get(id) ?: return false
        val limits = item.toCoreItem().shareLimits
        if (!limits.enabled) return false

        val now = System.currentTimeMillis()
        val since = snapshot.seedingSinceEpochMillis
        if (since > 0L && item.seedingSinceEpochMillis != since) {
            store.update(id) { it.copy(seedingSinceEpochMillis = since) }
        }

        val shouldStop = com.downloadhub.core.TransferRules.shouldStopSeeding(
            downloadedBytes = snapshot.bytesDownloaded,
            uploadedBytes = snapshot.uploadedBytes,
            seedingSinceEpochMillis = since,
            nowEpochMillis = now,
            limits = limits
        )
        if (!shouldStop) return true

        runCatching { engine.pause(item.toCoreItem()) }
        val reason = com.downloadhub.core.TransferRules.stopReason(
            snapshot.bytesDownloaded, snapshot.uploadedBytes, since, now, limits
        )
        store.update(id) {
            it.copy(
                status = com.downloadhub.core.DownloadStatus.PAUSED,
                speedBytesPerSecond = 0L,
                seedingStoppedAtEpochMillis = now,
                // Said out loud rather than the row just going quiet, which is what a
                // torrent that stops on its own looks like otherwise.
                errorMessage = reason
            )
        }
        store.persist()
        return false
    }

    private fun applySnapshot(id: String, snapshot: TorrentSnapshot) {
        store.update(id) {
            it.copy(
                // The status, which this did not write at all.
                //
                // It was added to the queue as QUEUED and nothing ever changed it, so a
                // torrent downloading at 1.1 MB/s said "Queued" in the list - the speed
                // column and the status column disagreeing about the same row, with the
                // speed the one that was right.
                //
                // Mostly a promotion: a paused item is not moved by a reading, because
                // the loop re-pauses it and waits, and a completed one is not either.
                // FAILED used to be terminal as well, and that was wrong - see below.
                status = when {
                    it.status == DownloadStatus.PAUSED -> it.status
                    it.status == DownloadStatus.COMPLETED -> it.status
                    // All the data is on disk, so it is finished - whatever the error
                    // field says.
                    //
                    // FAILED is terminal, and it was reached on any reading that carried
                    // an error. libtorrent reports errors for things a torrent recovers
                    // from by itself: a storage error that cleared, a tracker that went
                    // away, a resume-data rewrite that raced a poll. So a torrent could
                    // hit one mid-download, be marked Failed for good, go on to 100%, and
                    // sit in the list saying Failed beside a full progress bar. Asking the
                    // data first and the error only afterwards is what makes the row tell
                    // the truth.
                    snapshot.isFinished -> DownloadStatus.COMPLETED
                    // Still moving is not failed, however the last reading looked. A
                    // torrent that got itself going again says so, rather than sitting on
                    // Failed until somebody presses retry.
                    snapshot.downloadRate > 0 -> DownloadStatus.RUNNING
                    it.status == DownloadStatus.FAILED -> it.status
                    snapshot.error != null -> DownloadStatus.FAILED
                    // A magnet before the swarm sends its metadata is resolving, not
                    // queued: nothing has been asked for yet, as opposed to asked for and
                    // waiting for a slot.
                    !snapshot.hasMetadata -> DownloadStatus.RESOLVING
                    snapshot.isPaused -> DownloadStatus.PAUSED
                    else -> DownloadStatus.RUNNING
                },
                bytesDownloaded = snapshot.bytesDownloaded,
                totalBytes = snapshot.totalBytes,
                speedBytesPerSecond = snapshot.downloadRate,
                torrentInfoHash = snapshot.infoHash.takeIf { hash -> hash.isNotBlank() },
                fileName = snapshot.name?.takeIf { name -> name.isNotBlank() } ?: it.fileName,
                // Only kept while the row is actually failed. Carried on a running or
                // finished row, the message was a leftover from a reading that no longer
                // described anything, shown beside a download that was plainly working.
                errorMessage = if (it.status == DownloadStatus.FAILED && snapshot.error != null) {
                    it.errorMessage
                } else {
                    snapshot.error
                },
                // Mirrored rather than read straight from the engine, so the detail pane
                // and the status strip show the same numbers as the row. A pane that
                // polled the engine separately would show figures that disagree with the
                // list by a second, which is worse than not showing them at all.
                uploadRate = snapshot.uploadRate,
                seeds = snapshot.seeds,
                peerCount = snapshot.peers,
                uploadedBytes = snapshot.uploadedBytes,
                // Stamped once, on the first reading that says it finished. Re-stamping
                // every poll would make "finished at" mean "most recent poll", which is
                // not a time anyone can act on.
                completedAt = if (snapshot.isFinished && it.completedAt == 0L) {
                    System.currentTimeMillis()
                } else {
                    it.completedAt
                }
            )
            // Not part of the copy: it is transient, and a `copy` on a data class carries
            // only the constructor properties. Written through the item so the file list can
            // show what each file has rather than the same 0% on every row.
            .also { row ->
                if (snapshot.fileProgress.isNotEmpty()) {
                    row.torrentFileProgress = snapshot.fileProgress
                        .mapIndexed { index, bytes -> index to bytes }
                        .toMap()
                }
            }
        }
        onChange()
    }

    /**
     * Sets one file's priority, and sends it to libtorrent straight away.
     *
     * Applied to the store first and the engine second, so the row changes at once and the
     * piece queue follows. The reverse order leaves the list claiming something the engine
     * has not done yet, which is the same class of bug as a status that says Queued while
     * the speed column says otherwise.
     */
    fun setFilePriority(id: String, fileIndex: Int, priority: com.downloadhub.core.FilePriority) {
        val item = store.get(id) ?: return
        val updated = item.torrentFilePriorities +
            (fileIndex to priority.ordinal)
        store.update(id) { it.copy(torrentFilePriorities = updated) }
        store.persist()
        engine.setFilePriorities(store.get(id)!!.toCoreItem())
        onChange()
    }

    /**
     * Applies one priority to every file, which is what "set all" means and what the
     * detail pane's row of buttons does.
     */
    fun setAllFilePriorities(id: String, priority: com.downloadhub.core.FilePriority) {
        val item = store.get(id) ?: return
        val meta = item.torrentFilePath?.let(::File)?.takeIf { it.isFile }
            ?.let { runCatching { TorrentParser.parse(it) }.getOrNull() } ?: return
        store.update(id) {
            it.copy(
                torrentFilePriorities = meta.files.associate { file -> file.index to priority.ordinal }
            )
        }
        store.persist()
        engine.setFilePriorities(store.get(id)!!.toCoreItem())
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
        val item = store.get(id) ?: return
        engine.pause(item.toCoreItem())
        // The store, not just libtorrent.
        //
        // Pause told the handle to stop and wrote nothing, while resume wrote the status
        // and needed to tell libtorrent nothing - the poll loop sees QUEUED and carries on.
        // So a paused torrent still said "Downloading" in the list, and the only sign that
        // it had stopped was the speed column going quiet.
        store.update(id) {
            it.copy(status = DownloadStatus.PAUSED, speedBytesPerSecond = 0L)
        }
        store.persist()
        onChange()
    }

    /**
     * Stops a torrent outright, keeping every byte it has already fetched.
     *
     * Pause asks libtorrent to stop sending and to remember exactly where it was, so
     * resuming carries on from the same piece. This takes the torrent out of the
     * session instead: nothing holds it, nothing is uploading, and resuming starts it
     * afresh. That is what someone wants when a download is going wrong and they would
     * rather it let go than keep limping - and it is the per-selection counterpart to
     * Stop All, which is why it needed to exist as its own action rather than as a
     * second name for pause.
     *
     * The files are kept. Stopping is not deleting, and a download that has taken four
     * hours to fetch most of a file does not lose that because someone pressed the wrong
     * button.
     */
    fun stop(id: String) {
        val item = store.get(id) ?: return
        runCatching { engine.remove(item.toCoreItem(), deleteFiles = false) }
        store.update(id) {
            it.copy(
                status = DownloadStatus.PAUSED,
                speedBytesPerSecond = 0L,
                uploadRate = 0L,
                errorMessage = null
            )
        }
        store.persist()
        onChange()
    }

    fun resume(id: String) {
        store.update(id) { it.copy(status = DownloadStatus.QUEUED, errorMessage = null) }
        onChange()
    }

    fun remove(id: String, deleteFiles: Boolean, deleteCache: Boolean = true) {
        val item = store.get(id) ?: return
        // libtorrent is told about the files it wrote; the scratch folder is ours, and
        // only ours to delete. Both are needed - the engine knows the published files, and
        // the scratch folder is where a half-finished torrent actually lives.
        if (deleteFiles) engine.remove(item.toCoreItem(), deleteFiles)
        if (deleteCache) runCatching { area.torrentWorkDir(id).deleteRecursively() }
    }

    fun close() {
        runCatching { engine.shutdown() }
    }

    private companion object {
        const val POLL_MILLIS = 1000L

/**
 * How often a finished torrent is checked while it is still sharing.
 *
 * Slower than a downloading one: the only thing being decided is whether a share limit
 * has been reached, and that does not change second by second. A minute is also the
 * resolution a "stop seeding after N minutes" limit is really worth.
 */
const val SEEDING_POLL_MILLIS = 30_000L
        val MEDIA_EXTENSIONS = listOf(
            ".mp4", ".mkv", ".webm", ".mov", ".avi", ".m4v", ".flv",
            ".mp3", ".m4a", ".flac", ".wav", ".opus", ".aac"
        )
    }
}
