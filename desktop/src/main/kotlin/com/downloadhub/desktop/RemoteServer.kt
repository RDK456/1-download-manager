package com.downloadhub.desktop

import com.downloadhub.core.DownloadItem
import com.downloadhub.core.DownloadStatus
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lets a phone on the same Wi-Fi watch the list and pause, resume or add downloads.
 *
 * Off by default. Same hand-written HTTP as [CaptureServer], because the JDK's own server
 * module is not in the shipped runtime. Safety: only private-network clients are served,
 * every request but the page and the login needs the cookie a correct PIN earns, writes also
 * need a custom header a cross-site page cannot send, and wrong PINs lock the door for a
 * minute. It cannot delete anything.
 */
class RemoteServer(
    private val pin: () -> String,
    private val list: () -> List<DownloadItem>,
    private val pause: (String) -> Unit,
    private val resume: (String) -> Unit,
    private val add: (String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val session = CaptureServer.newToken()
    private var failures = 0
    private var lockedUntil = 0L

    @Synchronized
    fun start(): Boolean {
        if (running.get()) return true
        val socket = runCatching { ServerSocket(PORT, 16) }.getOrNull()
            ?: runCatching { ServerSocket(0, 16) }.getOrNull() ?: return false
        serverSocket = socket
        activePort = socket.localPort
        running.set(true)
        Thread({ acceptLoop(socket) }, "dlm-remote-accept").apply { isDaemon = true }.start()
        return true
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        activePort = 0
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                return
            } catch (_: Exception) {
                continue
            }
            Thread({
                runCatching { handle(client) }
                runCatching { client.close() }
            }, "dlm-remote").apply { isDaemon = true }.start()
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 10_000
        val address = client.inetAddress
        if (!(address.isSiteLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress)) {
            respond(client, 403, "text/plain", "local network only")
            return
        }
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val parts = (reader.readLine() ?: return).split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val length = (headers["content-length"]?.toIntOrNull() ?: 0).coerceIn(0, MAX_BODY)
        val body = if (length > 0) {
            val chars = CharArray(length)
            var read = 0
            while (read < length) {
                val n = reader.read(chars, read, length - read)
                if (n <= 0) break
                read += n
            }
            String(chars, 0, read)
        } else {
            ""
        }

        if (method == "GET" && path == "/") {
            respond(client, 200, "text/html; charset=utf-8", PAGE)
            return
        }
        if (method == "POST" && path == "/login") {
            login(client, body)
            return
        }
        val authorised = headers["cookie"].orEmpty().split(';').any { it.trim() == "mdm_remote=$session" }
        if (!authorised) {
            respond(client, 401, "application/json", """{"ok":false}""")
            return
        }
        if (method == "GET" && path == "/api/list") {
            respond(client, 200, "application/json", listJson())
            return
        }
        // Writes need a header a cross-site form cannot set.
        if (method != "POST" || headers["x-remote"] != "1") {
            respond(client, 403, "application/json", """{"ok":false}""")
            return
        }
        val id = query.split('&').firstOrNull { it.startsWith("id=") }?.substring(3)
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        when (path) {
            "/api/pause" -> id?.let(pause)
            "/api/resume" -> id?.let(resume)
            "/api/add" -> body.trim().takeIf { it.startsWith("http", true) || it.startsWith("magnet:", true) }?.let(add)
            else -> {
                respond(client, 404, "application/json", """{"ok":false}""")
                return
            }
        }
        respond(client, 200, "application/json", """{"ok":true}""")
    }

    private fun login(client: Socket, body: String) {
        val now = System.currentTimeMillis()
        val wanted = pin()
        val given = body.substringAfter("pin=", "").trim()
        val allowed = synchronized(this) {
            if (now < lockedUntil) {
                false
            } else if (wanted.isNotEmpty() && given == wanted) {
                failures = 0
                true
            } else {
                if (++failures >= 5) { lockedUntil = now + 60_000; failures = 0 }
                false
            }
        }
        if (allowed) {
            respond(client, 200, "application/json", """{"ok":true}""",
                "Set-Cookie: mdm_remote=$session; HttpOnly; SameSite=Strict; Path=/")
        } else {
            respond(client, 403, "application/json", """{"ok":false}""")
        }
    }

    private fun listJson(): String = list().joinToString(",", "[", "]") { item ->
        val percent = if (item.totalBytes > 0) (item.bytesDownloaded * 100 / item.totalBytes).toInt() else 0
        """{"id":${q(item.id)},"name":${q(item.fileName)},"status":${q(item.status.name)},""" +
            """"percent":$percent,"speed":${q(com.downloadhub.core.DisplayFormat.speed(item.speedBytesPerSecond))},""" +
            """"size":${q(if (item.totalBytes > 0) com.downloadhub.core.DisplayFormat.bytes(item.totalBytes) else "")},""" +
            """"done":${item.status == DownloadStatus.COMPLETED}}"""
    }

    private fun q(text: String): String = buildString {
        append('"')
        text.forEach { c ->
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append(' ')
                else -> append(c)
            }
        }
        append('"')
    }

    private fun respond(client: Socket, status: Int, type: String, body: String, extra: String? = null) {
        runCatching {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val head = buildString {
                append("HTTP/1.1 ").append(status).append(' ').append(if (status == 200) "OK" else "Error").append("\r\n")
                append("Content-Type: ").append(type).append("\r\n")
                append("Content-Length: ").append(bytes.size).append("\r\n")
                append("Cache-Control: no-store\r\n")
                extra?.let { append(it).append("\r\n") }
                append("Connection: close\r\n\r\n")
            }
            val out = client.getOutputStream()
            out.write(head.toByteArray())
            out.write(bytes)
            out.flush()
        }
    }

    companion object {
        const val PORT = 38622
        private const val MAX_BODY = 8 * 1024

        @Volatile
        var activePort: Int = 0
            private set

        /** A six-digit PIN. */
        fun newPin(): String = (java.security.SecureRandom().nextInt(900_000) + 100_000).toString()

        /** This computer's address on the local network, for the phone to type. */
        fun lanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()

        private val PAGE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Downloads</title>
<style>
body{font-family:system-ui,sans-serif;margin:0;background:#14161a;color:#eee}
main{max-width:640px;margin:0 auto;padding:16px}
h1{font-size:20px}input,button{font:inherit;padding:10px;border-radius:8px;border:1px solid #444;background:#1d2026;color:#eee}
button{cursor:pointer}.row{display:flex;gap:8px;margin:8px 0}.row input{flex:1;min-width:0}
.item{background:#1d2026;border-radius:10px;padding:10px;margin:8px 0}
.bar{height:6px;background:#333;border-radius:3px;margin:6px 0}.bar i{display:block;height:6px;background:#b5e61d;border-radius:3px}
small{color:#9aa}.name{word-break:break-all}
</style></head><body><main>
<h1>Downloads</h1>
<div id="login" hidden><div class="row"><input id="pin" inputmode="numeric" placeholder="PIN shown on your PC"><button id="go">Sign in</button></div><small id="err"></small></div>
<div id="app" hidden>
<div class="row"><input id="link" placeholder="Paste a link or magnet"><button id="add">Add</button></div>
<div id="items"></div></div>
<script>
const H={'X-Remote':'1'};
const el=id=>document.getElementById(id);
async function load(){
 const r=await fetch('/api/list');
 if(r.status===401){el('login').hidden=false;el('app').hidden=true;return}
 el('login').hidden=true;el('app').hidden=false;
 const list=await r.json(), box=el('items');box.textContent='';
 for(const d of list){
  const div=document.createElement('div');div.className='item';
  const n=document.createElement('div');n.className='name';n.textContent=d.name;
  const bar=document.createElement('div');bar.className='bar';const i=document.createElement('i');i.style.width=d.percent+'%';bar.appendChild(i);
  const s=document.createElement('small');s.textContent=d.status+' · '+d.percent+'%'+(d.size?' · '+d.size:'')+(d.speed?' · '+d.speed:'');
  div.append(n,bar,s);
  if(!d.done){
   const b=document.createElement('button');const running=d.status==='RUNNING'||d.status==='QUEUED'||d.status==='RESOLVING';
   b.textContent=running?'Pause':'Resume';b.style.marginLeft='10px';
   b.onclick=async()=>{await fetch('/api/'+(running?'pause':'resume')+'?id='+encodeURIComponent(d.id),{method:'POST',headers:H});load()};
   div.append(b);
  }
  box.append(div);
 }
}
el('go').onclick=async()=>{
 const r=await fetch('/login',{method:'POST',body:'pin='+el('pin').value});
 el('err').textContent=r.ok?'':'Wrong PIN, or locked for a minute.';load();
};
el('add').onclick=async()=>{
 const v=el('link').value.trim();if(!v)return;
 await fetch('/api/add',{method:'POST',headers:H,body:v});el('link').value='';setTimeout(load,500);
};
load();setInterval(()=>{if(!el('app').hidden)load()},2000);
</script></main></body></html>"""
    }
}
