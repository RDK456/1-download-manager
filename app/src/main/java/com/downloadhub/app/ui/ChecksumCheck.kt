package com.downloadhub.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.downloadhub.app.ui.expressive.component.OutlinedTextField
import com.downloadhub.core.Checksum
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Paste the hash a site published and compare the finished file against it. Both details views use it. */
@Composable
fun ChecksumCheck(outputPath: String) {
    // A torrent can publish a folder, which has no single hash.
    if (!outputPath.startsWith("content:") && File(outputPath).isDirectory) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expected by remember(outputPath) { mutableStateOf("") }
    var result by remember(outputPath) { mutableStateOf<String?>(null) }
    var matched by remember(outputPath) { mutableStateOf(false) }
    var busy by remember(outputPath) { mutableStateOf(false) }
    val algorithm = Checksum.algorithmFor(expected)

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            expected,
            { expected = it; result = null },
            label = { Text("Checksum (MD5 or SHA)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        TextButton(
            enabled = algorithm != null && !busy,
            onClick = {
                val wanted = expected
                busy = true
                result = "Checking…"
                scope.launch {
                    val actual = withContext(Dispatchers.IO) {
                        runCatching {
                            val stream = if (outputPath.startsWith("content:")) {
                                context.contentResolver.openInputStream(Uri.parse(outputPath))!!
                            } else {
                                File(outputPath).inputStream()
                            }
                            stream.use { Checksum.hex(it, Checksum.algorithmFor(wanted)!!) }
                        }.getOrNull()
                    }
                    busy = false
                    // The text may have been edited while a large file was being read.
                    if (wanted != expected) return@launch
                    matched = actual == Checksum.clean(wanted)
                    result = when {
                        actual == null -> "Could not read the file"
                        matched -> "Matches: the file is intact"
                        else -> "Does not match: download it again or check the source"
                    }
                }
            }
        ) { Text("Verify file") }
        result?.let {
            Text(
                it,
                color = if (matched || busy) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
