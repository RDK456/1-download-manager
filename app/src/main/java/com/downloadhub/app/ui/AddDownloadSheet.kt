package com.downloadhub.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.downloadhub.app.data.model.DownloadCategory
import com.downloadhub.app.data.model.DownloadSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDownloadSheet(
    seed: EditorSeed,
    onDismiss: () -> Unit,
    onAdd: (
        rawLink: String,
        fileName: String?,
        category: DownloadCategory?,
        sourceOverride: DownloadSource?,
        userAgent: String?,
        contentDisposition: String?
    ) -> Unit,
    onPickTorrent: (Uri) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current
    var link by rememberSaveable(seed.link) { mutableStateOf(seed.link) }
    var fileName by rememberSaveable(seed.fileName) { mutableStateOf(seed.fileName.orEmpty()) }
    var category by remember(seed.source) {
        mutableStateOf(seed.category ?: if (seed.source == DownloadSource.YOUTUBE) DownloadCategory.VIDEO else null)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onPickTorrent)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Add download", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
            Text(
                sourceTitle(seed.source),
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                color = androidx.compose.material3.MaterialTheme.colorScheme.primary
            )
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (seed.source == DownloadSource.TORRENT) "Magnet or .torrent URL" else "URL") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                trailingIcon = {
                    androidx.compose.material3.IconButton(onClick = {
                        clipboard.getText()?.text?.let { link = it }
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste link")
                    }
                }
            )
            if (seed.source == DownloadSource.TORRENT) {
                OutlinedButton(
                    onClick = { filePicker.launch(arrayOf("application/x-bittorrent", "application/octet-stream")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FileOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Choose .torrent file")
                }
            } else {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("File name (optional)") },
                    singleLine = true
                )
                CategorySelector(seed.source, category) { category = it }
            }
            Button(
                onClick = {
                    onAdd(
                        link,
                        fileName.takeIf { it.isNotBlank() },
                        category,
                        seed.source,
                        seed.userAgent,
                        seed.contentDisposition
                    )
                },
                enabled = link.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Add to queue")
            }
        }
    }
}

@Composable
private fun CategorySelector(source: DownloadSource, selected: DownloadCategory?, onSelect: (DownloadCategory) -> Unit) {
    val categories = if (source == DownloadSource.YOUTUBE) {
        listOf(DownloadCategory.VIDEO, DownloadCategory.AUDIO)
    } else {
        listOf(
            DownloadCategory.VIDEO,
            DownloadCategory.AUDIO,
            DownloadCategory.DOCUMENT,
            DownloadCategory.ARCHIVE,
            DownloadCategory.IMAGE,
            DownloadCategory.OTHER
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        categories.take(3).forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                label = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() }) }
            )
        }
    }
}

private fun sourceTitle(source: DownloadSource): String = when (source) {
    DownloadSource.HTTP -> "Direct download"
    DownloadSource.YOUTUBE -> "YouTube video or audio"
    DownloadSource.TORRENT -> "BitTorrent download"
}
