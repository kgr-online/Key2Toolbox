package com.kgr.key2toolbox.modules

import android.content.Context
import com.kgr.key2toolbox.core.AssetInstaller
import com.kgr.key2toolbox.core.RootShell
import com.kgr.key2toolbox.core.ShellResult

/**
 * Manages global Firebase Crashlytics telemetry block.
 * Installs /data/adb/service.d/block_telemetry.sh.
 */
object TelemetryController {

    private const val SCRIPT_NAME = "block_telemetry.sh"
    private const val TARGET = "/data/adb/service.d/$SCRIPT_NAME"
    private const val TEMPLATE_ASSET = "block_telemetry_template.sh"

    fun isPersisted(): Boolean = AssetInstaller.fileExists(TARGET)

    fun setEnabled(context: Context, enabled: Boolean): ShellResult {
        val result = if (enabled) {
            AssetInstaller.installFromAsset(context, TEMPLATE_ASSET, TARGET)
        } else {
            AssetInstaller.removeFile(TARGET)
        }
        return result
    }

    /** Runs the telemetry disable loop live. */
    fun applyLive(): ShellResult {
        val cmd = """
            nsenter --mount=/proc/1/ns/mnt -- sh -c '
            find /data/data/ -name "com.google.firebase.crashlytics.xml" 2>/dev/null | while read f; do
                if [ ! -f "${'$'}f" ]; then continue; fi
                if grep -q "firebase_crashlytics_collection_enabled" "${'$'}f"; then
                    sed -i "s/firebase_crashlytics_collection_enabled\" value=\"true\"/firebase_crashlytics_collection_enabled\" value=\"false\"/g" "${'$'}f"
                else
                    sed -i "s#</map>#    <boolean name=\"firebase_crashlytics_collection_enabled\" value=\"false\" />\n</map>#g" "${'$'}f"
                fi
            done
            '
        """.trimIndent()
        return RootShell.run(cmd)
    }

    /** Returns count of apps with Crashlytics XML files. */
    fun totalAffectedApps(): Int {
        val out = RootShell.run("nsenter --mount=/proc/1/ns/mnt -- find /data/data/ -name \"com.google.firebase.crashlytics.xml\" 2>/dev/null | wc -l").outString.trim()
        return out.toIntOrNull() ?: 0
    }

    /** Returns count of apps with Crashlytics set to false. */
    fun totalBlockedApps(): Int {
        val out = RootShell.run("nsenter --mount=/proc/1/ns/mnt -- sh -c 'find /data/data/ -name \"com.google.firebase.crashlytics.xml\" 2>/dev/null | xargs grep -l \"firebase_crashlytics_collection_enabled\\\" value=\\\"false\\\"\" 2>/dev/null | wc -l'").outString.trim()
        return out.toIntOrNull() ?: 0
    }
}
