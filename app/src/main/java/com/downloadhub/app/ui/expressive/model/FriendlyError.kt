package com.downloadhub.app.ui.expressive.model

/**
 * A failure in words a person can act on. [short] fits on a row, [detail] goes in the
 * details sheet with what to try. The raw message from the engine is kept as [raw] for the
 * sheet's "technical details" line, because support questions start there.
 */
data class FriendlyError(val short: String, val detail: String, val raw: String?)

private val HTTP_CODE = Regex("""HTTP(?:\s+Error)?\s*(\d{3})""", RegexOption.IGNORE_CASE)

fun friendlyError(message: String?): FriendlyError {
    val raw = message?.trim()?.takeIf { it.isNotEmpty() }
    if (raw == null) return FriendlyError("Needs attention", "The download could not be completed. Try again.", null)
    // yt-dlp prints warnings ahead of the reason; the reason is its last ERROR line.
    val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val reason = lines.lastOrNull { it.startsWith("ERROR:") }?.removePrefix("ERROR:")?.trim()
        ?: lines.filterNot { it.startsWith("WARNING:") }.joinToString(" ")
    val lower = reason.lowercase()
    val code = HTTP_CODE.find(reason)?.groupValues?.get(1)?.toIntOrNull()
    return when {
        "sign in to confirm" in lower -> FriendlyError(
            "Site wants a sign-in",
            "The site asked to check you are a person or signed in. Wait a while, or try another network.",
            raw
        )
        "private video" in lower || "video unavailable" in lower -> FriendlyError(
            "Video unavailable", "This video is private, removed or blocked where you are.", raw
        )
        // Nothing but warnings, or the site's page could not be read: usually an old yt-dlp.
        reason.isEmpty() || "unable to parse video information" in lower || "unable to extract" in lower -> FriendlyError(
            "Could not read the video",
            "The video tool could not read this page. Update it in Download settings, then retry.",
            raw
        )
        code == 401 || code == 403 -> FriendlyError(
            "Link refused ($code)",
            "The server would not hand the file over. The link may have expired, or it needs you to be signed in. Open the page again and copy a fresh link.",
            raw
        )
        code == 404 -> FriendlyError("File not found (404)", "The file is not at this address any more. Check the link.", raw)
        code == 410 -> FriendlyError("Link expired (410)", "This link has been taken down or has expired. Get a new one from the page it came from.", raw)
        code == 416 -> FriendlyError("Cannot continue (416)", "The server could not carry on from where it stopped. Retry starts the file again from the beginning.", raw)
        code == 429 -> FriendlyError("Too many requests (429)", "The server asked for a pause. Wait a few minutes, then retry.", raw)
        code != null && code in 500..599 -> FriendlyError("Server problem ($code)", "The server had a problem. This is not your phone. Try again later.", raw)
        "no space left" in lower || "enospc" in lower -> FriendlyError(
            "Not enough space",
            "There is no room left on this phone. Free some space or choose another folder, then retry. What was already saved is kept.",
            raw
        )
        "unable to resolve host" in lower || "unknownhost" in lower -> FriendlyError(
            "Server not found",
            "Could not find the server. Check your connection and that the address is spelled right.",
            raw
        )
        "timeout" in lower || "timed out" in lower -> FriendlyError("Server too slow", "The server took too long to answer. Retry, or try again later.", raw)
        "connection reset" in lower || "connection abort" in lower || "unexpected end of stream" in lower || "broken pipe" in lower ->
            FriendlyError("Connection dropped", "The connection was cut part way. Retry carries on from where it stopped when the server allows it.", raw)
        "ssl" in lower || "certificate" in lower || "handshake" in lower -> FriendlyError(
            "Secure connection failed",
            "The secure connection could not be set up. The site's certificate may be wrong or your date and time may be off.",
            raw
        )
        else -> FriendlyError(reason.take(60), reason, raw)
    }
}
