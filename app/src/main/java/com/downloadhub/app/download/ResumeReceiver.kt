package com.downloadhub.app.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.downloadhub.app.DownloadHubApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Carries downloads on after the app is updated or the phone restarts.
 *
 * Both end the process, and nothing started it again until the app was next opened - so
 * an update quietly stopped every download. These two broadcasts are among the few that
 * may start a foreground service from the background, so the queue picks up at once.
 * Android 15 no longer lets a data-sync service start at boot; there the rows are still
 * re-queued and continue the moment the app is opened.
 */
class ResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED && intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? DownloadHubApplication ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = app.container.database.downloadDao()
                dao.recoverInterrupted(System.currentTimeMillis())
                if (dao.observeActiveCount().first() > 0) runCatching { DownloadService.start(app) }
            } finally {
                pending.finish()
            }
        }
    }
}
