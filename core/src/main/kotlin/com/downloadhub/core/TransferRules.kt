package com.downloadhub.core

/**
 * Per-download settings, taken from the two apps this was compared against.
 *
 * Everything here is a plain function over numbers and enums, with no clock, no engine
 * and no platform, because the decisions are the part worth having and they have to be
 * identical on the phone and on Windows. The engines ask these questions; they never
 * answer them themselves.
 *
 * Where each idea comes from:
 *
 * - **Speed limit and scheduling** from AB Download Manager, whose `DownloadJobExtraConfig`
 *   and `ScheduleTimes` let a single download carry settings of its own rather than only
 *   inheriting the global ones. Ours had a global limit and nothing else, so one large
 *   file could saturate the connection and quietly slow down everything queued behind it.
 * - **Priority** from qBittorrent, which orders its queue by a per-torrent priority
 *   instead of insertion order alone.
 * - **Share limits** from qBittorrent's "stop at ratio" and "stop seeding at", which
 *   stop a torrent uploading for ever once it has given back enough. Without them a
 *   finished torrent keeps seeding until it is paused by hand, which is not what
 *   "downloaded" means to most people.
 */

/**
 * How urgently an item should be given a slot.
 *
 * Deliberately a queue-ordering concept only. It does not map onto libtorrent's own
 * seven torrent priorities, and pretending it does would produce a control that looks
 * like it is doing something on a torrent and is not.
 */
enum class DownloadPriority(val rank: Int, val label: String) {
    LOW(0, "Low"),
    BELOW_NORMAL(1, "Below normal"),
    NORMAL(2, "Normal"),
    ABOVE_NORMAL(3, "Above normal"),
    HIGH(4, "High");

    companion object {
        fun fromRank(rank: Int): DownloadPriority =
            entries.firstOrNull { it.rank == rank } ?: NORMAL
    }
}

/**
 * When a finished torrent should stop uploading.
 *
 * Both limits are off when zero or less, rather than being on at some default: a
 * torrent that quietly stops seeding at a ratio nobody asked for is worse than one that
 * seeds until it is paused.
 */
data class ShareLimits(
    /** Stop once this much has been uploaded per byte downloaded. */
    val ratioLimit: Double = 0.0,
    /** Stop this many minutes after the download finished. */
    val seedTimeLimitMinutes: Int = 0,
    /**
     * Stop sharing the instant the download completes, rather than after a period.
     *
     * Its own flag because it is not a limit and cannot be written as one. A ratio of
     * zero and a seed time of zero both read as "stop now", and "now" for a torrent that
     * has not started downloading is a torrent that arrives dead.
     */
    val stopWhenComplete: Boolean = false
) {
    val enabled: Boolean
        get() = ratioLimit > 0.0 || seedTimeLimitMinutes > 0 || stopWhenComplete
}

object TransferRules {

    /**
     * The limit that actually applies to one download.
     *
     * The item's own limit wins over the global one, so "this file, 500 KB/s" means
     * that even when the app-wide limit is off. Taking the smaller of the two would be
     * wrong in the other direction: raising a limit on one file should not silently
     * raise it above the cap the user set for everything.
     */
    fun effectiveSpeedLimit(itemLimitBytesPerSecond: Long, globalLimitBytesPerSecond: Long): Long {
        val global = globalLimitBytesPerSecond.coerceAtLeast(0L)
        val own = itemLimitBytesPerSecond.coerceAtLeast(0L)
        // The global cap still applies as a ceiling; only the item can lower it.
        return when {
            global <= 0L -> own
            own <= 0L -> global
            else -> minOf(global, own)
        }
    }

    /** Whether this limit is worth enforcing at all. */
    fun isThrottled(limitBytesPerSecond: Long): Boolean = limitBytesPerSecond > 0L

    /**
     * Whether a scheduled download may start yet.
     *
     * Zero or less means "whenever", which is every download added before this
     * feature existed.
     */
    fun isStartable(startAfterEpochMillis: Long, nowEpochMillis: Long): Boolean =
        startAfterEpochMillis <= 0L || startAfterEpochMillis <= nowEpochMillis

    /**
     * The order queued items are started in.
     *
     * Priority first, then the order they were added. Falling back to the creation time
     * matters: two items at the same priority must still come out in a predictable
     * order, or "restart the queue" reshuffles the list for no reason.
     */
    fun queueOrder(): Comparator<DownloadItem> =
        queueOrderBy({ it.priority.rank }, { it.createdAt }, { it.id })

    /**
     * The same order, for a platform whose own item type carries the fields rather than
     * a :core item.
     *
     * Android's queue is a channel of ids read out of a Room table, so it cannot sort
     * [DownloadItem]s without mapping every row first. Sharing the comparison here is
     * what keeps the two platforms from drifting: the desktop sorts mapped items and
     * the phone sorts raw ones, and both must agree on which one is served first.
     */
    fun <T> queueOrderBy(
        priorityRank: (T) -> Int,
        createdAt: (T) -> Long,
        id: (T) -> String
    ): Comparator<T> =
        compareByDescending<T> { priorityRank(it) }
            .thenBy { createdAt(it) }
            .thenBy { id(it) }

    /** Bytes uploaded per byte downloaded. Zero when nothing is known yet. */
    fun shareRatio(downloadedBytes: Long, uploadedBytes: Long): Double = when {
        downloadedBytes <= 0L -> 0.0
        else -> uploadedBytes.toDouble() / downloadedBytes.toDouble()
    }

    /**
     * Whether a finished torrent has given back enough and should be paused.
     *
     * Both conditions are checked and either is enough. [seedingSinceEpochMillis] is
     * when it finished downloading; zero means it has not finished, in which case there
     * is nothing to have shared yet.
     */
    fun shouldStopSeeding(
        downloadedBytes: Long,
        uploadedBytes: Long,
        seedingSinceEpochMillis: Long,
        nowEpochMillis: Long,
        limits: ShareLimits
    ): Boolean {
        if (!limits.enabled) return false
        // Still downloading: seeding time cannot have elapsed.
        if (seedingSinceEpochMillis <= 0L) return false

        // "Stop at 100%" is about the download, not about the sharing, and it is
        // satisfied the moment the torrent is whole. Checked before the others because it
        // is the only one of the three that can be true while the other two are at zero.
        if (limits.stopWhenComplete) return true

        if (limits.ratioLimit > 0.0 && downloadedBytes > 0L) {
            if (shareRatio(downloadedBytes, uploadedBytes) >= limits.ratioLimit) return true
        }
        if (limits.seedTimeLimitMinutes > 0) {
            val elapsedMillis = (nowEpochMillis - seedingSinceEpochMillis).coerceAtLeast(0L)
            if (elapsedMillis >= limits.seedTimeLimitMinutes * MILLIS_PER_MINUTE) return true
        }
        return false
    }

    /** Why seeding stopped, for saying so in the list rather than just going quiet. */
    fun stopReason(
        downloadedBytes: Long,
        uploadedBytes: Long,
        seedingSinceEpochMillis: Long,
        nowEpochMillis: Long,
        limits: ShareLimits
    ): String? {
        if (!shouldStopSeeding(downloadedBytes, uploadedBytes, seedingSinceEpochMillis, nowEpochMillis, limits)) {
            return null
        }
        if (limits.stopWhenComplete) {
            return "Stopped seeding as soon as the download finished (asked for stop at 100%)."
        }
        if (limits.ratioLimit > 0.0 && downloadedBytes > 0L &&
            shareRatio(downloadedBytes, uploadedBytes) >= limits.ratioLimit
        ) {
            val shown = String.format("%.2f", shareRatio(downloadedBytes, uploadedBytes))
            return "Stopped seeding at $shown (asked for ${trimNumber(limits.ratioLimit)})."
        }
        val minutes = (nowEpochMillis - seedingSinceEpochMillis).coerceAtLeast(0L) / MILLIS_PER_MINUTE
        return "Stopped seeding after $minutes min (asked for ${limits.seedTimeLimitMinutes})."
    }

    /** One decimal place, without a trailing .0 on a whole number. */
    private fun trimNumber(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else String.format("%.1f", value)

    private const val MILLIS_PER_MINUTE = 60_000L
}

/**
 * Whether a download is already in the list.
 *
 * Both apps this was modelled on suppress a duplicate rather than adding it - qBittorrent
 * reports "already in queue" and AB Download Manager filters them - and neither is being
 * shy about it. Every other download manager having this does not make it a nicety: the
 * same torrent can be handed over three times in a row by a browser, a double-click and
 * an extension, and each copy then seeds separately. That is not a list that looks
 * untidy, it is three times the upload.
 *
 * Torrents are matched on their info hash where there is one, so the same torrent added
 * as a magnet and then as a .torrent file is recognised as the same thing. Everything
 * else is matched on its normalised link.
 */
object DuplicateRules {

    private val infoHash = Regex("""xt=urn:btih:([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)

    /**
     * The torrent's info hash from whatever it was added as.
     *
     * Read from a magnet's `xt` parameter, from a hash already learned, or from the name
     * of a .torrent file - which is conventionally the hash plus `.torrent`. Null when
     * none of those apply, in which case nothing is compared rather than something being
     * guessed at.
     *
     * Written with explicit returns rather than one elvis chain: the chain version
     * returned null for a perfectly good `C:\Downloads\<hash>.torrent`, and a silent
     * null here means every duplicate check quietly passes.
     */
    fun infoHashOf(item: DownloadItem): String? {
        item.torrentInfoHash?.trim()?.takeIf { it.isNotBlank() }?.let { return it.lowercase() }

        val fromMagnet = infoHash.find(item.url)?.groupValues?.getOrNull(1)
        if (!fromMagnet.isNullOrBlank()) return fromMagnet.lowercase()

        val fileName = item.torrentFilePath?.replace('/', '\\')?.substringAfterLast('\\')
        if (fileName.isNullOrBlank()) return null
        val candidate = fileName.removeSuffix(".torrent").lowercase()
        if (candidate.length < MIN_HASH_LENGTH) return null
        if (!candidate.all { it.isLetterOrDigit() }) return null
        return candidate
    }

    /** A BitTorrent v1 info hash is 40 hex characters; v2 is 64. */
    private const val MIN_HASH_LENGTH = 32

    /**
     * Whether [candidate] is the same download as something already in [existing].
     *
     * A finished copy still counts as already having it: queueing the same file again
     * means downloading it again, which is what the person was trying to avoid.
     */
    fun isDuplicate(existing: List<DownloadItem>, candidate: DownloadItem): Boolean {
        if (existing.isEmpty()) return false

        if (candidate.source == DownloadSource.TORRENT) {
            val candidateHash = infoHashOf(candidate) ?: return false
            // Only compare against other torrents. An HTTP link to the same .torrent is
            // the same torrent, but a magnet and a web page are not comparable.
            return existing.any { other ->
                other.id != candidate.id &&
                    other.source == DownloadSource.TORRENT &&
                    infoHashOf(other)?.equals(candidateHash, ignoreCase = true) == true
            }
        }

        val candidateLink = normaliseLink(candidate.url)
        if (candidateLink.isBlank()) return false
        return existing.any { other ->
            other.id != candidate.id &&
                other.source != DownloadSource.TORRENT &&
                normaliseLink(other.url).equals(candidateLink, ignoreCase = true)
        }
    }

    /** Links compared by what they name, not by how they are spelled. */
    private fun normaliseLink(url: String): String =
        url.trim().lowercase().substringBefore('#')
}
