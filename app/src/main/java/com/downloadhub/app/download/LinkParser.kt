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

    /**
     * File providers are inconsistent about the MIME type they report for
     * torrents, so accept either a well-known bittorrent type or a .torrent name.
     */
    fun looksLikeTorrent(name: String?, mimeType: String? = null): Boolean {
        val mime = mimeType?.lowercase(Locale.US).orEmpty()
        if (mime.contains("torrent") || mime.contains("bittorrent")) return true
        val candidate = name?.lowercase(Locale.US).orEmpty()
        return candidate.endsWith(".torrent") ||
            candidate.substringAfterLast('/', "").endsWith(".torrent")
    }

    private val programExtensions = setOf(
        "exe", "msi", "msix", "apk", "xapk", "apks", "deb", "rpm", "appimage", "pkg",
        "dmg", "jar", "war", "run", "app", "msu", "snap", "flatpak", "msc", "bat", "cmd", "sh"
    )

    private val compressedExtensions = setOf(
        "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "lz", "lzma",
        "iso", "cab", "arj", "sit", "jar", "apkm"
    )

    private val documentExtensions = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "epub",
        "odt", "ods", "odp", "rtf", "md", "json", "xml", "html", "htm"
    )

    private val audioExtensions = setOf(
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "aiff", "m4b"
    )

    private val videoExtensions = setOf(
        "mp4", "mkv", "webm", "mov", "avi", "m4v", "flv", "wmv", "mpg", "mpeg", "ts", "3gp"
    )

    private val imageExtensions = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tiff", "svg", "ico"
    )

    fun categoryFor(
        source: DownloadSource,
        fileName: String,
        mimeType: String? = null
    ): DownloadCategory {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        val mime = mimeType?.lowercase(Locale.US).orEmpty()
        return when {
            extension in audioExtensions || mime.startsWith("audio/") ->
                DownloadCategory.AUDIO
            extension in videoExtensions || mime.startsWith("video/") ->
                DownloadCategory.VIDEO
            extension in imageExtensions || mime.startsWith("image/") ->
                DownloadCategory.IMAGE
            extension in programExtensions || mime.contains("android.package") ||
                mime.contains("msdownload") || mime.contains("x-msdownload") ||
                mime.contains("x-executable") || mime.contains("x-msdos-program") ->
                DownloadCategory.PROGRAM
            extension in compressedExtensions || mime.contains("zip") || mime.contains("compressed") ||
                mime.contains("x-tar") || mime.contains("x-7z") || mime.contains("x-rar") ->
                DownloadCategory.COMPRESSED
            extension in documentExtensions || mime.startsWith("text/") || mime.contains("pdf") ||
                mime.contains("officedocument") || mime.contains("msword") || mime.contains("vnd.ms-") ->
                DownloadCategory.DOCUMENT
            source == DownloadSource.YOUTUBE -> DownloadCategory.VIDEO
            source == DownloadSource.TORRENT -> DownloadCategory.OTHER
            mime.contains("bittorrent") || extension == "torrent" -> DownloadCategory.FILE
            else -> DownloadCategory.FILE
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
