package com.downloadhub.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The quality dialog used to spin for ever, and then misreport the failure.
 *
 * Two defects, both in how the app runs yt-dlp rather than in yt-dlp itself. The
 * process runner read the extractor's output before waiting with a timeout, so a
 * stalled connection held the dialog on "Reading what this video offers..." until
 * the user gave up: the timeout sat behind the read it was meant to bound. And a
 * public video the extractor was refused reads exactly like a removed one -
 * "[youtube] ...: This video is not available" - which the dialog repeated as fact.
 */
class YouTubeListingTest {

    private fun engine(): YtDlpEngine {
        val dir = File.createTempFile("dlm-listing-test", "").let {
            it.delete()
            File(it, "tools").apply { parentFile.mkdirs(); mkdirs() }
        }
        return YtDlpEngine(YtDlpTools(dir))
    }

    /**
     * The timeout has to fire even when the process produces nothing.
     *
     * `ping -n 30` sleeps about 29 seconds; a runner that reads the stream first
     * would sit in that read for the whole sleep, while one that waits properly
     * returns 124 at once. Twenty seconds is generous for process startup and still
     * proves the wait was bounded rather than endless.
     */
    @Test
    fun aSilentProcessDoesNotHoldTheRunnerPastItsTimeout() {
        val started = System.currentTimeMillis()
        val result = engine().run(
            listOf("cmd", "/c", "ping -n 30 127.0.0.1 > nul"),
            timeoutMinutes = 0
        )
        val elapsed = System.currentTimeMillis() - started
        assertEquals("a timed-out process must report 124", 124, result.first)
        assertTrue(
            "the timeout did not bound the wait: ${elapsed}ms",
            elapsed < 20_000L
        )
    }

    /** The reader thread must still capture normal output. */
    @Test
    fun aFinishedProcessKeepsItsOutput() {
        val result = engine().run(listOf("cmd", "/c", "echo hello"), timeoutMinutes = 1)
        assertEquals(0, result.first)
        assertTrue(
            "the process output was lost: <${result.second}>",
            result.second.contains("hello")
        )
    }

    @Test
    fun theListingAsksForAFailingFastSocket() {
        val source = File("src/main/kotlin/com/downloadhub/desktop/YtDlpEngine.kt").readText()
        assertTrue(
            "the dump-json call must bound a stalled connection itself, or the " +
                "process timeout minutes away is the only limit",
            source.contains("\"--socket-timeout\", \"30\"")
        )
    }

    @Test
    fun theTimeoutHasItsOwnMessage() {
        val source = File("src/main/kotlin/com/downloadhub/desktop/YtDlpEngine.kt").readText()
        assertTrue(
            "exit 124 must explain itself rather than falling into the generic failure",
            source.contains("result.first == 124")
        )
    }

    @Test
    fun videoIdsAreFoundWhateverShapeTheLinkArrivedIn() {
        val engine = engine()
        val id = "KPh2Efv66Aw"
        mapOf(
            "https://www.youtube.com/watch?v=$id" to id,
            "https://www.youtube.com/watch?v=$id&t=42s" to id,
            "https://youtu.be/$id" to id,
            "https://www.youtube.com/shorts/$id" to id,
            "https://www.youtube.com/embed/$id" to id,
            "https://www.youtube.com/live/$id" to id,
            "https://music.youtube.com/watch?v=$id" to id
        ).forEach { (url, expected) ->
            assertEquals("no id found in $url", expected, engine.extractYouTubeId(url))
        }
    }

    @Test
    fun nonVideoLinksHaveNoVideoId() {
        val engine = engine()
        listOf(
            "https://example.com/watch?v=KPh2Efv66Aw",
            "not a link",
            "",
            "https://www.youtube.com/playlist?list=PLabc123"
        ).forEach { url ->
            assertNull("an id was invented for <$url>", engine.extractYouTubeId(url))
        }
    }

    @Test
    fun unrelatedFailuresAreRepeatedVerbatim() {
        val engine = engine()
        val reason = "ERROR: unable to download video data: HTTP Error 403"
        assertEquals(
            "a failure that is not about availability must not gain the availability " +
                "explanation",
            reason,
            engine.explainExtractionFailure(
                "https://www.youtube.com/watch?v=KPh2Efv66Aw",
                reason,
                looksPublic = true
            )
        )
    }

    @Test
    fun aPublicVideoRefusedByTheExtractorSaysSo() {
        val engine = engine()
        val reason = "[youtube] KPh2Efv66Aw: This video is not available"
        val explained = engine.explainExtractionFailure(
            "https://www.youtube.com/watch?v=KPh2Efv66Aw",
            reason,
            looksPublic = true
        )
        assertTrue(
            "the original message must survive, naming the video: <$explained>",
            explained.startsWith(reason)
        )
        assertTrue(
            "a refused public video must not be reported as removed: <$explained>",
            explained.contains("refusing the extractor")
        )
    }

    @Test
    fun aGenuinelyGoneVideoKeepsTheOriginalMessage() {
        val engine = engine()
        val reason = "[youtube] KPh2Efv66Aw: This video is not available"
        assertEquals(
            "guessing 'refused' for a removed video would be worse than the bare message",
            reason,
            engine.explainExtractionFailure(
                "https://www.youtube.com/watch?v=KPh2Efv66Aw",
                reason,
                looksPublic = false
            )
        )
        // looksPublic = null asks the network, so it is not asserted here: a test
        // that phones YouTube is a test that fails on an aeroplane.
    }

    @Test
    fun versionsCompareAsDates() {
        assertEquals(0, YtDlpTools.compareVersions("2026.08.19", "2026.08.19"))
        assertTrue(YtDlpTools.compareVersions("2026.08.19", "2026.07.01") > 0)
        assertTrue(YtDlpTools.compareVersions("2026.07.01", "2026.08.19") < 0)
        assertTrue(YtDlpTools.compareVersions("2026.08.19", "2025.12.31") > 0)
    }

    /**
     * The dialog only stops spinning when the loader calls back. If the lookup
     * itself throws, the callback is skipped and no timeout or Retry can ever be
     * reached - so the action must answer even on failure.
     */
    @Test
    fun theLoaderAnswersEvenWhenTheLookupThrows() {
        val controller = File("src/main/kotlin/com/downloadhub/desktop/DesktopController.kt").readText()
        val body = controller.substringAfter("listVideoFormats = { url, done ->")
            .substringBefore("setFilePriority = { id, index, priority ->")
        assertTrue(
            "a thrown lookup must still answer the dialog:\n$body",
            body.contains("runCatching") && body.contains("getOrElse")
        )
    }

    /**
     * Every running YouTube download parks a thread in blocking process I/O for
     * its whole duration. On the shared Default pool - a thread per core - a few
     * of those starve the quality lookup coroutine, and the dialog spins for ever
     * with no timeout able to reach it, because the timeout lives inside the
     * starved coroutine.
     */
    @Test
    fun blockingYoutubeWorkStaysOffTheSharedPool() {
        val controller = File("src/main/kotlin/com/downloadhub/desktop/DesktopController.kt").readText()
        val loader = controller.substringAfter("listVideoFormats = { url, done ->")
            .substringBefore("setFilePriority = { id, index, priority ->")
        assertTrue(
            "the lookup must run where blocking is allowed:\n$loader",
            loader.contains("Dispatchers.IO")
        )
        assertTrue(
            "downloads must run where blocking is allowed",
            controller.contains("scope.launch(Dispatchers.IO) { runYtDlp(id) }")
        )
    }

    /**
     * The old download path read the process output before waiting, the same
     * defect the lookup had: a stalled transfer held the read open and the wait
     * behind it. Both paths now run through the one runner with the stall
     * detector.
     */
    @Test
    fun everyDownloadGoesThroughTheStallDetectingRunner() {
        val engine = File("src/main/kotlin/com/downloadhub/desktop/YtDlpEngine.kt").readText()
        // No newline in the end marker: the file may carry CRLF endings.
        val download = engine.substringAfter("fun download(")
            .substringBefore("What the site actually offers")
        assertTrue(
            "the download path must use the shared runner",
            download.contains("return runStreaming(args, targetDir, onProgress)")
        )
        assertFalse(
            "the inline read-then-wait loop must be gone",
            download.contains("forEachLine") && download.contains("process.waitFor(60")
        )
    }

    /**
     * The section needs its own rail glyph rather than reusing the filled
     * triangle, which already means an active download. Two paths because one
     * stroke and one fill cannot share a path: a ring drawn, a triangle filled.
     */
    @Test
    fun theSectionHasItsOwnGlyph() {
        val icons = File("src/main/kotlin/com/downloadhub/desktop/DlmIcons.kt").readText()
        val glyph = icons.substringAfter("val YouTube").substringBefore(".build()")
        assertTrue("the ring is missing:\n$glyph", glyph.contains("stroke = SolidColor"))
        assertTrue("the triangle is missing:\n$glyph", glyph.contains("fill = SolidColor"))
        assertTrue("the triangle points are missing:\n$glyph", glyph.contains("lineTo(16f, 12f)"))
    }

    /**
     * The section lists without downloading: one small object per entry, not a
     * full extraction per video.
     */
    /**
     * A video sat on "Connecting" for its whole download and then finished in one
     * step. Nothing moved the row out of RESOLVING, and nothing gave it a total,
     * so the progress bar had nothing to move against.
     */
    @Test
    fun aProgressLineEndsTheConnectingStateAndMovesTheBar() {
        val controller = File("src/main/kotlin/com/downloadhub/desktop/DesktopController.kt").readText()
        val body = controller.substringAfter("private suspend fun runYtDlp")
            .substringBefore("fun chooseFolder")
        assertTrue(
            "the first progress line must move the row to RUNNING, or the status " +
                "reads Connecting until the job ends",
            body.contains("status = DownloadStatus.RUNNING")
        )
        assertTrue(
            "bytes must be written against the row's total for the bar to move",
            body.contains("bytesDownloaded = if (total > 0)")
        )
        // And the total has to exist: the chosen row's size is known before the
        // download starts, and a zero total is a bar pinned at nothing.
        assertTrue(
            "a chosen video must carry its known size into the row",
            controller.contains("totalBytes = choice.totalBytes")
        )
    }

    /**
     * A video row read "watch" for its whole download: `fileNameFrom` took the
     * last path segment, and for `.../watch?v=...` that is the route, not a name.
     */
    @Test
    fun aVideoRowIsNeverNamedAfterItsRoute() {
        val parser = File("../core/src/main/kotlin/com/downloadhub/core/LinkParser.kt")
        org.junit.Assume.assumeTrue("core sources are not present here", parser.isFile)
        val text = parser.readText()
        assertTrue(
            "a video URL's last segment is a route, so the row must be named from " +
                "the id instead:\n$text",
            text.contains("\"youtube-\$id\"")
        )
    }

    /**
     * On a single video the exact-quality block already has a Download and a
     * Video/Audio-only pair; the batch footer beside it was a second Download and
     * a second row of chips for the same one download.
     */
    @Test
    fun theBatchFooterIsHiddenWhenThereIsOnlyOneEntry() {
        listOf(
            "src/main/kotlin/com/downloadhub/desktop/YouTubePanel.kt",
            "../app/src/main/java/com/downloadhub/app/ui/YouTubeScreen.kt"
        ).forEach { path ->
            val file = File(path)
            org.junit.Assume.assumeTrue("$path is not present here", file.isFile)
            assertFalse(
                "$path gates its batch footer on entries.isNotEmpty(), so a single " +
                    "video shows the footer as well as the quality block",
                file.readText().contains("if (entries.isNotEmpty())")
            )
        }
    }

    @Test
    fun theSectionFetchesFlatListings() {
        val engine = File("src/main/kotlin/com/downloadhub/desktop/YtDlpEngine.kt").readText()
        val fetch = engine.substringAfter("fun fetchPlaylist(").substringBefore("data class PlaylistFetch")
        assertTrue("no flat playlist fetch:\n$fetch", fetch.contains("--dump-single-json"))
        assertTrue("entries must stay small:\n$fetch", fetch.contains("--flat-playlist"))
    }

    /**
     * Queuing the same playlist twice must not double the queue. Titles repeat
     * across uploads, so the id is the key - and the batch is deduped as one,
     * because overlapping playlists repeat entries inside a single fetch.
     */
    @Test
    fun queuingIsDedupedByVideoId() {
        val controller = File("src/main/kotlin/com/downloadhub/desktop/DesktopController.kt").readText()
        val queue = controller.substringAfter("fun queueYouTubeEntries(")
            .substringBefore("fun addDownload(")
        assertTrue("no id-keyed dedup:\n$queue", queue.contains("known.add(entry.id)"))
        assertTrue("the batch must report what it queued:\n$queue", queue.contains("return queued"))
    }
}
