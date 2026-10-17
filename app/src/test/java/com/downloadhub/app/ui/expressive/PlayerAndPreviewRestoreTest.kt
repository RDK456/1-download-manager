package com.downloadhub.app.ui.expressive

import com.downloadhub.app.ui.expressive.model.PlayerSnapshot
import com.downloadhub.app.ui.expressive.model.PreviewRestore
import com.downloadhub.app.ui.expressive.model.SnapItem
import com.downloadhub.app.ui.expressive.model.linkSafeToSave
import com.downloadhub.app.ui.expressive.model.previewRestorePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class PlayerAndPreviewRestoreTest {
    private fun song(n: Int) = SnapItem("file:///music/song $n, ünï.mp3", "Artist – Song $n", isVideo = false)

    // ---- torrent preview ----

    @Test fun aMagnetIsAskedForAgain() {
        val plan = previewRestorePlan("magnet:?xt=urn:btih:abc&dn=Sintel", null, false)
        assertTrue(plan is PreviewRestore.Magnet)
    }

    @Test fun aTorrentFileComesBackOnlyIfItIsStillThere() {
        assertTrue(previewRestorePlan("/data/x.torrent", "/data/x.torrent", true) is PreviewRestore.TorrentFile)
        assertTrue(previewRestorePlan("/data/x.torrent", "/data/x.torrent", false) is PreviewRestore.None)
    }

    @Test fun nothingSavedMeansNothingRestored() {
        assertTrue(previewRestorePlan(null, null, false) is PreviewRestore.None)
    }

    @Test fun aNonMagnetLinkIsNotTreatedAsAPreview() {
        assertTrue(previewRestorePlan("https://example.com/a.torrent", null, false) is PreviewRestore.None)
    }

    @Test fun aMagnetNamingAPrivateTrackersPasskeyIsNotKept() {
        val encoded = "magnet:?xt=urn:btih:abc&tr=http%3A%2F%2Ftracker.example%2Fannounce%3Fpasskey%3Dsecret123"
        assertFalse(linkSafeToSave(encoded))
        assertTrue(previewRestorePlan(encoded, null, false) is PreviewRestore.None)
        assertFalse(linkSafeToSave("magnet:?xt=urn:btih:abc&tr=http://t.example/announce?passkey=secret"))
    }

    @Test fun anOrdinaryMagnetWithPublicTrackersIsKept() {
        val link = "magnet:?xt=urn:btih:abc&dn=Sintel&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce"
        assertTrue(linkSafeToSave(link))
    }

    // ---- player queue ----

    @Test fun aQueueSurvivesTheRoundTripExactly() {
        val snap = PlayerSnapshot(
            listOf(song(1), SnapItem("content://x/y", "Movie, the \"Cut\"\nPart 2", true, false, "UA/1.0", "https://r.example/")),
            index = 1, positionMs = 123_456L
        )
        assertEquals(snap, PlayerSnapshot.decode(snap.encode()))
    }

    @Test fun nullsStayNullAndEmptyStringsStayEmpty() {
        val snap = PlayerSnapshot(listOf(SnapItem("a", "", false, false, null, "")), 0, 0L)
        val back = PlayerSnapshot.decode(snap.encode())!!
        assertNull(back.items[0].userAgent)
        assertEquals("", back.items[0].referrer)
    }

    @Test fun damagedTextRestoresNothing() {
        assertNull(PlayerSnapshot.decode(null))
        assertNull(PlayerSnapshot.decode(""))
        assertNull(PlayerSnapshot.decode("v2\n0\n0\na,b,0,0,~,~\n"))
        assertNull(PlayerSnapshot.decode("v1\nx\n0\na,b,0,0,~,~\n"))
        assertNull(PlayerSnapshot.decode("v1\n0\n0\na,b,0\n"))
        assertNull(PlayerSnapshot.decode("v1\n5\n0\na,b,0,0,~,~\n"))
        assertNull(PlayerSnapshot.decode("v1\n0\n0\n"))
    }

    @Test fun aNegativePositionIsClampedToTheStart() {
        val text = "v1\n0\n-50\n" + PlayerSnapshot(listOf(song(1)), 0, 0).encode().lines()[3] + "\n"
        assertEquals(0L, PlayerSnapshot.decode(text)!!.positionMs)
    }

    @Test fun liveStreamsAreNotBroughtBack() {
        val live = SnapItem("https://tv/stream.m3u8", "Channel", isVideo = true, isLive = true)
        assertNull(PlayerSnapshot(listOf(live), 0, 0).restorable())
        val mixed = PlayerSnapshot(listOf(song(1), live, song(2)), 2, 10).restorable()!!
        assertEquals(listOf(song(1), song(2)), mixed.items)
        assertEquals(1, mixed.index)
    }

    @Test fun aHugeQueueIsCutToAWindowAroundTheCurrentItem() {
        val all = (0 until 1000).map { song(it) }
        val cut = PlayerSnapshot(all, 600, 5).restorable(maxItems = 100)!!
        assertEquals(100, cut.items.size)
        assertEquals(song(600), cut.items[cut.index])
        assertEquals(5L, cut.positionMs)
    }

    @Test fun theStartAndEndOfAQueueKeepTheirItem() {
        val all = (0 until 500).map { song(it) }
        assertEquals(song(0), PlayerSnapshot(all, 0, 0).restorable(100)!!.let { it.items[it.index] })
        assertEquals(song(499), PlayerSnapshot(all, 499, 0).restorable(100)!!.let { it.items[it.index] })
    }

    @Test fun paddingAroundTheTextIsTolerated() {
        val snap = PlayerSnapshot(listOf(song(1), song(2)), 1, 42L)
        assertEquals(snap, PlayerSnapshot.decode(snap.encode() + "    "))
        assertEquals(snap, PlayerSnapshot.decode("\n  " + snap.encode().replace("\n", " \n")))
    }

    @Test fun aSongQueuedTwiceKeepsTheRightPlace() {
        val q = listOf(song(1), song(2), song(1), song(3))
        val r = PlayerSnapshot(q, 2, 0).restorable()!!
        assertEquals(2, r.index)
    }
}
