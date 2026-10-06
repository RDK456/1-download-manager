package com.downloadhub.app.ui

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.data.model.DownloadCategory
import com.downloadhub.app.data.model.DownloadStatus
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The app's one player: downloaded music and video, and live TV streams. One ExoPlayer for
 * the whole app, so the mini player on every screen and the Player screen show the same thing.
 */
object AppPlayer {
    data class Item(val uri: String, val title: String, val isVideo: Boolean, val isLive: Boolean = false)

    data class State(
        val queue: List<Item> = emptyList(),
        val index: Int = -1,
        val playing: Boolean = false,
        val buffering: Boolean = false,
        val durationMillis: Long = 0L,
        val error: String? = null
    ) {
        val current: Item? get() = queue.getOrNull(index)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var exo: ExoPlayer? = null

    fun player(context: Context): ExoPlayer = exo ?: ExoPlayer.Builder(context.applicationContext).build().also { player ->
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = _state.update { it.copy(playing = isPlaying) }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) =
                _state.update { it.copy(index = player.currentMediaItemIndex, durationMillis = 0L, error = null) }
            override fun onPlaybackStateChanged(playbackState: Int) = _state.update {
                it.copy(
                    buffering = playbackState == Player.STATE_BUFFERING,
                    durationMillis = player.duration.takeIf { d -> d > 0 } ?: it.durationMillis
                )
            }
            override fun onPlayerError(error: PlaybackException) = _state.update {
                it.copy(playing = false, error = error.errorCodeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase())
            }
        })
        exo = player
    }

    fun play(context: Context, items: List<Item>, index: Int) {
        if (items.isEmpty()) return
        val player = player(context)
        player.setMediaItems(
            items.map { item ->
                MediaItem.Builder()
                    .setUri(if (item.uri.contains("://")) Uri.parse(item.uri) else Uri.fromFile(File(item.uri)))
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).build())
                    .build()
            },
            index.coerceIn(items.indices),
            0L
        )
        player.repeatMode = if (items.size > 1) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        player.prepare()
        player.play()
        _state.value = State(queue = items, index = index, playing = true)
    }

    fun toggle() {
        val player = exo ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    fun next() { exo?.seekToNextMediaItem() }
    fun previous() { exo?.seekToPrevious() }
    fun seek(millis: Long) { exo?.seekTo(millis) }
    fun position(): Long = exo?.currentPosition ?: 0L
    fun setSpeed(speed: Float) { exo?.setPlaybackSpeed(speed) }

    fun seekBy(millis: Long) {
        val player = exo ?: return
        val end = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + millis).coerceIn(0L, end))
    }

    fun stop() {
        exo?.stop()
        exo?.clearMediaItems()
        _state.value = State()
    }

    private val AUDIO = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "mka")
    private val VIDEO = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "ts")

    /**
     * The music and videos among finished downloads: the file itself, or the media inside a
     * torrent's folder. Files saved through the system file picker are content links and are
     * taken as they are.
     */
    fun libraryItems(downloads: List<DownloadEntity>): List<Item> = downloads
        .filter { it.status == DownloadStatus.COMPLETED && !it.outputPath.isNullOrBlank() }
        .flatMap { entity ->
            val path = entity.outputPath!!
            val categoryVideo = entity.category == DownloadCategory.VIDEO
            val categoryAudio = entity.category == DownloadCategory.AUDIO
            when {
                path.startsWith("content:") ->
                    if (categoryAudio || categoryVideo) listOf(Item(path, entity.fileName, categoryVideo)) else emptyList()
                File(path).isDirectory -> File(path).walkTopDown().maxDepth(4).filter { it.isFile }.take(200)
                    .mapNotNull { file -> kindOf(file.name)?.let { video -> Item(file.absolutePath, file.nameWithoutExtension, video) } }
                    .toList()
                else -> kindOf(path)?.let { listOf(Item(path, entity.fileName.substringBeforeLast('.'), it)) }.orEmpty()
            }
        }
        .distinctBy { it.uri }

    /** True for video, false for audio, null for neither. */
    private fun kindOf(name: String): Boolean? = when (name.substringAfterLast('.', "").lowercase()) {
        in VIDEO -> true
        in AUDIO -> false
        else -> null
    }
}
