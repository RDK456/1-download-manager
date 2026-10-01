package com.downloadhub.desktop

import org.junit.Assert.assertEquals
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
}
