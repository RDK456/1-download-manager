package com.downloadhub.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Opening the app *at* something.
 *
 * A download manager has to be launchable at a magnet link or a .torrent, not just from
 * the Start Menu. `fun main()` took no arguments at all, so every one of those clicks
 * started the app and then quietly did nothing - which is the whole of "magnet links
 * from the browser do not open" and "clicking a .torrent does not add to the queue".
 */
class StartupIntakeTest {

    private val ubuntuMagnet =
        "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f651b4c8d5e9c9a1a4a3&dn=Ubuntu+22.04&tr=udp%3A%2F%2Ftracker.example"

    /** A path that claims to be a .torrent, without touching the disk. */
    private fun pretendFileExists(vararg names: String): (String) -> Boolean = { path ->
        names.any { path.endsWith(it, ignoreCase = true) || path == it }
    }

    // --- magnets -------------------------------------------------------------

    @Test
    fun aMagnetIsQueued() {
        val target = classifyStartupArgument(ubuntuMagnet, pretendFileExists())
        assertNotNull("a magnet was ignored", target)
        assertEquals(ubuntuMagnet, target!!.link)
        assertNull("a magnet is not a file on disk", target.torrentFile)
    }

    /**
     * The magnet carries its own name.
     *
     * Without reading `dn` the list shows the raw link, which is the ugliest thing a
     * download manager can put in a list.
     */
    @Test
    fun aMagnetsOwnNameIsUsed() {
        assertEquals("Ubuntu 22.04", classifyStartupArgument(ubuntuMagnet)!!.name)
    }

    @Test
    fun anEncodedMagnetsNameIsDecoded() {
        val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Rick%20Astley%20-%20Never%20Gonna"
        assertEquals(
            "Rick Astley - Never Gonna",
            classifyStartupArgument(magnet)!!.name
        )
    }

    /** Case does not vary in practice, but a scheme that is matched case-sensitively would. */
    @Test
    fun theSchemeIsMatchedWithoutRegardToCase() {
        assertNotNull(classifyStartupArgument("MAGNET:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"))
    }

    // --- .torrent files -----------------------------------------------------

    @Test
    fun aLocalTorrentFileIsQueuedAsAFile() {
        val target = classifyStartupArgument("C:\\Users\\me\\Downloads\\ubuntu.torrent", pretendFileExists("ubuntu.torrent"))
        assertNotNull("a .torrent on disk was ignored", target)
        assertEquals("C:\\Users\\me\\Downloads\\ubuntu.torrent", target!!.link)
        assertEquals("ubuntu.torrent", target.name)
        assertEquals(
            "the engine has to be told it is a file, not a link to fetch - this is why " +
                "a picked .torrent used to sit in the list doing nothing",
            "C:\\Users\\me\\Downloads\\ubuntu.torrent",
            target.torrentFile
        )
    }

    @Test
    fun aFileUriIsUnwrapped() {
        val target = classifyStartupArgument(
            "file:///C:/Users/me/ubuntu.torrent",
            pretendFileExists("ubuntu.torrent")
        )
        assertNotNull("a file: URI was ignored", target)
        assertTrue(target!!.torrentFile!!.endsWith("ubuntu.torrent"))
    }

    /**
     * A path that is not there must not become a download.
     *
     * Windows happily passes a stale shortcut's target; queueing it produces a list of
     * entries that can never start and no explanation.
     */
    @Test
    fun aTorrentPathThatDoesNotExistIsIgnored() {
        assertNull(
            classifyStartupArgument("C:\\gone\\missing.torrent", pretendFileExists())
        )
    }

    // --- links ---------------------------------------------------------------

    @Test
    fun aRemoteLinkIsQueued() {
        val target = classifyStartupArgument("https://example.com/files/thing.zip", pretendFileExists())
        assertNotNull(target)
        assertEquals("https://example.com/files/thing.zip", target!!.link)
        assertNull("a remote link is not a local file", target.torrentFile)
        assertEquals("thing.zip", target.name)
    }

    @Test
    fun anUnusableArgumentIsIgnoredRatherThanQueued() {
        // The launcher and the JVM pass switches of their own. A queue full of entries
        // named "-Xmx512m" is worse than no queue at all.
        assertNull(classifyStartupArgument("-Xmx512m"))
        assertNull(classifyStartupArgument("--enable-preview"))
        assertNull(classifyStartupArgument("   "))
        assertNull(classifyStartupArgument("not a link at all"))
    }

    @Test
    fun theAppsOwnPathIsIgnored() {
        assertTrue(startupTargets(arrayOf("1DownloadManager.exe"), pretendFileExists()).isEmpty())
        assertTrue(
            startupTargets(arrayOf("C:\\Program Files\\1DownloadManager\\1DownloadManager.exe"), pretendFileExists())
                .isEmpty()
        )
    }

    @Test
    fun switchesAreSkippedAndRealArgumentsAreKeptInOrder() {
        val targets = startupTargets(
            arrayOf(
                "-Xmx1g",
                ubuntuMagnet,
                "--enable-preview",
                "https://example.com/a.zip"
            ),
            pretendFileExists()
        )
        assertEquals(2, targets.size)
        assertEquals(ubuntuMagnet, targets[0].link)
        assertEquals("https://example.com/a.zip", targets[1].link)
    }

    // --- one copy, and handing over -----------------------------------------

    /**
     * Only one copy of the app may run.
     *
     * Each copy had its own window and its own writes to the same queue file, and a
     * magnet link started a second copy rather than showing the first.
     */
    @Test
    fun onlyOneCopyMayRun() {
        val lock = tempLock("dlm-instance")
        val first = SingleInstance(lock, acquireTimeoutMillis = 500)
        val second = SingleInstance(lock, acquireTimeoutMillis = 500)
        try {
            assertTrue("the first copy should win the lock", first.tryAcquire())
            assertTrue(first.isPrimary)
            assertFalse("a second copy must not also win it", second.tryAcquire())
            assertFalse(second.isPrimary)
        } finally {
            second.release()
            first.release()
            lock.delete()
        }
    }

    /** Letting go has to actually let go, or the app could never be started again. */
    @Test
    fun releasingTheLockLetsTheNextCopyIn() {
        val lock = tempLock("dlm-release")
        try {
            val first = SingleInstance(lock, acquireTimeoutMillis = 500)
            assertTrue(first.tryAcquire())
            first.release()

            val second = SingleInstance(lock, acquireTimeoutMillis = 500)
            assertTrue("a released lock must be available again", second.tryAcquire())
            second.release()
        } finally {
            lock.delete()
        }
    }

    /**
     * A second copy must not just vanish.
     *
     * The reason Windows started it was to open something, so the request goes to the
     * copy that is already running and is queued there.
     */
    @Test
    fun aSecondCopyHandsItsArgumentsToTheFirst() {
        val file = File(tempDir(), "intake-test.txt")
        try {
            IntakeChannel.handOff(listOf(classifyStartupArgument(ubuntuMagnet)!!), file)
            val arrived = IntakeChannel.take(file)
            assertEquals(1, arrived.size)
            assertEquals(ubuntuMagnet, arrived[0].link)
            assertEquals("Ubuntu 22.04", arrived[0].name)
            assertEquals("taking twice must not return it again", 0, IntakeChannel.take(file).size)
        } finally {
            file.delete()
        }
    }

    /** A magnet is full of `&` and `?`; a path can contain anything. Both must survive. */
    @Test
    fun awkwardLinksSurviveTheHandover() {
        val file = File(tempDir(), "intake-awkward.txt")
        try {
            val awkward = QueueTarget(
                link = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=a%20b%26c&tr=x",
                name = "a b&c\td",
                torrentFile = "C:\\odd name\\weird & name.torrent"
            )
            IntakeChannel.handOff(listOf(awkward), file)
            val back = IntakeChannel.take(file).single()
            assertEquals(awkward.link, back.link)
            assertEquals(awkward.name, back.name)
            assertEquals(awkward.torrentFile, back.torrentFile)
        } finally {
            file.delete()
        }
    }

    /**
     * Two launches in quick succession both count.
     *
     * A second copy hands over and exits, so a third launched a moment later must not
     * find the file already emptied.
     */
    @Test
    fun twoHandoverKeepBoth() {
        val file = File(tempDir(), "intake-two.txt")
        try {
            val a = classifyStartupArgument("https://example.com/a.zip")!!
            val b = classifyStartupArgument("https://example.com/b.zip")!!
            IntakeChannel.handOff(listOf(a), file)
            IntakeChannel.handOff(listOf(b), file)
            val all = IntakeChannel.take(file)
            assertEquals(2, all.size)
            assertEquals(setOf(a.link, b.link), all.map { it.link }.toSet())
        } finally {
            file.delete()
        }
    }

    @Test
    fun nothingHandedOverIsNotAnError() {
        val file = File(tempDir(), "intake-empty.txt")
        assertEquals(emptyList<QueueTarget>(), IntakeChannel.take(file))
    }

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "dlm-intake-${System.nanoTime()}").apply { mkdirs() }

    private fun tempLock(name: String): File =
        File(tempDir(), "$name.lock").also { it.parentFile.mkdirs() }
}
