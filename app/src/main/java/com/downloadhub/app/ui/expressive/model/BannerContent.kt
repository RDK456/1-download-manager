package com.downloadhub.app.ui.expressive.model

/** What a notice says and offers. Plain strings, so the wording is reviewable in one place. */
data class BannerContent(
    val title: String,
    val body: String,
    /** Buttons, in order. The last one marked [BannerAction.dismisses] clears the notice. */
    val actions: List<BannerAction> = emptyList()
)

data class BannerAction(val label: String, val dismisses: Boolean = false)

fun BannerKind.content(): BannerContent = when (this) {
    BannerKind.STORAGE -> BannerContent(
        "Storage is almost full",
        "Downloads may fail until you free some space or pick another folder. Nothing already saved is touched.",
        listOf(BannerAction("Choose folder"))
    )
    BannerKind.NETWORK -> BannerContent(
        "No connection",
        "Downloads will carry on by themselves when you are back online."
    )
    BannerKind.NOTIFICATIONS -> BannerContent(
        "Notifications are off",
        "Downloads still run. You will not see progress or finished alerts.",
        listOf(BannerAction("Turn on"), BannerAction("Not now", dismisses = true))
    )
    BannerKind.BATTERY -> BannerContent(
        "Battery saver may stop downloads",
        "Allow this app to run in the background so large files can finish.",
        listOf(BannerAction("Allow"), BannerAction("Not now", dismisses = true))
    )
    BannerKind.UPDATE -> BannerContent(
        "Update available",
        "A new version is ready to install.",
        listOf(BannerAction("View"), BannerAction("Later", dismisses = true))
    )
}
