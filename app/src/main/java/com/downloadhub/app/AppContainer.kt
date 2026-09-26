package com.downloadhub.app

import android.content.Context
import androidx.room.Room
import com.downloadhub.app.data.DownloadRepository
import com.downloadhub.app.data.SettingsRepository
import com.downloadhub.app.data.local.DownloadDatabase
import com.downloadhub.app.download.DownloadStorage
import com.downloadhub.app.download.YoutubeDownloader

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: DownloadDatabase = Room.databaseBuilder(
        appContext,
        DownloadDatabase::class.java,
        "download-hub.db"
    )
        .addMigrations(DownloadDatabase.MIGRATION_1_2)
        .fallbackToDestructiveMigration()
        .build()

    val storage = DownloadStorage(appContext)
    val settings = SettingsRepository(appContext)
    val youtubeDownloader = YoutubeDownloader(
        appContext,
        database.downloadDao(),
        storage,
        settings
    )
    val repository = DownloadRepository(database.downloadDao(), storage)
}
