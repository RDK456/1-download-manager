package com.downloadhub.app.ui.expressive.screen.downloads

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import com.downloadhub.app.ui.expressive.screen.details.DetailsHost
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.downloadhub.app.ui.expressive.model.remainingBytes
import com.downloadhub.app.ui.expressive.model.storageIsLow
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.downloadhub.app.ui.AppUpdateViewModel
import com.downloadhub.app.ui.DownloadViewModel
import com.downloadhub.app.ui.expressive.model.BannerKind
import com.downloadhub.app.ui.expressive.model.DismissalLedger
import com.downloadhub.app.ui.expressive.model.RowConditions
import com.downloadhub.app.ui.expressive.shell.rememberNotificationsEnabled
import com.downloadhub.app.ui.expressive.shell.rememberOnResume
import com.downloadhub.app.ui.expressive.shell.rememberOnline
import com.downloadhub.app.ui.UpdateStatus

/**
 * Connects [DownloadsScreen] to the view model and to the phone: which notices apply right
 * now, and what their buttons do. Kept apart from the screen so the screen takes plain
 * values and can be previewed and tested without a view model.
 */
@Composable
fun DownloadsTab(
    viewModel: DownloadViewModel,
    updateViewModel: AppUpdateViewModel,
    isTorrents: Boolean,
    widePane: Boolean,
    onOpenAbout: () -> Unit,
    onOpenDownloadSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val torrentPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::addTorrentFile) }
    val items by (if (isTorrents) viewModel.visibleTorrents else viewModel.visibleDownloads).collectAsStateWithLifecycle()
    val summary by (if (isTorrents) viewModel.torrentSummary else viewModel.mainSummary).collectAsStateWithLifecycle()
    val kind by viewModel.currentKind.collectAsStateWithLifecycle()
    val kindCounts by viewModel.kindCounts.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val update by updateViewModel.snapshot.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val everything by viewModel.allDownloads.collectAsStateWithLifecycle()

    // Free space is read again every half minute while the list is on screen, and
    // whenever what is queued changes. A failed read is -1, which is never "low".
    val needed = remember(everything) { remainingBytes(everything) }
    val freeBytes by produceState(-1L, needed) {
        while (true) {
            value = withContext(Dispatchers.IO) { viewModel.freeStorageBytes() }
            delay(30_000)
        }
    }

    val online by rememberOnline()
    val notificationsOn by rememberNotificationsEnabled()
    val batteryExempt by rememberOnResume { viewModel.isBatteryExempt() }

    // "Not now" is remembered on the device, not just while this tab is on screen: leaving the
    // tab or closing the app must not bring the notice back (see DismissalLedger).
    val prefs = remember { context.getSharedPreferences("banners", android.content.Context.MODE_PRIVATE) }
    var ledger by remember { mutableStateOf(DismissalLedger.from { prefs.getLong("dismissed_${it.name}", 0L) }) }
    var expanded by rememberSaveable { mutableStateOf(false) }

    val active = remember(online, notificationsOn, batteryExempt, update.status, freeBytes, needed, everything.isEmpty()) {
        buildSet {
            if (storageIsLow(freeBytes, needed)) add(BannerKind.STORAGE)
            if (!online) add(BannerKind.NETWORK)
            if (!notificationsOn) add(BannerKind.NOTIFICATIONS)
            // Only worth nagging about once there is a download for the phone to stop.
            if (!batteryExempt && everything.isNotEmpty()) add(BannerKind.BATTERY)
            if (update.status is UpdateStatus.Available) add(BannerKind.UPDATE)
        }
    }

    val selected by viewModel.selectedDownload.collectAsStateWithLifecycle()
    val queues by viewModel.queues.collectAsStateWithLifecycle()
    val showPane = widePane && selected != null
    // In a wide window the details are a pane, not a sheet, so back has to close it.
    androidx.activity.compose.BackHandler(enabled = showPane) { viewModel.select(null) }

    Row(modifier) {
    DownloadsScreen(
        items = items,
        loaded = loaded,
        summary = summary,
        conditions = RowConditions(offline = !online),
        activeBanners = active,
        bannerExpanded = expanded,
        dismissedBanners = ledger.dismissed(System.currentTimeMillis()),
        onBannerAction = { banner, _ ->
            when (banner) {
                BannerKind.NOTIFICATIONS -> runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                BannerKind.BATTERY -> viewModel.requestBatteryExemption()
                BannerKind.UPDATE -> onOpenAbout()
                BannerKind.STORAGE -> onOpenDownloadSettings()
                else -> Unit
            }
        },
        onBannerDismiss = { kind ->
            val now = System.currentTimeMillis()
            ledger = ledger.dismiss(kind, now)
            prefs.edit().putLong("dismissed_${kind.name}", now).apply()
        },
        onBannerExpand = { expanded = true },
        kind = kind,
        kindCounts = kindCounts,
        showKinds = !isTorrents,
        query = query,
        onQueryChange = viewModel::setQuery,
        onKindChange = viewModel::setKindFilter,
        onOpen = viewModel::select,
        onPause = viewModel::pause,
        onResume = viewModel::resume,
        onDelete = viewModel::deleteWithUndo,
        onRetry = viewModel::retry,
        onPauseAll = viewModel::pauseAll,
        onResumeAll = viewModel::resumeAll,
        onAdd = { viewModel.openEditor() },
        onOpenTorrentFile = { torrentPicker.launch(arrayOf("application/x-bittorrent", "application/vnd.torrent", "application/octet-stream")) },
        isTorrents = isTorrents,
        modifier = Modifier.weight(1f)
    )
    // In a wide window the details sit beside the list instead of covering it.
    selected?.let { item ->
        if (showPane) {
            DetailsHost(
                item = item,
                viewModel = viewModel,
                queues = queues,
                conditions = RowConditions(offline = !online),
                asSheet = false,
                modifier = Modifier.weight(1f).padding(end = 16.dp, bottom = 16.dp)
            )
        }
    }
    }
}
