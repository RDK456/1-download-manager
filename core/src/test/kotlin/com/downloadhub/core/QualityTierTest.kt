package com.downloadhub.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The labels have to be the ones people compare against.
 *
 * A real video offered 1772, 1182, 886, 590 and 394 - all near a familiar rung,
 * none of them on one - because the site scales to the source rather than to a
 * menu. Read as pixel heights those rows say nothing; read as 4K, 1080p, 720p,
 * 480p and 360p they are the ladder everyone already has in their head.
 */
class QualityTierTest {

    private fun video(height: Int, fps: Int = 30) = StreamFormat(
        formatId = "v$height-$fps",
        ext = "mp4",
        height = height,
        fps = fps,
        videoCodec = "avc1",
        audioCodec = "none",
        sizeBytes = 100_000_000L,
        totalBitrate = null
    )

    @Test
    fun theRealHeightsFromAVideoLandOnTheRungsPeopleUse() {
        assertEquals("4K", video(1772).tierLabel)
        assertEquals("4K", video(2160).tierLabel)
        assertEquals("2K", video(1440).tierLabel)
        assertEquals("1080p", video(1182).tierLabel)
        assertEquals("1080p", video(1080).tierLabel)
        assertEquals("720p", video(886).tierLabel)
        assertEquals("720p", video(720).tierLabel)
        assertEquals("480p", video(590).tierLabel)
        assertEquals("480p", video(480).tierLabel)
        assertEquals("360p", video(394).tierLabel)
        assertEquals("360p", video(360).tierLabel)
        assertEquals("240p", video(240).tierLabel)
        assertEquals("144p", video(144).tierLabel)
    }

    /**
     * 1182 is 102 from 1080 and 258 from 1440, so nearest-number rounding calls
     * it 2K. It is 1080p, and a threshold is what says so.
     */
    @Test
    fun theBoundaryIsARungNotAMidpoint() {
        assertEquals("1080p", video(1182).tierLabel)
        assertEquals("2K", video(1200).tierLabel)
        assertEquals("4K", video(1600).tierLabel)
    }

    @Test
    fun theExactHeightStaysVisibleWhereItIsNotTheLabel() {
        assertEquals("1080p (1182p)", video(1182).displayLabel)
        assertEquals("720p (886p)", video(886).displayLabel)
        // 4K and 2K are said out loud, so the raw number is noise there.
        assertEquals("4K", video(1772).displayLabel)
        assertEquals("1080p", video(1080).displayLabel)
    }

    @Test
    fun frameRateStillMakesTheDifference() {
        // fullLabel keeps the raw height and the frame rate, so it stays exact.
        assertEquals("1080p60", video(1080, fps = 60).fullLabel)
        assertEquals("1182p", video(1182).fullLabel)
        // displayLabel is the friendly tier, and 4K says everything the 2160
        // would have, so the raw number is not appended there.
        assertEquals("4K", video(2160, fps = 60).displayLabel)
        assertEquals("1080p (1182p)", video(1182, fps = 60).displayLabel)
    }
}
