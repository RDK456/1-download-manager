package com.downloadhub.core

import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/** One entry in the Add Download window's category list, and the folder it saves into. */
data class SaveCategory(val name: String, val folder: File)

/**
 * The categories a plain download can be filed under: the user's own rules first, then
 * the built-in kinds, each resolved to a folder under the download folder - the same
 * folders a download lands in when nobody chooses.
 */
object SaveCategories {
    private val BUILT_IN = listOf(
        DownloadCategory.COMPRESSED,
        DownloadCategory.PROGRAM,
        DownloadCategory.VIDEO,
        DownloadCategory.AUDIO,
        DownloadCategory.IMAGE,
        DownloadCategory.DOCUMENT,
        DownloadCategory.OTHER
    )

    fun all(root: File, rules: List<CategoryRule>): List<SaveCategory> =
        (rules.map { SaveCategory(it.name, CategoryRules.folderFor(it, root)) } +
            BUILT_IN.map { SaveCategory(it.destinationFolder(), File(root, it.destinationFolder())) })
            .distinctBy { it.name.lowercase() }

    /** The category a file would be filed under by its name: a matching rule, else its kind. */
    fun forFile(fileName: String, root: File, rules: List<CategoryRule>): SaveCategory {
        CategoryRules.match(fileName, rules)?.let { return SaveCategory(it.name, CategoryRules.folderFor(it, root)) }
        val folder = LinkParser.categoryFor(DownloadSource.HTTP, fileName).destinationFolder()
        return SaveCategory(folder, File(root, folder))
    }
}

/** What a link turned out to be before downloading it. A null size means the server did not say. */
data class LinkProbe(val totalBytes: Long?, val fileName: String?)

/**
 * Asks a server how big a file is and what it is called, without downloading it.
 *
 * A one-byte ranged GET rather than HEAD: plenty of download hosts refuse HEAD or answer
 * it differently from the real request, and the range reply carries the full size in
 * Content-Range. Only the headers are read; the connection is closed before the body.
 */
object HttpProbe {
    fun probe(url: String, headers: Map<String, String> = emptyMap(), proxy: Proxy? = null): Result<LinkProbe> = runCatching {
        val connection = (proxy?.let { URL(url).openConnection(it) } ?: URL(url).openConnection()) as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 1-download-manager")
            connection.setRequestProperty("Range", "bytes=0-0")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val code = connection.responseCode
            if (code !in 200..299) error("The server answered $code")
            LinkProbe(
                totalBytes = totalFrom(code, connection.getHeaderField("Content-Range"), connection.contentLengthLong),
                fileName = LinkParser.fileNameFrom(
                    connection.url.toString(),
                    connection.getHeaderField("Content-Disposition"),
                    connection.contentType
                ).takeIf { it.isNotBlank() }
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The full size from a reply to a one-byte range request: Content-Range's total when
     * the server honoured the range, Content-Length when it ignored it and sent everything.
     */
    fun totalFrom(code: Int, contentRange: String?, contentLength: Long): Long? {
        if (code == 206) {
            return contentRange?.substringAfterLast('/')?.trim()?.toLongOrNull()?.takeIf { it > 0 }
        }
        return contentLength.takeIf { it > 0 }
    }
}
