package com.downloadhub.desktop

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Drives the bundled yt-dlp and ffmpeg executables.
 *
 * The Android build embeds a Python interpreter and runs yt-dlp through JNI, which
 * cannot work on Windows. The desktop build ships the standalone yt-dlp.exe and
 * ffmpeg.exe instead, which is simpler: no runtime, no JNI, and the same CLI
 * everybody else uses.
 *
 * The binaries are placed beside the app by the Gradle build and copied into the
 * user's tools directory on first run, because an installed program directory can
 * be read-only.
 */
class YtDlpTools(private val installDir: File = AppPaths.toolsDir) {

    val ytDlp: File get() = File(installDir, "yt-dlp.exe")
    val ffmpeg: File get() = File(installDir, "ffmpeg.exe")

    val available: Boolean get() = ytDlp.isFile && ytDlp.length() > 0

    /**
     * Copies the bundled executables out of the application into a writable
     * location.
     *
     * The resources are read from the classpath rather than from a directory: the
     * packaged app folds `lib/yt-dlp.exe` and `lib/ffmpeg.exe` inside the module
     * jar, so looking for loose files next to the executable finds nothing and
     * YouTube would fail on an installed build while working in the IDE.
     */
    fun install(): Boolean {
        if (available) return true
        installDir.mkdirs()
        return extractResource("lib/yt-dlp.exe", ytDlp) &&
            // ffmpeg is optional: without it yt-dlp can still fetch a single
            // progressive stream, just not merge separate video and audio.
            extractResource("lib/ffmpeg.exe", ffmpeg)
    }

    private fun extractResource(resource: String, target: File): Boolean = runCatching {
        val stream = YtDlpTools::class.java.classLoader?.getResourceAsStream(resource)
            ?: YtDlpTools::class.java.getResourceAsStream("/$resource")
            ?: return false
        val partial = File(target.parentFile, target.name + ".part")
        partial.outputStream().use { sink -> stream.use { input -> input.copyTo(sink) } }
        stream.close()
        if (partial.length() <= 0L) {
            partial.delete()
            return false
        }
        target.delete()
        if (!partial.renameTo(target)) {
            partial.delete()
            return false
        }
        true
    }.getOrDefault(false)

    /** Reported to the UI so a missing binary is explained rather than silent. */
    fun statusText(): String = when {
        !available -> "yt-dlp is not installed yet"
        else -> "yt-dlp ready"
    }
}

/** One download job produced by yt-dlp. */
data class YtDlpRequest(
    val url: String,
    val audioOnly: Boolean,
    val audioFormat: String,
    val maxHeight: Int?,
    val playlist: Boolean
)

/** Result of a yt-dlp run. */
data class YtDlpResult(
    val success: Boolean,
    val message: String,
    val producedFiles: List<File> = emptyList()
)

/**
 * Runs yt-dlp for YouTube downloads.
 *
 * Output goes to a fresh folder per job so the caller can publish the finished
 * file without guessing what yt-dlp named it. Progress is read from yt-dlp's own
 * machine-readable output rather than scraped from the terminal.
 */
class YtDlpEngine(private val tools: YtDlpTools) {

    fun probe(url: String): YtDlpResult {
        if (!tools.available) {
            return YtDlpResult(false, "yt-dlp is not installed")
        }
        val command = listOf(
            tools.ytDlp.absolutePath,
            "--dump-json", "--no-warnings", "--skip-download", url
        )
        val result = run(command, timeoutMinutes = 3)
        return if (result.first == 0) {
            YtDlpResult(true, "ok")
        } else {
            YtDlpResult(false, result.second.ifBlank { "yt-dlp could not read that link" })
        }
    }

    /**
     * Downloads to [targetDir], reporting progress in percent.
     *
     * [onProgress] is called on a background thread, so callers must hop to their
     * own dispatcher before touching UI state.
     */
    fun download(
        request: YtDlpRequest,
        targetDir: File,
        onProgress: (Int, String) -> Unit
    ): YtDlpResult {
        if (!tools.available) return YtDlpResult(false, "yt-dlp is not installed")
        targetDir.mkdirs()

        val args = mutableListOf(
            tools.ytDlp.absolutePath,
            "--newline",
            "--no-warnings",
            "--no-playlist",
            "--ffmpeg-location", tools.ffmpeg.absolutePath,
            "-o", File(targetDir, "%(title)s.%(ext)s").absolutePath
        )
        if (request.playlist) {
            args[args.indexOf("--no-playlist")] = "--yes-playlist"
            args.add("--ignore-errors")
        }
        if (request.audioOnly) {
            args.add("-x")
            args.add("--audio-format")
            args.add(request.audioFormat.ifBlank { "m4a" })
        } else {
            args.add("-f")
            args.add("bestvideo[height<=${request.maxHeight ?: 1080}]+bestaudio/best[height<=${request.maxHeight ?: 1080}]/best")
            args.add("--merge-output-format")
            args.add("mp4")
        }
        args.add(request.url)

        val process = ProcessBuilder(args)
            .redirectErrorStream(true)
            .start()

        val progressRegex = Regex("\\[download\\]\\s+([0-9.]+)%")
        process.inputStream.bufferedReader().forEachLine { line ->
            val match = progressRegex.find(line)
            val percent = match?.groupValues?.get(1)?.toDoubleOrNull()?.toInt() ?: -1
            if (percent >= 0) onProgress(percent, line.trim())
        }
        val finished = process.waitFor(60, TimeUnit.MINUTES)
        if (!finished) {
            process.destroyForcibly()
            return YtDlpResult(false, "yt-dlp timed out")
        }
        val files = targetDir.listFiles()?.filter { it.isFile && it.length() > 0 } ?: emptyList()
        if (process.exitValue() != 0) {
            return YtDlpResult(false, "yt-dlp could not download that link", files)
        }
        return YtDlpResult(true, "ok", files)
    }

    private fun run(command: List<String>, timeoutMinutes: Long): Pair<Int, String> {
        return runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                return@runCatching 124 to output
            }
            process.exitValue() to output
        }.getOrDefault(-1 to "")
    }
}
