package com.downloadhub.app.ui.expressive.model

private const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * How long "Not now" lasts for each notice. Permission and battery nudges stay away for a
 * month, the update notice for a week. Notices about something happening right now (no
 * connection, low storage) have no dismiss button and so no entry here.
 */
fun BannerKind.snoozeMillis(): Long? = when (this) {
    BannerKind.BATTERY, BannerKind.NOTIFICATIONS -> 30 * DAY_MS
    BannerKind.UPDATE -> 7 * DAY_MS
    BannerKind.STORAGE, BannerKind.NETWORK -> null
}

/**
 * When each notice was last dismissed. Immutable; the caller keeps it in state and writes
 * the new time to storage so a dismissal survives leaving the tab and restarting the app.
 */
data class DismissalLedger(private val at: Map<BannerKind, Long> = emptyMap()) {
    /** The notices still snoozed at time [now]. A clock that ran backwards counts as expired. */
    fun dismissed(now: Long): Set<BannerKind> = at.filter { (kind, t) ->
        val span = kind.snoozeMillis()
        span != null && t in 1..now && now - t < span
    }.keys

    fun dismiss(kind: BannerKind, now: Long): DismissalLedger = DismissalLedger(at + (kind to now))

    companion object {
        /** Builds a ledger from stored times (0 or missing means never dismissed). */
        fun from(read: (BannerKind) -> Long): DismissalLedger =
            DismissalLedger(BannerKind.entries.associateWith(read).filterValues { it > 0 })
    }
}
