package com.downloadhub.core

import java.net.URI
import java.util.Locale

/** What kind of thing was found on a page. */
enum class MediaKind { VIDEO, AUDIO, FILE, PLAYER }

data class MediaCandidate(
    val url: String,
    val label: String,
    val kind: MediaKind,
    val extension: String,
    val sizeBytes: Long? = null
)

/**
 * Pulls downloadable media out of an HTML page.
 *
 * Kept free of network and Android types so the extraction rules can be unit
 * tested directly. Handles `<video>`/`<audio>`/`<source>`, Open Graph and Twitter
 * player tags, JSON-LD `VideoObject`, anchor links to media files, HLS
 * playlists, and embedded YouTube/Vimeo players.
 */
object PageMediaParser {

    private val mediaTag = Regex("""<(video|audio|source)\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val anchorTag = Regex(
        """<a\b[^>]*href\s*=\s*["']([^"']+)["'][^>]*>([\s\S]*?)</a>""",
        RegexOption.IGNORE_CASE
    )
    private val metaTag = Regex("""<meta\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val linkTag = Regex("""<link\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val frameTag = Regex("""<iframe\b[^>]*src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val jsonLdBlock = Regex(
        """<script[^>]*type\s*=\s*["']application/ld\+json["'][^>]*>([\s\S]*?)</script>""",
        RegexOption.IGNORE_CASE
    )
    private val jsonUrlField = Regex(
        """"(contentUrl|embedUrl|audio|contentUrl)"\s*:\s*(?:\{\s*)?"([^"]+)"""",
        RegexOption.IGNORE_CASE
    )
    private val tagText = Regex("<[^>]+>")

    private val videoExtensions = setOf(
        "mp4", "m4v", "webm", "mkv", "mov", "avi", "flv", "ts", "3gp", "m3u8", "mpd"
    )
    private val audioExtensions = setOf(
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "wma", "m3u8"
    )
    private val fileExtensions = setOf(
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "pdf", "apk", "exe", "msi", "epub", "jpg",
        "jpeg", "png", "gif", "webp", "srt", "vtt", "json", "xml", "csv", "txt", "iso"
    )

    /** Extensions worth offering even without knowing the MIME type. */
    val knownExtensions: Set<String> = videoExtensions + audioExtensions + fileExtensions

    fun parse(html: String, pageUrl: String, limit: Int = DEFAULT_LIMIT): List<MediaCandidate> {
        if (html.isBlank()) return emptyList()
        val found = LinkedHashMap<String, MediaCandidate>()

        fun offer(rawUrl: String, label: String?, kindHint: MediaKind? = null) {
            val absolute = resolve(pageUrl, rawUrl) ?: return
            if (found.containsKey(absolute)) return
            val extension = extensionOf(absolute)
            val kind = kindHint ?: classify(extension, absolute)
            if (kind == null) return
            val candidate = MediaCandidate(
                url = absolute,
                label = label?.takeIf { it.isNotBlank() } ?: defaultLabel(absolute, extension),
                kind = kind,
                extension = extension
            )
            if (found.size < limit) found[absolute] = candidate
        }

        // <video>/<audio>/<source> tags.
        mediaTag.findAll(html).forEach { match ->
            val tag = match.groupValues[1].lowercase(Locale.US)
            val attributes = match.groupValues[2]
            val src = attribute(attributes, "src") ?: return@forEach
            val type = attribute(attributes, "type")?.lowercase(Locale.US).orEmpty()
            val hint = when {
                type.startsWith("video/") -> MediaKind.VIDEO
                type.startsWith("audio/") -> MediaKind.AUDIO
                tag == "video" -> MediaKind.VIDEO
                tag == "audio" -> MediaKind.AUDIO
                else -> null
            }
            offer(src, null, hint)
        }

        // Open Graph / Twitter player metadata.
        metaTag.findAll(html).forEach { match ->
            val attributes = match.groupValues[1]
            val name = (attribute(attributes, "property") ?: attribute(attributes, "name"))
                ?.lowercase(Locale.US) ?: return@forEach
            if (!name.startsWith("og:") && !name.startsWith("twitter:")) return@forEach
            if (!name.contains("video") && !name.contains("audio") && name != "twitter:player:stream") {
                return@forEach
            }
            val content = attribute(attributes, "content") ?: return@forEach
            offer(content, null, if (name.contains("audio")) MediaKind.AUDIO else MediaKind.VIDEO)
        }

        // Alternate link for HLS/DASH playlists.
        linkTag.findAll(html).forEach { match ->
            val attributes = match.groupValues[1]
            val type = attribute(attributes, "type")?.lowercase(Locale.US).orEmpty()
            if (!type.contains("mpegurl") && !type.contains("dash+xml")) return@forEach
            val href = attribute(attributes, "href") ?: return@forEach
            offer(href, null, MediaKind.VIDEO)
        }

        // Embedded players.
        frameTag.findAll(html).forEach { match ->
            val src = resolve(pageUrl, match.groupValues[1]) ?: return@forEach
            val host = hostOf(src)
            if (host != null && (host.contains("youtube.com") || host.contains("youtu.be") || host.contains("vimeo.com"))) {
                if (found.size < limit) {
                    found[src] = MediaCandidate(
                        url = src,
                        label = "Embedded video",
                        kind = MediaKind.PLAYER,
                        extension = ""
                    )
                }
            }
        }

        // JSON-LD structured data.
        jsonLdBlock.findAll(html).forEach { block ->
            jsonUrlField.findAll(block.groupValues[1]).forEach { field ->
                val key = field.groupValues[1].lowercase(Locale.US)
                val value = field.groupValues[2]
                if (key == "embedurl") {
                    val absolute = resolve(pageUrl, value) ?: return@forEach
                    val host = hostOf(absolute)
                    if (host != null && (host.contains("youtube.com") || host.contains("youtu.be"))) {
                        if (found.size < limit) {
                            found[absolute] = MediaCandidate(
                                url = absolute,
                                label = "Embedded video",
                                kind = MediaKind.PLAYER,
                                extension = ""
                            )
                        }
                    }
                } else {
                    offer(value, null, if (key == "audio") MediaKind.AUDIO else null)
                }
            }
        }

        // Anchor links that point straight at a downloadable file.
        anchorTag.findAll(html).forEach { match ->
            val href = match.groupValues[1]
            val extension = extensionOf(href.substringBefore('?').substringBefore('#'))
            if (extension !in knownExtensions) return@forEach
            val label = tagText.replace(match.groupValues[2], " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            offer(href, label)
        }

        return found.values.toList()
    }

    /** True when the URL already points at a media file, so scanning is pointless. */
    fun looksLikeDirectMedia(url: String): Boolean {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        val extension = path.substringAfterLast('.', "").lowercase(Locale.US)
        return extension in knownExtensions
    }

    private fun classify(extension: String, url: String): MediaKind? = when {
        extension == "m3u8" || extension == "mpd" -> MediaKind.VIDEO
        extension in videoExtensions -> MediaKind.VIDEO
        extension in audioExtensions -> MediaKind.AUDIO
        extension in fileExtensions -> MediaKind.FILE
        url.contains(".m3u8", ignoreCase = true) -> MediaKind.VIDEO
        else -> null
    }

    private fun resolve(base: String, raw: String): String? {
        val trimmed = raw.trim().removePrefix("\uFEFF")
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase(Locale.US)
        if (lower.startsWith("javascript:") || lower.startsWith("data:") || lower.startsWith("about:")) {
            return null
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) return trimmed
        if (lower.startsWith("//")) {
            val scheme = runCatching { URI(base).scheme }.getOrNull() ?: "https"
            return "$scheme:$trimmed"
        }
        if (lower.startsWith("#") || lower.startsWith("mailto:")) return null
        return runCatching { URI(base).resolve(trimmed).toString() }.getOrNull()
    }

    private fun attribute(attributes: String, name: String): String? =
        Regex("""\b$name\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(attributes)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun extensionOf(url: String): String {
        val path = runCatching { URI(url).path }.getOrNull() ?: url.substringBefore('?')
        return path.substringAfterLast('.', "").lowercase(Locale.US).takeIf { it.length in 1..5 } ?: ""
    }

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host?.lowercase(Locale.US) }.getOrNull()

    private fun defaultLabel(url: String, extension: String): String {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        val name = path.substringAfterLast('/').takeIf { it.isNotBlank() && it.contains('.') }
        if (name != null) return name
        val host = hostOf(url)
        return when {
            extension.isNotEmpty() && host != null -> "$host.$extension"
            host != null -> host
            else -> "Media"
        }
    }

    private const val DEFAULT_LIMIT = 40
}
