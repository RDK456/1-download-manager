package com.downloadhub.app.update

/** One downloadable file attached to a GitHub release. */
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long,
    val contentType: String? = null
)

/** A published GitHub release, normalised for the updater UI. */
data class ReleaseInfo(
    val tag: String,
    val name: String,
    val notes: String,
    val pageUrl: String,
    val publishedAt: String,
    val assets: List<ReleaseAsset>,
    val prerelease: Boolean = false
) {
    /** Tag without the leading "v", e.g. `v1.2.0` -> `1.2.0`. */
    val version: String
        get() = tag.trim().removePrefix("v").removePrefix("V").ifBlank { name.trim() }

    val displayName: String
        get() = name.ifBlank { "Version $version" }

    /** Prefers an APK asset; falls back to the first asset so links still work. */
    fun installAsset(): ReleaseAsset? =
        assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) } ?: assets.firstOrNull()

    fun isNewerThan(currentVersion: String): Boolean =
        compareVersions(version, currentVersion) > 0

    /** True when the user already skipped this version or a newer one. */
    fun isSkipped(skippedVersion: String?): Boolean {
        if (skippedVersion.isNullOrBlank()) return false
        return compareVersions(version, skippedVersion) <= 0
    }
}

/**
 * Compares dotted versions numerically, so `1.10.0` correctly beats `1.9.9`.
 * Non-numeric suffixes such as `-rc1` or `+build` are ignored.
 */
fun compareVersions(left: String, right: String): Int {
    val leftParts = versionParts(left)
    val rightParts = versionParts(right)
    val size = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until size) {
        val a = leftParts.getOrElse(index) { 0 }
        val b = rightParts.getOrElse(index) { 0 }
        if (a != b) return a.compareTo(b)
    }
    return 0
}

private fun versionParts(value: String): List<Int> = value
    .trim()
    .removePrefix("v")
    .removePrefix("V")
    .substringBefore('+')
    .substringBefore('-')
    .split(Regex("[^0-9]+"))
    .filter { it.isNotBlank() }
    .map { it.toIntOrNull() ?: 0 }
