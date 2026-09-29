package com.downloadhub.app

import android.content.Context
import androidx.room.Room
import com.downloadhub.app.data.DownloadRepository
import com.downloadhub.app.data.SettingsRepository
import com.downloadhub.app.data.local.DownloadDatabase
import com.downloadhub.app.download.BatteryOptimisation
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
        // Taken from the database's own list rather than spelled out again here: a
        // migration written in one place and forgotten in the other is a crash on
        // launch, because Room finds no path and throws instead of falling back.
        .addMigrations(*DownloadDatabase.ALL)
        // Last resort only. With no path available, starting empty beats refusing to
        // open - but it must never be the reason a path is missing.
        .fallbackToDestructiveMigration()
        .build()

    val thumbnailCache = ThumbnailCache(appContext)
    val speedLimiter = SpeedLimiter()
    val networkMonitor = NetworkMonitor(appContext)
    val batteryOptimisation = BatteryOptimisation(appContext)

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
