package com.downloadhub.desktop

import com.downloadhub.core.DownloadStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietStartTest {
    @Test
    fun anIdleQueueLoadsNoWindowLayer() {
        val idle = listOf(DownloadStatus.COMPLETED, DownloadStatus.PAUSED, DownloadStatus.FAILED, DownloadStatus.QUEUED)
        assertFalse(QuietStart.needsUi(opened = false, hasIntake = false, statuses = idle))
    }

    @Test
    fun aShowRequestIsSeenOnce() {
        val file = java.io.File.createTempFile("show", ".request").apply { delete() }
        assertFalse(QuietStart.takeShowRequest(file))
        QuietStart.askToShow(file)
        assertTrue(QuietStart.takeShowRequest(file))
        assertFalse(QuietStart.takeShowRequest(file))
    }

    @Test
    fun theTrayAHandOffOrAMovingDownloadBringsItIn() {
        assertTrue(QuietStart.needsUi(true, false, emptyList()))
        assertTrue(QuietStart.needsUi(false, true, emptyList()))
        assertTrue(QuietStart.needsUi(false, false, listOf(DownloadStatus.RUNNING)))
    }
}
