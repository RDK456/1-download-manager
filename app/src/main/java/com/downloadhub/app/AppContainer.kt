package com.downloadhub.app

import android.content.Context
import androidx.room.Room
import com.downloadhub.app.data.DownloadRepository
import com.downloadhub.app.data.SettingsRepository
import com.downloadhub.app.data.local.DownloadDatabase
import com.downloadhub.app.download.DownloadStorage
import com.downloadhub.app.download.NetworkMonitor
import com.downloadhub.app.download.SpeedLimiter
import com.downloadhub.app.download.ThumbnailCache
import com.downloadhub.app.download.YoutubeDownloader

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: DownloadDatabase = Room.databaseBuilder(
        appContext,
        DownloadDatabase::class.java,
        "download-hub.db"
    )
        .addMigrations(
            DownloadDatabase.MIGRATION_1_2,
            DownloadDatabase.MIGRATION_2_3,
            DownloadDatabase.MIGRATION_3_4
        )
        .fallbackToDestructiveMigration()
        .build()

    val thumbnailCache = ThumbnailCache(appContext)
    val speedLimiter = SpeedLimiter()
    val networkMonitor = NetworkMonitor(appContext)

    val storage = DownloadStorage(appContext)
    val settings = SettingsRepository(appContext)
    val youtubeDownloader = YoutubeDownloader(
        appContext,
        database.downloadDao(),
        storage,
        settings,
        thumbnailCache
    )
    val repository = DownloadRepository(database.downloadDao(), storage, thumbnailCache)
}
