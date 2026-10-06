package com.downloadhub.app.ui

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
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
    data class Item(
        val uri: String,
        val title: String,
        val isVideo: Boolean,
        val isLive: Boolean = false,
        val userAgent: String? = null,
        val referrer: String? = null
    )

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
    private var liveRejoins = 0

    /**
     * Decoders that fail to start give way to the next one, and emulator decoders
     * ("goldfish") go last: they tear live streams into stripes when the picture size changes.
     */
    @OptIn(UnstableApi::class)
    private fun renderers(context: Context) = DefaultRenderersFactory(context)
        .setEnableDecoderFallback(true)
        .setMediaCodecSelector { mimeType, secure, tunneling ->
            MediaCodecUtil.getDecoderInfos(mimeType, secure, tunneling).sortedBy { it.name.contains("goldfish") }
        }

    @OptIn(UnstableApi::class)
    fun player(context: Context): ExoPlayer = exo ?: ExoPlayer.Builder(context.applicationContext, renderers(context.applicationContext)).build().also { player ->
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
            override fun onPlayerError(error: PlaybackException) {
                // A live stream paused or stalled past what the broadcaster keeps: rejoin it live.
                // Capped, so a stream that keeps falling behind ends in an error, not a loop.
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && liveRejoins++ < 3) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                _state.update { it.copy(playing = false, error = describe(error)) }
            }
        })
        exo = player
    }

    @OptIn(UnstableApi::class)
    fun play(context: Context, items: List<Item>, index: Int) {
        if (items.isEmpty()) return
        val player = player(context)
        liveRejoins = 0
        // The application context: the shared player outlives the screen that called this.
        player.setMediaSources(items.map { mediaSource(context.applicationContext, it) }, index.coerceIn(items.indices), 0L)
        player.repeatMode = if (items.size > 1) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        player.prepare()
        player.play()
        _state.value = State(queue = items, index = index, playing = true)
    }

    /**
     * One item as something ExoPlayer can play. Streams are told apart by their address
     * (HLS, DASH or a plain file), may move between http and https on the way, and get the
     * user agent and referrer a channel asks for.
     */
    @OptIn(UnstableApi::class)
    private fun mediaSource(context: Context, item: Item): MediaSource {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setUserAgent(item.userAgent ?: "Mozilla/5.0 (Linux; Android) 1DM")
            .setDefaultRequestProperties(listOfNotNull(item.referrer?.let { "Referer" to it }).toMap())
        val address = item.uri.substringBefore('?').lowercase()
        val mediaItem = MediaItem.Builder()
            .setUri(if (item.uri.contains("://")) Uri.parse(item.uri) else Uri.fromFile(File(item.uri)))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).build())
            .apply {
                when {
                    address.contains(".m3u8") -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    address.endsWith(".mpd") -> setMimeType(MimeTypes.APPLICATION_MPD)
                }
            }
            .build()
        return DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)).createMediaSource(mediaItem)
    }

    private fun describe(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The channel refused the connection. It may be offline or blocked in your region."
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Could not reach the stream. It may be offline."
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "This stream is not allowed over plain http."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "This stream's format cannot be played on this phone."
        else -> error.errorCodeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase()
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
