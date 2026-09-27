package com.downloadhub.app.update

import com.downloadhub.app.ui.ytdlpStatusDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * yt-dlp now updates itself, so the row must be read-only unless there is genuinely
 * something the user can do. The old build always showed a "Check for update"
 * button, which is why the user saw a control that did nothing most of the time.
 */
class YtDlpUpdateStateTest {

    @Test
    fun aFreshInstallOffersNoAction() {
        assertFalse(YtDlpUpdateState.Idle.needsAction)
    }

    @Test
    fun anUpToDateInstallOffersNoAction() {
        assertFalse(YtDlpUpdateState.UpToDate("2026.08.19").needsAction)
    }

    @Test
    fun checkingIsNotOfferedAsAnAction() {
        // The check runs by itself; a button during this state would let a second
        // tap start a rival pass.
        assertFalse(YtDlpUpdateState.Checking.needsAction)
    }

    @Test
    fun installingIsNotOfferedAsAnAction() {
        val state = YtDlpUpdateState.Updating("2026.08.19", "2026.09.01")
        assertFalse(state.needsAction)
        assertTrue(state.isBusy)
    }

    @Test
    fun aFailedCheckOffersARetry() {
        assertTrue(YtDlpUpdateState.Failed("no network").needsAction)
    }

    @Test
    fun anUnfinishedInstallOffersARetry() {
        val state = YtDlpUpdateState.Available("2026.08.19", "2026.09.01")
        assertTrue(state.needsAction)
        assertTrue(state.hasUpdate)
    }

    @Test
    fun busyStatesAreOnlyTheOnesInFlight() {
        assertTrue(YtDlpUpdateState.Checking.isBusy)
        assertTrue(YtDlpUpdateState.Updating("a", "b").isBusy)
        assertFalse(YtDlpUpdateState.Idle.isBusy)
        assertFalse(YtDlpUpdateState.UpToDate("a").isBusy)
        assertFalse(YtDlpUpdateState.Available("a", "b").isBusy)
        assertFalse(YtDlpUpdateState.Failed("x").isBusy)
    }

    @Test
    fun aFinishedCheckIsNotStillReportedAsAnUpdate() {
        // hasUpdate must stay false once the install succeeded, otherwise the row
        // would keep claiming there is something to apply.
        assertFalse(YtDlpUpdateState.UpToDate("2026.09.01").hasUpdate)
        assertFalse(YtDlpUpdateState.Idle.hasUpdate)
        assertFalse(YtDlpUpdateState.Updating("a", "b").hasUpdate)
    }

    @Test
    fun everyStateHasAStatusLine() {
        val states = listOf(
            YtDlpUpdateState.Idle,
            YtDlpUpdateState.Checking,
            YtDlpUpdateState.Updating("2026.08.19", "2026.09.01"),
            YtDlpUpdateState.UpToDate("2026.09.01"),
            YtDlpUpdateState.Available("2026.08.19", "2026.09.01"),
            YtDlpUpdateState.Failed("no network")
        )
        states.forEach { state ->
            val text = ytdlpStatusDetail(state)
            assertTrue("$state produced blank text", text.isNotBlank())
            assertTrue(
                "status text for $state must stay short: '$text'",
                text.length < 60
            )
        }
    }

    @Test
    fun everyStatusLineIsPlainAscii() {
        // A corrupt literal once rendered as thousands of garbage characters; the
        // status line is user-visible, so keep it boring.
        val states = listOf(
            YtDlpUpdateState.Idle,
            YtDlpUpdateState.Checking,
            YtDlpUpdateState.Updating("a", "b"),
            YtDlpUpdateState.UpToDate("a"),
            YtDlpUpdateState.Available("a", "b"),
            YtDlpUpdateState.Failed("x")
        )
        states.forEach { state ->
            val text = ytdlpStatusDetail(state)
            assertTrue(
                "non-ascii in status text for $state: '$text'",
                text.all { it.code in 32..126 }
            )
        }
    }

    @Test
    fun theInstallingLineNamesTheVersion() {
        val text = ytdlpStatusDetail(YtDlpUpdateState.Updating("2026.08.19", "2026.09.01"))
        assertTrue("expected the target version in '$text'", text.contains("2026.09.01"))
    }
}
