package com.downloadhub.desktop

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Drives the bundled yt-dlp executable, and ffmpeg fetched on demand.
 *
 * The Android build embeds a Python interpreter and runs yt-dlp through JNI, which
 * cannot work on Windows. The desktop build ships the standalone yt-dlp.exe
 * instead, which is simpler: no runtime, no JNI, and the same CLI everybody else
 * uses.
 *
 * ffmpeg is deliberately *not* shipped. It is 100 MB of the 150 MB download, and
 * shipping it made the install a thousand files, which is what a security agent
 * fights with - the install died with "Failed to launch JVM" on a machine whose
 * agent quarantines runtime files as they are written. ffmpeg is now downloaded the
 * first time a job actually needs it, and most users never need it: only merging
 * separate video and audio streams, or converting to a non-WebM audio format, does.
 * Anything else works with yt-dlp alone.
 *
 * The binaries are copied into the user's tools directory on first run, because an
 * installed program directory can be read-only.
 */
class YtDlpTools(private val installDir: File = AppPaths.toolsDir) {

    val ytDlp: File get() = File(installDir, "yt-dlp.exe")
    val ffmpeg: File get() = File(installDir, "ffmpeg.exe")

    val available: Boolean get() = ytDlp.isFile && ytDlp.length() > 0

    /** True once ffmpeg has been fetched; false is not an error, only a limitation. */
    val ffmpegReady: Boolean get() = ffmpeg.isFile && ffmpeg.length() > 0

    /**
     * Copies the bundled yt-dlp out of the application into a writable location.
     *
     * The resource is read from the classpath rather than from a directory: the
     * packaged app folds `lib/yt-dlp.exe` inside the module jar, so looking for a
     * loose file next to the executable finds nothing and YouTube would fail on an
     * installed build while working in the IDE.
     */
    fun install(): Boolean {
        if (available) return true
        installDir.mkdirs()
        return extractResource("lib/yt-dlp.exe", ytDlp)
    }

    /**
     * Downloads ffmpeg if it is not already here.
     *
     * `blocking` is for callers that are already on a background thread and need the
     * file before continuing - the download jobs are. Anything on the UI thread
     * should use the default and draw the result from the callback instead, because
     * this is a 100 MB download.
     *
     * A failure is not fatal: yt-dlp still fetches a progressive stream without
     * ffmpeg, so this returns false and the caller carries on.
     */
    @JvmOverloads
    fun ensureFfmpeg(blocking: Boolean = false, onDone: (Boolean) -> Unit = {}): Boolean {
        if (ffmpegReady) {
            onDone(true)
            return true
        }
        if (blocking) {
            val ok = runCatching { fetchFfmpeg() }.getOrDefault(false)
            onDone(ok)
            return ok
        }
        Thread({
            val ok = runCatching { fetchFfmpeg() }.getOrDefault(false)
            onDone(ok)
        }, "dlm-ffmpeg-fetch").apply { isDaemon = true }.start()
        return false
    }

    /**
     * Fetches a static ffmpeg build and keeps just the executable.
     *
     * The archive is a .zip holding an `ffmpeg-x86_64-.../bin/` tree of 100-odd
     * files; only ffmpeg.exe is kept, because that is all yt-dlp calls. Reading the
     * zip in-process avoids needing a tar or 7-Zip on the machine.
     */
    private fun fetchFfmpeg(): Boolean {
        installDir.mkdirs()
        val archive = File(installDir, "ffmpeg-download.zip")
        try {
            download(FFMPEG_URL, archive)
            if (!archive.isFile || archive.length() < 1_000_000L) return false
            return unpackFfmpeg(archive)
        } finally {
            archive.delete()
        }
    }

    /**
     * Picks the executable out of the archive and puts it in place.
     *
     * Split out from the download so it can be tested against a real archive layout
     * without a 100 MB network round trip. The archive is gyan.dev's, and its top
     * folder is named after the build - `ffmpeg-9.0.2-essentials_build/` today - so
     * the path is matched rather than hard-coded.
     */
    internal fun unpackFfmpeg(archive: File): Boolean = runCatching {
        java.util.zip.ZipFile(archive).use { zip ->
            // Only bin/ffmpeg.exe. The same archive also carries ffprobe, presets,
            // headers and documentation, none of which is needed here.
            val entry = zip.stream().toList().firstOrNull { candidate ->
                !candidate.isDirectory &&
                    candidate.name.replace('\\', '/').substringAfterLast('/') == "ffmpeg.exe" &&
                    candidate.name.replace('\\', '/').contains("/bin/")
            } ?: return@use false

            val partial = File(installDir, "ffmpeg.exe.part")
            partial.delete()
            zip.getInputStream(entry).use { input ->
                partial.outputStream().use { sink -> input.copyTo(sink) }
            }
            // Promoting a truncated download would leave an exe that fails at run
            // time, in the middle of a job the user is waiting on.
            if (partial.length() < 1_000_000L) {
                partial.delete()
                return@use false
            }
            ffmpeg.delete()
            if (!partial.renameTo(ffmpeg)) {
                partial.delete()
                return@use false
            }
            true
        }
    }.getOrDefault(false)

    /**
     * Downloads a URL to a file.
     *
     * curl.exe is used first because Java's HttpURLConnection is unreliable for the
     * large redirecting downloads involved here; curl ships with Windows 10 1803+.
     */
    private fun download(url: String, destination: File) {
        val partial = File(destination.parentFile, destination.name + ".part")
        partial.delete()
        val curl = File("C:/Windows/System32/curl.exe")
        val viaCurl = curl.isFile && runCatching {
            val process = ProcessBuilder(
                curl.absolutePath, "-sSL", "--fail", "--retry", "3",
                "--connect-timeout", "30", "--max-time", "1800",
                "-o", partial.absolutePath, url
            ).redirectErrorStream(true).start()
            process.inputStream.readBytes()
            process.waitFor() == 0
        }.getOrDefault(false)

        if (!viaCurl) {
            java.net.URI(url).toURL().openStream().use { input ->
                partial.outputStream().use { sink -> input.copyTo(sink) }
            }
        }
        if (!partial.isFile || partial.length() <= 0L) {
            partial.delete()
            throw IllegalStateException("Download of $url produced nothing")
        }
        destination.delete()
        if (!partial.renameTo(destination)) {
            partial.delete()
            throw IllegalStateException("Could not move the download into place")
        }
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
        ffmpegReady -> "yt-dlp ready, ffmpeg ready"
        // Not an error: the formats that need ffmpeg are simply unavailable, and
        // saying so is more useful than a spinner that never resolves.
        else -> "yt-dlp ready (high-quality formats download ffmpeg on demand)"
    }

    private companion object {
        /** gyan.dev's static Windows build of ffmpeg. */
        const val FFMPEG_URL =
            "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip"
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

        // The chosen formats need ffmpeg: `-x` converts the audio stream, and
        // bestvideo+bestaudio are two files that must be muxed together. Fetching it
        // here, rather than at startup, means the first run of the app costs nothing
        // and only the users who actually need the extra formats pay the 100 MB.
        val needsFfmpeg = request.audioOnly || request.maxHeight == null
        if (needsFfmpeg && !tools.ensureFfmpeg(blocking = true) && !tools.ffmpegReady) {
            return YtDlpResult(
                false,
                "That format needs ffmpeg, which could not be downloaded. " +
                    "Try a lower quality, or retry once you are online."
            )
        }

        val args = mutableListOf(
            tools.ytDlp.absolutePath,
            "--newline",
            "--no-warnings",
            "--no-playlist",
            "-o", File(targetDir, "%(title)s.%(ext)s").absolutePath
        )
        // Only passed when it exists: yt-dlp treats the path as a directory and
        // fails outright if the file is not there.
        if (tools.ffmpegReady) {
            args.add("--ffmpeg-location")
            args.add(tools.ffmpeg.absolutePath)
        }
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
