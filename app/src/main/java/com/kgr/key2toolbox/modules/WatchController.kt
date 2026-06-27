package com.kgr.key2toolbox.modules

import android.content.Context
import com.kgr.key2toolbox.core.AssetInstaller
import com.kgr.key2toolbox.core.RootShell
import com.kgr.key2toolbox.core.ShellResult

/**
 * Wearable Device Power Saver.
 *
 * Wearable devices (smartwatches, trackers) paired through Google Play Services
 * are registered in the connectionconfig.db store. When a configured wearable is out of
 * range but enabled, GMS regularly fires alarms to attempt reconnections, causing
 * background battery drain.
 *
 * This controller toggles connectionEnabled dynamically in GMS's databases,
 * allowing the user to put any registered wearable into a "Dormant" state
 * when not in use.
 */
object WatchController {

    private const val DB = "/data/data/com.google.android.gms/databases/connectionconfig.db"
    private const val SCRIPT_NAME = "wearable_dormant.sh"
    private const val TARGET = "/data/adb/service.d/$SCRIPT_NAME"
    private const val TEMPLATE_ASSET = "wearable_dormant_template.sh"

    data class WearableDevice(
        val name: String,
        val macAddress: String,
        val enabled: Boolean
    )

    private fun sqlite(query: String): ShellResult =
        RootShell.run("sqlite3 '$DB' \"$query\"")

    fun isSupported(): Boolean = RootShell.run("[ -f '$DB' ]").success

    /** Reads the registered wearables from the GMS database. */
    fun getDevices(): List<WearableDevice> {
        val out = sqlite("SELECT name, pairedBtAddress, connectionEnabled FROM connectionConfigurations;").out
        return out.mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size >= 3) {
                WearableDevice(
                    name = parts[0].trim().ifEmpty { "Unnamed Wearable" },
                    macAddress = parts[1].trim(),
                    enabled = (parts[2].trim().toIntOrNull() ?: 1) >= 1
                )
            } else null
        }
    }

    /** Toggles the connection state of a specific wearable. */
    fun setDeviceDormant(context: Context, macAddress: String, dormant: Boolean): ShellResult {
        // 1. Update GMS database
        val dbValue = if (dormant) 0 else 1
        val dbResult = sqlite("UPDATE connectionConfigurations SET connectionEnabled=$dbValue WHERE pairedBtAddress='$macAddress';")
        
        // Restart GMS so it immediately picks up the state change
        RootShell.run("am force-stop com.google.android.gms")

        // 2. Update persistent boot script state
        val devices = getDevices()
        val dormantMacs = devices.filter { 
            if (it.macAddress == macAddress) dormant else !it.enabled 
        }.map { it.macAddress }

        if (dormantMacs.isEmpty()) {
            AssetInstaller.removeFile(TARGET)
        } else {
            // Build the SQL IN clause: e.g. "'MAC1', 'MAC2'"
            val macListStr = dormantMacs.joinToString(", ") { "'$it'" }
            AssetInstaller.installFromAsset(context, TEMPLATE_ASSET, TARGET) { raw ->
                raw.replace("__MAC_LIST__", macListStr)
            }
        }

        return dbResult
    }
}
