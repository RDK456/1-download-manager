package com.downloadhub.app.ui.expressive.model

private val SECRET_PARAM = Regex(
    "[?&;](token|access_token|key|apikey|api_key|sig|signature|auth|password|passwd|pwd|secret|session|sessionid|credential|passkey|torrent_pass|authkey|uid|x-amz-[a-z-]+|x-goog-[a-z-]+)=",
    RegexOption.IGNORE_CASE
)

/**
 * Whether a half-typed link may be kept in the saved-state record. Links with a login in them or
 * a signed or token query are not kept: that record is written out of the app's own process, and
 * a link that works only for a few minutes is not worth restoring anyway.
 */
fun linkSafeToSave(link: String): Boolean {
    val afterScheme = link.substringAfter("://", "")
    if (afterScheme.substringBefore('/').contains('@')) return false
    // A magnet carries its trackers percent-encoded, so a passkey inside one only shows once decoded.
    val plain = runCatching { java.net.URLDecoder.decode(link, "UTF-8") }.getOrDefault(link)
    return !SECRET_PARAM.containsMatchIn(link) && !SECRET_PARAM.containsMatchIn(plain)
}
