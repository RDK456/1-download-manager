package com.downloadhub.app.ui.expressive

import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.data.model.DownloadCategory
import com.downloadhub.app.data.model.DownloadSource
import com.downloadhub.app.data.model.DownloadStatus
import com.downloadhub.app.ui.expressive.model.BannerKind
import com.downloadhub.app.ui.expressive.model.BarStyle
import com.downloadhub.app.ui.expressive.model.RowConditions
import com.downloadhub.app.ui.expressive.model.StateShapeKind
import com.downloadhub.app.ui.expressive.model.planBanners
import com.downloadhub.app.ui.expressive.model.spoken
import com.downloadhub.app.ui.expressive.model.splitFileName
import com.downloadhub.app.ui.expressive.model.toRowModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RowModelTest {
    private fun entity(
        status: DownloadStatus,
        done: Long = 0,
        total: Long = 0,
        speed: Long = 0,
        eta: Long = -1,
        error: String? = null,
        percent: Int = 0
    ) = DownloadEntity(
        id = "x", source = DownloadSource.HTTP, url = "https://e.org/f", fileName = "f.iso", mimeType = null,
        category = DownloadCategory.FILE, status = status, bytesDownloaded = done, totalBytes = total,
        progressPercent = percent, speedBytesPerSecond = speed, etaSeconds = eta, outputPath = null,
        torrentFilePath = null, torrentInfoHash = null, userAgent = null, contentDisposition = null,
        etag = null, lastModified = null, errorMessage = error, createdAt = 0, updatedAt = 0
    )

    @Test
    fun runningRowShowsProgressSpeedAndTime() {
        val m = entity(DownloadStatus.RUNNING, 620, 1000, speed = 2_000_000, eta = 125).toRowModel()
        assertEquals("Downloading", m.headline)
        assertEquals("62% of 1.0 KB", m.detail)
        assertEquals("2.0 MB/s", m.speed)
        assertEquals("2m 5s", m.eta)
        assertEquals(BarStyle.WAVY, m.bar)
        assertEquals(StateShapeKind.COOKIE, m.shape)
    }

    @Test
    fun noSpeedGivesDashesNotInfinity() {
        val m = entity(DownloadStatus.RUNNING, 10, 1000, speed = 0, eta = -1).toRowModel()
        assertEquals("—", m.speed)
        assertEquals("—", m.eta)
    }

    @Test
    fun speedOnlyShownWhileTransferring() {
        for (s in listOf(DownloadStatus.PAUSED, DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.QUEUED)) {
            val m = entity(s, 10, 100, speed = 500).toRowModel()
            assertNull("$s", m.speed)
            assertNull("$s", m.eta)
        }
    }

    @Test
    fun unknownSizeIsIndeterminate() {
        val m = entity(DownloadStatus.RUNNING, total = 0, percent = 0).toRowModel()
        assertEquals(BarStyle.WAVY_INDETERMINATE, m.bar)
        assertEquals("0%", m.detail)
    }

    @Test
    fun offlineHoldsRunningRowsWithoutAnError() {
        val m = entity(DownloadStatus.RUNNING, 500, 1000, speed = 1000).toRowModel(RowConditions(offline = true))
        assertEquals("Waiting for network", m.headline)
        assertTrue(m.isWaiting)
        assertFalse(m.isError)
        assertEquals(StateShapeKind.CIRCLE, m.shape)
        assertEquals(BarStyle.FLAT, m.bar)
        assertNull(m.speed)
    }

    @Test
    fun lowStorageWinsOverOffline() {
        val m = entity(DownloadStatus.RUNNING, 500, 1000).toRowModel(RowConditions(offline = true, lowStorage = true))
        assertEquals("Paused: not enough space", m.headline)
    }

    @Test
    fun conditionsDoNotTouchFinishedOrPausedRows() {
        val c = RowConditions(offline = true, lowStorage = true)
        assertEquals("Finished", entity(DownloadStatus.COMPLETED, 10, 10).toRowModel(c).headline)
        assertEquals("Paused", entity(DownloadStatus.PAUSED, 5, 10).toRowModel(c).headline)
    }

    @Test
    fun failedRowCarriesItsReason() {
        val m = entity(DownloadStatus.FAILED, 40, 100, error = "Server said 403").toRowModel()
        assertEquals("Server said 403", m.headline)
        assertTrue(m.isError)
        assertEquals(StateShapeKind.PENTAGON, m.shape)
        assertTrue(m.canResume)
    }

    @Test
    fun failedRowWithoutAReasonStillSaysSomething() {
        assertEquals("Needs attention", entity(DownloadStatus.FAILED, error = "  ").toRowModel().headline)
    }

    @Test
    fun finishedRowHasNoBar() {
        val m = entity(DownloadStatus.COMPLETED, 100, 100).toRowModel()
        assertEquals(BarStyle.NONE, m.bar)
        assertEquals("100 B", m.detail)
    }

    @Test
    fun queuedRowAtZeroHasNoBar() {
        assertEquals(BarStyle.NONE, entity(DownloadStatus.QUEUED, 0, 1000).toRowModel().bar)
    }

    @Test
    fun resolvingRowIsIndeterminate() {
        assertEquals(BarStyle.WAVY_INDETERMINATE, entity(DownloadStatus.RESOLVING).toRowModel().bar)
    }

    // ---- file names -------------------------------------------------------------

    @Test
    fun shortNamesAreNotCut() {
        val p = splitFileName("notes.txt")
        assertEquals("", p.head)
        assertEquals("notes.txt", p.tail)
    }

    @Test
    fun longNamesKeepTheEndAndExtension() {
        val p = splitFileName("the-quick-brown-fox-jumps-over-the-lazy-dog-final-v2.pdf")
        assertEquals("the-quick-brown-fox-jumps-over-the-lazy-dog-fi", p.head)
        assertEquals("nal-v2.pdf", p.tail.takeLast(10))
        assertEquals("the-quick-brown-fox-jumps-over-the-lazy-dog-final-v2.pdf", p.full)
    }

    @Test
    fun nameWithoutExtensionSplitsToo() {
        val p = splitFileName("a-very-long-name-with-no-extension-at-all")
        assertEquals("a-very-long-name-with-no-extension-at-all", p.full)
        assertEquals(6, p.tail.length)
    }

    @Test
    fun dotFilesAndOddSuffixesAreNotExtensions() {
        assertEquals("", splitFileName(".bashrc").head)
        assertEquals("archive.version.thirtytwocharacters", splitFileName("archive.version.thirtytwocharacters").full)
    }

    @Test
    fun cutNeverSplitsAnEmoji() {
        val name = "holiday-pictures-from-the-trip-to-the-beach😀ab.jpg"
        val p = splitFileName(name, tailChars = 3)
        assertFalse(p.head.last().isHighSurrogate())
        assertEquals(name, p.full)
    }

    // ---- banners ----------------------------------------------------------------

    @Test
    fun oneBannerAtATimeInPriorityOrder() {
        val plan = planBanners(setOf(BannerKind.BATTERY, BannerKind.STORAGE, BannerKind.NETWORK))
        assertEquals(listOf(BannerKind.STORAGE), plan.shown)
        assertEquals(2, plan.hiddenCount)
    }

    @Test
    fun expandingShowsAllInOrder() {
        val plan = planBanners(setOf(BannerKind.UPDATE, BannerKind.NETWORK), expanded = true)
        assertEquals(listOf(BannerKind.NETWORK, BannerKind.UPDATE), plan.shown)
        assertEquals(0, plan.hiddenCount)
    }

    @Test
    fun dismissedBannersStayGone() {
        val plan = planBanners(setOf(BannerKind.NOTIFICATIONS, BannerKind.BATTERY), dismissed = setOf(BannerKind.NOTIFICATIONS))
        assertEquals(listOf(BannerKind.BATTERY), plan.shown)
        assertEquals(0, plan.hiddenCount)
    }

    @Test
    fun nothingActiveShowsNothing() {
        val plan = planBanners(emptySet())
        assertTrue(plan.shown.isEmpty())
        assertEquals(0, plan.hiddenCount)
    }

    // ---- screen reader ------------------------------------------------------------

    @Test
    fun spokenDescriptionIsOneSentence() {
        val m = entity(DownloadStatus.RUNNING, 620, 1000, speed = 2_000_000, eta = 125).toRowModel()
        assertEquals("f.iso, Downloading, 62 percent, 2.0 MB per second, 2m 5s left", m.spoken())
    }

    @Test
    fun spokenDescriptionSkipsWhatItDoesNotKnow() {
        assertEquals("f.iso, Finished", entity(DownloadStatus.COMPLETED, 10, 10).toRowModel().spoken())
        assertEquals("f.iso, Downloading, 1 percent", entity(DownloadStatus.RUNNING, 10, 1000).toRowModel().spoken())
    }

    @Test
    fun percentRoundsDownFromBytesNotUp() {
        assertEquals(29, entity(DownloadStatus.PAUSED, 29, 100).toRowModel().percent)
        assertEquals(99, entity(DownloadStatus.PAUSED, 999, 1000).toRowModel().percent)
    }
}
