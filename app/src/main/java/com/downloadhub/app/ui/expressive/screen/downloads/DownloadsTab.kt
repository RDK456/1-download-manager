package com.downloadhub.app.ui.expressive.screen.downloads

import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.downloadhub.app.ui.AppUpdateViewModel
import com.downloadhub.app.ui.DownloadViewModel
import com.downloadhub.app.ui.expressive.model.BannerKind
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
    onOpenAbout: () -> Unit,
    onOpenDownloadSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
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

    var dismissed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var expanded by rememberSaveable { mutableStateOf(false) }

    val active = remember(online, notificationsOn, batteryExempt, update.status, freeBytes, needed) {
        buildSet {
            if (storageIsLow(freeBytes, needed)) add(BannerKind.STORAGE)
            if (!online) add(BannerKind.NETWORK)
            if (!notificationsOn) add(BannerKind.NOTIFICATIONS)
            if (!batteryExempt) add(BannerKind.BATTERY)
            if (update.status is UpdateStatus.Available) add(BannerKind.UPDATE)
        }
    }

    DownloadsScreen(
        items = items,
        loaded = loaded,
        summary = summary,
        conditions = RowConditions(offline = !online),
        activeBanners = active,
        bannerExpanded = expanded,
        dismissedBanners = dismissed.mapNotNull { name -> BannerKind.entries.firstOrNull { it.name == name } }.toSet(),
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
        onBannerDismiss = { dismissed = dismissed + it.name },
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
        onPauseAll = viewModel::pauseAll,
        onResumeAll = viewModel::resumeAll,
        onAdd = { viewModel.openEditor() },
        isTorrents = isTorrents,
        modifier = modifier
    )
}
