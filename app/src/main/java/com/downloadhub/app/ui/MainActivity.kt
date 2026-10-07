package com.downloadhub.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.downloadhub.app.download.LinkParser

class MainActivity : ComponentActivity() {
    private val viewModel: DownloadViewModel by viewModels()
    private val updateViewModel: AppUpdateViewModel by viewModels()
    private var incomingLink by mutableStateOf<String?>(null)
    private var incomingDownloadId by mutableStateOf<String?>(null)
    private var openUpdates by mutableStateOf(false)

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // The display settings are read before the first frame, so it is drawn right.
        com.downloadhub.app.ui.theme.UiEffects.load(this)
        com.downloadhub.app.ui.theme.UiStyle.load(this)
        setContent {
            DownloadHubApp(
                viewModel = viewModel,
                updateViewModel = updateViewModel,
                incomingLink = incomingLink,
                incomingDownloadId = incomingDownloadId,
                openUpdates = openUpdates,
                onIncomingConsumed = {
                    incomingLink = null
                    incomingDownloadId = null
                },
                onUpdatesConsumed = { openUpdates = false }
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) pickUpCopiedLink()
    }

    /**
     * A download link copied in the browser is picked up when the app comes back to the
     * front, as AB Download Manager does. Only on focus, because Android lets only the
     * focused app read the clipboard. Each link is picked up once, and never one that is
     * already in the list (the app's own "copy link" would otherwise come straight back).
     */
    private fun pickUpCopiedLink() {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java) ?: return
        val text = runCatching { clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }
            .getOrNull() ?: return
        val link = LinkParser.extractFirstLink(text)
            ?.takeIf { com.downloadhub.core.LinkParser.looksLikeDownload(it) } ?: return
        val prefs = getSharedPreferences("clipboard", MODE_PRIVATE)
        if (prefs.getString("last", null) == link) return
        prefs.edit().putString("last", link).apply()
        if (viewModel.allDownloads.value.any { it.url == link }) return
        incomingLink = link
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        // Tapping the "update ready" notification opens the update screen directly.
        if (intent.getBooleanExtra(EXTRA_OPEN_UPDATES, false)) {
            intent.removeExtra(EXTRA_OPEN_UPDATES)
            openUpdates = true
        }

        val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val clipItem = intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val clipText = clipItem?.text?.toString()
        val data = intent.data
        val clipUri = clipItem?.uri
        val streamUri = intent.extras?.getParcelable<Uri>(Intent.EXTRA_STREAM)

        // A .torrent handed to us by a file manager or another app wins over text,
        // so "Open with" starts the torrent instead of showing an empty editor.
        val torrentUri = listOfNotNull(data, clipUri, streamUri)
            .firstOrNull { isTorrentContent(it) }
        if (torrentUri != null) {
            viewModel.addTorrentFile(torrentUri)
            return
        }

        val link = LinkParser.extractFirstLink(sharedText.orEmpty())
            ?: LinkParser.extractFirstLink(clipText.orEmpty())
            ?: listOfNotNull(data, clipUri, streamUri)
                .firstOrNull { it.scheme == "http" || it.scheme == "https" || it.scheme == "magnet" }
                ?.toString()
        if (link != null) incomingLink = link

        // Anything else that arrived as a file (a PDF, an APK, an image) is not a
        // link we can queue, so say so instead of opening an unusable editor.
        val unusableFile = listOfNotNull(data, clipUri, streamUri).any {
            it.scheme == "content" || it.scheme == "file"
        }
        if (link == null && unusableFile) {
            viewModel.notify("Only .torrent files can be opened here. Use a link for other downloads.")
        }
        incomingDownloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID) ?: incomingDownloadId
    }

    private fun isTorrentContent(uri: Uri): Boolean = runCatching {
        if (uri.scheme != "content" && uri.scheme != "file") return@runCatching false
        val mime = runCatching { contentResolver.getType(uri) }.getOrNull()
        val name = runCatching { queryName(uri) }.getOrNull()
            ?: uri.lastPathSegment
            ?: uri.path
            ?: ""
        LinkParser.looksLikeTorrent(name, mime) ||
            name.substringAfterLast('/').endsWith(".torrent", ignoreCase = true)
    }.getOrDefault(false)

    private fun queryName(uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }

    companion object {
        const val EXTRA_DOWNLOAD_ID = "download_id"

        /** Set by the update notification so tapping it lands on the update screen. */
        const val EXTRA_OPEN_UPDATES = "open_updates"
    }
}
