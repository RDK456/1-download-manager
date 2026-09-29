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
    val seedTimeLimitMinutes: Int = 0
) {
    val enabled: Boolean get() = ratioLimit > 0.0 || seedTimeLimitMinutes > 0
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
