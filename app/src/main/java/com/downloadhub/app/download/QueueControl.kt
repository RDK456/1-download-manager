package com.downloadhub.app.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.downloadhub.app.AppContainer
import com.downloadhub.app.DownloadHubApplication
import com.downloadhub.app.data.model.DownloadStatus
import com.downloadhub.core.QueueRules
import com.downloadhub.core.QueueSwitch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** A queue's Start and Stop, shared by the Queues screen and the schedule worker. */
object QueueControl {
    /**
     * Stopping pauses what the queue is running and remembers it; starting resumes those
     * and wakes the service for whatever else of the queue is waiting.
     */
    suspend fun setStarted(context: Context, container: AppContainer, id: String, started: Boolean) {
        val settings = container.settings
        settings.saveQueues(settings.currentQueues().map { if (it.id == id) it.copy(started = started) else it })
        val dao = container.database.downloadDao()
        val paused = settings.queuePausedIds()
        if (started) {
            val ours = paused.filter { pausedId -> dao.getById(pausedId)?.queueId == id }.toSet()
            ours.forEach { DownloadService.action(context, DownloadService.ACTION_RESUME, it) }
            settings.setQueuePausedIds(paused - ours)
            DownloadService.action(context, DownloadService.ACTION_RECOVER)
        } else {
            val running = dao.getByStatuses(listOf(DownloadStatus.RUNNING, DownloadStatus.RESOLVING))
                .filter { it.queueId == id }
            running.forEach { DownloadService.action(context, DownloadService.ACTION_PAUSE, it.id) }
            settings.setQueuePausedIds(paused + running.map { it.id })
        }
    }

    /** Presses Start or Stop for every queue whose scheduled time passed since the last check. */
    suspend fun runSchedules(context: Context, container: AppContainer) {
        val settings = container.settings
        val now = System.currentTimeMillis()
        val last = settings.lastQueueCheck().takeIf { it > 0 } ?: (now - 60_000)
        settings.setLastQueueCheck(now)
        val zone = ZoneId.systemDefault()
        val from = LocalDateTime.ofInstant(Instant.ofEpochMilli(last), zone)
        val to = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        settings.currentQueues().forEach { queue ->
            when (QueueRules.switchBetween(queue.schedule, from, to)) {
                QueueSwitch.START -> if (!queue.started) setStarted(context, container, queue.id, true)
                QueueSwitch.STOP -> if (queue.started) setStarted(context, container, queue.id, false)
                null -> Unit
            }
        }
    }
}

/**
 * Checks the queue schedules every 15 minutes - the shortest period Android allows for
 * background work - so a scheduled start or stop lands within a quarter of an hour.
 */
class QueueScheduleWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? DownloadHubApplication ?: return Result.failure()
        runCatching { QueueControl.runSchedules(applicationContext, app.container) }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<QueueScheduleWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "download-hub-queue-schedules",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
