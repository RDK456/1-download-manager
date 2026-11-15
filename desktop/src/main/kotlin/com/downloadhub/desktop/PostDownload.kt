package com.downloadhub.desktop

import java.io.File
import java.util.zip.ZipFile

/** What happens to a finished file: unzip it, scan it. Each step reports instead of throwing. */
object PostDownload {
    /**
     * Unzips [zip] into a folder named after it, beside it. Returns the folder, or null when
     * the file is not a readable zip. Entries that would land outside the folder ("zip slip")
     * are skipped, and the archive is left alone unless everything was written.
     */
    fun extractZip(zip: File): File? {
        if (!zip.name.endsWith(".zip", ignoreCase = true)) return null
        val target = File(zip.parentFile, zip.nameWithoutExtension)
        return runCatching {
            ZipFile(zip).use { archive ->
                val root = target.canonicalFile
                target.mkdirs()
                for (entry in archive.entries()) {
                    val out = File(root, entry.name).canonicalFile
                    if (out != root && !out.path.startsWith(root.path + File.separator)) continue
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile.mkdirs()
                        archive.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                    }
                }
            }
            target
        }.getOrNull()
    }

    /** Windows Defender's command-line scanner, if this machine has it. */
    private fun defender(): File? {
        val fixed = File("C:\\Program Files\\Windows Defender\\MpCmdRun.exe")
        if (fixed.isFile) return fixed
        return File("C:\\ProgramData\\Microsoft\\Windows Defender\\Platform").listFiles()
            ?.sortedByDescending { it.name }
            ?.map { File(it, "MpCmdRun.exe") }
            ?.firstOrNull { it.isFile }
    }

    /** True when Defender found a threat, false when clean, null when it could not scan. */
    fun threatFound(file: File): Boolean? {
        val scanner = defender() ?: return null
        return runCatching {
            val process = ProcessBuilder(scanner.absolutePath, "-Scan", "-ScanType", "3", "-File", file.absolutePath)
                .redirectErrorStream(true).start()
            process.inputStream.readBytes()
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES)) {
                process.destroyForcibly()
                return@runCatching null
            }
            when (process.exitValue()) {
                0 -> false
                2 -> true
                else -> null
            }
        }.getOrNull()
    }
}
