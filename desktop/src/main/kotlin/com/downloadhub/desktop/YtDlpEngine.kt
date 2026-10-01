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
        // A previous run may have downloaded a newer yt-dlp and then died between
        // deleting the old exe and moving the new one in. A leftover of either half
        // is still a newer yt-dlp than the bundled copy, so it is promoted before
        // the bundled one is unpacked over it.
        if (!available) promotePendingUpdate()
        // Both, and independently: one being present says nothing about the other, and
        // an install that returned early on yt-dlp would never unpack the ffmpeg it also
        // shipped.
        if (!available) extractResource("lib/yt-dlp.exe", ytDlp)
        if (!ffmpegReady) extractResource("lib/ffmpeg.exe", ffmpeg)
        return available
    }

    /**
     * The yt-dlp this install is actually running, or null when it cannot be asked.
     *
     * yt-dlp versions are dates - 2026.08.19 - so they compare as version numbers and
     * read as them too. Cached after the first ask: this is read on every status
     * refresh, and spawning a process per refresh would be felt.
     */
    @Volatile
    private var versionCache: String? = null

    fun installedVersion(): String? {
        versionCache?.let { return it }
        if (!available) return null
        return runCatching {
            val process = ProcessBuilder(ytDlp.absolutePath, "--version")
                .redirectErrorStream(true).start()
            // One line on stdout and no network, so reading first is safe here: this
            // cannot stall the way a dump-json over a dead connection can.
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(1, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                return null
            }
            output.trim().lineSequence().lastOrNull { it.isNotBlank() }?.trim()
                ?.takeIf { it.matches(Regex("\\d{4}\\.\\d{2}\\.\\d{2}.*")) }
                ?.also { versionCache = it }
        }.getOrNull()
    }

    /**
     * Brings yt-dlp up to date in the background, at most once a day.
     *
     * The extractor is a scraper, and YouTube changes the pages it scrapes without
     * notice: a yt-dlp that read every video in August can call a public video "not
     * available" in October, with no other symptom. The install ships whatever was
     * current on build day and would otherwise stay that age for ever, so this is the
     * only thing standing between a working YouTube download and a slowly rotting
     * one. The callback reports whether anything was replaced.
     *
     * Replacement is done old-to-backup, new-into-place, so a crash mid-swap leaves
     * a promotable copy rather than no yt-dlp at all - and [install] promotes one on
     * the next start.
     */
    fun updateCheckInBackground(onDone: (Boolean) -> Unit = {}) {
        Thread({
            val updated = runCatching { updateYtDlpIfNeeded() }.getOrDefault(false)
            runCatching { onDone(updated) }
        }, "dlm-ytdlp-update").apply { isDaemon = true }.start()
    }

    private fun updateYtDlpIfNeeded(): Boolean {
        if (!available) return false
        val marker = File(installDir, "yt-dlp.lastcheck")
        val last = runCatching { marker.readText().trim().toLong() }.getOrDefault(0L)
        if (System.currentTimeMillis() - last < 24L * 60L * 60L * 1000L) return false
        // No marker write on failure: a failed check retries on the next launch
        // rather than going quiet for a day.
        val latest = latestReleaseTag() ?: return false
        val current = installedVersion()
        if (current != null && compareVersions(current, latest) >= 0) {
            runCatching { marker.writeText(System.currentTimeMillis().toString()) }
            return false
        }
        if (!fetchReleaseAsset(latest)) return false
        versionCache = latest
        runCatching { marker.writeText(System.currentTimeMillis().toString()) }
        return true
    }

    private fun latestReleaseTag(): String? = runCatching {
        java.net.URI("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest")
            .toURL().openConnection().apply {
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "1-download-manager")
                connectTimeout = 20_000
                readTimeout = 20_000
            }.getInputStream().bufferedReader().use { reader ->
                Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"")
                    .find(reader.readText())?.groupValues?.get(1)
                    ?.takeIf { it.matches(Regex("\\d{4}\\.\\d{2}\\.\\d{2}.*")) }
            }
    }.getOrNull()

    private fun fetchReleaseAsset(tag: String): Boolean = runCatching {
        val pending = File(installDir, "yt-dlp.exe.new")
        pending.delete()
        download(
            "https://github.com/yt-dlp/yt-dlp/releases/download/$tag/yt-dlp.exe",
            pending
        )
        if (!pending.isFile || pending.length() < 1_000_000L) {
            pending.delete()
            return false
        }
        // Windows will not rename over a running exe, and deleting first would leave
        // a gap. The old exe steps aside instead: if anything below fails, the backup
        // is still there for [promotePendingUpdate] to put back.
        val backup = File(installDir, "yt-dlp.exe.bak")
        backup.delete()
        if (ytDlp.isFile && !ytDlp.renameTo(backup)) {
            pending.delete()
            return false
        }
        if (!pending.renameTo(ytDlp)) {
            runCatching { backup.renameTo(ytDlp) }
            pending.delete()
            return false
        }
        backup.delete()
        true
    }.getOrDefault(false)

    private fun promotePendingUpdate() {
        val pending = File(installDir, "yt-dlp.exe.new")
        if (pending.isFile && pending.length() > 1_000_000L && !ytDlp.isFile) {
            runCatching { pending.renameTo(ytDlp) }
        } else {
            pending.delete()
        }
        val backup = File(installDir, "yt-dlp.exe.bak")
        if (!ytDlp.isFile && backup.isFile && backup.length() > 1_000_000L) {
            runCatching { backup.renameTo(ytDlp) }
        }
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
        ffmpegReady -> "yt-dlp ${installedVersion() ?: "ready"}, ffmpeg ready"
        // Not an error, but on a packaged install it should not happen: ffmpeg ships in
        // the app and is unpacked beside yt-dlp. It is still worth saying, because the one
        // time it happens is a first run racing itself, and "fetching it now" tells the
        // user to wait rather than to go looking for a bug.
        else -> "yt-dlp ready, fetching ffmpeg"
    }

    internal companion object {
        /** gyan.dev's static Windows build of ffmpeg. */
        const val FFMPEG_URL =
            "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip"

        /** Silence plus zero growth for this long means a wedged transfer. */
        const val STALL_MILLIS = 10L * 60L * 1000L

        /**
         * Orders yt-dlp releases, which are dates with an optional suffix.
         *
         * Positive when [a] is newer, so the updater knows whether the installed
         * copy is behind the release it just resolved.
         */
        internal fun compareVersions(a: String, b: String): Int {
            val parts = { version: String ->
                version.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }
            }
            val left = parts(a)
            val right = parts(b)
            for (i in 0 until maxOf(left.size, right.size)) {
                val compared = (left.getOrElse(i) { 0 }).compareTo(right.getOrElse(i) { 0 })
                if (compared != 0) return compared
            }
            return 0
        }
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
            // A stalled transfer must fail and retry rather than park a thread - and
            // a queue row - for ever. Thirty seconds of silence means the network is
            // gone, not slow; yt-dlp retries the fragment from there.
            "--socket-timeout", "30",
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
        // Artist, title and artwork travel inside the file, which is what makes a
        // downloaded playlist a library rather than a folder of numbered files.
        args.add("--embed-metadata")
        if (request.audioOnly) args.add("--embed-thumbnail")
        args.add(request.url)

        // One runner for every download: it consumes output on its own thread while
        // waiting with a timeout, so a stalled transfer cannot hold the reading
        // thread past the wait the way the inline loop here used to.
        return runStreaming(args, targetDir, onProgress)
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
            listOf(
                tools.ytDlp.absolutePath,
                "--dump-json", "--no-warnings", "--skip-download",
                // Without this a stalled connection hangs the extractor, and the process
                // timeout above is minutes away. Thirty seconds of silence means the
                // network is gone, not slow.
                "--socket-timeout", "30",
                // A lookup is not a download: there is nothing to resume, so grinding
                // through the default retries only moves a refusal from seconds to
                // minutes. Two attempts, then an answer.
                "--extractor-retries", "2",
                url
            ),
            timeoutMinutes = 3
        )
        if (result.first == 124) {
            return FormatListing(
                emptyList(), emptyList(), "", 0L,
                "Reading that link timed out - the connection stalled for over three " +
                    "minutes. If your phone on the same network downloads the same " +
                    "video fine, the block is on this PC: a firewall or antivirus " +
                    "holding yt-dlp's connections looks exactly like this, and the " +
                    "fix is allowing it through rather than retrying."
            )
        }
        if (result.first != 0) {
            val reason = result.second.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("ERROR") || it.startsWith("WARNING") }
                ?.removePrefix("ERROR:")?.removePrefix("WARNING:")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: result.second.lineSequence().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
            return FormatListing(
                emptyList(), emptyList(), "", 0L,
                explainExtractionFailure(url, reason.ifBlank { "yt-dlp could not read that link" })
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
        // The track keeps its details: artist, title and artwork travel inside the
        // file, which is what makes a downloaded playlist a library rather than a
        // folder of "videoplayback" files. Needs ffmpeg, which is guaranteed above.
        args += listOf("--embed-metadata", "--embed-thumbnail")
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

    /**
     * Runs a download to completion, with a stall detector instead of a deadline.
     *
     * A download has no natural length - a total timeout would kill legitimate
     * multi-hour transfers - but a process that is silent *and* growing nothing is
     * doing nothing. The reader thread timestamps every line; the waiting thread
     * also watches the target directory, because the ffmpeg merge at the end
     * writes the output file for minutes at a time. Ten minutes with neither a
     * line nor a byte means the transfer is wedged, and the row and the thread
     * are released instead of held for ever.
     */
    private fun runStreaming(args: List<String>, targetDir: File, onProgress: (Int, String) -> Unit): YtDlpResult {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val progressRegex = Regex("\\[download\\]\\s+([0-9.]+)%")
        val tail = StringBuilder()
        val lastActivity = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        fun dirSize(): Long = targetDir.listFiles()?.sumOf { it.length() } ?: 0L
        var lastSize = dirSize()
        val reader = Thread({
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    lastActivity.set(System.currentTimeMillis())
                    val match = progressRegex.find(line)
                    val percent = match?.groupValues?.get(1)?.toDoubleOrNull()?.toInt() ?: -1
                    if (percent >= 0) onProgress(percent, line.trim())
                    else if (line.contains("ERROR")) {
                        synchronized(tail) { tail.append(line.trim()).append('\n') }
                    }
                }
            }
        }, "dlm-ytdlp-stream").apply { isDaemon = true; start() }

        var code: Int? = null
        while (code == null) {
            if (process.waitFor(60, TimeUnit.SECONDS)) {
                code = process.exitValue()
            } else if (System.currentTimeMillis() - lastActivity.get() > YtDlpTools.STALL_MILLIS &&
                dirSize() == lastSize
            ) {
                process.destroyForcibly()
                reader.join(5_000)
                return YtDlpResult(
                    false,
                    "The download stalled - no progress for ten minutes - so it was " +
                        "stopped rather than left running. Retrying usually resumes " +
                        "where it stopped."
                )
            } else {
                lastSize = dirSize()
            }
        }
        reader.join(10_000)
        return if (code == 0) {
            YtDlpResult(true, "ok", targetDir.listFiles()?.toList().orEmpty())
        } else {
            // A 403 part way through is YouTube rate-limiting, not a broken link, and the
            // two want completely different responses from the user.
            val errors = synchronized(tail) { tail.toString() }.trim()
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

    /** What [fetchPlaylist] found: entries, or why there are none. */
    data class PlaylistFetch(
        val title: String,
        val entries: List<com.downloadhub.core.YouTubeEntry>,
        val single: Boolean,
        val error: String?
    ) {
        val isEmpty: Boolean get() = entries.isEmpty() && error == null
    }

    /**
     * Lists what a link holds: a playlist, album, channel, or one video.
     *
     * `--dump-single-json` answers playlists and single videos with the same
     * shape, and `--flat-playlist` keeps each entry small - a full extraction
     * downloads a hundred kilobytes per video before the first row can show.
     * Same timeouts as the quality lookup, for the same stalled-network reason.
     */
    fun fetchPlaylist(url: String): PlaylistFetch {
        if (!tools.available) {
            return PlaylistFetch("", emptyList(), false, "yt-dlp is not installed")
        }
        val result = run(
            listOf(
                tools.ytDlp.absolutePath,
                "--dump-single-json", "--flat-playlist",
                "--no-warnings", "--no-playlist",
                "--socket-timeout", "30",
                "--extractor-retries", "2",
                url
            ),
            timeoutMinutes = 3
        )
        if (result.first == 124) {
            return PlaylistFetch(
                "", emptyList(), false,
                "Reading that link timed out - the connection stalled for over three " +
                    "minutes. If your phone on the same network reads it fine, the " +
                    "block is on this PC: a firewall or antivirus holding yt-dlp's " +
                    "connections looks exactly like this."
            )
        }
        if (result.first != 0 || result.second.isBlank()) {
            val reason = result.second.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("ERROR") }
                ?.removePrefix("ERROR:")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "yt-dlp could not read that link"
            return PlaylistFetch("", emptyList(), false, reason)
        }
        val listing = com.downloadhub.core.parseYouTubeListing(result.second)
            ?: return PlaylistFetch(
                "", emptyList(), false,
                "That link held nothing downloadable."
            )
        return PlaylistFetch(listing.title, listing.entries, listing.single, null)
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

    /**
     * Says what an extraction failure actually means, rather than repeating yt-dlp.
     *
     * "This video is not available" is what the extractor says both for a video that
     * is gone and for a public video it was refused - rate-limiting or bot-checking
     * on this network, which a current yt-dlp still hits. The two want opposite
     * responses from the user, so a "not available" failure earns one cheap check:
     * YouTube's oEmbed endpoint answers for any public video with no login, so if it
     * answers, the video is there and the extractor was refused. If it does not, or
     * the check itself fails, the original message stands - a wrong guess would be
     * worse than an unadorned one.
     */
    internal fun explainExtractionFailure(
        url: String,
        reason: String,
        /**
         * Whether YouTube admits the video exists. Null asks the network; passing it
         * explicitly is what makes this testable without one.
         */
        looksPublic: Boolean? = null
    ): String {
        if (!reason.contains("not available", ignoreCase = true) &&
            !reason.contains("private", ignoreCase = true)
        ) {
            return reason
        }
        val id = extractYouTubeId(url) ?: return reason
        return when (looksPublic ?: videoLooksPublic(id)) {
            true -> "$reason. The video itself looks public, so this is YouTube " +
                "refusing the extractor rather than a removed video - usually " +
                "rate-limiting or bot-checking on this network. Waiting a while and " +
                "trying again is what fixes it."
            else -> reason
        }
    }

    /**
     * The video id in a YouTube URL, whatever shape it arrived in.
     *
     * One copy, in :core: the app and the message check used to own their own,
     * and two copies of an eleven-character regex in two modules is how they
     * drift apart.
     */
    internal fun extractYouTubeId(url: String): String? =
        com.downloadhub.core.youTubeIdFromUrl(url)

    /**
     * Whether YouTube itself admits the video exists: true for public, false for
     * gone-or-private, null when the check could not complete.
     *
     * Best-effort by design - a null answer changes nothing, and the caller keeps
     * the extractor's own message in that case.
     */
    internal fun videoLooksPublic(videoId: String): Boolean? = runCatching {
        val connection = java.net.URI(
            "https://www.youtube.com/oembed?url=" +
                "https://www.youtube.com/watch?v=$videoId&format=json"
        ).toURL().openConnection().apply {
            setRequestProperty("User-Agent", "1-download-manager")
            connectTimeout = 8_000
            readTimeout = 8_000
        } as java.net.HttpURLConnection
        try {
            when (connection.responseCode) {
                200 -> true
                401, 403, 404 -> false
                else -> null
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
    /**
     * Runs a command and returns its exit code with everything it printed.
     *
     * The output is consumed on its own thread while the main one waits with a real
     * timeout. Reading the stream on the waiting thread - the obvious
     * `readText()`-then-`waitFor()` - blocks until the process exits, which puts the
     * timeout behind the very thing it is meant to bound: a stalled network keeps
     * yt-dlp alive, `readText()` never returns, and the caller spins for ever. That
     * is exactly how the quality dialog used to sit on "Reading what this video
     * offers..." until the user gave up. Exit code 124 marks the timeout, the way
     * the Unix `timeout` command does.
     */
    internal fun run(command: List<String>, timeoutMinutes: Long): Pair<Int, String> {
        return runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = StringBuilder()
            val reader = Thread({
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(output) { output.appendLine(line) }
                    }
                }
            }, "dlm-ytdlp-read").apply { isDaemon = true; start() }
            if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                reader.join(5_000)
                return@runCatching 124 to synchronized(output) { output.toString() }
            }
            reader.join(10_000)
            process.exitValue() to synchronized(output) { output.toString() }
        }.getOrDefault(-1 to "")
    }
}
