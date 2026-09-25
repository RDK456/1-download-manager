package com.downloadhub.app.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.downloadhub.app.DownloadHubApplication
import java.util.concurrent.TimeUnit

class DownloadRecoveryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? DownloadHubApplication ?: return Result.failure()
        runCatching { app.container.youtubeDownloader.updateYtDlpIfNeeded() }
        app.container.database.downloadDao().recoverInterrupted(System.currentTimeMillis())
        DownloadService.start(applicationContext)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "download-hub-recovery"

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<DownloadRecoveryWorker>()
                .setInitialDelay(1, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
