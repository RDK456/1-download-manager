package com.downloadhub.app.ui.expressive

import com.downloadhub.app.ui.expressive.model.BannerKind
import com.downloadhub.app.ui.expressive.model.DismissalLedger
import com.downloadhub.app.ui.expressive.model.snoozeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BannerDismissalsTest {
    private val day = 24L * 60 * 60 * 1000

    @Test fun aDismissedNudgeStaysAwayForThirtyDays() {
        val ledger = DismissalLedger().dismiss(BannerKind.BATTERY, now = 1_000)
        assertEquals(setOf(BannerKind.BATTERY), ledger.dismissed(now = 1_000 + 29 * day))
        assertTrue(ledger.dismissed(now = 1_000 + 30 * day).isEmpty())
    }

    @Test fun theUpdateNoticeComesBackAfterAWeek() {
        val ledger = DismissalLedger().dismiss(BannerKind.UPDATE, now = 5)
        assertEquals(setOf(BannerKind.UPDATE), ledger.dismissed(now = 5 + 6 * day))
        assertTrue(ledger.dismissed(now = 5 + 7 * day).isEmpty())
    }

    @Test fun noticesAboutRightNowCannotBeSnoozed() {
        assertNull(BannerKind.NETWORK.snoozeMillis())
        assertNull(BannerKind.STORAGE.snoozeMillis())
        val ledger = DismissalLedger().dismiss(BannerKind.NETWORK, now = 5)
        assertTrue(ledger.dismissed(now = 6).isEmpty())
    }

    @Test fun dismissingOneDoesNotHideTheOther() {
        val ledger = DismissalLedger().dismiss(BannerKind.BATTERY, 10)
        assertEquals(setOf(BannerKind.BATTERY), ledger.dismissed(20))
        assertTrue(BannerKind.NOTIFICATIONS !in ledger.dismissed(20))
    }

    @Test fun aClockSetBackwardsDoesNotHideANoticeForever() {
        val ledger = DismissalLedger().dismiss(BannerKind.BATTERY, now = 1_000_000)
        assertTrue(ledger.dismissed(now = 500).isEmpty())
    }

    @Test fun storedTimesRoundTrip() {
        val stored = mapOf(BannerKind.BATTERY to 777L)
        val ledger = DismissalLedger.from { stored[it] ?: 0L }
        assertEquals(setOf(BannerKind.BATTERY), ledger.dismissed(800))
        assertTrue(DismissalLedger.from { 0L }.dismissed(800).isEmpty())
    }
}
