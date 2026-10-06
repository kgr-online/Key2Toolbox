package com.kgr.key2toolbox.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlin.concurrent.thread

/**
 * Quick Settings tile that opens Google TTS engine settings.
 * EngineSettings is not exported, so it is launched as root.
 * The host app must be granted root in Magisk.
 */
class TtsTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        thread {
            try {
                val cmd = "am start --user 0 -n $COMPONENT; cmd statusbar collapse"
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                p.waitFor()
            } catch (_: Exception) {
                // su unavailable or denied
            }
        }
    }

    companion object {
        private const val COMPONENT =
            "com.google.android.tts/com.google.android.apps.speech.tts.googletts.settings.EngineSettings"
    }
}
