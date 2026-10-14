package com.downloadhub.app.ui

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * A Quick Settings tile: copy a link in any app, pull down the shade, tap this, and the
 * app opens with that link ready to add. The clipboard can only be read by the focused
 * app, so the tile's job is just to bring the app forward and say "take it now".
 */
class ClipboardTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            label = "Download copied link"
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_FROM_CLIPBOARD, true)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            // Below Android 14 the PendingIntent overload does not exist; lint's warning is for 34+.
            @Suppress("DEPRECATION")
            @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
