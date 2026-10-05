package com.downloadhub.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.downloadhub.core.TorrentAddRequest
import com.downloadhub.core.TorrentCreator
import com.downloadhub.core.TorrentParser
import java.io.File
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * qBittorrent's Torrent Creator. Hashes a file or folder into a .torrent, saves it where
 * the user says, and optionally starts seeding it from where the files already are.
 */
@Composable
internal fun CreateTorrentDialog(
    onSeed: (TorrentAddRequest) -> Unit,
    onDismiss: () -> Unit
) {
    var source by remember { mutableStateOf("") }
    var trackers by remember { mutableStateOf("") }
    var webSeeds by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var private by remember { mutableStateOf(false) }
    var seed by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val working = progress != null

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        properties = APP_DIALOG_PROPERTIES,
        title = { Text("Create torrent") },
        text = {
            Column(
                Modifier.width(520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it; problem = null },
                    label = { Text("File or folder to share") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { choose(JFileChooser.FILES_ONLY)?.let { source = it.absolutePath } }) {
                        Text("Choose file...")
                    }
                    OutlinedButton(onClick = { choose(JFileChooser.DIRECTORIES_ONLY)?.let { source = it.absolutePath } }) {
                        Text("Choose folder...")
                    }
                }
                OutlinedTextField(
                    value = trackers,
                    onValueChange = { trackers = it },
                    label = { Text("Trackers, one per line (a blank line starts the next tier)") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = webSeeds,
                    onValueChange = { webSeeds = it },
                    label = { Text("Web seed URLs, one per line (optional)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("Comment (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                TickRow("Private torrent", private, detail = "Peers come only from its trackers: no DHT, PEX or local discovery.") { private = it }
                TickRow("Start seeding it", seed, detail = "Shares the files from where they are now.") { seed = it }
                progress?.let { fraction ->
                    Spacer(Modifier.height(4.dp))
                    Text("Hashing pieces...", fontSize = 11.sp, color = AppTheme.Palette.muted)
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = AppTheme.Palette.accent,
                        trackColor = AppTheme.Palette.raised
                    )
                }
                problem?.let { Text(it, fontSize = 11.sp, color = AppTheme.Palette.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = source.isNotBlank() && !working,
                onClick = {
                    val file = File(source.trim())
                    if (!file.exists()) {
                        problem = "There is no file or folder at that path."
                        return@Button
                    }
                    progress = 0f
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                TorrentCreator.create(
                                    TorrentCreator.Request(file, trackers, webSeeds, comment, private)
                                ) { done, total -> if (total > 0) progress = done.toFloat() / total }
                            }
                        }
                        progress = null
                        val bytes = result.getOrElse {
                            problem = "Could not create it: ${it.message ?: it::class.simpleName}"
                            return@launch
                        }
                        val target = chooseSaveTarget(File(file.absoluteFile.parentFile, file.name + ".torrent"))
                            ?: return@launch
                        val saved = runCatching { target.writeBytes(bytes); target }.getOrElse {
                            problem = "Could not save ${target.name}: ${it.message}"
                            return@launch
                        }
                        if (seed) {
                            runCatching { TorrentParser.parse(saved) }.getOrNull()?.let { meta ->
                                onSeed(
                                    TorrentAddRequest(
                                        metainfo = meta,
                                        metainfoFile = saved,
                                        // The torrent is named after the file or folder itself, so
                                        // its data is found where it already is.
                                        saveDirectory = file.absoluteFile.parentFile,
                                        startImmediately = true,
                                        link = saved.absolutePath
                                    )
                                )
                            }
                        }
                        onDismiss()
                    }
                }
            ) { Text("Create") }
        },
        dismissButton = { TextButton(enabled = !working, onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun choose(mode: Int): File? {
    val chooser = JFileChooser().apply { fileSelectionMode = mode }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

private fun chooseSaveTarget(suggested: File): File? {
    val chooser = JFileChooser(suggested.parentFile).apply { selectedFile = suggested }
    if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
    val picked = chooser.selectedFile
    return if (picked.name.endsWith(".torrent", ignoreCase = true)) picked else File(picked.parentFile, picked.name + ".torrent")
}
