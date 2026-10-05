package com.downloadhub.core

import java.io.File

/**
 * What the user chose in the pre-download dialog.
 *
 * Everything here is a choice made *before* anything is queued. That is what separates it
 * from the per-download options that already exist: the point of looking first is that a
 * torrent is a container, and queued without looking it downloads everything in it,
 * including the parts nobody wanted, and by then the only way to stop is to delete it.
 */
data class TorrentAddRequest(
    /** What is known about the download. Empty for a magnet or an ordinary link. */
    val metainfo: TorrentMetainfo,
    /**
     * Where those bytes came from, when they came from a file on this machine.
     *
     * Kept so the torrent can be copied into the app's own folder before it is queued.
     * The original is usually in Downloads, which gets tidied and emptied, and a torrent
     * whose `.torrent` has gone is a permanent row that can never start.
     *
     * Null for a magnet or a link, neither of which has a file behind it.
     */
    val metainfoFile: File? = null,
    /** Where the finished files go. */
    val saveDirectory: File,
    /**
     * Which files to fetch, by their index in the torrent.
     *
     * Empty means all of them, which is also what a magnet and a plain link mean: there
     * is no list to choose from.
     */
    val selectedFiles: Set<Int> = emptySet(),
    /** Fetch files in order, which helps on content where earlier parts matter. */
    val sequentialDownload: Boolean = false,
    /** Download the first and last pieces first, which is what a video needs to start. */
    val downloadFirstAndLastPiecesFirst: Boolean = false,
    /** Add it to the queue but do not start it. */
    val startImmediately: Boolean = true,
    /** Stop sharing once this condition is met, or never. */
    val stopCondition: TorrentStopCondition = TorrentStopCondition.Never,
    /** Folder name under the save directory. Blank for a single-file torrent. */
    val contentFolder: String = "",
    /** The link to queue. A magnet, an http link, or a path to a `.torrent`. */
    val link: String = "",
    /** Headers, cookies and login for a plain link. Ignored for a torrent. */
    val http: HttpRequestOptions = HttpRequestOptions()
) {
    /** True when there is a file to choose between, so the file list means something. */
    val hasFileList: Boolean get() = metainfo.files.isNotEmpty()
}

/**
 * When to stop sharing after a torrent finishes.
 *
 * Both limits are optional and both default to off, because a torrent that quietly stops
 * seeding after some unstated ratio is a torrent that stops contributing to the swarm
 * without ever saying so.
 */
sealed class TorrentStopCondition {
    /** Keep seeding. */
    object Never : TorrentStopCondition()

    /**
     * Stop sharing the moment the download finishes.
     *
     * Distinct from a share limit, and not a special case of one: a ratio of zero and a
     * seed time of zero both read as "stop immediately", including before the download
     * has finished at all, and either would be a torrent that arrives already dead. This
     * is the only condition that waits for completion and is about the download rather
     * than the sharing.
     *
     * Worth having for the obvious case: a large release fetched from one seeder, where
     * the last few per cent is the only part worth returning.
     */
    object WhenComplete : TorrentStopCondition()

    /** Stop once the share ratio reaches this. */
    data class AtRatio(val ratio: Double) : TorrentStopCondition()

    /** Stop once this much has been uploaded, whatever the ratio works out at. */
    data class AtUploadedAmount(val bytes: Long) : TorrentStopCondition()

    /**
     * Stop once it has been seeding for this long.
     *
     * Times the period *after* finishing, not the total, which is the thing people mean
     * when they set it.
     */
    data class AfterSeedingFor(val minutes: Int) : TorrentStopCondition()

    /** True when this is not [Never]. */
    val isLimited: Boolean get() = this !is Never

    /** The share limits this maps to, for the engine. */
    fun toShareLimits(downloadedBytes: Long): ShareLimits = when (this) {
        Never -> ShareLimits()
        // Carried as its own flag, not as a limit of zero. A ratio of zero would stop
        // sharing before the download had started, which is a torrent that arrives
        // dead rather than one that stops when it is finished.
        WhenComplete -> ShareLimits(stopWhenComplete = true)
        is AtRatio -> ShareLimits(ratioLimit = ratio)
        // An uploaded-amount limit is a ratio expressed in bytes, because that is the
        // only way the existing comparison can act on it.
        is AtUploadedAmount -> ShareLimits(
            ratioLimit = if (downloadedBytes > 0) bytes.toDouble() / downloadedBytes else 0.0
        )
        is AfterSeedingFor -> ShareLimits(seedTimeLimitMinutes = minutes)
    }
}

/** The rules the dialog's choices turn into. */
object TorrentSelection {

    /**
     * Every file, which is what the checkboxes start on.
     *
     * Starting with everything ticked is deliberate: a user who wants three files of forty
     * opens the dialog, unticks thirty-seven, and adds. Starting with nothing ticked means
     * an added torrent downloads nothing at all, which looks like the feature not working.
     */
    fun allSelected(meta: TorrentMetainfo): Set<Int> = meta.files.map { it.index }.toSet()

    /** Sum of the selected files' sizes. */
    fun selectedSize(meta: TorrentMetainfo, selected: Set<Int>): Long =
        meta.files.filter { it.index in selected }.sumOf { it.size }

    /**
     * The folder the files land in.
     *
     * A single-file torrent is saved as the file itself - creating a folder for one file
     * is just noise - and a multi-file one needs its name, or its files land loose in the
     * save directory beside everything else.
     */
    fun contentFolder(meta: TorrentMetainfo, chosen: String): String = when {
        meta.isSingleFile -> ""
        chosen.isNotBlank() -> chosen
        else -> meta.name
    }

    /**
     * A name that is safe on Windows and empty when there is nothing to make one.
     *
     * Torrents carry names written on whatever machine made them, and a name with a colon
     * or a slash in it is a download that fails at the last step with nothing to explain
     * it.
     */
    fun safeName(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().trim('.').take(200)

    /**
     * Builds a request, or null if it could not be queued.
     *
     * Null means the link is not something that can be downloaded at all - not a
     * refusal to queue an empty selection, which is a different thing entirely. The
     * dialog's Add button stays disabled for the former, so this should not normally be
     * reachable from the UI.
     */
    fun validated(
        meta: TorrentMetainfo,
        link: String,
        saveDirectory: File,
        selected: Set<Int>,
        metainfoFile: File? = null,
        sequential: Boolean = false,
        firstLastPiecesFirst: Boolean = false,
        startImmediately: Boolean = true,
        stopCondition: TorrentStopCondition = TorrentStopCondition.Never,
        chosenFolder: String = ""
    ): TorrentAddRequest? {
        // A torrent has to have bytes behind it. Refusing here is what makes the failure
        // say why, instead of queueing a row whose "link" is a piece of text and then
        // reporting `no protocol: <the torrent's title>` an hour later.
        if (metainfoFile != null && !metainfoFile.isFile) return null
        if (link.isBlank()) return null
        if (!LinkParser.isFetchable(link, metainfoFile)) return null

        // A selection of nothing is only a problem when there was a list to choose from.
        // A magnet and a link have no files to pick, so an empty selection is normal.
        val usable = if (meta.files.isEmpty()) {
            emptySet()
        } else {
            val chosen = selected.intersect(meta.files.map { it.index }.toSet())
            if (chosen.isEmpty()) return null
            chosen
        }

        return TorrentAddRequest(
            metainfo = meta,
            metainfoFile = metainfoFile,
            saveDirectory = saveDirectory,
            selectedFiles = usable,
            sequentialDownload = sequential,
            downloadFirstAndLastPiecesFirst = firstLastPiecesFirst,
            startImmediately = startImmediately,
            stopCondition = stopCondition,
            contentFolder = safeName(contentFolder(meta, chosenFolder)),
            link = link
        )
    }

    /**
     * Reads a `.torrent` and builds the request in one step.
     *
     * The single place a dropped file turns into something queueable, so drag-and-drop, a
     * file picker and a double-click in Explorer all go through the same checks.
     */
    fun fromFile(
        file: File,
        saveDirectory: File,
        chosenFolder: String = ""
    ): Result<TorrentAddRequest> {
        val meta = runCatching { TorrentParser.parse(file) }.getOrElse {
            // Name the file. Several things can be dropped at once, and "the torrent file
            // is truncated" with no indication of which leaves nothing to act on.
            return Result.failure(
                TorrentParseException("${file.name}: ${it.message ?: "not a torrent file"}")
            )
        }
        val request = validated(
            meta = meta,
            link = file.absolutePath,
            saveDirectory = saveDirectory,
            selected = allSelected(meta),
            metainfoFile = file,
            chosenFolder = chosenFolder
        ) ?: return Result.failure(
            TorrentParseException("${meta.name} contains no files to download")
        )
        return Result.success(request)
    }
}
