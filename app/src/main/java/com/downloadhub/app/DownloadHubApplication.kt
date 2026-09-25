package com.downloadhub.app

import android.app.Application
import com.downloadhub.app.download.DownloadRecoveryWorker
import com.downloadhub.app.download.DownloadNotifications

class DownloadHubApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        DownloadNotifications.createChannels(this)
        DownloadRecoveryWorker.schedule(this)
    }
}
