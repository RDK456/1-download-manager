package com.downloadhub.app.ui.expressive.model

/**
 * A failure in words a person can act on. [short] fits on a row, [detail] goes in the
 * details sheet with what to try. The raw message from the engine is kept as [raw] for the
 * sheet's "technical details" line, because support questions start there.
 */
data class FriendlyError(val short: String, val detail: String, val raw: String?)

private val HTTP_CODE = Regex("""HTTP\s*(\d{3})""", RegexOption.IGNORE_CASE)

fun friendlyError(message: String?): FriendlyError {
    val raw = message?.trim()?.takeIf { it.isNotEmpty() }
    if (raw == null) return FriendlyError("Needs attention", "The download could not be completed. Try again.", null)
    val lower = raw.lowercase()
    val code = HTTP_CODE.find(raw)?.groupValues?.get(1)?.toIntOrNull()
    return when {
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
        else -> FriendlyError(raw.take(60), raw, raw)
    }
}
