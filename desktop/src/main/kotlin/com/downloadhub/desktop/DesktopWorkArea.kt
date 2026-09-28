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

    override fun publishFile(
        source: File,
        preferredName: String,
        destinationTreeUri: String?,
        category: DownloadCategory
    ): PublishedTarget {
        val root = settings().downloadDirFile()
        if (!root.exists()) root.mkdirs()
        val dir = File(root, category.destinationFolder())
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
