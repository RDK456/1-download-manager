package com.downloadhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The same torrent must not end up in the list three times.
 *
 * Found in a real queue: one .torrent, queued three times, each copy seeding
 * separately. The same file can arrive from a browser, a double-click and an extension
 * within seconds of each other, and nothing was stopping it. Both apps this was modelled
 * on refuse a duplicate outright.
 */
class DuplicateRulesTest {

    private val hash = "d7384fe16a5ecc18b41450e27537db987ab1231d"
    private val magnet = "magnet:?xt=urn:btih:$hash&dn=Chainsaw+Man"

    private fun torrent(url: String = magnet, file: String? = null, id: String = "a") = DownloadItem(
        id = id,
        url = url,
        fileName = "x.torrent",
        source = DownloadSource.TORRENT,
        torrentFilePath = file
    )

    @Test
    fun theSameMagnetTwiceIsADuplicate() {
        val existing = listOf(torrent())
        assertTrue(DuplicateRules.isDuplicate(existing, torrent(id = "b")))
    }

    /**
     * The same torrent as a magnet and as a .torrent file is one torrent.
     *
     * Matching on the link alone would miss this, and it is the common case: a browser
     * hands over the magnet, then the user also picks the file.
     */
    @Test
    fun aMagnetAndItsTorrentFileAreTheSameThing() {
        val existing = listOf(torrent())
        val asFile = torrent(
            url = """C:\Users\me\Downloads\${hash}.torrent""",
            file = """C:\Users\me\Downloads\${hash}.torrent""",
            // A different id, because the candidate is not in the list yet. Two entries
            // sharing an id are the same row, not a duplicate of one another.
            id = "b"
        )
        assertTrue(
            "the same torrent added as a file after a magnet is a duplicate",
            DuplicateRules.isDuplicate(existing, asFile)
        )
    }

    @Test
    fun theInfoHashIsReadFromEitherShape() {
        assertEquals("from a magnet", hash, DuplicateRules.infoHashOf(torrent()))
        val filePath = """C:\Downloads\${hash}.torrent"""
        val fromFile = torrent(url = filePath, file = filePath)
        assertEquals(
            "the test's own path is not what it claims to be",
            "C:\\Downloads\\" + hash + ".torrent", fromFile.torrentFilePath
        )
        assertEquals(
            "from a .torrent whose name is the hash",
            hash,
            DuplicateRules.infoHashOf(fromFile)
        )
        assertEquals(
            "a known hash is used as it stands",
            hash,
            DuplicateRules.infoHashOf(torrent(url = magnet, file = null).copy(torrentInfoHash = hash))
        )
        assertNull(
            "nothing recognisable, so nothing to compare",
            DuplicateRules.infoHashOf(torrent(url = "not a torrent at all"))
        )
    }

    @Test
    fun differentTorrentsAreNotDuplicates() {
        val existing = listOf(torrent())
        val other = torrent(
            url = "magnet:?xt=urn:btih:0000000000000000000000000000000000000001&dn=Other"
        )
        assertFalse(DuplicateRules.isDuplicate(existing, other))
    }

    /** A finished copy still counts: queueing it again means downloading it again. */
    @Test
    fun aFinishedCopyStillCountsAsAlreadyHavingIt() {
        val existing = listOf(torrent().copy(status = DownloadStatus.COMPLETED))
        assertTrue(DuplicateRules.isDuplicate(existing, torrent(id = "b")))
    }

    // --- plain links ---------------------------------------------------------

    @Test
    fun theSameUrlTwiceIsADuplicate() {
        val existing = listOf(DownloadItem(id = "a", url = "https://x/a.zip", fileName = "a.zip"))
        assertTrue(
            DuplicateRules.isDuplicate(existing, DownloadItem(id = "b", url = "https://x/a.zip", fileName = "a.zip"))
        )
    }

    @Test
    fun caseAndFragmentDoNotMakeALinkDifferent() {
        val existing = listOf(DownloadItem(id = "a", url = "https://X/a.zip#frag", fileName = "a.zip"))
        assertTrue(
            DuplicateRules.isDuplicate(existing, DownloadItem(id = "b", url = "https://x/a.zip", fileName = "a.zip"))
        )
    }

    @Test
    fun differentUrlsAreNotDuplicates() {
        val existing = listOf(DownloadItem(id = "a", url = "https://x/a.zip", fileName = "a.zip"))
        assertFalse(
            DuplicateRules.isDuplicate(existing, DownloadItem(id = "b", url = "https://x/b.zip", fileName = "b.zip"))
        )
    }

    /**
     * A magnet and an HTTP link to that .torrent are the same download.
     *
     * Both are torrents, so they are compared on hash; a magnet whose hash we cannot read
     * is left alone rather than guessed at.
     */
    @Test
    fun aTorrentIsOnlyComparedAgainstOtherTorrents() {
        val http = DownloadItem(id = "a", url = "https://x/$hash.torrent", fileName = "x.torrent")
        assertFalse(
            "an unreadable hash means no comparison, not a match on the link text",
            DuplicateRules.isDuplicate(listOf(http), torrent(id = "b"))
        )
    }

    @Test
    fun anEmptyQueueHasNoDuplicates() {
        assertFalse(DuplicateRules.isDuplicate(emptyList(), torrent()))
        assertFalse(DuplicateRules.isDuplicate(emptyList(), DownloadItem(id = "b", url = "https://x/a.zip", fileName = "a")))
    }

    /** The candidate is not compared against itself while it is being added. */
    @Test
    fun anItemIsNotItsOwnDuplicate() {
        val one = torrent()
        assertFalse(DuplicateRules.isDuplicate(listOf(one), one))
    }
}
