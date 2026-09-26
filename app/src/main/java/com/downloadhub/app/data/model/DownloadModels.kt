package com.downloadhub.app.data.model

enum class DownloadSource {
    HTTP,
    YOUTUBE,
    TORRENT
}

enum class DownloadStatus {
    QUEUED,
    RESOLVING,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED
}

enum class DownloadCategory {
    PROGRAM,
    COMPRESSED,
    FILE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    IMAGE,
    ARCHIVE,
    OTHER
}

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    AMOLED
}

/**
 * Video quality ceiling for YouTube downloads. "AUDIO" drops the video stream
 * entirely, which is what the user sees as "audio only".
 */
enum class MediaQuality(val value: String, val label: String, val maxHeight: Int?) {
    BEST("best", "Best available", null),
    Q2160("2160", "4K · 2160p", 2160),
    Q1440("1440", "2K · 1440p", 1440),
    Q1080("1080", "Full HD · 1080p", 1080),
    Q720("720", "HD · 720p", 720),
    Q480("480", "SD · 480p", 480),
    Q360("360", "Low · 360p", 360),
    AUDIO("audio", "Audio only", null);

    val isAudioOnly: Boolean get() = this == AUDIO

    companion object {
        fun fromValue(value: String?): MediaQuality =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) } ?: BEST
    }
}

/** Container/codec used when the user asks for audio only. */
enum class AudioFormat(val value: String, val label: String, val extension: String) {
    M4A("m4a", "M4A (AAC)", "m4a"),
    MP3("mp3", "MP3", "mp3"),
    OPUS("opus", "Opus (smallest)", "opus"),
    WAV("wav", "WAV (lossless)", "wav");

    companion object {
        fun fromValue(value: String?): AudioFormat =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) } ?: M4A
    }
}

val DownloadCategory.label: String
    get() = when (this) {
        DownloadCategory.PROGRAM -> "Programs"
        DownloadCategory.COMPRESSED, DownloadCategory.ARCHIVE -> "Compressed"
        DownloadCategory.FILE -> "Files"
        DownloadCategory.VIDEO -> "Video"
        DownloadCategory.AUDIO -> "Audio"
        DownloadCategory.DOCUMENT -> "Documents"
        DownloadCategory.IMAGE -> "Images"
        DownloadCategory.OTHER -> "Other"
    }

val DownloadStatus.isActive: Boolean
    get() = this == DownloadStatus.QUEUED ||
        this == DownloadStatus.RESOLVING ||
        this == DownloadStatus.RUNNING

val DownloadStatus.canPause: Boolean
    get() = this == DownloadStatus.QUEUED ||
        this == DownloadStatus.RESOLVING ||
        this == DownloadStatus.RUNNING

val DownloadStatus.canResume: Boolean
    get() = this == DownloadStatus.PAUSED || this == DownloadStatus.FAILED

data class DownloadCreateRequest(
    val source: DownloadSource,
    val url: String,
    val fileName: String? = null,
    val mimeType: String? = null,
    val category: DownloadCategory? = null,
    val userAgent: String? = null,
    val contentDisposition: String? = null,
    val torrentFilePath: String? = null,
    val quality: String? = null,
    val audioFormat: String? = null
)
