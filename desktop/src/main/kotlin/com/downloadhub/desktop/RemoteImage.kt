package com.downloadhub.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Covers and posters for the grids, fetched once and kept: in memory for this run and on
 * disk for the next, so scrolling back up or reopening a search does not fetch them again.
 */
object RemoteImages {
    private const val MEMORY_ITEMS = 300
    private val memory = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > MEMORY_ITEMS
    }
    private val dir: File by lazy { File(AppPaths.home, "cache/images").apply { mkdirs() } }

    fun cached(url: String): ImageBitmap? = synchronized(memory) { memory[url] }

    suspend fun load(url: String): ImageBitmap? = cached(url) ?: withContext(Dispatchers.IO) {
        runCatching {
            val name = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
            val file = File(dir, "$name.img")
            val bytes = if (file.isFile && file.length() > 0) {
                file.readBytes()
            } else {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 1-download-manager")
                }
                try {
                    if (connection.responseCode !in 200..299) return@runCatching null
                    connection.inputStream.use { it.readBytes() }.also { file.writeBytes(it) }
                } finally {
                    connection.disconnect()
                }
            }
            org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                .also { synchronized(memory) { memory[url] = it } }
        }.getOrNull()
    }
}

/** The image at [url] once it has arrived; null while loading, or when there is none. */
@Composable
fun rememberRemoteImage(url: String?): ImageBitmap? {
    var image by remember(url) { mutableStateOf(url?.let(RemoteImages::cached)) }
    LaunchedEffect(url) {
        if (url != null && image == null) image = RemoteImages.load(url)
    }
    return image
}
