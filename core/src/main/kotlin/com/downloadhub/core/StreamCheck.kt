package com.downloadhub.core

/** Looks at a stream playlist before it is downloaded. */
object StreamCheck {
    private val drm = Regex("""#EXT-X-(?:SESSION-)?KEY:[^\n]*(?:METHOD=SAMPLE-AES|URI="skd:)""", RegexOption.IGNORE_CASE)

    /**
     * True for DRM-protected HLS (FairPlay / SAMPLE-AES), which this app does not download.
     * Plain AES-128 is ordinary segment encryption with a fetchable key and is not refused.
     */
    fun isDrmProtectedHls(playlist: String): Boolean = drm.containsMatchIn(playlist)
}
