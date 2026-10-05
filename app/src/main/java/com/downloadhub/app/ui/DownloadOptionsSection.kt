package com.downloadhub.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.downloadhub.app.data.AppQueue
import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.core.HttpRequestOptions

/** Which queue a download is in. Tapping another chip moves it there. */
@Composable
internal fun QueueChips(current: String, queues: List<AppQueue>, onMove: (String) -> Unit) {
    if (queues.size <= 1) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Queue", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            queues.forEach { queue ->
                FilterChip(selected = queue.id == current, onClick = { onMove(queue.id) }, label = { Text(queue.name) })
            }
        }
    }
}

/**
 * Headers, cookies and a login for an ordinary link: what a site that only serves its own
 * signed-in visitors needs. Folded away until asked for, because almost no link needs it.
 */
@Composable
internal fun RequestSection(item: DownloadEntity, onSave: (HttpRequestOptions) -> Unit) {
    var open by remember(item.id) { mutableStateOf(false) }
    if (!open) {
        TextButton(onClick = { open = true }) { Text("Headers, cookies and login...") }
        return
    }
    RequestFields(
        initial = HttpRequestOptions(
            headers = HttpRequestOptions.parseHeaders(item.requestHeaders.orEmpty()),
            cookies = item.cookies.orEmpty(),
            username = item.username.orEmpty(),
            password = item.password.orEmpty()
        ),
        actionLabel = "Save for this download",
        onAction = { onSave(it); open = false }
    )
}

/** The fields themselves, shared by the details sheet and the add sheet. */
@Composable
internal fun RequestFields(
    initial: HttpRequestOptions,
    actionLabel: String?,
    onAction: (HttpRequestOptions) -> Unit,
    onChange: (HttpRequestOptions) -> Unit = {}
) {
    var headers by remember { mutableStateOf(HttpRequestOptions.formatHeaders(initial.headers)) }
    var cookies by remember { mutableStateOf(initial.cookies) }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }
    fun current() = HttpRequestOptions(HttpRequestOptions.parseHeaders(headers), cookies.trim(), username.trim(), password)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = headers,
            onValueChange = { headers = it; onChange(current()) },
            label = { Text("Extra headers, one per line (Name: value)") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = cookies,
            onValueChange = { cookies = it; onChange(current()) },
            label = { Text("Cookies (name=value; other=value)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it; onChange(current()) },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; onChange(current()) },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.weight(1f)
            )
        }
        if (actionLabel != null) OutlinedButton(onClick = { onAction(current()) }) { Text(actionLabel) }
    }
}
