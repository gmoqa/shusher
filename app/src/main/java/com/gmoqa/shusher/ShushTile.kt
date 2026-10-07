package com.gmoqa.shusher

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class ShushTile : TileService() {

    override fun onStartListening() {
        val tile = qsTile ?: return
        tile.state = if (ShushService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        if (ShushService.running) {
            stopService(Intent(this, ShushService::class.java))
            return
        }
        // Android 11+ solo da acceso al micrófono a un servicio arrancado con la app visible,
        // así que el tile abre MainActivity, que arranca el servicio y se cierra.
        // ponytail: hay un parpadeo breve de la activity; un tema translúcido lo oculta si molesta.
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_START)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            @SuppressLint("StartActivityAndCollapseDeprecated") // solo corre en < 34
            startActivityAndCollapse(intent)
        }
    }
}
