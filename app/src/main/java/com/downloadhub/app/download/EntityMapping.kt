package com.downloadhub.app.download

import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.data.model.DownloadCategory as AppCategory
import com.downloadhub.app.data.model.DownloadSource as AppSource
import com.downloadhub.app.data.model.DownloadStatus as AppStatus
import com.downloadhub.core.DownloadCategory
import com.downloadhub.core.DownloadItem
import com.downloadhub.core.DownloadSource
import com.downloadhub.core.DownloadStatus

/**
 * Maps the Room entity onto the platform-neutral engine model.
 *
 * The engines in :core know nothing about Room, so this is the only place the two
 * representations meet. Keeping the conversion in one file stops the two field
 * lists from drifting apart as the model grows.
 */
internal fun DownloadEntity.toCoreItem(): DownloadItem = DownloadItem(
    id = id,
    url = url,
    fileName = fileName,
    source = when (source) {
        AppSource.HTTP -> DownloadSource.HTTP
        AppSource.YOUTUBE -> DownloadSource.YOUTUBE
        AppSource.TORRENT -> DownloadSource.TORRENT
    },
    status = when (status) {
        AppStatus.QUEUED -> DownloadStatus.QUEUED
        AppStatus.RESOLVING -> DownloadStatus.RESOLVING
        AppStatus.RUNNING -> DownloadStatus.RUNNING
        AppStatus.PAUSED -> DownloadStatus.PAUSED
        AppStatus.COMPLETED -> DownloadStatus.COMPLETED
        AppStatus.FAILED -> DownloadStatus.FAILED
    },
    category = when (category) {
        AppCategory.PROGRAM -> DownloadCategory.PROGRAM
        AppCategory.COMPRESSED -> DownloadCategory.COMPRESSED
        AppCategory.FILE -> DownloadCategory.FILE
        AppCategory.VIDEO -> DownloadCategory.VIDEO
        AppCategory.AUDIO -> DownloadCategory.AUDIO
        AppCategory.DOCUMENT -> DownloadCategory.DOCUMENT
        AppCategory.IMAGE -> DownloadCategory.IMAGE
        AppCategory.ARCHIVE -> DownloadCategory.ARCHIVE
        AppCategory.OTHER -> DownloadCategory.OTHER
    },
    bytesDownloaded = bytesDownloaded,
    totalBytes = totalBytes,
    speedBytesPerSecond = speedBytesPerSecond,
    errorMessage = errorMessage,
    location = outputPath,
    etag = etag,
    lastModified = lastModified,
    mimeType = mimeType,
    userAgent = userAgent,
    quality = quality,
    audioFormat = audioFormat,
    createdAt = createdAt,
    torrentFilePath = torrentFilePath,
    torrentInfoHash = torrentInfoHash,
    outputPath = outputPath
)
