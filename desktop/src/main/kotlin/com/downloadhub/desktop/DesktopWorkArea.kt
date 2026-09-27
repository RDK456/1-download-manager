package com.downloadhub.desktop

import com.downloadhub.core.PublishedTarget
import com.downloadhub.core.WorkArea
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Desktop file layout: a scratch directory for in-flight transfers and a single
 * destination folder for finished files.
 *
 * The Android build publishes into a Storage Access Framework tree; here it is a
 * plain path, so [publishFile] is a move that also picks a non-colliding name.
 */
class DesktopWorkArea(private val settings: () -> DesktopSettings) : WorkArea {

    override fun workFile(id: String): File = AppPaths.workDir.resolve("part-$id")

    override fun publishFile(source: File, preferredName: String, destinationTreeUri: String?): PublishedTarget {
        val dir = settings().downloadDirFile()
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
