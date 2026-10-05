package com.downloadhub.desktop

import com.downloadhub.core.DownloadCategory
import com.downloadhub.core.PublishedTarget
import com.downloadhub.core.destinationFolder
import com.downloadhub.core.WorkArea
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Desktop file layout: a scratch directory for in-flight transfers, and a destination
 * tree for finished files.
 *
 * The Android build publishes into a Storage Access Framework tree; here it is a plain
 * path, so [publishFile] is a move that also picks a non-colliding name.
 *
 * Finished files are filed into a subfolder named after their category - Videos,
 * Music, Compressed and so on - because the sidebar already groups them that way and
 * having the two disagree means learning the same idea twice. The subfolders are
 * created on demand, so a category nobody has downloaded into yet does not leave an
 * empty folder behind on day one.
 */
class DesktopWorkArea(private val settings: () -> DesktopSettings) : WorkArea {

    override fun workFile(id: String): File = AppPaths.workDir.resolve("part-$id")

    /**
     * The scratch folder a torrent's pieces are written into.
     *
     * Separate from an HTTP download's single `part-<id>` file, because a torrent's
     * pieces are spread across whatever the torrent contains. Removing the item has to be
     * able to find and delete exactly this, and a rule that guessed one shape for both
     * left whichever was wrong behind on disk.
     */
    fun torrentWorkDir(id: String): File =
        AppPaths.workDir.resolve("torrent-$id").apply { if (!isDirectory) mkdirs() }

    override fun publishFile(
        source: File,
        preferredName: String,
        destinationTreeUri: String?,
        category: DownloadCategory
    ): PublishedTarget {
        // A folder chosen in the Add Download window wins over the category rules; the
        // window already resolved the category to that folder when it was offered.
        destinationTreeUri?.takeIf { it.isNotBlank() }?.let { chosen ->
            return publishInto(source, preferredName, File(chosen))
        }
        val current = settings()
        val root = current.downloadDirFile()
        if (!root.exists()) root.mkdirs()
        // The user's own categories first: a rule naming this file's extension picks the
        // folder. Only when none does is the file filed by its built-in type.
        val rule = com.downloadhub.core.CategoryRules.match(preferredName, current.categoryRules.map { it.toRule() })
        val dir = rule?.let { com.downloadhub.core.CategoryRules.folderFor(it, root) }
            ?: File(root, category.destinationFolder())
        if (!dir.exists() && !dir.mkdirs()) {
            // A read-only or otherwise unusable destination is worth falling back from
            // rather than failing the whole download over: the file is already
            // downloaded and throwing here would lose it.
            return publishInto(source, preferredName, root)
        }
        return publishInto(source, preferredName, dir)
    }

    private fun publishInto(source: File, preferredName: String, dir: File): PublishedTarget {
        if (!dir.exists()) dir.mkdirs()
        val target = uniqueTarget(dir, preferredName)
        // Rename first so a move is instant when both are on the same volume, and
        // fall back to a copy across volumes.
        if (!source.renameTo(target)) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return PublishedTarget(target.absolutePath)
    }

    /** Never silently overwrites: "file (1).ext" style suffixes. */
    private fun uniqueTarget(dir: File, preferredName: String): File {
        val safe = preferredName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "download" }
        var candidate = File(dir, safe)
        if (!candidate.exists()) return candidate
        val base = safe.substringBeforeLast('.', safe)
        val extension = safe.substringAfterLast('.', "")
        var counter = 1
        while (candidate.exists() && counter < 1000) {
            val name = if (extension.isEmpty()) "$base ($counter)" else "$base ($counter).$extension"
            candidate = File(dir, name)
            counter++
        }
        return candidate
    }

    /** A no-op on Windows: there is no media scanner to notify. */
    override fun scan(file: File) = Unit
}
