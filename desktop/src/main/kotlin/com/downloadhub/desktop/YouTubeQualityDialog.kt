package com.downloadhub.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.sp
import com.downloadhub.core.DisplayFormat
import com.downloadhub.core.StreamFormat
import com.downloadhub.core.chooseStream

/**
 * Which stream to download, with the real ones on offer.
 *
 * Not a "best / 1080p / 720p" dropdown. Those numbers are guesses, and on this site they
 * would be guesses about a list that is not shaped that way: a real response offers 2160p60,
 * 1440p60, 1080p60, 720p60, 480p, 360p, 240p and 144p, and the honest top row on a 4K
 * video is **1.37 GB**. A chooser that says "best" and then quietly stops at 1080p is
 * describing something other than what it will do.
 *
 * So every row is a real format with the real byte count, and every row says that it is two
 * files joined together - because on YouTube that is now true of all of them, and a user
 * told the file is 257 MB and then spends 257 MB and ends up with something unplayable has
 * been told a lie by omission.
 */
@Composable
fun YouTubeQualityDialog(
    url: String,
    /**
     * Asks the engine what the link offers, and calls back with the answer.
     *
     * A callback rather than the engine itself, because the engine lives in the controller
     * and running it is a process launch that takes seconds - not something a composable
     * should be holding and starting from a `LaunchedEffect` on whatever dispatcher it
     * happens to be recomposed on.
     */
    loader: (String, (YtDlpEngine.FormatListing) -> Unit) -> Unit,
    onPick: (com.downloadhub.core.StreamChoice, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var listing by remember(url) { mutableStateOf<YtDlpEngine.FormatListing?>(null) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    var busy by remember(url) { mutableStateOf(true) }
    var audioOnly by remember(url) { mutableStateOf(false) }
    var chosen by remember(url) { mutableStateOf<StreamFormat?>(null) }
    // Bumped by Retry, so the lookup below runs again. The link is the same, which is
    // the point: a transient failure - rate-limiting, a stalled connection - is worth
    // one more attempt without making the user close the dialog and paste again.
    var attempt by remember(url) { mutableStateOf(0) }

    LaunchedEffect(url, attempt) {
        busy = true
        error = null
        listing = null
        loader(url) { answer ->
            if (answer.error != null) error = answer.error else listing = answer
            busy = false
        }
    }

    val found = listing
    val all = remember(found) { found?.videoFormats.orEmpty() }
    val audioOptions = remember(found) { found?.audioFormats.orEmpty() }

    // A real Dialog rather than a panel drawn inside the main window, and with the
    // platform default width switched off so the list gets the width it needs instead of
    // the 560 dp cap Material puts on dialog content.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.width(560.dp).background(AppTheme.Palette.surface, RoundedCornerShape(8.dp))) {
        Column(Modifier.padding(18.dp)) {
            Text(
                found?.title?.takeIf { it.isNotBlank() } ?: "YouTube download",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = AppTheme.Palette.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                when {
                    busy -> "Reading what this video offers..."
                    error != null -> ""
                    found != null -> DisplayFormat.bytes(found.bestTotalBytes) +
                        " for the largest option. Every quality is two files joined together."
                    else -> ""
                },
                fontSize = 11.sp,
                color = AppTheme.Palette.muted,
                maxLines = 2
            )
            Spacer(Modifier.height(12.dp))

            if (error != null) {
                Text(error!!, fontSize = 12.sp, color = AppTheme.Palette.error)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { attempt++ }) { Text("Retry") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                return@Column
            }

            if (busy || found == null) {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AppTheme.Palette.accent, modifier = Modifier.size(26.dp))
                }
                return@Column
            }

            // The two tabs, as a toggle rather than a dropdown: it is two words, and a
            // phone-width dialog has no room to hide them behind a tap.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TabLabel("Video", !audioOnly) { audioOnly = false }
                TabLabel("Audio only", audioOnly) { audioOnly = true }
            }
            Spacer(Modifier.height(8.dp))

            val rows = if (audioOnly) audioOptions else all
            if (rows.isEmpty()) {
                Text(
                    if (audioOnly) "This video offers no audio-only stream." else "No streams.",
                    fontSize = 12.sp,
                    color = AppTheme.Palette.muted
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(rows, key = { it.formatId }) { format ->
                        val choice = if (audioOnly) {
                            com.downloadhub.core.StreamChoice(format, null)
                        } else {
                            chooseStream(
                                (all + audioOptions).distinctBy { it.formatId },
                                format.height ?: 0,
                                audioOptions
                            )
                        }
                        val total = choice?.totalBytes ?: format.sizeBytes ?: 0L
                        val selected = chosen?.formatId == format.formatId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    if (selected) AppTheme.Palette.accentContainer else AppTheme.Palette.surface,
                                    RoundedCornerShape(4.dp)
                                )
                                .clickable { chosen = format }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    format.fullLabel,
                                    fontSize = 13.sp,
                                    color = if (selected) AppTheme.Palette.accent else AppTheme.Palette.onSurface
                                )
                                Text(
                                    buildString {
                                        append(format.ext)
                                        if (format.fps != null) append("  ·  ").append(format.fps).append(" fps")
                                        format.videoCodec?.let { append("  ·  ").append(it.substringBefore('.')) }
                                        if (format.needsMerge) append("  ·  needs joining")
                                    },
                                    fontSize = 10.sp,
                                    color = AppTheme.Palette.faint
                                )
                            }
                            // The real number, or a dash where the site did not say.
                            Text(
                                if (total > 0L) DisplayFormat.bytes(total) else "size unknown",
                                fontSize = 11.sp,
                                color = AppTheme.Palette.muted
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = AppTheme.Palette.outlineVariant)
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                val format = chosen
                Button(
                    onClick = {
                        val choice = if (audioOnly || format == null) {
                            com.downloadhub.core.StreamChoice(
                                format ?: audioOptions.firstOrNull()
                                    ?: return@Button,
                                null
                            )
                        } else {
                            chooseStream(
                                (all + audioOptions).distinctBy { it.formatId },
                                format.height ?: 0,
                                audioOptions
                            ) ?: return@Button
                        }
                        onPick(choice, audioOnly)
                    },
                    enabled = format != null
                ) { Text("Download") }
            }
        }
        }
    }
}

@Composable
private fun TabLabel(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(
                if (selected) AppTheme.Palette.accentContainer else AppTheme.Palette.surface,
                RoundedCornerShape(4.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) AppTheme.Palette.accent else AppTheme.Palette.muted
        )
    }
}
