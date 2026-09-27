package com.downloadhub.app.download

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.util.Locale

/** A location that can be either a normal file path or a persisted content URI. */
data class PublishedTarget(
    val location: String,
    val isDirectory: Boolean = false
)

class DownloadStorage(private val context: Context) {
    private val publicRoot = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "DownloadHub"
    )
    private val fallbackRoot = File(
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads"),
        "DownloadHub"
    )

    /** Default visible destination when the user has not selected a SAF folder. */
    val root: File = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || publicRoot.canWrite()) {
        publicRoot
    } else {
        fallbackRoot
    }

    /**
     * All engines write here first. Keeping partial data outside the selected
     * destination means a folder permission change cannot corrupt an in-flight
     * download, and yt-dlp/libtorrent always receive a real filesystem path.
     */
    private val stagingRoot = File(
        context.getExternalFilesDir(null) ?: File(context.filesDir, "downloadhub"),
        "staging"
    )
    private val workRoot = File(stagingRoot, "work")
    private val torrentRoot = File(stagingRoot, "torrents")

    init {
        runCatching { root.mkdirs() }
        runCatching { stagingRoot.mkdirs() }
        runCatching { workRoot.mkdirs() }
        runCatching { torrentRoot.mkdirs() }
    }

    fun targetFile(preferredName: String): File {
        root.mkdirs()
        val safe = LinkParser.sanitizeFileName(preferredName)
        var candidate = File(root, safe)
        var suffix = 1
        while (candidate.exists()) {
            val stem = safe.substringBeforeLast('.', safe)
            val extension = safe.substringAfterLast('.', "")
            val name = if (extension.isBlank()) "$stem ($suffix)" else "$stem ($suffix).$extension"
            candidate = File(root, name)
            suffix++
        }
        return candidate
    }

    fun workFile(id: String): File {
        workRoot.mkdirs()
        return File(workRoot, "$id.part")
    }

    fun workDirectory(id: String): File {
        workRoot.mkdirs()
        return File(workRoot, id)
    }

    /**
     * Root folder for in-progress torrents.
     *
     * The shared engine takes a path supplier rather than a Context, so the
     * Windows build can hand it an equivalent folder of its own.
     */
    fun torrentRoot(): File = torrentRoot

    fun torrentDirectory(name: String): File {
        torrentRoot.mkdirs()
        val safe = LinkParser.sanitizeFileName(name)
        var candidate = File(torrentRoot, safe)
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(torrentRoot, "$safe ($suffix)")
            suffix++
        }
        return candidate
    }

    /**
     * Publishes a completed file to the selected SAF tree, or to the default
     * Download/DownloadHub folder when no tree was selected.
     */
    fun publishFile(
        source: File,
        preferredName: String,
        destinationTreeUri: String?
    ): PublishedTarget {
        if (!source.exists()) throw IOException("Download staging file is missing")
        if (destinationTreeUri.isNullOrBlank()) {
            val target = targetFile(preferredName)
            moveOrCopyFile(source, target)
            return PublishedTarget(target.absolutePath)
        }

        val tree = treeFor(destinationTreeUri)
        val safeName = LinkParser.sanitizeFileName(preferredName)
        val name = uniqueDocumentName(tree, safeName)
        val mime = mimeFor(name)
        val document = tree.createFile(mime, name)
            ?: throw IOException("Could not create $name in the selected folder")
        try {
            context.contentResolver.openOutputStream(document.uri, "w")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            } ?: throw IOException("Could not open the selected destination")
        } catch (error: Throwable) {
            runCatching { context.contentResolver.delete(document.uri, null, null) }
            throw error
        }
        source.delete()
        return PublishedTarget(document.uri.toString())
    }

    /** Publishes a completed torrent payload while preserving its directory tree. */
    fun publishDirectory(
        source: File,
        preferredName: String,
        destinationTreeUri: String?
    ): PublishedTarget {
        if (!source.exists()) throw IOException("Download staging directory is missing")
        if (source.isFile) return publishFile(source, preferredName, destinationTreeUri)
        if (destinationTreeUri.isNullOrBlank()) {
            val target = uniqueDirectory(root, preferredName)
            moveOrCopyDirectory(source, target)
            return PublishedTarget(target.absolutePath, isDirectory = true)
        }

        val tree = treeFor(destinationTreeUri)
        val safeName = LinkParser.sanitizeFileName(preferredName)
        val name = uniqueDocumentName(tree, safeName)
        val directory = tree.createDirectory(name)
            ?: throw IOException("Could not create $name in the selected folder")
        copyDirectoryToDocument(source, directory)
        source.deleteRecursively()
        return PublishedTarget(directory.uri.toString(), isDirectory = true)
    }

    fun deleteWork(id: String) {
        workFile(id).delete()
        workDirectory(id).deleteRecursively()
    }

    fun deleteOutput(location: String?) {
        if (location.isNullOrBlank()) return
        if (location.startsWith("content:")) {
            runCatching {
                DocumentFile.fromSingleUri(context, Uri.parse(location))?.delete()
            }
            return
        }
        val file = File(location)
        if (!isManagedLocation(file)) return
        if (file.isDirectory) file.deleteRecursively() else file.delete()
    }

    fun outputExists(location: String?): Boolean {
        if (location.isNullOrBlank()) return false
        if (location.startsWith("content:")) {
            return runCatching {
                DocumentFile.fromSingleUri(context, Uri.parse(location))?.exists() == true
            }.getOrDefault(false)
        }
        return File(location).exists()
    }

    fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun scan(file: File) {
        runCatching {
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        }
    }

    fun largestDownloadableFile(directory: File): File? =
        directory.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            .maxByOrNull { it.length() }

    fun copyDirectory(source: File, target: File) {
        if (!target.exists()) target.mkdirs()
        source.listFiles()?.forEach { child ->
            val destination = File(target, child.name)
            if (child.isDirectory) copyDirectory(child, destination) else child.copyTo(destination, overwrite = true)
        }
    }

    private fun treeFor(uriString: String): DocumentFile {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(uriString))
        if (tree == null || !tree.canWrite()) {
            throw IOException("The selected download folder is no longer writable")
        }
        return tree
    }

    private fun uniqueDocumentName(tree: DocumentFile, desired: String): String {
        if (tree.findFile(desired) == null) return desired
        val stem = desired.substringBeforeLast('.', desired)
        val extension = desired.substringAfterLast('.', "")
        var suffix = 1
        while (true) {
            val name = if (extension.isBlank()) "$stem ($suffix)" else "$stem ($suffix).$extension"
            if (tree.findFile(name) == null) return name
            suffix++
        }
    }

    private fun uniqueDirectory(parent: File, desired: String): File {
        parent.mkdirs()
        val safe = LinkParser.sanitizeFileName(desired)
        var candidate = File(parent, safe)
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(parent, "$safe ($suffix)")
            suffix++
        }
        return candidate
    }

    private fun moveOrCopyFile(source: File, target: File) {
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = false)
            source.delete()
        }
    }

    private fun moveOrCopyDirectory(source: File, target: File) {
        if (!source.renameTo(target)) {
            copyDirectory(source, target)
            source.deleteRecursively()
        }
    }

    private fun copyDirectoryToDocument(source: File, target: DocumentFile) {
        source.listFiles()?.forEach { child ->
            val name = LinkParser.sanitizeFileName(child.name, "file")
            if (child.isDirectory) {
                val childDirectory = target.createDirectory(name)
                    ?: throw IOException("Could not create $name in the selected folder")
                copyDirectoryToDocument(child, childDirectory)
            } else {
                val document = target.createFile(mimeFor(name), name)
                    ?: throw IOException("Could not create $name in the selected folder")
                try {
                    context.contentResolver.openOutputStream(document.uri, "w")?.use { output ->
                        child.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
                    } ?: throw IOException("Could not open the selected destination")
                } catch (error: Throwable) {
                    runCatching { context.contentResolver.delete(document.uri, null, null) }
                    throw error
                }
            }
        }
    }

    private fun mimeFor(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.US)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    private fun isManagedLocation(file: File): Boolean = runCatching {
        val path = file.canonicalPath
        val rootPath = root.canonicalPath
        val stagingPath = stagingRoot.canonicalPath
        path.startsWith("$rootPath${File.separator}") || path.startsWith("$stagingPath${File.separator}")
    }.getOrDefault(false)

    companion object {
        fun extensionFor(file: File): String =
            file.extension.lowercase(Locale.US)
    }
}
