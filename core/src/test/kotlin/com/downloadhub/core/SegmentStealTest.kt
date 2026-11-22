package com.downloadhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentStealTest {
    private val mb = 1024L * 1024

    @Test
    fun idleWorkerTakesFarHalfAndRangesStillTile() {
        val state = SegmentState.plan(8 * mb, 1)
        state.steal() // the only range is now being fetched
        val taken = state.steal()!!
        assertEquals(state.segments[0].end + 1, taken.start)
        assertEquals(8 * mb - 1, taken.end)
        assertNotNull(SegmentState.decode(state.encode()))
    }

    @Test
    fun smallRemainderIsNotSplit() {
        val state = SegmentState.plan(3 * mb / 2 + 1, 1)
        state.steal()
        assertNull(state.steal())
    }

    @Test
    fun unfinishedIdleRangeComesBeforeSplitting() {
        val state = SegmentState.plan(8 * mb, 2)
        val a = state.steal()!!
        val b = state.steal()!!
        assertTrue(a !== b)
        assertEquals(2, state.segments.size)
    }
}
