package com.kgr.key2toolbox.modules

import android.content.Context
import com.kgr.key2toolbox.core.AssetInstaller
import com.kgr.key2toolbox.core.RootShell
import com.kgr.key2toolbox.core.ShellResult

/**
 * Galaxy/Wear watch power saver.
 *
 * A paired watch is registered in Play Services' wearable store
 * (connectionconfig.db) with connectionEnabled=1. While enabled but out of
 * range, GMS fires a Bluetooth RETRY_CONNECTION alarm every few minutes,
 * waking the CPU + Bluetooth HAL all day - a major screen-off drain. The
 * companion plugin being frozen does NOT stop this; the retries come from the
 * GMS node itself.
 *
 * This toggles connectionEnabled in that DB *without unpairing*: the BT bond
 * and the node survive, so flipping back to active reconnects with no
 * re-pair (no Samsung factory-reset dance). "Dormant" stops the retries while
 * leaving Bluetooth free for other devices (speakers, etc).
 *
 * Persistence: when set dormant, installs /data/adb/service.d/watch_dormant.sh
 * which re-applies connectionEnabled=0 at boot, since GMS can re-enable the
 * node on a cold boot. Setting active removes that script.
 */
object WatchController {

    private const val DB = "/data/data/com.google.android.gms/databases/connectionconfig.db"
    private const val SCRIPT_NAME = "watch_dormant.sh"
    private const val TARGET = "/data/adb/service.d/$SCRIPT_NAME"

    enum class State { ACTIVE, DORMANT, NONE, UNKNOWN }

    private fun sqlite(query: String): ShellResult =
        RootShell.run("sqlite3 '$DB' \"$query\"")

    /** Number of registered watch nodes. */
    fun nodeCount(): Int =
        sqlite("SELECT COUNT(*) FROM connectionConfigurations;").outString.trim().toIntOrNull() ?: 0

    /** Names of registered watches, for display. */
    fun nodeNames(): List<String> =
        sqlite("SELECT name FROM connectionConfigurations;").out
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun currentState(): State {
        if (nodeCount() == 0) return State.NONE
        val enabled = sqlite("SELECT MAX(connectionEnabled) FROM connectionConfigurations;")
            .outString.trim().toIntOrNull() ?: return State.UNKNOWN
        return if (enabled >= 1) State.ACTIVE else State.DORMANT
    }

    fun isPersistedDormant(): Boolean = AssetInstaller.fileExists(TARGET)

    /**
     * Sets all watch nodes dormant (enabled=0) or active (enabled=1), restarts
     * GMS so it reloads the store, and persists/removes the boot script.
     * Returns the result of the DB update + GMS restart.
     */
    fun setDormant(context: Context, dormant: Boolean): ShellResult {
        if (dormant) AssetInstaller.installFromAsset(context, SCRIPT_NAME, TARGET)
        else AssetInstaller.removeFile(TARGET)

        return RootShell.run(
            "sqlite3 '$DB' \"UPDATE connectionConfigurations SET connectionEnabled=" +
                "${if (dormant) 0 else 1};\"; am force-stop com.google.android.gms"
        )
    }
}
