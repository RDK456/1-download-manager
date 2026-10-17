package com.downloadhub.app.ui.expressive

import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.data.model.DownloadCategory
import com.downloadhub.app.data.model.DownloadSource
import com.downloadhub.app.data.model.DownloadStatus
import com.downloadhub.app.ui.expressive.model.FREE_SPACE_FLOOR
import com.downloadhub.app.ui.expressive.model.PendingDeletes
import com.downloadhub.app.ui.expressive.model.remainingBytes
import com.downloadhub.app.ui.expressive.model.storageIsLow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteAndStorageTest {
    private var clock = 1_000L
    private fun pending() = PendingDeletes(now = { clock }, windowMillis = 8_000)

    @Test
    fun addedIdsAreHiddenAtOnce() {
        val p = pending()
        p.add(listOf("a", "b"))
        assertEquals(setOf("a", "b"), p.ids())
    }

    @Test
    fun nothingIsDueInsideTheWindow() {
        val p = pending()
        p.add(listOf("a"))
        clock += 7_999
        assertTrue(p.takeDue().isEmpty())
        assertEquals(setOf("a"), p.ids())
    }

    @Test
    fun idsBecomeDueWhenTheWindowRunsOutAndAreTakenOnce() {
        val p = pending()
        p.add(listOf("a"))
        clock += 8_000
        assertEquals(listOf("a"), p.takeDue())
        assertTrue(p.takeDue().isEmpty())
        assertTrue(p.ids().isEmpty())
    }

    @Test
    fun undoBringsRowsBackAndLateUndoDoesNothing() {
        val p = pending()
        p.add(listOf("a", "b"))
        assertEquals(listOf("a"), p.undo(listOf("a")))
        assertEquals(setOf("b"), p.ids())
        clock += 9_000
        p.takeDue()
        assertTrue(p.undo(listOf("b")).isEmpty())
    }

    @Test
    fun laterDeletesGetTheirOwnWindow() {
        val p = pending()
        p.add(listOf("a"))
        clock += 5_000
        p.add(listOf("b"))
        clock += 3_000
        assertEquals(listOf("a"), p.takeDue())
        assertEquals(setOf("b"), p.ids())
    }

    // ---- storage ------------------------------------------------------------------

    private fun item(status: DownloadStatus, done: Long, total: Long) = DownloadEntity(
        id = "i$done$total$status", source = DownloadSource.HTTP, url = "u", fileName = "f", mimeType = null,
        category = DownloadCategory.FILE, status = status, bytesDownloaded = done, totalBytes = total,
        progressPercent = 0, speedBytesPerSecond = 0, etaSeconds = -1, outputPath = null,
        torrentFilePath = null, torrentInfoHash = null, userAgent = null, contentDisposition = null,
        etag = null, lastModified = null, errorMessage = null, createdAt = 0, updatedAt = 0
    )

    @Test
    fun remainingCountsOnlyWorkStillToDo() {
        val list = listOf(
            item(DownloadStatus.RUNNING, 400, 1000),
            item(DownloadStatus.QUEUED, 0, 500),
            item(DownloadStatus.PAUSED, 0, 9999),
            item(DownloadStatus.COMPLETED, 10, 10),
            item(DownloadStatus.RUNNING, 0, 0)
        )
        assertEquals(1100L, remainingBytes(list))
    }

    @Test
    fun lowWhenUnderTheFloor() {
        assertTrue(storageIsLow(FREE_SPACE_FLOOR - 1, 0))
        assertFalse(storageIsLow(FREE_SPACE_FLOOR, 0))
    }

    @Test
    fun lowWhenTheQueueNeedsMoreThanIsFree() {
        assertTrue(storageIsLow(2_000_000_000L, 2_000_000_000L))
        assertFalse(storageIsLow(2_000_000_000L, 1_000_000_000L))
    }

    @Test
    fun unreadableFreeSpaceIsNotLow() {
        assertFalse(storageIsLow(-1, 5_000_000_000L))
    }
}
