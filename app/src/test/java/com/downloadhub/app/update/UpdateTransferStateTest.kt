package com.downloadhub.app.update

import com.downloadhub.app.update.TransferState.Done
import com.downloadhub.app.update.TransferState.Failed
import com.downloadhub.app.update.TransferState.Running
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The update download used to run in a ViewModel coroutine, so minimising the app
 * or changing page cancelled it. It now runs in a foreground service and hands its
 * outcome back through [UpdateTransferState]; these tests pin that hand-off, since
 * it is what makes a backgrounded update installable.
 */
class UpdateTransferStateTest {

    private val apk = File("1-download-manager-1.2.8.apk")

    /**
     * The state holder is a process-wide singleton, so each test starts from a known
     * value instead of whatever the previous one left behind. Going through fail()
     * then acknowledge() resets it because acknowledge() only spares a running
     * transfer.
     */
    @Before
    fun resetState() {
        UpdateTransferState.fail("reset")
        UpdateTransferState.acknowledge()
        assertEquals(TransferState.Idle, UpdateTransferState.state.value)
    }

    @Test
    fun startsIdle() {
        assertEquals(TransferState.Idle, UpdateTransferState.state.value)
    }

    @Test
    fun reportsProgressAsBytesArrive() {
        UpdateTransferState.start("1.2.8", "Version 1.2.8", totalBytes = 1000L)
        UpdateTransferState.progress(downloaded = 250L, total = 1000L)

        val running = UpdateTransferState.state.value as Running
        assertEquals(25, running.percent)
        assertEquals(250L, running.downloadedBytes)
        assertEquals(1000L, running.totalBytes)
    }

    @Test
    fun percentIsZeroWhileTheSizeIsUnknown() {
        // A server that omits Content-Length must not produce a divide-by-zero or
        // a misleading 100%.
        UpdateTransferState.start("1.2.8", "Version 1.2.8", totalBytes = 0L)
        UpdateTransferState.progress(downloaded = 5_000_000L, total = 0L)
        assertEquals(0, (UpdateTransferState.state.value as Running).percent)
    }

    @Test
    fun keepsTheKnownTotalWhenTheServerOmitsItLater() {
        UpdateTransferState.start("1.2.8", "Version 1.2.8", totalBytes = 2048L)
        UpdateTransferState.progress(downloaded = 1024L, total = 0L)
        assertEquals(2048L, (UpdateTransferState.state.value as Running).totalBytes)
    }

    @Test
    fun progressIsIgnoredBeforeTheTransferStarts() {
        UpdateTransferState.acknowledge()
        UpdateTransferState.progress(downloaded = 10L, total = 100L)
        assertEquals(
            "a stray progress tick must not invent a running transfer",
            TransferState.Idle,
            UpdateTransferState.state.value
        )
    }

    @Test
    fun aFinishedTransferKeepsTheFileForTheInstaller() {
        UpdateTransferState.start("1.2.8", "Version 1.2.8", 100L)
        UpdateTransferState.complete("1.2.8", "Version 1.2.8", apk)

        val done = UpdateTransferState.state.value as Done
        assertEquals("1.2.8", done.version)
        assertEquals(apk, done.apk)
    }

    @Test
    fun failuresAreSurfacedNotSwallowed() {
        UpdateTransferState.fail("HTTP 503")
        assertEquals(Failed("HTTP 503"), UpdateTransferState.state.value)
    }

    @Test
    fun acknowledgingResetsTheOutcomeButNotARunningTransfer() {
        UpdateTransferState.complete("1.2.8", "Version 1.2.8", apk)
        UpdateTransferState.acknowledge()
        assertEquals(TransferState.Idle, UpdateTransferState.state.value)

        // A download still going must not be reset by a stray acknowledge.
        UpdateTransferState.start("1.2.9", "Version 1.2.9", 100L)
        UpdateTransferState.acknowledge()
        assertTrue(UpdateTransferState.state.value is Running)
    }

    @Test
    fun aLaterTransferReplacesAnEarlierOutcome() {
        UpdateTransferState.complete("1.2.8", "Version 1.2.8", apk)
        UpdateTransferState.start("1.2.9", "Version 1.2.9", 100L)
        val running = UpdateTransferState.state.value as Running
        assertEquals("1.2.9", running.version)
    }
}
