package com.downloadhub.core

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A real local server, real sockets, and the bytes compared at the end. */
class SegmentedDownloadTest {
    private val body = Random(7).nextBytes(5 * 1024 * 1024 + 123)
    private val rangedRequests = AtomicInteger()
    private var honourRanges = true
    private lateinit var server: HttpServer
    private lateinit var dir: File
    private var status: DownloadStatus? = null
    private var published: File? = null

    @Before
    fun start() {
        dir = Files.createTempDirectory("segments").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file.bin") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            val match = range?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
            if (honourRanges && match != null) {
                rangedRequests.incrementAndGet()
                val from = match.groupValues[1].toInt()
                val to = match.groupValues[2].toIntOrNull() ?: (body.size - 1)
                exchange.responseHeaders.add("Content-Range", "bytes $from-$to/${body.size}")
                exchange.sendResponseHeaders(206, (to - from + 1).toLong())
                exchange.responseBody.use { it.write(body, from, to - from + 1) }
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
        dir.deleteRecursively()
    }

    private fun downloader(connections: Int) = HttpDownloader(
        store = object : DownloadStore {
            override fun updateValidators(id: String, etag: String?, lastModified: String?, now: Long) = Unit
            override fun updateMetadata(id: String, fileName: String, mimeType: String?, category: DownloadCategory, totalBytes: Long, now: Long) = Unit
            override fun updateProgress(id: String, bytesDownloaded: Long, totalBytes: Long, percent: Int, speedBytesPerSecond: Long, etaSeconds: Long, now: Long) = Unit
            override fun updateOutputPath(id: String, location: String?, now: Long) = Unit
            override fun setStatus(id: String, status: DownloadStatus, error: String?, now: Long) {
                this@SegmentedDownloadTest.status = status
            }
        },
        area = object : WorkArea {
            override fun workFile(id: String) = File(dir, "part-$id")
            override fun publishFile(source: File, preferredName: String, destinationTreeUri: String?, category: DownloadCategory): PublishedTarget {
                val target = File(dir, preferredName)
                source.renameTo(target)
                published = target
                return PublishedTarget(target.absolutePath)
            }
            override fun scan(file: File) = Unit
        },
        policies = object : TransferPolicyProvider {
            override suspend fun policyFor(id: String) = TransferPolicy(connections = connections)
        },
        speedLimiter = SpeedLimiter()
    )

    private fun item() = DownloadItem(
        id = "x",
        url = "http://127.0.0.1:${server.address.port}/file.bin",
        fileName = "file.bin"
    )

    @Test
    fun fourConnectionsProduceTheSameBytes() = runBlocking {
        downloader(4).download(item())
        assertEquals(DownloadStatus.COMPLETED, status)
        assertArrayEquals(body, published!!.readBytes())
        // One probe plus four segments: it really was split.
        assertTrue("ranged requests: ${rangedRequests.get()}", rangedRequests.get() >= 5)
        assertFalse("segment state is cleaned up", File(dir, "part-x.segments").exists())
    }

    @Test
    fun aServerWithoutRangesFallsBackToOneStream() = runBlocking {
        honourRanges = false
        downloader(4).download(item())
        assertEquals(DownloadStatus.COMPLETED, status)
        assertArrayEquals(body, published!!.readBytes())
    }

    @Test
    fun aResumeContinuesFromTheSavedSegments() = runBlocking {
        // As if a previous run got the first half of every segment and was paused.
        val plan = SegmentState.plan(body.size.toLong(), 4)
        val work = File(dir, "part-x")
        java.io.RandomAccessFile(work, "rw").use { file ->
            file.setLength(body.size.toLong())
            plan.segments.forEach { segment ->
                val half = segment.length / 2
                file.seek(segment.start)
                file.write(body, segment.start.toInt(), half.toInt())
                segment.done = half
            }
        }
        plan.write(SegmentState.fileFor(work))

        downloader(4).download(item())
        assertArrayEquals(body, published!!.readBytes())
    }

    @Test
    fun theSegmentFileRoundTripsAndRejectsGaps() {
        val plan = SegmentState.plan(10_000_000, 3)
        plan.segments[1].done = 42
        val decoded = SegmentState.decode(plan.encode())!!
        assertEquals(plan.total, decoded.total)
        assertEquals(42L, decoded.segments[1].done)
        assertEquals(plan.segments.last().end, plan.total - 1)
        assertNull(SegmentState.decode("v1 100\n0 10 0\n20 99 0\n"))
        // A small file gets fewer connections than asked for.
        assertEquals(1, SegmentState.plan(500_000, 8).segments.size)
    }
}
