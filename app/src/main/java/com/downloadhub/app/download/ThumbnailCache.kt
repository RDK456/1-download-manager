package com.downloadhub.app.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Small thumbnail loader so the app does not need a full image library.
 *
 * Remote thumbnails are downloaded once into app-private storage and decoded with
 * `inSampleSize`, so a queue of hundreds of cards stays cheap in both memory and
 * bandwidth. Failures return null and the caller falls back to a category icon.
 */
class ThumbnailCache(private val context: Context) {
    private val memory: LruCache<String, Bitmap> = run {
        val limitKb = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceIn(4 * 1024, 32 * 1024)
        object : LruCache<String, Bitmap>(limitKb) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    private fun diskDir(): File = File(context.filesDir, "thumbnails").apply { mkdirs() }

    /**
     * @param localPath a file already on disk (a cached thumbnail, or the finished
     *   file itself when the download is an image).
     * @param remoteUrl optional remote source, downloaded and cached on first use.
     */
    suspend fun load(localPath: String?, remoteUrl: String?): Bitmap? = withContext(Dispatchers.IO) {
        localPath?.let { path ->
            decodeFile(File(path))?.let { return@withContext it }
        }
        val url = remoteUrl?.takeIf { it.isNotBlank() && it.startsWith("http") } ?: return@withContext null
        memory.get(url)?.let { return@withContext it }

        val target = File(diskDir(), fileNameFor(url))
        if (!target.isFile || target.length() <= 0L) {
            val downloaded = download(url, target)
            if (!downloaded) return@withContext null
        }
        decodeFile(target)?.also { memory.put(url, it) }
    }

    /** Drops the cached copy of a download's artwork. */
    fun discard(localPath: String?, remoteUrl: String?) {
        remoteUrl?.let { memory.remove(it) }
        val file = localPath?.let { File(it) } ?: remoteUrl?.let { File(diskDir(), fileNameFor(it)) }
        if (file != null && file.parentFile == diskDir()) {
            file.delete()
        }
    }

    private fun decodeFile(file: File): Bitmap? {
        if (!file.isFile || file.length() <= 0L) return null
        val memoryKey = "file:${file.absolutePath}:${file.lastModified()}"
        memory.get(memoryKey)?.let { return it }
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, TARGET_MAX_PX)
                // Keep the default ARGB_8888 so PNG thumbnails with alpha do not
                // come out with a black background.
            }
            BitmapFactory.decodeFile(file.absolutePath, options)?.also {
                memory.put(memoryKey, it)
            }
        }.getOrNull()
    }

    private fun download(url: String, target: File): Boolean = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode !in 200..299) return false
            val type = connection.contentType.orEmpty()
            if (type.isNotEmpty() && !type.startsWith("image/")) return false
            val temporary = File(target.parentFile, "${target.name}.part")
            connection.inputStream.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            if (temporary.length() <= 0L) {
                temporary.delete()
                return false
            }
            if (!temporary.renameTo(target)) {
                temporary.delete()
                return false
            }
            true
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    private fun sampleSizeFor(width: Int, height: Int, maxPx: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (maxOf(w, h) / 2 >= maxPx) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    private fun fileNameFor(url: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .joinToString(separator = "") { "%02x".format(it) }
        return "$digest.img"
    }

    private companion object {
        const val TARGET_MAX_PX = 320
        const val CONNECT_TIMEOUT_MILLIS = 8_000
        const val READ_TIMEOUT_MILLIS = 8_000
    }
}
