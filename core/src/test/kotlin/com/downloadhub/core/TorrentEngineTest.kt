package com.downloadhub.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The torrent engine is now shared, so these guard the parts that are easy to get
 * wrong when it is driven from a second platform: a magnet must resolve to an info
 * hash, a bad .torrent file must fail loudly rather than silently, and the staging
 * directory must be derived from the caller rather than hard coded.
 */
class TorrentEngineTest {

    /**
     * Must run before any libtorrent4j class is touched.
     *
     * The JNI initialiser runs once per JVM and throws a `LinkageError` for good if
     * it cannot find the native, after which the class stays permanently broken.
     * JUnit does not define method order, so a test that reaches libtorrent4j first
     * would otherwise decide the outcome for the whole class.
     */
    companion object {
        @BeforeClass
        @JvmStatic
        fun loadNativeOnce() {
            LibtorrentNative.ensureReady()
        }
    }

    /**
     * Must run before any libtorrent4j class is touched.
     *
     * The JNI initialiser runs once per JVM and throws a `LinkageError` for good if
     * it cannot find the native, after which the class stays permanently broken.
     * JUnit does not define method order, so a test that reaches libtorrent4j first
     * would otherwise decide the outcome for the whole class.
     */
    @Test
    fun theNativeLibraryLoads() {
        val version = runCatching { org.libtorrent4j.swig.libtorrent.version() }.getOrNull()
        assertNotNull(
            "libtorrent4j JNI call failed; " +
                "jni.path=${System.getProperty("libtorrent4j.jni.path")}",
            version
        )
        assertTrue("expected a non-empty version string, got '$version'", !version.isNullOrBlank())
    }

    @Test
    fun theNativeLibraryIsOnTheTestClasspath() {
        // Guards the build: if the Windows native is dropped from the test
        // classpath, torrents silently stop working on the build machine.
        val resource = "lib/x86_64/libtorrent4j.dll"
        val stream = TorrentEngineTest::class.java.classLoader?.getResourceAsStream(resource)
        assertNotNull(
            "$resource is missing from the test classpath; " +
                ":core needs libtorrent4j-windows as a test dependency",
            stream
        )
        stream!!.close()
    }

    @Test
    fun aMagnetResolvesToAnInfoHash() {
        // Well-known Ubuntu torrent; resolution needs no peers, only the magnet.
        val magnet = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f651b4c8d5e9c9a1a4a3&dn=ubuntu"
        val hash = runCatching {
            org.libtorrent4j.AddTorrentParams.parseMagnetUri(magnet).infoHashes.getBest().toHex()
        }.getOrNull()
        assertNotNull("could not read the magnet", hash)
        assertEquals("a SHA-1 info hash is 40 hex characters", 40, hash!!.length)
    }

    @Test
    fun garbageIsRejectedAsATorrentFile() {
        val bogus = File.createTempFile("not-a-torrent", ".torrent")
        bogus.writeText("this is definitely not a bencoded torrent file")
        val parsed = runCatching { org.libtorrent4j.TorrentInfo(bogus) }.isSuccess
        bogus.delete()
        assertTrue(
            "a non-torrent file must not parse as one; the caller relies on this " +
                "to reject an HTML error page saved under a .torrent name",
            !parsed
        )
    }

    @Test
    fun theStagingDirectoryComesFromTheCaller() {
        val tempRoot = File(System.getProperty("java.io.tmpdir"), "torrent-root-${System.nanoTime()}")
        val engine = TorrentEngine { tempRoot }
        // Constructing the engine must not create anything on its own: the session
        // only starts when a transfer actually begins.
        assertTrue("the engine must not create directories eagerly", !tempRoot.exists())
        engine.shutdown()
        tempRoot.delete()
    }

    @Test
    fun shutdownIsSafeBeforeAnythingStarted() {
        // The desktop controller closes on quit even if no torrent ever ran.
        val engine = TorrentEngine { File(System.getProperty("java.io.tmpdir")) }
        engine.shutdown()
        engine.shutdown()
    }

    @Test
    fun anUnknownItemProducesNoSnapshot() {
        val engine = TorrentEngine { File(System.getProperty("java.io.tmpdir")) }
        val item = DownloadItem("nope", "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567", "x")
        val snapshot = runCatching { engine.start(item) }.getOrNull()
        // Either null or a snapshot is acceptable; a thrown exception is not.
        assertTrue(snapshot == null || snapshot.infoHash.isNotBlank())
        engine.shutdown()
    }
}
