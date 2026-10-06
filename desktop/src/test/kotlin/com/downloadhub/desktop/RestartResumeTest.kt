package com.downloadhub.desktop

import com.downloadhub.core.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class RestartResumeTest {
    private fun row(status: DownloadStatus) =
        QueuedDownload(id = status.name, url = "https://e/a.zip", fileName = "a", status = status, speedBytesPerSecond = 5_000L)

    @Test
    fun `whatever was moving when the app closed or updated carries on by itself`() {
        for (moving in listOf(DownloadStatus.RUNNING, DownloadStatus.RESOLVING, DownloadStatus.QUEUED)) {
            val back = resumedAfterRestart(row(moving))
            assertEquals("$moving should come back queued, not paused", DownloadStatus.QUEUED, back.status)
            assertEquals(0L, back.speedBytesPerSecond)
        }
    }

    @Test
    fun `windows accepts the opt-out from background throttling`() {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        org.junit.Assert.assertTrue("SetProcessInformation(ProcessPowerThrottling) was refused", PowerThrottling.optOut())
    }

    @Test
    fun `what the user paused, and what is finished or failed, is left as it was`() {
        for (settled in listOf(DownloadStatus.PAUSED, DownloadStatus.COMPLETED, DownloadStatus.FAILED)) {
            assertEquals(settled, resumedAfterRestart(row(settled)).status)
        }
    }
}
