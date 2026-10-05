package com.downloadhub.core

import java.io.File
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
        return matchesHost(host, "youtu.be") || matchesHost(host, "youtube.com") ||
            matchesHost(host, "youtube-nocookie.com")
    }

    /**
     * Does this host *are* that one, or a subdomain of it?
     *
     * Comparing the host exactly misses `www.`, which is how `youtube-nocookie.com` was
     * being missed and every embed URL came through as ordinary HTTP. Comparing only by
     * suffix is not enough either: `notyoutube.com` ends with the same letters but is
     * somebody else's domain.
     */
    private fun matchesHost(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")

    /**
     * Can this link actually be downloaded?
     *
     * http and https, a magnet, or a `.torrent` that is on disk. Anything else - a bare
     * file name, a title, a sentence someone pasted - has nothing behind it, and queued
     * it produces a row that can only fail. Catching that at the point of adding is the
     * difference between "that is not a link" and a download that sits in the list
     * reporting `no protocol: <the text>`.
     *
     * [localFile] is supplied when the caller already has a file in hand, so a `.torrent`
     * does not have to be re-resolved from its path.
     */
    fun isFetchable(link: String, localFile: File? = null): Boolean {
        val trimmed = link.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("magnet:", ignoreCase = true)) return true
        if (trimmed.startsWith("http://", ignoreCase = true)) return true
        if (trimmed.startsWith("https://", ignoreCase = true)) return true
        // A file the caller already has in hand counts whatever the link text says, so a
        // `.torrent` handed over by a drop or a picker does not have to round-trip
        // through its own path.
        if (localFile?.isFile == true) return true
        if (sourceFor(trimmed) == DownloadSource.TORRENT) {
            return runCatching { File(trimmed).isFile }.getOrDefault(false)
        }
        return false
    }

    /**
     * The `dn` parameter of a magnet, which is the torrent's own name.
     *
     * Present on almost every real magnet and the only name a magnet carries, so without
     * it the list shows the raw link. A magnet has no path, so [fileNameFrom] cannot see
     * it and falls back to "download" - which is what the pre-download dialog's title and
     * the list would then say.
     */
    fun magnetDisplayName(magnet: String): String? =
        magnet.split('&')
            .firstOrNull { it.startsWith("dn=", ignoreCase = true) }
            ?.substring(3)
            ?.let { runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }.getOrNull() }
            ?.replace('+', ' ')
            ?.takeIf { it.isNotBlank() }

    /**
     * Is this link a video, or a site that serves video?
     *
     * This is what decides whether the quality and audio choices mean anything. They are
     * read by yt-dlp, so they were being offered for every link - a zip, a torrent, a
     * plain file - where selecting "1080p" does nothing at all and only makes the dialog
     * look like it is offering a choice it cannot honour.
     */
    fun isVideo(link: String): Boolean {
        val trimmed = link.trim()
        if (trimmed.isEmpty()) return false
        val normalized = trimmed.lowercase(Locale.US)
        if (sourceFor(normalized) == DownloadSource.TORRENT) return false
        if (isYouTube(normalized)) return true
        val host = runCatching { URI(trimmed).host?.lowercase(Locale.US) }.getOrNull()
        if (host != null && videoHosts.any { matchesHost(host, it) }) return true
        val path = normalized.substringBefore('?').substringBefore('#')
        return videoExtensions.contains(path.substringAfterLast('.').substringAfterLast('/'))
    }

    private val videoHosts = setOf(
        "vimeo.com", "dailymotion.com", "twitch.tv", "streamable.com", "rumble.com",
        "odysee.com", "bilibili.com", "nicovideo.jp", "coub.com", "peertube.fr",
        "facebook.com", "instagram.com", "tiktok.com", "t.me", "vk.com", "rutube.ru",
        "bbc.co.uk", "bbc.com", "cnn.com", "pbs.org", "arte.tv"
    )



    /**
     * Whether a link is plainly a download: a magnet, or an http(s) link whose path ends
     * in a file extension people download. Used to pick links up from the clipboard, where
     * an ordinary page link must be left alone.
     */
    fun looksLikeDownload(link: String): Boolean {
        val trimmed = link.trim()
        if (trimmed.startsWith("magnet:", ignoreCase = true)) return true
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) return false
        val path = runCatching { java.net.URI(trimmed).path }.getOrNull()?.lowercase(Locale.US) ?: return false
        val extension = path.substringAfterLast('/').substringAfterLast('.', "")
        return extension in DOWNLOAD_EXTENSIONS
    }

    private val DOWNLOAD_EXTENSIONS = setOf(
        "torrent", "zip", "rar", "7z", "tar", "gz", "xz", "bz2", "iso", "img", "dmg",
        "apk", "xapk", "exe", "msi", "deb", "rpm", "appimage",
        "mp4", "mkv", "webm", "mov", "avi", "m4v", "flv", "wmv", "ts",
        "mp3", "m4a", "flac", "wav", "opus", "aac", "ogg",
        "pdf", "epub", "cbz", "cbr"
    )

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
        "exe", "msi", "msix", "apk", "xapk", "apks", "deb", "rpm", "appimage", "pkg", "dmg",
        "jar", "war", "run", "app", "msu", "snap", "flatpak", "msc", "bat", "cmd", "sh",
        "elf", "so", "dll", "dylib", "pak", "nupkg", "crx", "xpi"
    )

    private val compressedExtensions = setOf(
        "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "lz", "lzma", "iso", "cab",
        "arj", "sit", "jar", "apkm", "txz", "tbz2", "z", "lha", "pak", "wim", "qcow2",
        "img", "vhd", "vhdx", "squashfs", "br"
    )

    private val documentExtensions = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "epub", "odt", "ods",
        "odp", "rtf", "md", "markdown", "json", "xml", "yml", "yaml", "html", "htm", "srt", "vtt",
        "ass", "ssa", "tex", "log", "db", "sqlite", "ics", "eml", "msg"
    )

    private val audioExtensions = setOf(
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "wma", "aiff", "aif", "m4b",
        "amr", "ac3", "dts", "mid", "midi", "ape", "wv", "alac", "caf", "mka"
    )

    private val videoExtensions = setOf(
        "mp4", "mkv", "webm", "mov", "avi", "m4v", "flv", "wmv", "mpg", "mpeg", "ts", "3gp",
        "3g2", "mts", "m2ts", "ogv", "vob", "rm", "rmvb", "asf", "divx", "f4v", "mxf", "y4m", "m3u8",
        "dv", "mpe", "qt", "swf"
    )

    private val imageExtensions = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tiff", "tif", "svg",
        "ico", "avif", "jfif", "pjpeg", "raw", "cr2", "nef", "dng"
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

        // A video link's last path segment is a route, not a name: `watch?v=...`
        // gave a row the name "watch", and `/shorts/`, `/embed/` and `/live/` are
        // no better. The id is the only identifying part of the URL, so the row is
        // named after that until the extractor supplies the real title - which it
        // does the moment the download starts.
        if (isYouTube(url)) {
            val id = youTubeIdFromUrl(url)
            if (id != null) return "youtube-$id"
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
        mimeType.contains("torrent") || mimeType.contains("bittorrent") -> "torrent"
        mimeType.contains("matroska") -> "mkv"
        mimeType.contains("quicktime") -> "mov"
        mimeType.contains("x-matroska") -> "mkv"
        mimeType.contains("pdf") -> "pdf"
        mimeType.contains("json") -> "json"
        mimeType.contains("image") -> "jpg"
        mimeType.contains("audio") -> "m4a"
        mimeType.contains("video") -> "mp4"
        else -> ""
    }
}
