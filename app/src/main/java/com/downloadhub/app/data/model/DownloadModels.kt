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
    VIDEO,
    AUDIO,
    DOCUMENT,
    ARCHIVE,
    IMAGE,
    OTHER
}

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    AMOLED
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
    val torrentFilePath: String? = null
)
