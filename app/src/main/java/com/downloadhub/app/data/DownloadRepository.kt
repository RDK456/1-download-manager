package com.downloadhub.app.data

import com.downloadhub.app.data.local.DownloadDao
import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.data.model.DownloadCreateRequest
import com.downloadhub.app.download.DownloadStorage
import com.downloadhub.app.download.LinkParser
import com.downloadhub.app.download.ThumbnailCache
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow

class DownloadRepository(
    private val dao: DownloadDao,
    private val storage: DownloadStorage,
    private val thumbnailCache: ThumbnailCache
) {
    fun observeAll(): Flow<List<DownloadEntity>> = dao.observeAll()

    fun observeTorrents(): Flow<List<DownloadEntity>> = dao.observeTorrents()

    fun observeActiveCount(): Flow<Int> = dao.observeActiveCount()

    suspend fun get(id: String): DownloadEntity? = dao.getById(id)

    suspend fun create(request: DownloadCreateRequest): DownloadEntity {
        val now = System.currentTimeMillis()
        val sourceName = request.fileName?.takeIf { it.isNotBlank() }
            ?: if (request.source == com.downloadhub.app.data.model.DownloadSource.YOUTUBE) {
                "youtube-video"
            } else {
                LinkParser.fileNameFrom(request.url, request.contentDisposition, request.mimeType)
            }
        val fileName = LinkParser.sanitizeFileName(sourceName)
        val outputPath = when (request.source) {
            com.downloadhub.app.data.model.DownloadSource.HTTP -> null
            com.downloadhub.app.data.model.DownloadSource.TORRENT -> storage.torrentDirectory(fileName).path
            com.downloadhub.app.data.model.DownloadSource.YOUTUBE -> null
        }
        val category = request.category
            ?: LinkParser.categoryFor(request.source, fileName, request.mimeType)
        val entity = DownloadEntity(
            id = UUID.randomUUID().toString(),
            source = request.source,
            url = request.url,
            fileName = fileName,
            mimeType = request.mimeType,
            category = category,
            status = com.downloadhub.app.data.model.DownloadStatus.QUEUED,
            bytesDownloaded = 0,
            totalBytes = 0,
            progressPercent = 0,
            speedBytesPerSecond = 0,
            etaSeconds = -1,
            outputPath = outputPath,
            torrentFilePath = request.torrentFilePath,
            torrentInfoHash = null,
            userAgent = request.userAgent,
            contentDisposition = request.contentDisposition,
            etag = null,
            lastModified = null,
            errorMessage = null,
            createdAt = now,
            updatedAt = now,
            quality = request.quality,
            audioFormat = request.audioFormat,
            streamFormatId = request.streamFormatId,
            streamAudioFormatId = request.streamAudioFormatId
        )
        dao.insert(entity)
        return entity
    }

    suspend fun update(item: DownloadEntity) = dao.update(item)

    suspend fun delete(item: DownloadEntity) {
        storage.deleteWork(item.id)
        storage.deleteOutput(item.outputPath)
        thumbnailCache.discard(item.thumbnailPath, item.thumbnailUrl)
        if (item.source == com.downloadhub.app.data.model.DownloadSource.TORRENT) {
            item.torrentFilePath?.let { path -> File(path).delete() }
        }
        dao.deleteById(item.id)
    }

    suspend fun setStatus(id: String, status: com.downloadhub.app.data.model.DownloadStatus, error: String? = null) {
        dao.setStatus(id, status, error, System.currentTimeMillis())
    }

    suspend fun pauseAll() {
        dao.getByStatuses(
            listOf(
                com.downloadhub.app.data.model.DownloadStatus.QUEUED,
                com.downloadhub.app.data.model.DownloadStatus.RESOLVING,
                com.downloadhub.app.data.model.DownloadStatus.RUNNING
            )
        ).forEach { item ->
            dao.setStatus(item.id, com.downloadhub.app.data.model.DownloadStatus.PAUSED, null, System.currentTimeMillis())
        }
    }
}
