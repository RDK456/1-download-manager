package com.downloadhub.app.ui.expressive.model

import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.data.model.DownloadSource
import com.downloadhub.app.download.LinkParser

/** What the add sheet makes of what was typed or pasted, before anything is queued. */
sealed interface LinkCheck {
    data object Empty : LinkCheck

    /** Not something that can be downloaded. [reason] says what a valid link looks like. */
    data class Invalid(val reason: String) : LinkCheck

    /** Already in the list. [percent] is how far it got, so "Open it" is an informed choice. */
    data class Duplicate(val existingId: String, val name: String, val percent: Int) : LinkCheck

    /** Several links, or a numbered range. [capped] means the engine's limit cut it short. */
    data class Batch(val count: Int, val capped: Boolean) : LinkCheck

    data object YouTube : LinkCheck
    data class Torrent(val isMagnet: Boolean) : LinkCheck
    data object Http : LinkCheck

    val canSubmit: Boolean
        get() = when (this) {
            is Empty, is Invalid, is YouTube -> false
            else -> true
        }
}

private fun sameLink(a: String, b: String): Boolean {
    fun norm(s: String) = s.trim().substringBefore('#').trimEnd('/').lowercase()
    return norm(a) == norm(b)
}

/**
 * Looks at the field. [batchSize] is how many links the batch expander found (1 for a
 * single link); [batchMax] is the most it will take. Duplicates are only reported for a
 * single link, since a batch is judged one by one when it is queued.
 */
fun checkLink(raw: String, existing: List<DownloadEntity>, batchSize: Int, batchMax: Int): LinkCheck {
    if (raw.isBlank()) return LinkCheck.Empty
    if (batchSize > 1) return LinkCheck.Batch(batchSize, batchSize >= batchMax)
    val link = LinkParser.extractFirstLink(raw) ?: raw.trim()
    val lower = link.lowercase()
    val valid = lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("magnet:")
    if (!valid) {
        val reason = when {
            lower.startsWith("www.") || lower.matches(Regex("""[a-z0-9-]+(\.[a-z0-9-]+)+(/.*)?""")) ->
                "Add https:// to the start of the address."
            else -> "That does not look like a link. Links start with http://, https:// or magnet:"
        }
        return LinkCheck.Invalid(reason)
    }
    existing.firstOrNull { sameLink(it.url, link) }?.let {
        val percent = if (it.totalBytes > 0) (it.bytesDownloaded * 100 / it.totalBytes).toInt().coerceIn(0, 100) else it.progressPercent
        return LinkCheck.Duplicate(it.id, it.fileName, percent)
    }
    return when (LinkParser.sourceFor(link)) {
        DownloadSource.YOUTUBE -> LinkCheck.YouTube
        DownloadSource.TORRENT -> LinkCheck.Torrent(isMagnet = lower.startsWith("magnet:"))
        DownloadSource.HTTP -> LinkCheck.Http
    }
}
