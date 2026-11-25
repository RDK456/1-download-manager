package com.downloadhub.core

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FtpAndMirrorTest {
    private val payload = ByteArray(50_000) { (it % 251).toByte() }

    /** A one-login passive server. [resumes] false answers REST with a refusal. */
    private fun fakeServer(resumes: Boolean): ServerSocket {
        val control = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            control.accept().use { client ->
                val reader = client.getInputStream().bufferedReader()
                val out = client.getOutputStream()
                fun say(text: String) { out.write((text + "\r\n").toByteArray()); out.flush() }
                var data: ServerSocket? = null
                var offset = 0
                say("220-hello\r\n220 ready")
                while (true) {
                    val line = reader.readLine() ?: break
                    when (line.substringBefore(' ')) {
                        "USER" -> say("331 password please")
                        "PASS" -> say("230 in")
                        "TYPE" -> say("200 binary")
                        "SIZE" -> say("213 ${payload.size}")
                        "EPSV" -> {
                            data = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
                            say("229 Extended passive (|||${data!!.localPort}|)")
                        }
                        "REST" -> if (resumes) { offset = line.substringAfter(' ').toInt(); say("350 ok") } else say("502 no")
                        "RETR" -> {
                            say("150 sending")
                            data!!.accept().use { it.getOutputStream().write(payload, offset, payload.size - offset) }
                            say("226 done")
                        }
                        else -> say("502 no")
                    }
                }
            }
        }
        return control
    }

    private fun fetch(server: ServerSocket, offset: Long): Pair<ByteArray, Long> {
        val (client, path) = FtpClient.open("ftp://127.0.0.1:${server.localPort}/dir/file.bin")
        return client.use {
            assertEquals("dir/file.bin", path)
            assertEquals(payload.size.toLong(), it.size(path))
            val (stream, start) = it.retrieve(path, offset)
            val bytes = stream.readBytes()
            it.finish()
            bytes to start
        }
    }

    @Test
    fun resumesFromAnOffset() {
        fakeServer(resumes = true).use { server ->
            val (bytes, start) = fetch(server, 20_000)
            assertEquals(20_000L, start)
            assertArrayEquals(payload.copyOfRange(20_000, payload.size), bytes)
        }
    }

    @Test
    fun saysWhenItMustStartAgain() {
        fakeServer(resumes = false).use { server ->
            val (bytes, start) = fetch(server, 20_000)
            assertEquals(0L, start)
            assertArrayEquals(payload, bytes)
        }
    }

    @Test
    fun refusesAnAddressThatSmugglesACommand() {
        assertThrows(IOException::class.java) { FtpClient.open("ftp://127.0.0.1:1/a%0d%0aDELE%20x") }
    }

    @Test
    fun mirrorsOnlyPublicGithubFilesAndOnlyWhenChosen() {
        val file = "https://github.com/a/b/releases/download/v1/x.zip"
        assertEquals("https://m.example/$file", GithubMirror.rewrite(file, "https://m.example/"))
        assertNull(GithubMirror.rewrite(file, ""))
        assertNull(GithubMirror.rewrite(file, "http://m.example/"))
        assertNull(GithubMirror.rewrite("https://github.com/a/b", "https://m.example"))
        assertNull(GithubMirror.rewrite("$file?token=1", "https://m.example"))
        assertNull(GithubMirror.rewrite("https://notgithub.com/a/b/releases/download/v1/x", "https://m.example"))
    }

    @Test
    fun ftpLinksCanBeAdded() {
        assertEquals(true, LinkParser.isFetchable("ftp://host/file.zip"))
        assertEquals(DownloadSource.HTTP, LinkParser.sourceFor("ftp://host/file.zip"))
    }
}
