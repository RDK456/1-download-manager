package com.downloadhub.core

import java.net.URI

/**
 * A new address for a download whose link expired, taken from the page it came from.
 *
 * Signed links keep the file's path and change the query, so only a link with the same file
 * name counts. Names every file shares ("download", "get.php") match nothing: resuming one
 * file onto another is worse than asking for a fresh link.
 */
object LinkRefresh {
    private val EXPIRED = Regex("""HTTP (403|404|410)""")
    private val SCRIPTS = setOf("php", "asp", "aspx", "jsp", "cgi", "ashx", "html", "htm")

    fun looksExpired(error: String?): Boolean = error != null && EXPIRED.containsMatchIn(error)

    /**
     * The link on the page standing for [oldUrl]. With [carriesSecrets] (cookies, a login,
     * extra headers) only the old host will do, so they are never sent anywhere new.
     */
    fun pick(oldUrl: String, candidates: List<String>, carriesSecrets: Boolean): String? {
        val name = fileNameOf(oldUrl) ?: return null
        val host = hostOf(oldUrl) ?: return null
        val same = candidates.distinct().filter { it != oldUrl && fileNameOf(it) == name }
        val onHost = same.filter { hostOf(it) == host }
        if (onHost.isNotEmpty()) return onHost.singleOrNull()
        return same.singleOrNull()?.takeIf { !carriesSecrets }
    }

    /** Reads [referer] and picks from what it links to; null when there is nothing safe to use. */
    suspend fun find(oldUrl: String, referer: String?, carriesSecrets: Boolean): String? {
        if (referer.isNullOrBlank() || referer == oldUrl) return null
        val found = PageScanner().scan(referer) as? PageScanState.Found ?: return null
        return pick(oldUrl, found.items.map { it.url }, carriesSecrets)
    }

    private fun fileNameOf(url: String): String? {
        val name = runCatching { URI(url).path }.getOrNull()?.substringAfterLast('/').orEmpty()
        val extension = name.substringAfterLast('.', "").lowercase()
        return name.takeIf { extension.isNotEmpty() && extension !in SCRIPTS }
    }

    private fun hostOf(url: String): String? = runCatching { URI(url).host?.lowercase() }.getOrNull()
}
