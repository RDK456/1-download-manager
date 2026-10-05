package com.downloadhub.app.download

import com.downloadhub.app.data.AppQueue
import com.downloadhub.app.data.QueueCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueCodecTest {
    @Test
    fun queuesRoundTripAndMainIsAlwaysFirst() {
        val night = AppQueue("q1", "Night\tshift", maxConcurrent = 2, started = false, scheduleEnabled = true, days = setOf(6, 7), startMinute = 120, stopMinute = 360)
        val decoded = QueueCodec.decode(QueueCodec.encode(listOf(night)))
        assertEquals("main", decoded.first().id)
        assertEquals(night.copy(name = "Night shift"), decoded[1])
        assertEquals(listOf(AppQueue.main()), QueueCodec.decode(null))
        assertEquals(listOf(AppQueue.main()), QueueCodec.decode("garbage line"))
    }
}
