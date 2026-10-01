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
        installDir.mkdirs()
        // Both, and independently: one being present says nothing about the other, and
        // an install that returned early on yt-dlp would never unpack the ffmpeg it also
        // shipped.
        if (!available) extractResource("lib/yt-dlp.exe", ytDlp)
        if (!ffmpegReady) extractResource("lib/ffmpeg.exe", ffmpeg)
        return available
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
        // Not an error, but on a packaged install it should not happen: ffmpeg ships in
        // the app and is unpacked beside yt-dlp. It is still worth saying, because the one
        // time it happens is a first run racing itself, and "fetching it now" tells the
        // user to wait rather than to go looking for a bug.
        else -> "yt-dlp ready, fetching ffmpeg"
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
    val playlist: Boolean,
    /**
     * Exact streams to take, when the chooser was used.
     *
     * Null falls back to the height selector, which is what every row queued before the
     * chooser existed - and what a row added without the dialog still uses, because the
     * dialog is not the only way into the queue.
     */
    val videoFormatId: String? = null,
    val audioFormatId: String? = null
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

        // A row that names its streams goes down the exact path, with no height cap and
        // no fallback chain: the user picked these two out of the list that was in front of
        // them, so anything else would be substituting one download for another.
        if (request.videoFormatId != null) {
            val video = com.downloadhub.core.StreamFormat(
                formatId = request.videoFormatId,
                ext = if (request.audioOnly) request.audioFormat.ifBlank { "m4a" } else "mp4",
                height = request.maxHeight,
                fps = null,
                videoCodec = null,
                audioCodec = null,
                sizeBytes = null,
                totalBitrate = null
            )
            val audio = request.audioFormatId?.let {
                com.downloadhub.core.StreamFormat(
                    formatId = it,
                    ext = "m4a",
                    height = null,
                    fps = null,
                    videoCodec = null,
                    audioCodec = null,
                    sizeBytes = null,
                    totalBitrate = null
                )
            }
            return downloadChoice(
                url = request.url,
                choice = com.downloadhub.core.StreamChoice(video, audio),
                targetDir = targetDir,
                audioOnly = request.audioOnly,
                onProgress = onProgress
            )
        }

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

    /**
     * What the site actually offers, for a link.
     *
     * The old probe ran yt-dlp with `--dump-json` and then threw all of it away, keeping
     * only the exit code - a hundred and forty kilobytes of formats, with sizes, fed
     * through a process pipe and thrown in the bin. Everything the quality chooser needs
     * was already on the wire.
     *
     * Fails with the extractor's own message rather than a generic one: "this video is
     * private" and "sign in to confirm your age" are the two most common, and neither is
     * something a user can act on unless they are told which.
     */
    fun listFormats(url: String): FormatListing {
        if (!tools.available) {
            return FormatListing(emptyList(), emptyList(), "", 0L, "yt-dlp is not installed")
        }
        val result = run(
            listOf(tools.ytDlp.absolutePath, "--dump-json", "--no-warnings", "--skip-download", url),
            timeoutMinutes = 3
        )
        if (result.first != 0) {
            val reason = result.second.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("ERROR") || it.startsWith("WARNING") }
                ?.removePrefix("ERROR:")?.removePrefix("WARNING:")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: result.second.lineSequence().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
            return FormatListing(
                emptyList(), emptyList(), "", 0L,
                reason.ifBlank { "yt-dlp could not read that link" }
            )
        }
        val all = com.downloadhub.core.parseStreamFormats(result.second)
        if (all.isEmpty()) {
            return FormatListing(
                emptyList(), emptyList(), "", 0L,
                "No downloadable streams were offered for that link."
            )
        }
        val video = com.downloadhub.core.offerVideoFormats(all)
        val audio = com.downloadhub.core.offerAudioFormats(all)
        val title = Regex("\"title\"\\s*:\\s*\"(.*?)\"").find(result.second)
            ?.groupValues?.get(1).orEmpty()
        return FormatListing(
            videoFormats = video,
            audioFormats = audio,
            title = title,
            // The best option, because that is what the list is ordered by and the number
            // beside it is the number a user choosing the top row will spend.
            bestTotalBytes = video.firstOrNull()?.let {
                com.downloadhub.core.chooseStream(all, it.height ?: 0, audio)?.totalBytes
            } ?: 0L,
            error = null
        )
    }

    /**
     * Downloads exactly the two streams [choice] names.
     *
     * Passing ids rather than a height is what lets the chooser offer what is really there:
     * 2160p60 is not "1080p", and the old selector could not express it because it only
     * ever built `bestvideo[height<=N]`. With ffmpeg present the merge is happening
     * anyway, so capping the height was capping quality for no reason.
     */
    fun downloadChoice(
        url: String,
        choice: com.downloadhub.core.StreamChoice,
        targetDir: File,
        audioOnly: Boolean = false,
        onProgress: (Int, String) -> Unit
    ): YtDlpResult {
        if (!tools.available) return YtDlpResult(false, "yt-dlp is not installed")
        // Every format on offer needs the merge, so a missing ffmpeg is not a degraded
        // download - it is no download. Said plainly, because the alternative is a pair of
        // files the user discovers are unplayable after waiting for both.
        if (!tools.ffmpegReady && !tools.ensureFfmpeg()) {
            return YtDlpResult(
                false,
                "This quality needs ffmpeg to join the video and audio, and it is still " +
                    "downloading. It is being fetched now - try again in a minute, or " +
                    "choose a lower quality once it is ready."
            )
        }
        targetDir.mkdirs()

        val args = mutableListOf(
            tools.ytDlp.absolutePath,
            "--newline", "--no-warnings", "--no-playlist",
            "--ffmpeg-location", tools.ffmpeg.absolutePath,
            "-o", File(targetDir, "%(title)s.%(ext)s").absolutePath
        )
        args += if (audioOnly) {
            listOf("-x", "--audio-format", choice.video.ext.ifBlank { "m4a" })
        } else {
            val selector = buildString {
                append(choice.video.formatId)
                choice.audio?.let { append("+").append(it.formatId) }
            }
            listOf("-f", selector, "--merge-output-format", "mp4")
        }
        args += url
        return runStreaming(args, targetDir, onProgress)
    }

    /**
     * A single-file download, for when ffmpeg is not there and one is all there is.
     *
     * YouTube offers no such format today - four videos checked, none - so this is a path
     * that currently always reports that there is nothing single-file to take. It is kept
     * because that is a property of the site rather than of this code, and when it changes
     * the fallback exists. `hasAnyProgressiveFormat` has a test asserting it is still
     * false, so nobody mistakes this for a live feature.
     */
    fun downloadProgressive(
        url: String,
        targetDir: File,
        onProgress: (Int, String) -> Unit
    ): YtDlpResult {
        val result = listFormats(url)
        val single = result.videoFormats.firstOrNull { !it.needsMerge }
            ?: return YtDlpResult(
                false,
                "This video has no single-file format, so ffmpeg is needed to join its " +
                    "video and audio."
            )
        return downloadChoice(url, com.downloadhub.core.StreamChoice(single, null), targetDir, onProgress = onProgress)
    }

    private fun runStreaming(args: List<String>, targetDir: File, onProgress: (Int, String) -> Unit): YtDlpResult {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val progressRegex = Regex("\\[download\\]\\s+([0-9.]+)%")
        val tail = StringBuilder()
        process.inputStream.bufferedReader().forEachLine { line ->
            val match = progressRegex.find(line)
            val percent = match?.groupValues?.get(1)?.toDoubleOrNull()?.toInt() ?: -1
            if (percent >= 0) onProgress(percent, line.trim())
            else if (line.contains("ERROR")) {
                tail.append(line.trim()).append('\n')
            }
        }
        val code = process.waitFor()
        return if (code == 0) {
            YtDlpResult(true, "ok", targetDir.listFiles()?.toList().orEmpty())
        } else {
            // A 403 part way through is YouTube rate-limiting, not a broken link, and the
            // two want completely different responses from the user.
            val errors = tail.toString().trim()
            val message = when {
                errors.contains("403") ->
                    "YouTube refused this request (HTTP 403). That is rate limiting rather " +
                        "than a broken link - wait a moment and try again."
                errors.isNotBlank() -> errors.lineSequence().last().trim()
                else -> "yt-dlp exited with code $code"
            }
            YtDlpResult(false, message)
        }
    }

    /** What [listFormats] found. */
    data class FormatListing(
        val videoFormats: List<com.downloadhub.core.StreamFormat>,
        val audioFormats: List<com.downloadhub.core.StreamFormat>,
        val title: String,
        /** Bytes the top video option actually costs, audio included. */
        val bestTotalBytes: Long,
        val error: String?
    ) {
        val isEmpty: Boolean get() = videoFormats.isEmpty() && audioFormats.isEmpty()
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
