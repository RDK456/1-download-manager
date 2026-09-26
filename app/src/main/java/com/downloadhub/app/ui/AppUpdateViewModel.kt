package com.downloadhub.app.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.downloadhub.app.BuildConfig
import com.downloadhub.app.DownloadHubApplication
import com.downloadhub.app.update.AppUpdateChecker
import com.downloadhub.app.update.ReleaseInfo
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Result of comparing the installed version with the newest GitHub release. */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class UpToDate(val version: String) : UpdateStatus
    data class Available(val release: ReleaseInfo) : UpdateStatus
    data class Failed(val message: String) : UpdateStatus
}

/** An APK that has already been fetched and is waiting to be installed. */
data class PendingInstall(val release: ReleaseInfo, val apk: File, val needsPermission: Boolean)

/** Progress of the release APK download. */
data class DownloadProgress(
    val release: ReleaseInfo,
    val percent: Int,
    val downloadedBytes: Long,
    val totalBytes: Long
)

/**
 * One immutable snapshot so the UI never has to combine several flows.
 * [pending] survives dialog dismissal, so the About page can always finish an
 * install that was already downloaded.
 */
data class UpdateSnapshot(
    val status: UpdateStatus = UpdateStatus.Idle,
    val progress: DownloadProgress? = null,
    val pending: PendingInstall? = null
)

/**
 * Drives the "check for updates" flow: query GitHub, compare the tag with the
 * installed version, download the release APK, then hand it to the system
 * package installer.
 */
class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as DownloadHubApplication
    private val settings = app.container.settings
    private val checker = AppUpdateChecker()

    private val _snapshot = MutableStateFlow(UpdateSnapshot())
    val snapshot: StateFlow<UpdateSnapshot> = _snapshot.asStateFlow()

    val currentVersion: String = BuildConfig.VERSION_NAME
    val currentVersionCode: Int = BuildConfig.VERSION_CODE

    val autoCheckUpdates: StateFlow<Boolean> = settings.autoCheckUpdates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    init {
        viewModelScope.launch {
            val pendingVersion = settings.pendingInstallVersion()
            if (pendingVersion.isNullOrBlank()) return@launch
            val apk = updateFile(pendingVersion)
            if (apk.isFile && apk.length() > 0L) {
                // A download interrupted by process death is still installable.
                _snapshot.value = _snapshot.value.copy(
                    pending = PendingInstall(
                        release = ReleaseInfo(
                            tag = "v$pendingVersion",
                            name = "Version $pendingVersion",
                            notes = "",
                            pageUrl = BuildConfig.GITHUB_URL,
                            publishedAt = "",
                            assets = emptyList()
                        ),
                        apk = apk,
                        needsPermission = !canRequestInstalls()
                    )
                )
            }
        }
    }

    /** Silent check on launch, throttled so it never spams the API. */
    fun checkOnLaunch() {
        viewModelScope.launch {
            if (!settings.autoCheckUpdates.first()) return@launch
            val last = settings.lastUpdateCheckOnce()
            if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MILLIS) return@launch
            performCheck(announceUpToDate = false)
        }
    }

    fun checkForUpdatesNow() {
        viewModelScope.launch { performCheck(announceUpToDate = true) }
    }

    fun setAutoCheck(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoCheckUpdates(enabled) }
    }

    private suspend fun performCheck(announceUpToDate: Boolean) {
        val current = _snapshot.value
        if (current.status is UpdateStatus.Checking || current.progress != null) return
        _snapshot.value = current.copy(status = UpdateStatus.Checking)
        settings.markUpdateCheck()
        runCatching { checker.latestRelease() }
            .onSuccess { release ->
                val asset = release.installAsset()
                when {
                    asset == null -> {
                        _snapshot.value = _snapshot.value.copy(
                            status = UpdateStatus.UpToDate(currentVersion)
                        )
                        if (announceUpToDate) {
                            _messages.value = "${release.displayName} has no downloadable file yet"
                        }
                    }
                    !release.isNewerThan(currentVersion) -> {
                        _snapshot.value = _snapshot.value.copy(
                            status = UpdateStatus.UpToDate(currentVersion)
                        )
                        if (announceUpToDate) {
                            _messages.value = "You are on the latest version ($currentVersion)"
                        }
                    }
                    release.isSkipped(settings.currentSkippedVersion()) -> {
                        _snapshot.value = _snapshot.value.copy(
                            status = UpdateStatus.UpToDate(currentVersion)
                        )
                        if (announceUpToDate) {
                            _messages.value = "Version ${release.version} was skipped"
                        }
                    }
                    else -> _snapshot.value = _snapshot.value.copy(
                        status = UpdateStatus.Available(release)
                    )
                }
            }
            .onFailure { error ->
                _snapshot.value = _snapshot.value.copy(
                    status = UpdateStatus.Failed(error.message ?: "Could not reach GitHub")
                )
            }
    }

    fun downloadUpdate() {
        val release = (_snapshot.value.status as? UpdateStatus.Available)?.release ?: return
        val asset = release.installAsset() ?: return
        viewModelScope.launch {
            _snapshot.value = _snapshot.value.copy(
                progress = DownloadProgress(release, 0, 0L, asset.size),
                pending = null
            )
            runCatching {
                withContext(Dispatchers.IO) { downloadUpdateApk(asset.downloadUrl, release.version) }
            }.onSuccess { file ->
                settings.setPendingInstallVersion(release.version)
                _snapshot.value = _snapshot.value.copy(
                    progress = null,
                    pending = PendingInstall(release, file, !canRequestInstalls())
                )
            }.onFailure { error ->
                _snapshot.value = _snapshot.value.copy(
                    progress = null,
                    status = UpdateStatus.Failed(error.message ?: "Update download failed")
                )
            }
        }
    }

    fun skipVersion() {
        val release = (_snapshot.value.status as? UpdateStatus.Available)?.release ?: return
        viewModelScope.launch {
            settings.setSkippedVersion(release.version)
            _snapshot.value = _snapshot.value.copy(status = UpdateStatus.UpToDate(currentVersion))
            _messages.value = "Version ${release.version} will be skipped"
        }
    }

    /** Opens the installer, or the "allow unknown apps" screen when required. */
    fun installUpdate(context: Context) {
        val pending = _snapshot.value.pending ?: return
        if (pending.needsPermission) {
            openInstallPermissionSettings(context)
            return
        }
        launchInstaller(context, pending.apk)
    }

    /** Re-evaluates the permission after the user returns from system settings. */
    fun refreshInstallPermission() {
        _snapshot.value.pending?.let { pending ->
            _snapshot.value = _snapshot.value.copy(
                pending = pending.copy(needsPermission = !canRequestInstalls())
            )
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { _messages.value = "Open Settings > Apps > Special app access > Install unknown apps" }
    }

    /** Hides the dialog; the downloaded APK stays available for a later install. */
    fun dismiss() {
        _snapshot.value = _snapshot.value.copy(progress = null)
    }

    fun consumeMessage() {
        _messages.value = null
    }

    private fun launchInstaller(context: Context, apk: File) {
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        }.getOrNull()
        if (uri == null) {
            _messages.value = "Could not open the downloaded update"
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onSuccess { viewModelScope.launch { settings.clearPendingInstallVersion() } }
            .onFailure { _messages.value = "No package installer found on this device" }
    }

    private fun canRequestInstalls(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            true
        } else {
            app.packageManager.canRequestPackageInstalls()
        }
    }.getOrDefault(false)

    private fun updateFile(version: String): File =
        File(File(app.filesDir, "updates"), "1-download-manager-$version.apk")

    private fun downloadUpdateApk(url: String, version: String): File {
        val directory = File(app.filesDir, "updates").apply { mkdirs() }
        val target = File(directory, "1-download-manager-$version.apk")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = true
            setRequestProperty("Accept", APK_MIME_TYPE)
            setRequestProperty("User-Agent", "1-download-manager/$currentVersion")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) error("Download failed with HTTP $status")
            val total = connection.contentLengthLong.coerceAtLeast(0L)
            var downloaded = 0L
            var lastPublished = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastPublished >= PROGRESS_INTERVAL_MILLIS) {
                            lastPublished = now
                            val percent = if (total > 0) {
                                ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }
                            _snapshot.value = _snapshot.value.copy(
                                progress = DownloadProgress(
                                    release = ReleaseInfo(
                                        tag = "v$version",
                                        name = "Version $version",
                                        notes = "",
                                        pageUrl = BuildConfig.GITHUB_URL,
                                        publishedAt = "",
                                        assets = emptyList()
                                    ),
                                    percent = percent,
                                    downloadedBytes = downloaded,
                                    totalBytes = total
                                )
                            )
                        }
                    }
                }
            }
            if (target.length() <= 0L) error("The downloaded update was empty")
            return target
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 30_000
        const val PROGRESS_INTERVAL_MILLIS = 250L
        const val AUTO_CHECK_INTERVAL_MILLIS = 6L * 60L * 60L * 1000L
    }
}
