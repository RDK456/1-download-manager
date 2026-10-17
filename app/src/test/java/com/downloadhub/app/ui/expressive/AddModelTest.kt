package com.downloadhub.app.ui.expressive

import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.data.model.DownloadCategory
import com.downloadhub.app.data.model.DownloadSource
import com.downloadhub.app.data.model.DownloadStatus
import com.downloadhub.app.ui.expressive.model.AddNote
import com.downloadhub.app.ui.expressive.model.LinkCheck
import com.downloadhub.app.ui.expressive.model.NoteLevel
import com.downloadhub.app.ui.expressive.model.Probe
import com.downloadhub.app.ui.expressive.model.checkLink
import com.downloadhub.app.ui.expressive.model.interpretProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddModelTest {
    private fun existing(url: String, done: Long = 50, total: Long = 100) = DownloadEntity(
        id = "e1", source = DownloadSource.HTTP, url = url, fileName = "thing.iso", mimeType = null,
        category = DownloadCategory.FILE, status = DownloadStatus.RUNNING, bytesDownloaded = done, totalBytes = total,
        progressPercent = 0, speedBytesPerSecond = 0, etaSeconds = -1, outputPath = null,
        torrentFilePath = null, torrentInfoHash = null, userAgent = null, contentDisposition = null,
        etag = null, lastModified = null, errorMessage = null, createdAt = 0, updatedAt = 0
    )

    private fun check(text: String, batch: Int = 1, list: List<DownloadEntity> = emptyList()) = checkLink(text, list, batch, 1000)

    @Test fun blankIsEmptyAndCannotSubmit() {
        assertEquals(LinkCheck.Empty, check("   "))
        assertFalse(check("").canSubmit)
    }

    @Test fun plainTextIsInvalidWithAHelpfulReason() {
        val r = check("hello there") as LinkCheck.Invalid
        assertTrue(r.reason.contains("http://"))
        assertFalse(check("hello there").canSubmit)
    }

    @Test fun anAddressWithoutASchemeIsToldToAddOne() {
        assertTrue((check("www.example.com/file.zip") as LinkCheck.Invalid).reason.contains("https://"))
        assertTrue((check("example.org/file.zip") as LinkCheck.Invalid).reason.contains("https://"))
    }

    @Test fun httpAndHttpsAreFine() {
        assertEquals(LinkCheck.Http, check("https://e.org/a.zip"))
        assertEquals(LinkCheck.Http, check("http://e.org/a.zip"))
        assertTrue(check("https://e.org/a.zip").canSubmit)
    }

    @Test fun aLinkInsideSharedTextIsFound() {
        assertEquals(LinkCheck.Http, check("look at this https://e.org/a.zip it is good"))
    }

    @Test fun magnetsAndTorrentUrlsAreTorrents() {
        assertEquals(LinkCheck.Torrent(isMagnet = true), check("magnet:?xt=urn:btih:abc"))
        assertEquals(LinkCheck.Torrent(isMagnet = false), check("https://e.org/a.torrent"))
    }

    @Test fun youTubeIsHandedOffNotSubmitted() {
        assertEquals(LinkCheck.YouTube, check("https://www.youtube.com/watch?v=abc"))
        assertFalse(check("https://youtu.be/abc").canSubmit)
    }

    @Test fun duplicateReportsHowFarTheExistingOneGot() {
        val r = check("https://e.org/thing.iso/", list = listOf(existing("https://e.org/thing.iso"))) as LinkCheck.Duplicate
        assertEquals("e1", r.existingId)
        assertEquals(50, r.percent)
        assertTrue(r.canSubmit)
    }

    @Test fun batchWinsAndFlagsTheCap() {
        assertEquals(LinkCheck.Batch(120, false), check("x", batch = 120))
        assertEquals(LinkCheck.Batch(1000, true), check("x", batch = 1000))
    }

    // ---- probe ------------------------------------------------------------------------

    private fun probe(code: Int = 200, length: Long = 10_000_000, type: String? = "application/zip", ranges: Boolean = true, finalUrl: String? = null) =
        Probe(code, length, type, ranges, finalUrl)

    private fun notes(p: Probe?, url: String = "https://e.org/a.zip", free: Long = 50_000_000_000, removable: Boolean = false) =
        interpretProbe(p, url, free, removable)

    private fun List<AddNote>.titles() = map { it.title }

    @Test fun aCleanProbeSaysNothing() {
        assertTrue(notes(probe()).isEmpty())
    }

    @Test fun noProbeIsOneGentleLine() {
        val n = notes(null)
        assertEquals(1, n.size)
        assertEquals(NoteLevel.INFO, n[0].level)
    }

    @Test fun notFoundAndGoneAreErrors() {
        assertEquals(NoteLevel.ERROR, notes(probe(404)).single().level)
        assertTrue(notes(probe(410)).single().title.contains("expired"))
    }

    @Test fun refusedIsAWarningNotAnError() {
        assertEquals(NoteLevel.WARN, notes(probe(403)).single().level)
    }

    @Test fun htmlWhereAFileWasPromisedIsAWarning() {
        assertTrue("This looks like a web page" in notes(probe(type = "text/html; charset=utf-8"), "https://e.org/setup.exe").titles())
    }

    @Test fun htmlFromASignInAddressSaysSignIn() {
        val n = notes(probe(type = "text/html", finalUrl = "https://accounts.e.org/login?next=x"), "https://e.org/setup.exe")
        assertTrue("This link needs you to sign in" in n.titles())
    }

    @Test fun anHtmlPageUrlIsNotWarnedAbout() {
        assertTrue(notes(probe(type = "text/html"), "https://e.org/index.html").isEmpty())
    }

    @Test fun notEnoughSpaceIsAnErrorWithNumbers() {
        val n = notes(probe(length = 5_000_000_000), free = 5_050_000_000)
        assertEquals(NoteLevel.ERROR, n.single().level)
        assertTrue(n.single().body.contains("5.0 GB"))
    }

    @Test fun enoughSpaceIsQuiet() {
        assertTrue(notes(probe(length = 5_000_000_000), free = 6_000_000_000).isEmpty())
    }

    @Test fun unknownFreeSpaceDoesNotRaiseAFalseAlarm() {
        assertTrue(notes(probe(length = 5_000_000_000), free = -1).isEmpty())
    }

    @Test fun overFourGigabytesOnACardWarns() {
        assertTrue("May be too big for this folder" in notes(probe(length = 6_000_000_000), removable = true).titles())
        assertFalse("May be too big for this folder" in notes(probe(length = 6_000_000_000), removable = false).titles())
        assertFalse("May be too big for this folder" in notes(probe(length = 3_000_000_000), removable = true).titles())
    }

    @Test fun noRangesOnABigFileMentionsResume() {
        assertTrue("Cannot be resumed" in notes(probe(length = 500_000_000, ranges = false)).titles())
        assertFalse("Cannot be resumed" in notes(probe(length = 5_000_000, ranges = false)).titles())
    }

    @Test fun aMissingFileIsNotAlsoCalledAWebPage() {
        val n = notes(probe(404, length = -1, type = "text/html"), "https://e.org/file.dat")
        assertEquals(listOf("File not found (404)"), n.titles())
    }
}
