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
import com.downloadhub.app.update.TransferState
import com.downloadhub.app.update.UpdateService
import com.downloadhub.app.update.UpdateTransferState
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Result of comparing the installed version with the newest GitHub release. */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class UpToDate(val version: String) : UpdateStatus
    data class Available(val release: ReleaseInfo) : UpdateStatus
    data class Failed(val message: String) : UpdateStatus
}

/** An APK that has already been fetched and is waiting to be installed. */
data class PendingInstall(
    val release: ReleaseInfo,
    val apk: File,
    val needsPermission: Boolean,
    /**
     * False when the downloaded APK is signed with a different key than the running
     * build (for example a debug-signed install receiving a release-signed update),
     * which Android rejects with INSTALL_FAILED_UPDATE_INCOMPATIBLE.
     */
    val signatureMatches: Boolean = true
)

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
        observeTransferState()
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

    /**
     * Hands the transfer to [UpdateService] instead of downloading here.
     *
     * Running it in `viewModelScope` tied the download to this Activity: minimising
     * the app or leaving the page cancelled it. A foreground service keeps the
     * transfer alive in the background and shows its own notification, and it
     * publishes the finished APK through [UpdateTransferState] so the install can
     * still be offered after the app was gone.
     */
    fun downloadUpdate() {
        val release = (_snapshot.value.status as? UpdateStatus.Available)?.release ?: return
        val asset = release.installAsset() ?: return
        // Clear the "available" status so its dialog gives way to the download
        // progress dialog instead of two dialogs fighting for the screen.
        _snapshot.value = _snapshot.value.copy(
            status = UpdateStatus.Idle,
            progress = DownloadProgress(release, 0, 0L, asset.size),
            pending = null
        )
        UpdateService.start(app, asset.downloadUrl, release.version, release.displayName)
    }

    /**
     * Mirrors the service's transfer state into [snapshot] so the UI can be on any
     * page: a download started here keeps reporting progress, and one that
     * finished in the background becomes an install prompt on return.
     */
    private fun observeTransferState() {
        viewModelScope.launch {
            UpdateTransferState.state.collect { transfer ->
                when (transfer) {
                    is TransferState.Idle -> Unit
                    is TransferState.Running -> {
                        val release = ReleaseInfo(
                            tag = "v${transfer.version}",
                            name = "Version ${transfer.version}",
                            notes = "",
                            pageUrl = BuildConfig.GITHUB_URL,
                            publishedAt = "",
                            assets = emptyList()
                        )
                        _snapshot.value = _snapshot.value.copy(
                            progress = DownloadProgress(
                                release = release,
                                percent = transfer.percent,
                                downloadedBytes = transfer.downloadedBytes,
                                totalBytes = transfer.totalBytes
                            ),
                            status = UpdateStatus.Idle
                        )
                    }
                    is TransferState.Done -> {
                        val apk = transfer.apk
                        if (!apk.isFile || apk.length() <= 0L) {
                            // A file that is not there cannot be installed; drop it
                            // rather than offering a broken install.
                            _snapshot.value = _snapshot.value.copy(
                                progress = null,
                                status = UpdateStatus.Failed("The downloaded update is no longer available")
                            )
                            return@collect
                        }
                        viewModelScope.launch {
                            settings.setPendingInstallVersion(transfer.version)
                            _snapshot.value = _snapshot.value.copy(
                                progress = null,
                                pending = PendingInstall(
                                    release = ReleaseInfo(
                                        tag = "v${transfer.version}",
                                        name = "Version ${transfer.version}",
                                        notes = "",
                                        pageUrl = BuildConfig.GITHUB_URL,
                                        publishedAt = "",
                                        assets = emptyList()
                                    ),
                                    apk = apk,
                                    needsPermission = !canRequestInstalls(),
                                    signatureMatches = isSignedBySameKey(apk)
                                )
                            )
                        }
                        UpdateTransferState.acknowledge()
                    }
                    is TransferState.Failed -> {
                        _snapshot.value = _snapshot.value.copy(
                            progress = null,
                            status = UpdateStatus.Failed(transfer.message)
                        )
                        UpdateTransferState.acknowledge()
                    }
                }
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
        if (!pending.signatureMatches) {
            openAppDetails(context)
            return
        }
        if (pending.needsPermission) {
            openInstallPermissionSettings(context)
            return
        }
        launchInstaller(context, pending.apk)
    }

    /** Takes the user to this app's system page so an old build can be removed. */
    fun openAppDetails(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                _messages.value =
                    "Uninstall the previous build first, then install version ${_snapshot.value.pending?.release?.version}."
            }
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

    /**
     * Compares the signing certificate of a downloaded APK with the running build so
     * a key mismatch is explained before Android rejects the install.
     */
    private fun isSignedBySameKey(apk: File): Boolean {
        val installed = installedSignatureDigest() ?: return true
        val downloaded = archiveSignatureDigest(apk) ?: return true
        return installed == downloaded
    }

    private fun installedSignatureDigest(): String? = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            android.content.pm.PackageManager.GET_SIGNATURES
        }
        val info = app.packageManager.getPackageInfo(app.packageName, flags)
        certificateDigest(info)
    }.getOrNull()

    private fun archiveSignatureDigest(apk: File): String? = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            android.content.pm.PackageManager.GET_SIGNATURES
        }
        val info = app.packageManager.getPackageArchiveInfo(apk.absolutePath, flags) ?: return@runCatching null
        certificateDigest(info)
    }.getOrNull()

    private fun certificateDigest(info: android.content.pm.PackageInfo): String? {
        val bytes: ByteArray = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return null
            val signers = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
            signers.firstOrNull()?.toByteArray() ?: return null
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray() ?: return null
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    private fun updateFile(version: String): File =
        File(File(app.filesDir, "updates"), "1-download-manager-$version.apk")

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val AUTO_CHECK_INTERVAL_MILLIS = 6L * 60L * 60L * 1000L
    }
}
