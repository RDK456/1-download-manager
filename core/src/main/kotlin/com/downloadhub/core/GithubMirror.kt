package com.downloadhub.core

import java.net.URI

/**
 * Sends a GitHub file download through a mirror the user chose.
 *
 * Mirrors of this kind take the whole GitHub address after their own prefix. There is no
 * built-in default: a third-party mirror serves whatever bytes it likes, so using one has
 * to be the user's own decision, and the real address stays as the fallback.
 */
object GithubMirror {
    /** The mirrored address, or null when the mirror is off or [url] is not a GitHub file. */
    fun rewrite(url: String, prefix: String): String? {
        val base = prefix.trim()
        if (!base.startsWith("https://", ignoreCase = true)) return null
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        if (!"https".equals(uri.scheme, ignoreCase = true) || uri.userInfo != null) return null
        // A signed query is a credential; it is never handed to a third party.
        if (uri.rawQuery != null) return null
        val path = uri.path.orEmpty()
        val isFile = when (uri.host?.lowercase()) {
            "github.com" -> "/releases/download/" in path || "/releases/latest/download/" in path || "/archive/" in path
            "raw.githubusercontent.com" -> path.count { it == '/' } >= 4
            else -> false
        }
        return if (isFile) base.trimEnd('/') + "/" + url.trim() else null
    }
}
