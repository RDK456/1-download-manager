package com.downloadhub.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.downloadhub.core.HostResolver
import dev.datlag.kcef.KCEF
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefRendering
import org.cef.callback.CefBeforeDownloadCallback
import org.cef.callback.CefDownloadItem
import org.cef.callback.CefDownloadItemCallback
import org.cef.handler.CefDownloadHandler
import org.cef.handler.CefLoadHandlerAdapter

/** The embedded Chromium: downloaded once on first use (about 100 MB), then started in-process. */
private object ChromiumRuntime {
    var downloaded by mutableFloatStateOf(-1f)
    var ready by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    private var started = false

    suspend fun start() {
        if (started) return
        started = true
        val root = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "1DownloadManager/chromium")
        KCEF.init(
            builder = {
                installDir(File(root, "bundle"))
                progress {
                    onDownloading { downloaded = it.coerceAtLeast(0f) }
                    onInitialized { ready = true }
                }
                settings { cachePath = File(root, "cache").absolutePath }
            },
            onError = { error = it?.message ?: "Chromium could not start"; started = false },
            onRestartRequired = { error = "Restart the app to finish installing Chromium" }
        )
    }
}

/**
 * Runs inside the file host's own page: the page is only reachable after its Cloudflare check,
 * which the user passes in this window. Once through, the page's DOWNLOAD request is repeated
 * and its HX-Redirect target (the real file URL) is navigated to, which the download handler
 * catches. A reload after the check kills the script; the next load starts it again.
 */
private const val FETCH_FILE_URL = """
(async () => {
  if (window.__dlh) return; window.__dlh = 1;
  const id = location.pathname.split('/').filter(Boolean)[0];
  for (let i = 0; i < 200; i++) {
    try {
      const r = await fetch('/f/' + id + '/go', { method: 'POST', headers: { 'HX-Request': 'true' } });
      const h = r.headers.get('hx-redirect');
      if (h) { location.href = h; return; }
    } catch (e) {}
    await new Promise(r => setTimeout(r, 1500));
  }
})();
"""

/**
 * Walks [links] one by one through a real browser view. The user only has to pass the host's
 * check when it appears; each file URL found is stored in [HostResolver] and reported through
 * [onResolved], then the next link loads by itself.
 */
@Composable
fun HostCheckWindow(links: List<String>, onResolved: (String) -> Unit, onClose: () -> Unit) {
    DialogWindow(
        onCloseRequest = onClose,
        title = "Verify with the file host",
        state = rememberDialogState(size = DpSize(920.dp, 700.dp))
    ) {
        LaunchedEffect(Unit) { withContext(Dispatchers.IO) { ChromiumRuntime.start() } }
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!ChromiumRuntime.ready) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        ChromiumRuntime.error
                            ?: if (ChromiumRuntime.downloaded >= 0f) "Getting the browser component… ${ChromiumRuntime.downloaded.toInt()}%"
                            else "Starting the browser component…",
                        fontSize = 14.sp
                    )
                }
            } else {
                HostCheckBrowser(links, onResolved, onClose)
            }
        }
    }
}

@Composable
private fun HostCheckBrowser(links: List<String>, onResolved: (String) -> Unit, onClose: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    var found by remember { mutableStateOf<String?>(null) }
    val client = remember { KCEF.newClientOrNullBlocking() }
    if (client == null) {
        Text("The browser component could not be created.", fontSize = 14.sp)
        return
    }
    val browser = remember(client) { client.createBrowser(links.first(), CefRendering.DEFAULT, false) }

    DisposableEffect(client, browser) {
        val download = object : CefDownloadHandler {
            // Not continuing the callback cancels the download: only the URL is wanted.
            override fun onBeforeDownload(b: CefBrowser, item: CefDownloadItem, name: String?, cb: CefBeforeDownloadCallback) {
                found = item.url
            }
            override fun onDownloadUpdated(b: CefBrowser, item: CefDownloadItem, cb: CefDownloadItemCallback) = cb.cancel()
        }
        val load = object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(b: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (frame.isMain && frame.url.contains("fuckingfast.co")) b.executeJavaScript(FETCH_FILE_URL, frame.url, 0)
            }
        }
        client.addDownloadHandler(download)
        client.addLoadHandler(load)
        onDispose { browser.close(true); client.dispose() }
    }

    fun next() {
        if (index + 1 < links.size) { index++; browser.loadURL(links[index]) } else onClose()
    }

    LaunchedEffect(found) {
        val direct = found ?: return@LaunchedEffect
        found = null
        HostResolver.provide(links[index], direct)
        onResolved(links[index])
        next()
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Link ${index + 1} of ${links.size}. Pass the check if it appears; the file link is taken automatically.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(onClick = { next() }) { Text("Skip") }
    }
    SwingPanel(factory = { browser.uiComponent }, modifier = Modifier.fillMaxSize())
}
