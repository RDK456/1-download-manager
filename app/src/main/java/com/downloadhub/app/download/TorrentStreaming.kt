package com.downloadhub.app.download

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.downloadhub.app.data.local.DownloadEntity
import java.io.File

/**
 * Plays a torrent's video while it is still downloading: switches the torrent to in-order
 * pieces, then opens the largest video or audio file with the phone's player. Seeking ahead
 * may stall until those parts arrive.
 */
object TorrentStreaming {
    private val PLAYABLE = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "ts", "mp3", "m4a", "flac", "ogg", "opus")

    fun play(context: Context, item: DownloadEntity) {
        DownloadService.requestSequential(context, item.id, true)
        val path = item.outputPath?.takeIf { it.isNotBlank() && !it.startsWith("content:") }
        val root = path?.let(::File)?.takeIf { it.exists() }
        val video = root?.let { if (it.isDirectory) it.walkTopDown().filter { f -> f.isFile && f.extension.lowercase() in PLAYABLE }.maxByOrNull(File::length) else it }
        if (video == null) {
            // Saved to a shared folder (content://) or the file has not appeared yet.
            Toast.makeText(context, "Fetching in playing order. Tap Play again in a minute.", Toast.LENGTH_LONG).show()
            return
        }
        val uri = runCatching { FileProvider.getUriForFile(context, "${context.packageName}.files", video) }.getOrNull()
        if (uri == null) {
            Toast.makeText(context, "This file cannot be opened.", Toast.LENGTH_LONG).show()
            return
        }
        val mime = when (video.extension.lowercase()) {
            "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "flac" -> "audio/flac"; "ogg", "opus" -> "audio/ogg"
            "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; else -> "video/*"
        }
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            Toast.makeText(context, "No app found to play this.", Toast.LENGTH_LONG).show()
        }
    }
}
