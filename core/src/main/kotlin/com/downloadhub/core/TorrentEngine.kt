package com.downloadhub.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.libtorrent4j.AnnounceEntry
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.ip_filter
import org.libtorrent4j.swig.address
import org.libtorrent4j.swig.error_code
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
    val seedingSinceEpochMillis: Long = 0L,
    /**
     * Bytes downloaded per file, indexed by file index.
     *
     * Empty when there is no file list yet - a magnet before the swarm has sent its
     * metadata - and one entry per file once there is. Read on every poll, so it is the
     * same second as the row's speed rather than a separate reading that can disagree
     * with it.
     */
    val fileProgress: LongArray = LongArray(0)
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
    /**
     * Downloads whose file choices have reached libtorrent. A magnet has no file list at
     * [start], so its choices are applied by [poll] once the metadata arrives.
     */
    private val selectionApplied = ConcurrentHashMap.newKeySet<String>()
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
        if (applyFileSelection(item, handle)) selectionApplied += item.id
        handles[item.id] = handle
        return snapshot(item, handle)
    }

    /**
     * Applies the dialog's file selection and the per-file priorities to a started torrent.
     *
     * A file set to [FilePriority.SKIP] is given libtorrent's `IGNORE`, which is how it is
     * told to leave that file's pieces alone - the difference between "I want three of
     * these forty files" and "I want all of them slowly". This is the empty path for every
     * magnet and every torrent added without the dialog.
     *
     * It goes through [planFilePriorities] rather than setting ranks one file at a time,
     * because a file at Maximum also needs its *pieces* raised and the piece arithmetic
     * needs every file's offset, and doing it per file is how you end up raising only the
     * pieces of the first file.
     */
    private fun applyFileSelection(item: DownloadItem, handle: TorrentHandle): Boolean {
        val info = runCatching { handle.torrentFile() }.getOrNull() ?: return false
        val fileCount = runCatching { info.numFiles() }.getOrDefault(0)
        if (fileCount <= 0) return false
        val pieceCount = runCatching { info.numPieces() }.getOrDefault(0)
        val pieceLength = runCatching { info.pieceLength().toLong() }.getOrDefault(0L)

        val sizes = fileSizesOf(item, fileCount)
        val perFile = item.torrentFilePriorities
            .mapValues { (_, ordinal) -> FilePriority.fromOrdinal(ordinal) }
        val selected = item.torrentSelectedFiles.toSet()
        val chosen = (0 until fileCount).associateWith { index ->
            effectiveFilePriority(selected, perFile, index)
        }
        val plan = planFilePriorities(sizes, pieceCount, pieceLength, chosen)
        applyPlan(plan, handle)

        if (item.torrentSequential) {
            runCatching { handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) }
        }
        if (item.torrentFirstLastPiecesFirst) {
            // The first and last pieces are what a player needs before it can start, so
            // asking for them turns a video from "nothing plays for an hour" into
            // "playable almost immediately".
            runCatching {
                if (pieceCount > 0) {
                    val priorities = Priority.array(Priority.DEFAULT, pieceCount)
                    priorities[0] = Priority.TOP_PRIORITY
                    priorities[pieceCount - 1] = Priority.TOP_PRIORITY
                    handle.prioritizePieces(priorities)
                }
            }
        }
        return true
    }

    /**
     * Sends a priority plan to libtorrent.
     *
     * One call for the whole file array rather than one per file: `prioritize_files` is a
     * single swap, and a per-file loop on a thousand-file torrent is a thousand JNI calls
     * to set values that are already the default.
     *
     * The array is one entry per file, which is not the same length as `Priority.values()`:
     * libtorrent's enum is eight long because it has eight ranks, and a torrent has as many
     * files as its author put in it. Passing the enum itself would set eight files.
     */
    private fun applyPlan(plan: FilePriorityPlan, handle: TorrentHandle) {
        if (plan.filePriorities.isEmpty()) return
        val ranks = Priority.values()
        val files = Array(plan.filePriorities.size) { index ->
            ranks[plan.filePriorities[index].coerceIn(ranks.first().ordinal, ranks.last().ordinal)]
        }
        runCatching { handle.prioritizeFiles(files) }
        // Only when something asked for its pieces to be raised. Sending an all-default
        // array would clear a piece order somebody set elsewhere, such as first-and-last.
        plan.piecePriorities?.let { pieces ->
            val raised = Array(pieces.size) { index ->
                ranks[pieces[index].coerceIn(ranks.first().ordinal, ranks.last().ordinal)]
            }
            runCatching { handle.prioritizePieces(raised) }
        }
    }

    /**
     * Each file's size, which is what the piece arithmetic needs.
     *
     * Read from the metainfo rather than from the queue, because the queue does not carry
     * forty file sizes and would have to for every poll. The torrent file is on disk - it is
     * copied there when the torrent is added, and a magnet that went through the
     * pre-download dialog had its fetched metainfo written there too.
     *
     * Empty when it cannot be found. That is not a failure: the per-file ranks still go out,
     * which is what "skip this file" and "raise this file" need. Only "Maximum" loses
     * something, because raising a file's pieces is what needs the sizes, and so it falls
     * back to competing like High does. Saying so is better than guessing offsets.
     */
    private fun fileSizesOf(item: DownloadItem, fileCount: Int): List<Long> {
        val cached = item.torrentFilePath?.let(::File)?.takeIf { it.isFile }
            ?.let { runCatching { TorrentParser.parse(it) }.getOrNull() }
        if (cached != null && cached.files.size == fileCount) {
            return cached.files.sortedBy { it.index }.map { it.size }
        }
        return emptyList()
    }

    /**
     * Re-applies per-file priorities to an already-running torrent.
     *
     * This is what a priority control in the file list calls. It does not restart the
     * torrent and it does not touch the piece queue unless something asked for Maximum, so
     * "stop this one file" takes effect on the next piece request rather than after a
     * recheck.
     */
    fun setFilePriorities(item: DownloadItem): Boolean {
        val handle = handles[item.id] ?: start(item)?.let { handles[item.id] } ?: return false
        if (applyFileSelection(item, handle)) selectionApplied += item.id
        return true
    }

    fun poll(item: DownloadItem): TorrentSnapshot? {
        val hash = item.torrentInfoHash ?: runCatching {
            AddTorrentParams.parseMagnetUri(item.url).infoHashes.getBest().toHex()
        }.getOrNull() ?: return handles[item.id]?.let { snapshot(item, it) }
        val handle = sessionManager.find(Sha1Hash.parseHex(hash))
            ?: return handles[item.id]?.let { snapshot(item, it) }
        handles[item.id] = handle
        if (item.id !in selectionApplied && applyFileSelection(item, handle)) selectionApplied += item.id
        return snapshot(item, handle)
    }

    fun pause(item: DownloadItem) {
        handles[item.id]?.pause()
    }

    /** What the session runs with; kept so a session started later still gets it. */
    @Volatile
    private var sessionSettings: TorrentSessionSettings? = null

    /**
     * Speed limits and connection settings for the whole session, as in qBittorrent's
     * Speed and Connection pages. Applied now if the session is running, and at start
     * otherwise. Cheap to call repeatedly; libtorrent only acts on what changed.
     */
    fun applySettings(settings: TorrentSessionSettings) {
        sessionSettings = settings
        synchronized(lock) {
            if (sessionManager.isRunning) runCatching { sessionManager.applySettings(packFor(settings)) }
        }
    }

    /** The blocked ranges; kept so a session started later still gets them. */
    @Volatile
    private var blockedRanges: List<IpRange> = emptyList()

    /** qBittorrent's IP filter: peers in these ranges are refused. An empty list clears it. */
    fun setIpFilter(ranges: List<IpRange>) {
        blockedRanges = ranges
        synchronized(lock) {
            if (sessionManager.isRunning) applyIpFilter(ranges)
        }
    }

    private fun applyIpFilter(ranges: List<IpRange>) = runCatching {
        val filter = ip_filter()
        val blocked = ip_filter.access_flags.blocked.swigValue().toLong()
        ranges.forEach { range ->
            val error = error_code()
            val from = address.from_string(range.start, error)
            val to = address.from_string(range.end, error)
            if (error.value() == 0) filter.add_rule(from, to, blocked)
        }
        sessionManager.swig()?.set_ip_filter(filter)
    }

    private fun packFor(s: TorrentSessionSettings): SettingsPack {
        val pack = SettingsPack()
            // libtorrent reads 0 as unlimited, which is the same meaning the app gives it.
            .downloadRateLimit(s.downloadLimitBytesPerSecond.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
            .uploadRateLimit(s.uploadLimitBytesPerSecond.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
            .anonymousMode(s.anonymousMode)
        if (s.maxConnections > 0) pack.connectionsLimit(s.maxConnections)
        pack.setEnableDht(s.dht)
        pack.setEnableLsd(s.localPeerDiscovery)
        pack.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), s.portForwarding)
        pack.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), s.portForwarding)
        val policy = when (s.encryption) {
            TorrentEncryption.REQUIRED -> 0 // pe_forced
            TorrentEncryption.ALLOWED -> 1 // pe_enabled
            TorrentEncryption.DISABLED -> 2 // pe_disabled
        }
        pack.setInteger(settings_pack.int_types.in_enc_policy.swigValue(), policy)
        pack.setInteger(settings_pack.int_types.out_enc_policy.swigValue(), policy)
        if (s.listenPort in 1..65535) {
            pack.listenInterfaces("0.0.0.0:${s.listenPort},[::]:${s.listenPort}")
        }
        return pack
    }

    // --- qBittorrent's Trackers and Peers tabs, and its two "force" actions ---------

    /** The torrent's handle whether or not this run started it; null when it is not in the session. */
    private fun handleFor(item: DownloadItem): TorrentHandle? {
        handles[item.id]?.takeIf { it.isValid }?.let { return it }
        val hash = item.torrentInfoHash ?: return null
        return runCatching { sessionManager.find(Sha1Hash.parseHex(hash)) }.getOrNull()?.takeIf { it.isValid }
    }

    fun trackers(item: DownloadItem): List<TrackerRow> {
        val handle = handleFor(item) ?: return emptyList()
        return runCatching {
            handle.trackers().map { entry ->
                // One endpoint per local interface; the best answer any of them got.
                val infos = entry.endpoints().mapNotNull { runCatching { it.infohashV1() }.getOrNull() }
                TrackerRow(
                    url = entry.url(),
                    tier = entry.tier(),
                    status = trackerStatus(
                        contacted = infos.isNotEmpty(),
                        updating = infos.any { it.updating() },
                        working = infos.any { it.isWorking() },
                        fails = infos.maxOfOrNull { it.fails().toInt() } ?: 0
                    ),
                    message = infos.map { it.message() }.firstOrNull { it.isNotBlank() }.orEmpty()
                )
            }
        }.getOrDefault(emptyList())
    }

    fun peers(item: DownloadItem): List<PeerRow> {
        val handle = handleFor(item) ?: return emptyList()
        return runCatching {
            handle.peerInfo().map { peer ->
                PeerRow(
                    address = peer.ip(),
                    client = peer.client(),
                    progressPercent = (peer.progress() * 100).toInt().coerceIn(0, 100),
                    downloadRate = peer.downSpeed().toLong().coerceAtLeast(0),
                    uploadRate = peer.upSpeed().toLong().coerceAtLeast(0),
                    downloaded = peer.totalDownload(),
                    uploaded = peer.totalUpload()
                )
            }.sortedByDescending { it.downloadRate + it.uploadRate }
        }.getOrDefault(emptyList())
    }

    /** Adds trackers; ones already there are left alone. True when the torrent was found. */
    fun addTrackers(item: DownloadItem, urls: List<String>): Boolean {
        val handle = handleFor(item) ?: return false
        val existing = runCatching { handle.trackers().map { it.url() }.toSet() }.getOrDefault(emptySet())
        urls.map { it.trim() }.filter { it.isNotEmpty() && it !in existing }
            .forEach { runCatching { handle.addTracker(AnnounceEntry(it)) } }
        return true
    }

    /** Re-reads every piece on disk against its hash: qBittorrent's Force recheck. */
    fun forceRecheck(item: DownloadItem): Boolean =
        handleFor(item)?.let { runCatching { it.forceRecheck() }.isSuccess } ?: false

    /** Asks every tracker for peers now rather than at its next interval: Force reannounce. */
    fun forceReannounce(item: DownloadItem): Boolean =
        handleFor(item)?.let { runCatching { it.forceReannounce() }.isSuccess } ?: false

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
        selectionApplied.remove(item.id)
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
                // Whatever applySettings was given before there was a session to apply it to.
                sessionSettings?.let { runCatching { sessionManager.applySettings(packFor(it)) } }
                if (blockedRanges.isNotEmpty()) applyIpFilter(blockedRanges)
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
        // Not SEQUENTIAL_DOWNLOAD: that was set on every torrent, which fetches pieces in
        // order instead of rarest-first and slows the whole swarm. It is applied in
        // applyFileSelection only when the add dialog asked for it.
        val flags = TorrentFlags.UPDATE_SUBSCRIBE
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
            // Connected peers and connected seeds. Peers was read from numSeeds, so the
            // pane's Peers figure was the seed count.
            peers = status.numPeers(),
            seeds = status.numSeeds(),
            // Read from the item, not from libtorrent.
            //
            // This was hardcoded false, so nothing downstream could tell a paused torrent
            // from a running one that happened to be moving nothing. This binding of
            // libtorrent exposes no paused flag at all - neither `torrent_status` nor
            // `torrent_handle` has one - so the answer has to come from the caller's own
            // record. A torrent that is finished is also not transferring, whether or not
            // it is still seeding.
            isPaused = item.status == DownloadStatus.PAUSED ||
                item.status == DownloadStatus.COMPLETED,
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
            seedingSinceEpochMillis = seedingSince(infoHash, finished),
            // One reading per file, so the file list can show what each one has rather
            // than the same 0% on every row. Empty before there is a file list at all.
            fileProgress = if (status.hasMetadata()) {
                runCatching { handle.fileProgress() ?: LongArray(0) }.getOrDefault(LongArray(0))
            } else {
                LongArray(0)
            }
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

/** One row of the Trackers tab. */
data class TrackerRow(
    val url: String,
    val tier: Int,
    /** "Working", "Updating...", "Not working" or "Not contacted yet", as qBittorrent words it. */
    val status: String,
    /** What the tracker last said, such as an error; empty when it said nothing. */
    val message: String
)

/** One row of the Peers tab. */
data class PeerRow(
    val address: String,
    val client: String,
    val progressPercent: Int,
    val downloadRate: Long,
    val uploadRate: Long,
    val downloaded: Long,
    val uploaded: Long
)

/** A tracker's state in qBittorrent's words, from what its announces reported. */
internal fun trackerStatus(contacted: Boolean, updating: Boolean, working: Boolean, fails: Int): String = when {
    !contacted -> "Not contacted yet"
    updating -> "Updating..."
    working -> "Working"
    fails > 0 -> "Not working"
    else -> "Not contacted yet"
}

enum class TorrentEncryption(val label: String) {
    ALLOWED("Allow encryption"),
    REQUIRED("Require encryption"),
    DISABLED("Disable encryption")
}

/**
 * The session-wide torrent settings, as qBittorrent's Speed and Connection pages lay them
 * out. Limits are bytes per second, 0 meaning unlimited. A port of 0 leaves libtorrent's
 * own choice; a connection limit of 0 leaves libtorrent's default.
 */
data class TorrentSessionSettings(
    val downloadLimitBytesPerSecond: Long = 0,
    val uploadLimitBytesPerSecond: Long = 0,
    val listenPort: Int = 0,
    val dht: Boolean = true,
    val localPeerDiscovery: Boolean = true,
    /** UPnP and NAT-PMP together, as qBittorrent's single tick box does. */
    val portForwarding: Boolean = true,
    val encryption: TorrentEncryption = TorrentEncryption.ALLOWED,
    val maxConnections: Int = 0,
    val anonymousMode: Boolean = false
)
