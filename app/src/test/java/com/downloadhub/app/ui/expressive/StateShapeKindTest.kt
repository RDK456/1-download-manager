package com.downloadhub.app.ui.expressive

import com.downloadhub.app.data.model.DownloadStatus
import com.downloadhub.app.ui.expressive.model.StateShapeKind
import com.downloadhub.app.ui.expressive.model.shapeKindFor
import com.downloadhub.app.ui.expressive.model.spins
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StateShapeKindTest {
    @Test
    fun everyStatusHasAShape() {
        for (s in DownloadStatus.entries) shapeKindFor(s)
    }

    @Test
    fun runningIsTheOnlyKindThatSpins() {
        assertTrue(shapeKindFor(DownloadStatus.RUNNING).spins)
        for (s in DownloadStatus.entries - DownloadStatus.RUNNING) assertFalse("$s", shapeKindFor(s).spins)
    }

    @Test
    fun pausedAndRunningLookDifferent() {
        assertEquals(StateShapeKind.SQUIRCLE, shapeKindFor(DownloadStatus.PAUSED))
        assertEquals(StateShapeKind.COOKIE, shapeKindFor(DownloadStatus.RUNNING))
    }

    @Test
    fun failureAndNeedsAttentionShareTheWarningShape() {
        assertEquals(StateShapeKind.PENTAGON, shapeKindFor(DownloadStatus.FAILED))
        assertEquals(StateShapeKind.PENTAGON, shapeKindFor(DownloadStatus.RUNNING, needsAttention = true))
    }

    @Test
    fun aRunningRowThatIsWaitingLooksQueuedNotFailed() {
        assertEquals(StateShapeKind.CIRCLE, shapeKindFor(DownloadStatus.RUNNING, waiting = true))
    }

    @Test
    fun finishedIsABurstEvenIfSomethingIsWaiting() {
        assertEquals(StateShapeKind.BURST, shapeKindFor(DownloadStatus.COMPLETED, waiting = true))
    }

    @Test
    fun queuedAndResolvingAreAtRest() {
        assertEquals(StateShapeKind.CIRCLE, shapeKindFor(DownloadStatus.QUEUED))
        assertEquals(StateShapeKind.CIRCLE, shapeKindFor(DownloadStatus.RESOLVING))
    }
}
