package com.downloadhub.core

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.net.URLDecoder

/**
 * The little of FTP a downloader needs: log in, ask a size, and fetch one file from an
 * offset, in passive mode. The JDK's own `ftp:` handler cannot resume, which is why this
 * exists. Plain FTP only: FTPS and SFTP are different protocols.
 */
class FtpClient private constructor(private val control: Socket, private val proxy: Proxy?) : Closeable {
    private val input = control.getInputStream().buffered()
    private val output = control.getOutputStream()
    private var data: Socket? = null

    private data class Reply(val code: Int, val text: String)

    private fun readLine(): String {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("The FTP server closed the connection")
            if (b == '\n'.code) break
            if (b != '\r'.code) line.append(b.toChar())
            if (line.length > MAX_LINE) throw IOException("The FTP server sent an over-long reply")
        }
        return String(line.toString().toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
    }

    private fun reply(): Reply {
        val first = readLine()
        val code = first.take(3).toIntOrNull() ?: throw IOException("Not an FTP reply: $first")
        // "123-" opens a multi-line reply, which ends at a line starting "123 ".
        if (first.length > 3 && first[3] == '-') {
            while (!readLine().startsWith("$code ")) Unit
        }
        return Reply(code, first.drop(4))
    }

    private fun command(line: String): Reply {
        output.write((line + "\r\n").toByteArray(Charsets.UTF_8))
        output.flush()
        return reply()
    }

    private fun login(user: String, password: String) {
        var answer = command("USER $user")
        if (answer.code == 331) answer = command("PASS $password")
        if (answer.code != 230) throw IOException("FTP login refused (${answer.code})")
        if (command("TYPE I").code != 200) throw IOException("The FTP server refused binary mode")
    }

    /** The file's length, or -1 when the server will not say. */
    fun size(path: String): Long {
        val answer = command("SIZE $path")
        return if (answer.code == 213) answer.text.trim().toLongOrNull() ?: -1L else -1L
    }

    private fun passive(): Socket {
        val extended = command("EPSV")
        val port = if (extended.code == 229) {
            Regex("""\((.)\1\1(\d+)\1\)""").find(extended.text)?.groupValues?.get(2)?.toIntOrNull()
        } else {
            val answer = command("PASV")
            if (answer.code != 227) throw IOException("The FTP server refused passive mode (${answer.code})")
            Regex("""(\d+),(\d+),(\d+),(\d+),(\d+),(\d+)""").find(answer.text)?.groupValues
                ?.let { it[5].toInt() * 256 + it[6].toInt() }
        } ?: throw IOException("The FTP server gave no data port")
        // Always the control connection's host: the address in a PASV reply is whatever the
        // server says, which may be a private one or somebody else's machine.
        return connect(control.inetAddress?.hostAddress ?: throw IOException("FTP connection lost"), port, proxy)
    }

    /**
     * Opens the file at [offset]. The second value is where the bytes really start: zero when
     * the server cannot resume, so the caller must then start its file again.
     */
    fun retrieve(path: String, offset: Long): Pair<InputStream, Long> {
        val socket = passive()
        data = socket
        val start = if (offset > 0 && command("REST $offset").code == 350) offset else 0L
        val answer = command("RETR $path")
        if (answer.code != 150 && answer.code != 125) {
            throw IOException("The FTP server would not send the file (${answer.code} ${answer.text})")
        }
        return socket.getInputStream() to start
    }

    /** Call after the data stream ends: only this reply says the whole file was sent. */
    fun finish() {
        runCatching { data?.close() }
        val answer = reply()
        if (answer.code != 226 && answer.code != 250) throw IOException("FTP transfer failed (${answer.code})")
    }

    override fun close() {
        runCatching { data?.close() }
        runCatching { control.close() }
    }

    companion object {
        private const val MAX_LINE = 4096

        private fun connect(host: String, port: Int, proxy: Proxy?): Socket {
            if (proxy?.type() == Proxy.Type.HTTP) throw IOException("FTP cannot go through an HTTP proxy")
            val socket = if (proxy != null) Socket(proxy) else Socket()
            socket.soTimeout = 35_000
            try {
                socket.connect(InetSocketAddress(host, port), 20_000)
            } catch (error: IOException) {
                socket.close()
                throw error
            }
            return socket
        }

        /** A decoded URL part, refused if it could smuggle a second command onto the line. */
        private fun clean(raw: String): String {
            val value = URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
            if (value.any { it == '\r' || it == '\n' || it == '\u0000' }) throw IOException("Invalid FTP address")
            return value
        }

        /** Connects and logs in. Returns the client and the file's path on the server. */
        fun open(url: String, user: String = "", password: String = "", proxy: Proxy? = null): Pair<FtpClient, String> {
            val uri = runCatching { URI(url.trim()) }.getOrElse { throw IOException("Invalid FTP address") }
            val host = uri.host ?: throw IOException("Invalid FTP address")
            // RFC 1738: the path is relative to the login directory, so the first slash goes.
            val path = clean(uri.rawPath.orEmpty().removePrefix("/"))
            if (path.isEmpty() || path.endsWith("/")) throw IOException("The FTP address does not name a file")
            val inUrl = uri.rawUserInfo
            val name = user.ifEmpty { inUrl?.substringBefore(':')?.let(::clean) ?: "anonymous" }
            val secret = password.ifEmpty { inUrl?.substringAfter(':', "")?.let(::clean).orEmpty() }
                .ifEmpty { "anonymous@" }
            if ((name + secret).any { it == '\r' || it == '\n' }) throw IOException("Invalid FTP login")
            val client = FtpClient(connect(host, if (uri.port > 0) uri.port else 21, proxy), proxy)
            try {
                if (client.reply().code != 220) throw IOException("The FTP server is not ready")
                client.login(name, secret)
            } catch (error: Throwable) {
                client.close()
                throw error
            }
            return client to path
        }
    }
}
