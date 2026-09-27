package com.downloadhub.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.downloadhub.app.R
import com.downloadhub.app.update.YtDlpUpdateState

/**
 * yt-dlp status, kept read-only until it has something to offer.
 *
 * The update installs itself, so there is no button to press in the normal case.
 * A retry only appears when a check failed or an install did not take, which is
 * the one situation where tapping something actually helps.
 */
@Composable
fun YtDlpStatusRow(
    installed: String,
    state: YtDlpUpdateState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_system_update),
                contentDescription = null,
                tint = if (state.needsAction) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "yt-dlp $installed",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    ytdlpStatusDetail(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state.isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            }
        }

        if (state is YtDlpUpdateState.Updating) {
            Spacer(Modifier.width(0.dp))
            LinearProgressIndicator(
                progress = { 1f },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }

        if (state.needsAction) {
            Text(
                text = if (state is YtDlpUpdateState.Available) {
                    "The automatic install did not finish."
                } else {
                    "yt-dlp could not be checked automatically."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            TextButton(
                onClick = onRetry,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Text("Try again")
            }
        }
    }
}

/** One line of state. Short, because it sits under a read-only summary. */
fun ytdlpStatusDetail(state: YtDlpUpdateState): String = when (state) {
    YtDlpUpdateState.Idle -> "Checked automatically once a day"
    YtDlpUpdateState.Checking -> "Checking for a newer release"
    is YtDlpUpdateState.Updating -> "Installing ${state.latest}"
    is YtDlpUpdateState.UpToDate -> "Up to date"
    is YtDlpUpdateState.Available -> "Version ${state.latest} is waiting to install"
    is YtDlpUpdateState.Failed -> "Check failed: ${state.message}"
}

/** Kept for the About page's one-line version row. */
fun ytdlpSummary(installed: String, state: YtDlpUpdateState): String = when (state) {
    YtDlpUpdateState.Idle -> "yt-dlp $installed"
    YtDlpUpdateState.Checking -> "yt-dlp $installed - checking for a newer release"
    is YtDlpUpdateState.Updating -> "yt-dlp $installed - installing ${state.latest}"
    is YtDlpUpdateState.UpToDate -> "yt-dlp $installed is up to date"
    is YtDlpUpdateState.Available -> "yt-dlp $installed - version ${state.latest} is available"
    is YtDlpUpdateState.Failed -> "yt-dlp $installed - ${state.message}"
}
