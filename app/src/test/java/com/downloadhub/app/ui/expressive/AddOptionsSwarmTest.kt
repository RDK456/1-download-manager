package com.downloadhub.app.ui.expressive

import com.downloadhub.app.data.AppQueue
import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.data.model.DownloadCategory
import com.downloadhub.app.data.model.DownloadSource
import com.downloadhub.app.data.model.DownloadStatus
import com.downloadhub.app.ui.expressive.model.AddChoice
import com.downloadhub.app.ui.expressive.model.AddOptions
import com.downloadhub.app.ui.expressive.model.Swarm
import com.downloadhub.app.ui.expressive.model.addChoices
import com.downloadhub.app.ui.expressive.model.label
import com.downloadhub.app.ui.expressive.model.primaryAddLabel
import com.downloadhub.app.ui.expressive.model.spoken
import com.downloadhub.app.ui.expressive.model.swarmText
import com.downloadhub.app.ui.expressive.model.toRowModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddOptionsSwarmTest {
    private val main = AppQueue(id = "main", name = "Main")
    private val night = AppQueue(id = "q2", name = "Overnight")
    private val work = AppQueue(id = "q3", name = "Work")

    @Test fun defaultOptionsAreTheMainQueueStartedAtOnce() {
        assertTrue(AddOptions().isDefault)
        assertTrue(AddOptions(queueId = "main").isDefault)
        assertFalse(AddOptions(startPaused = true).isDefault)
        assertFalse(AddOptions(queueId = "q2").isDefault)
    }

    @Test fun theMenuOffersPausedThenEveryOtherQueue() {
        val choices = addChoices(listOf(main, night, work))
        assertEquals(listOf("Add paused", "Add to Overnight", "Add to Work"), choices.map { it.label() })
        assertTrue(choices[0].options.startPaused)
        assertEquals("q2", choices[1].options.queueId)
        assertFalse(choices[1].options.startPaused)
    }

    @Test fun withOnlyTheMainQueueTheMenuIsJustPaused() {
        assertEquals(listOf(AddChoice.Paused), addChoices(listOf(main)))
        assertEquals(listOf(AddChoice.Paused), addChoices(emptyList()))
    }

    @Test fun theMainButtonSaysWhatItWillDo() {
        assertEquals("Add to queue", primaryAddLabel(1, AddOptions(), listOf(main)))
        assertEquals("Add 120 to queue", primaryAddLabel(120, AddOptions(), listOf(main)))
        assertEquals("Add paused", primaryAddLabel(1, AddOptions(startPaused = true), listOf(main)))
        assertEquals("Add 5 paused", primaryAddLabel(5, AddOptions(startPaused = true), listOf(main)))
        assertEquals("Add to Overnight", primaryAddLabel(1, AddOptions(queueId = "q2"), listOf(main, night)))
        assertEquals("Add to queue", primaryAddLabel(1, AddOptions(queueId = "gone"), listOf(main, night)))
    }

    // ---- swarm -----------------------------------------------------------------------

    @Test fun swarmTextWhileDownloading() {
        assertEquals("84 seeds · 12 peers", swarmText(Swarm(84, 12), finished = false))
        assertEquals("1 seed · 1 peer", swarmText(Swarm(1, 1), finished = false))
        assertEquals("0 seeds · 0 peers", swarmText(Swarm(0, 0), finished = false))
    }

    @Test fun swarmTextWhileSeeding() {
        assertEquals("Sharing with 3 peers", swarmText(Swarm(0, 3), finished = true))
        assertNull(swarmText(Swarm(0, 0), finished = true))
    }

    @Test fun noSwarmNoText() {
        assertNull(swarmText(null, finished = false))
    }

    private fun torrent(status: DownloadStatus, updatedAt: Long = 0, speed: Long = 0) = DownloadEntity(
        id = "t", source = DownloadSource.TORRENT, url = "magnet:?xt=urn:btih:x", fileName = "debian.iso", mimeType = null,
        category = DownloadCategory.FILE, status = status, bytesDownloaded = 100, totalBytes = 1000,
        progressPercent = 10, speedBytesPerSecond = speed, etaSeconds = -1, outputPath = null,
        torrentFilePath = null, torrentInfoHash = null, userAgent = null, contentDisposition = null,
        etag = null, lastModified = null, errorMessage = null, createdAt = 0, updatedAt = updatedAt
    )

    @Test fun aTorrentRowCarriesItsSwarm() {
        val m = torrent(DownloadStatus.RUNNING, speed = 500).toRowModel(swarm = Swarm(84, 12))
        assertEquals("84 seeds · 12 peers", m.swarm)
        assertTrue(m.spoken().endsWith("84 seeds · 12 peers"))
    }

    @Test fun aStalledTorrentWithNoOneConnectedSaysSo() {
        val m = torrent(DownloadStatus.RUNNING, updatedAt = 1).toRowModel(now = 100_000, swarm = Swarm(0, 0))
        assertEquals("No seeds or peers found", m.headline)
    }

    @Test fun aStalledTorrentWithPeersButNoSeedsSaysWaiting() {
        val m = torrent(DownloadStatus.RUNNING, updatedAt = 1).toRowModel(now = 100_000, swarm = Swarm(0, 4))
        assertEquals("No seeds · waiting for a peer", m.headline)
    }

    @Test fun aStalledTorrentWithSeedsStillSaysStalled() {
        val m = torrent(DownloadStatus.RUNNING, updatedAt = 1).toRowModel(now = 100_000, swarm = Swarm(3, 4))
        assertEquals("Stalled · no one is sending", m.headline)
    }

    @Test fun noSwarmShownWhileWaitingForTheNetwork() {
        val m = torrent(DownloadStatus.RUNNING).toRowModel(com.downloadhub.app.ui.expressive.model.RowConditions(offline = true), swarm = Swarm(5, 5))
        assertNull(m.swarm)
    }

    @Test fun otherSourcesNeverShowASwarm() {
        val http = torrent(DownloadStatus.RUNNING).copy(source = DownloadSource.HTTP)
        assertNull(http.toRowModel(swarm = Swarm(5, 5)).swarm)
    }

    @Test fun aTorrentThatNeverGotAByteIsJudgedFromWhenItWasAdded() {
        // The row is touched all the time while peers are sought, so updatedAt is recent.
        val fresh = torrent(DownloadStatus.RUNNING, updatedAt = 99_990).copy(bytesDownloaded = 0, createdAt = 1)
        val m = fresh.toRowModel(now = 100_000, swarm = Swarm(0, 0))
        assertEquals("No seeds or peers found", m.headline)
        assertEquals("0 seeds · 0 peers", m.swarm)
    }

    @Test fun aTorrentAddedJustNowIsNotCalledStalled() {
        val fresh = torrent(DownloadStatus.RUNNING, updatedAt = 99_990).copy(bytesDownloaded = 0, createdAt = 90_000)
        assertEquals("Downloading", fresh.toRowModel(now = 100_000, swarm = Swarm(0, 0)).headline)
    }
}
