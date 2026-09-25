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
    private var incomingLink by mutableStateOf<String?>(null)
    private var incomingDownloadId by mutableStateOf<String?>(null)

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
        setContent {
            DownloadHubApp(
                viewModel = viewModel,
                incomingLink = incomingLink,
                incomingDownloadId = incomingDownloadId,
                onIncomingConsumed = {
                    incomingLink = null
                    incomingDownloadId = null
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val clipItem = intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val clipText = clipItem?.text?.toString()
        val data = intent.data
        val clipUri = clipItem?.uri
        val streamUri = intent.extras?.getParcelable<Uri>(Intent.EXTRA_STREAM)
        val link = LinkParser.extractFirstLink(sharedText.orEmpty())
            ?: LinkParser.extractFirstLink(clipText.orEmpty())
            ?: when {
                data != null && (data.scheme == "http" || data.scheme == "https" || data.scheme == "magnet") ->
                    data.toString()
                clipUri != null -> clipUri.toString()
                streamUri != null -> streamUri.toString()
                else -> null
            }
        if (link != null) incomingLink = link

        val sharedContentUri = when {
            data?.scheme == "content" -> data
            clipUri?.scheme == "content" -> clipUri
            streamUri?.scheme == "content" -> streamUri
            else -> null
        }
        if (sharedContentUri != null && isTorrentContent(sharedContentUri)) {
            viewModel.addTorrentFile(sharedContentUri)
        }
        incomingDownloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID) ?: incomingDownloadId
    }

    private fun isTorrentContent(uri: Uri): Boolean = runCatching {
        val mime = contentResolver.getType(uri).orEmpty()
        val name = queryName(uri).orEmpty()
        mime.contains("torrent", ignoreCase = true) ||
            mime.contains("bittorrent", ignoreCase = true) ||
            name.endsWith(".torrent", ignoreCase = true) ||
            uri.toString().contains("torrent", ignoreCase = true)
    }.getOrDefault(false)

    private fun queryName(uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }

    companion object {
        const val EXTRA_DOWNLOAD_ID = "download_id"
    }
}
