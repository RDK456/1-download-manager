package com.downloadhub.app.download

import com.downloadhub.app.data.model.DownloadCategory
import com.downloadhub.app.data.model.DownloadSource
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

object LinkParser {
    private val linkPattern = Regex("""(?i)(?:https?://|magnet:\?)[^\s<>"']+""")
    private val contentDispositionName = Regex(
        """filename\*\s*=\s*UTF-8''([^;]+)|filename\s*=\s*["']?([^"';]+)""",
        RegexOption.IGNORE_CASE
    )

    fun extractFirstLink(text: String): String? =
        linkPattern.find(text)?.value?.trimEnd('.', ',', ')', ']', '}')

    fun sourceFor(link: String): DownloadSource {
        val normalized = link.trim().lowercase(Locale.US)
        return when {
            normalized.startsWith("magnet:") -> DownloadSource.TORRENT
            isYouTube(normalized) -> DownloadSource.YOUTUBE
            normalized.substringBefore('?').endsWith(".torrent", ignoreCase = true) ->
                DownloadSource.TORRENT
            else -> DownloadSource.HTTP
        }
    }

    fun isYouTube(link: String): Boolean {
        val host = runCatching { URI(link.trim()).host?.lowercase(Locale.US) }.getOrNull()
            ?: return link.trim().lowercase(Locale.US).startsWith("youtu.be/")
        return host == "youtu.be" ||
            host == "youtube.com" ||
            host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com"
    }

    fun categoryFor(
        source: DownloadSource,
        fileName: String,
        mimeType: String? = null
    ): DownloadCategory {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        val mime = mimeType?.lowercase(Locale.US).orEmpty()
        return when {
            source == DownloadSource.YOUTUBE && extension == "mp3" || mime.contains("audio") ->
                DownloadCategory.AUDIO
            extension in setOf("mp4", "mkv", "webm", "mov", "avi", "m4v") || mime.startsWith("video/") ->
                DownloadCategory.VIDEO
            extension in setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus") ->
                DownloadCategory.AUDIO
            extension in setOf("jpg", "jpeg", "png", "gif", "webp", "heic") || mime.startsWith("image/") ->
                DownloadCategory.IMAGE
            extension in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz") ->
                DownloadCategory.ARCHIVE
            extension in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "epub") ||
                mime.startsWith("text/") || mime.contains("pdf") -> DownloadCategory.DOCUMENT
            source == DownloadSource.TORRENT -> DownloadCategory.ARCHIVE
            else -> DownloadCategory.OTHER
        }
    }

    fun fileNameFrom(
        url: String,
        contentDisposition: String? = null,
        mimeType: String? = null
    ): String {
        contentDispositionName.find(contentDisposition.orEmpty())?.let { match ->
            val encoded = match.groupValues.getOrNull(1)
            val plain = match.groupValues.getOrNull(2)
            val candidate = if (encoded != null) {
                runCatching { URLDecoder.decode(encoded, StandardCharsets.UTF_8.name()) }.getOrNull()
            } else {
                plain
            }
            if (!candidate.isNullOrBlank()) return sanitizeFileName(candidate)
        }

        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        val pathName = path.substringAfterLast('/')
        if (pathName.isNotBlank()) {
            val decoded = runCatching {
                URLDecoder.decode(pathName, StandardCharsets.UTF_8.name())
            }.getOrDefault(pathName)
            return sanitizeFileName(decoded)
        }

        val extension = extensionForMime(mimeType)
        return if (extension.isBlank()) "download" else "download.$extension"
    }

    fun sanitizeFileName(value: String, fallback: String = "download"): String {
        val cleaned = value
            .replace(Regex("[\\x00-\\x1F\\x7F]"), "")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
        return cleaned.take(180).ifBlank { fallback }
    }

    private fun extensionForMime(mimeType: String?): String = when {
        mimeType == null -> ""
        mimeType.contains("zip") -> "zip"
        mimeType.contains("pdf") -> "pdf"
        mimeType.contains("json") -> "json"
        mimeType.contains("image") -> "jpg"
        mimeType.contains("audio") -> "m4a"
        mimeType.contains("video") -> "mp4"
        else -> ""
    }
}
