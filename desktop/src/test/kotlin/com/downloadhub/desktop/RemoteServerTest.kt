package com.downloadhub.desktop

import com.downloadhub.core.DownloadItem
import java.net.HttpURLConnection
import java.net.URL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RemoteServerTest {
    private val added = mutableListOf<String>()
    private val paused = mutableListOf<String>()
    private lateinit var server: RemoteServer
    private lateinit var base: String

    @Before
    fun start() {
        server = RemoteServer(
            pin = { "123456" },
            list = { listOf(DownloadItem(id = "a", url = "http://x/f.bin", fileName = "f.bin")) },
            pause = { paused += it },
            resume = {},
            add = { added += it }
        )
        assertTrue(server.start())
        base = "http://127.0.0.1:${RemoteServer.activePort}"
    }

    @After
    fun stop() = server.stop()

    private fun call(path: String, method: String = "GET", body: String? = null, cookie: String? = null, remote: Boolean = false): Pair<Int, String> {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        cookie?.let { c.setRequestProperty("Cookie", it) }
        if (remote) c.setRequestProperty("X-Remote", "1")
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
        val set = c.getHeaderField("Set-Cookie")
        return code to (if (set != null) set else text)
    }

    private fun session(): String = call("/login", "POST", "pin=123456").second.substringBefore(';')

    @Test
    fun theListNeedsALoginAndAWrongPinIsRefused() {
        assertEquals(401, call("/api/list").first)
        assertEquals(403, call("/login", "POST", "pin=000000").first)
        val cookie = session()
        val (code, text) = call("/api/list", cookie = cookie)
        assertEquals(200, code)
        assertTrue(text.contains("f.bin"))
    }

    @Test
    fun writesNeedTheHeaderAndLinksAreChecked() {
        val cookie = session()
        assertEquals(403, call("/api/add", "POST", "http://x/y", cookie).first)
        call("/api/add", "POST", "not a link", cookie, remote = true)
        call("/api/add", "POST", "http://x/y", cookie, remote = true)
        call("/api/pause?id=a", "POST", "", cookie, remote = true)
        assertEquals(listOf("http://x/y"), added)
        assertEquals(listOf("a"), paused)
    }

    @Test
    fun fiveWrongPinsLockTheDoor() {
        repeat(5) { call("/login", "POST", "pin=000000") }
        assertEquals(403, call("/login", "POST", "pin=123456").first)
    }
}
