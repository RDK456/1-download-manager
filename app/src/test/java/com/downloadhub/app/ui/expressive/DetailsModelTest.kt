package com.downloadhub.app.ui.expressive

import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.data.model.DownloadCategory
import com.downloadhub.app.data.model.DownloadSource
import com.downloadhub.app.data.model.DownloadStatus
import com.downloadhub.app.ui.expressive.model.PrimaryAction
import com.downloadhub.app.ui.expressive.model.facts
import com.downloadhub.app.ui.expressive.model.friendlyError
import com.downloadhub.app.ui.expressive.model.primaryActionFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailsModelTest {
    private fun entity(status: DownloadStatus, source: DownloadSource = DownloadSource.HTTP, total: Long = 1000, speed: Long = 0, path: String? = null) = DownloadEntity(
        id = "x", source = source, url = "https://e.org/f.iso", fileName = "f.iso", mimeType = "application/x-iso9660-image",
        category = DownloadCategory.FILE, status = status, bytesDownloaded = 250, totalBytes = total,
        progressPercent = 25, speedBytesPerSecond = speed, etaSeconds = if (speed > 0) 60 else -1, outputPath = path,
        torrentFilePath = null, torrentInfoHash = null, userAgent = null, contentDisposition = null,
        etag = null, lastModified = null, errorMessage = null, createdAt = 0, updatedAt = 0
    )

    private fun labels(e: DownloadEntity) = e.facts().map { it.label }

    @Test
    fun speedAndTimeLeftOnlyWhileRunning() {
        assertTrue("Speed" in labels(entity(DownloadStatus.RUNNING, speed = 500)))
        assertTrue("Speed" !in labels(entity(DownloadStatus.PAUSED, speed = 500)))
        assertTrue("Time left" !in labels(entity(DownloadStatus.COMPLETED)))
    }

    @Test
    fun unknownSizeSaysUnknown() {
        val f = entity(DownloadStatus.PAUSED, total = 0).facts().first { it.label == "Size" }
        assertEquals("Unknown", f.value)
    }

    @Test
    fun runningWithNoSpeedShowsDashForTimeLeft() {
        val f = entity(DownloadStatus.RUNNING, speed = 0).facts().first { it.label == "Time left" }
        assertEquals("—", f.value)
    }

    @Test
    fun savedToAndLinkTakeAFullRow() {
        val facts = entity(DownloadStatus.COMPLETED, path = "/storage/Download/f.iso").facts()
        assertTrue(facts.first { it.label == "Saved to" }.wide)
        assertTrue(facts.first { it.label == "Link" }.wide)
    }

    @Test
    fun noSavedToWhenThereIsNoFileYet() {
        assertTrue("Saved to" !in labels(entity(DownloadStatus.RUNNING)))
    }

    @Test
    fun primaryActionFollowsStatus() {
        assertEquals(PrimaryAction.PAUSE, primaryActionFor(DownloadStatus.RUNNING))
        assertEquals(PrimaryAction.PAUSE, primaryActionFor(DownloadStatus.QUEUED))
        assertEquals(PrimaryAction.RESUME, primaryActionFor(DownloadStatus.PAUSED))
        assertEquals(PrimaryAction.RETRY, primaryActionFor(DownloadStatus.FAILED))
        assertEquals(PrimaryAction.OPEN, primaryActionFor(DownloadStatus.COMPLETED))
    }

    // ---- error wording --------------------------------------------------------------

    @Test
    fun httpCodesBecomeSentences() {
        assertEquals("Link refused (403)", friendlyError("Server returned HTTP 403").short)
        assertEquals("Link refused (401)", friendlyError("Server returned HTTP 401").short)
        assertEquals("File not found (404)", friendlyError("Server returned HTTP 404").short)
        assertEquals("Link expired (410)", friendlyError("Server returned HTTP 410").short)
        assertEquals("Too many requests (429)", friendlyError("Server returned HTTP 429").short)
        assertEquals("Server problem (503)", friendlyError("Server returned HTTP 503").short)
    }

    @Test
    fun commonIoFailuresAreRecognised() {
        assertEquals("Not enough space", friendlyError("write failed: ENOSPC (No space left on device)").short)
        assertEquals("Server not found", friendlyError("Unable to resolve host \"example.org\"").short)
        assertEquals("Server too slow", friendlyError("SocketTimeoutException: timeout").short)
        assertEquals("Connection dropped", friendlyError("Connection reset").short)
        assertEquals("Secure connection failed", friendlyError("javax.net.ssl.SSLHandshakeException").short)
    }

    @Test
    fun missingMessageStillSaysSomethingAndKeepsNoRaw() {
        val e = friendlyError("   ")
        assertEquals("Needs attention", e.short)
        assertNull(e.raw)
    }

    @Test
    fun unknownMessagesPassThroughTrimmedForTheRow() {
        val long = "x".repeat(200)
        val e = friendlyError(long)
        assertEquals(60, e.short.length)
        assertEquals(long, e.detail)
        assertNotNull(e.raw)
    }
}
