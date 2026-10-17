package com.downloadhub.app.ui.expressive.model

/**
 * Notices that can sit at the top of the Downloads list. Declaration order is priority: the
 * first one that applies is the one shown. Storage comes first because it stops the whole
 * queue; the update notice comes last because it never blocks anything.
 */
enum class BannerKind { STORAGE, NETWORK, NOTIFICATIONS, BATTERY, UPDATE }

/** What to draw: [shown] in priority order, and how many more are folded behind a link. */
data class BannerPlan(val shown: List<BannerKind>, val hiddenCount: Int)

/**
 * One banner at a time, so a bad day does not stack four cards above the list. The rest
 * fold behind "N more notices" until the user opens them. Dismissed ones are gone for the
 * session.
 */
fun planBanners(
    active: Set<BannerKind>,
    dismissed: Set<BannerKind> = emptySet(),
    expanded: Boolean = false
): BannerPlan {
    val queue = BannerKind.entries.filter { it in active && it !in dismissed }
    return if (expanded || queue.size <= 1) BannerPlan(queue, 0)
    else BannerPlan(queue.take(1), queue.size - 1)
}
