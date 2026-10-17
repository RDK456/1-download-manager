package com.downloadhub.app.ui.expressive.model

private val SECRET_PARAM = Regex(
    "[?&;](token|access_token|key|apikey|api_key|sig|signature|auth|password|passwd|pwd|secret|session|sessionid|credential|x-amz-[a-z-]+|x-goog-[a-z-]+)=",
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
    return !SECRET_PARAM.containsMatchIn(link)
}
