package com.downloadhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-download settings, taken from AB Download Manager and qBittorrent.
 *
 * Each rule here is a decision the user can see get made or not made, and each was a
 * decision that did not exist before: one large file could saturate the connection and
 * quietly slow everything behind it, the queue had no order the user could influence,
 * and a finished torrent seeded for ever with no way to say otherwise.
 */
class TransferRulesTest {

    // --- speed limit ---------------------------------------------------------

    @Test
    fun withNeitherLimitSetNothingIsThrottled() {
        assertEquals(0L, TransferRules.effectiveSpeedLimit(0L, 0L))
        assertFalse(TransferRules.isThrottled(TransferRules.effectiveSpeedLimit(0L, 0L)))
    }

    /** The app-wide limit still applies when an item sets none of its own. */
    @Test
    fun anItemWithNoLimitOfItsOwnUsesTheGlobalOne() {
        assertEquals(1024L, TransferRules.effectiveSpeedLimit(0L, 1024L))
    }

    /**
     * An item's own limit wins.
     *
     * This is the point of the feature: capping one file so it stops crowding out
     * everything queued behind it.
     */
    @Test
    fun anItemsOwnLimitIsUsed() {
        assertEquals(512L, TransferRules.effectiveSpeedLimit(512L, 0L))
    }

    /**
     * The global limit stays a ceiling.
     *
     * Raising a limit on one file must not push it past the cap the user set for
     * everything, or "unlimited per item" would quietly mean "unlimited overall".
     */
    @Test
    fun anItemCanLowerTheGlobalLimitButNotRaiseIt() {
        assertEquals("below the global limit, its own applies", 512L, TransferRules.effectiveSpeedLimit(512L, 4096L))
        assertEquals("above the global limit, the global cap holds", 4096L, TransferRules.effectiveSpeedLimit(9999L, 4096L))
    }

    @Test
    fun aNegativeLimitIsTreatedAsNone() {
        assertEquals(2048L, TransferRules.effectiveSpeedLimit(-1L, 2048L))
        assertEquals(0L, TransferRules.effectiveSpeedLimit(-1L, -1L))
    }

    // --- scheduling ----------------------------------------------------------

    @Test
    fun anUnscheduledItemStartsWhenever() {
        assertTrue(TransferRules.isStartable(0L, nowEpochMillis = 1_700_000_000_000L))
        assertTrue(TransferRules.isStartable(-5L, nowEpochMillis = 1_700_000_000_000L))
    }

    @Test
    fun aScheduledItemWaitsForItsTime() {
        val start = 1_700_000_000_000L
        assertFalse("too early", TransferRules.isStartable(start, start - 1))
        assertTrue("exactly on time", TransferRules.isStartable(start, start))
        assertTrue("after", TransferRules.isStartable(start, start + 1))
    }

    // --- queue order ---------------------------------------------------------

    private fun item(id: String, priority: DownloadPriority, createdAt: Long) =
        DownloadItem(id = id, url = "https://x/$id", fileName = id, priority = priority, createdAt = createdAt)

    @Test
    fun higherPriorityIsServedFirst() {
        val items = listOf(
            item("low", DownloadPriority.LOW, 1),
            item("high", DownloadPriority.HIGH, 3),
            item("normal", DownloadPriority.NORMAL, 2)
        )
        assertEquals(listOf("high", "normal", "low"), items.sortedWith(TransferRules.queueOrder()).map { it.id })
    }

    /**
     * Same priority falls back to the order they were added.
     *
     * Without this, restarting the queue could reshuffle items that were all "normal",
     * which reads as the app having lost track of them.
     */
    @Test
    fun equalPriorityKeepsTheOrderTheyWereAdded() {
        val items = listOf(
            item("third", DownloadPriority.NORMAL, 30),
            item("first", DownloadPriority.NORMAL, 10),
            item("second", DownloadPriority.NORMAL, 20)
        )
        assertEquals(
            listOf("first", "second", "third"),
            items.sortedWith(TransferRules.queueOrder()).map { it.id }
        )
    }

    @Test
    fun priorityRankRoundTrips() {
        DownloadPriority.entries.forEach { priority ->
            assertEquals(priority, DownloadPriority.fromRank(priority.rank))
        }
        assertEquals(DownloadPriority.NORMAL, DownloadPriority.fromRank(99))
    }

    // --- share limits --------------------------------------------------------

    private val now = 1_700_000_000_000L

    @Test
    fun withNoLimitsATorrentSeedsForEver() {
        val limits = ShareLimits()
        assertFalse(limits.enabled)
        assertFalse(
            TransferRules.shouldStopSeeding(1000, 999_999, seedingSinceEpochMillis = now - 99_999, nowEpochMillis = now, limits = limits)
        )
    }

    /**
     * Stop at a ratio, the way qBittorrent does.
     *
     * Reached exactly counts as reached: "stop at 2.0" means it does not go past 2.0.
     */
    @Test
    fun aRatioLimitStopsSeedingOnceReached() {
        val limits = ShareLimits(ratioLimit = 2.0)
        assertFalse("below", TransferRules.shouldStopSeeding(1000, 1500, now - 1000, now, limits))
        assertTrue("exactly", TransferRules.shouldStopSeeding(1000, 2000, now - 1000, now, limits))
        assertTrue("past", TransferRules.shouldStopSeeding(1000, 5000, now - 1000, now, limits))
    }

    @Test
    fun aTimeLimitStopsSeedingOnceElapsed() {
        val limits = ShareLimits(seedTimeLimitMinutes = 30)
        val thirty = 30 * 60_000L
        assertFalse("29 minutes", TransferRules.shouldStopSeeding(1000, 0, now - (thirty - 1), now, limits))
        assertTrue("exactly 30", TransferRules.shouldStopSeeding(1000, 0, now - thirty, now, limits))
    }

    @Test
    fun eitherLimitAloneIsEnough() {
        val ratioOnly = ShareLimits(ratioLimit = 1.0)
        assertTrue(TransferRules.shouldStopSeeding(1000, 1000, now - 1, now, ratioOnly))

        val timeOnly = ShareLimits(seedTimeLimitMinutes = 1)
        assertTrue(TransferRules.shouldStopSeeding(1000, 0, now - 60_000L, now, timeOnly))
    }

    /**
     * A torrent that has not finished is not seeding.
     *
     * Zero means "not finished yet" throughout, so a time limit cannot fire while a
     * file is still downloading and the clock has not started.
     */
    @Test
    fun aTorrentThatHasNotFinishedIsNeverStopped() {
        val limits = ShareLimits(ratioLimit = 0.1, seedTimeLimitMinutes = 1)
        assertFalse(
            TransferRules.shouldStopSeeding(1000, 5000, seedingSinceEpochMillis = 0L, nowEpochMillis = now, limits = limits)
        )
    }

    @Test
    fun theShareRatioIsUploadedOverDownloaded() {
        assertEquals(2.0, TransferRules.shareRatio(1000, 2000), 0.001)
        assertEquals(0.5, TransferRules.shareRatio(2000, 1000), 0.001)
        assertEquals("nothing downloaded yet means no ratio", 0.0, TransferRules.shareRatio(0, 500), 0.001)
    }

    /**
     * Saying why it stopped, rather than the torrent just going quiet.
     *
     * A torrent that stops uploading on its own and gives no explanation looks broken.
     */
    @Test
    fun stoppingSaysWhichLimitWasReached() {
        val ratio = TransferRules.stopReason(1000, 3000, now - 1000, now, ShareLimits(ratioLimit = 2.0))
        assertNotNull(ratio)
        assertTrue("mentions the ratio it reached: $ratio", ratio!!.contains("3.00"))

        val time = TransferRules.stopReason(
            1000, 0, now - 30 * 60_000L, now, ShareLimits(seedTimeLimitMinutes = 30)
        )
        assertNotNull(time)
        assertTrue("mentions the time: $time", time!!.contains("30 min"))

        assertNull(
            "nothing to say when it has not stopped",
            TransferRules.stopReason(1000, 0, now - 1000, now, ShareLimits())
        )
    }

    @Test
    fun anItemExposesItsSharingLimits() {
        val item = DownloadItem(
            id = "x",
            url = "magnet:?xt=urn:btih:0",
            fileName = "x",
            shareRatioLimit = 2.5,
            seedTimeLimitMinutes = 60
        )
        assertTrue(item.shareLimits.enabled)
        assertEquals(2.5, item.shareLimits.ratioLimit, 0.001)
        assertEquals(60, item.shareLimits.seedTimeLimitMinutes)
    }

    /** A download from before this existed must behave exactly as it did. */
    @Test
    fun anItemWithNoSettingsBehavesLikeTheOldOne() {
        val item = DownloadItem(id = "x", url = "https://x/a.zip", fileName = "a.zip")
        assertEquals(DownloadPriority.NORMAL, item.priority)
        assertEquals(0L, item.speedLimitBytesPerSecond)
        assertTrue(TransferRules.isStartable(item.startAfterEpochMillis, now))
        assertFalse(item.shareLimits.enabled)
    }
}
