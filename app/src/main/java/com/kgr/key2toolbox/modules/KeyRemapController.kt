package com.kgr.key2toolbox.modules

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import com.kgr.key2toolbox.R
import com.kgr.key2toolbox.core.AssetInstaller
import com.kgr.key2toolbox.core.RootShell
import com.kgr.key2toolbox.core.ShellResult

/**
 * Remaps a chosen physical key to Ctrl via `stmpe.kl`, the same
 * remount-/vendor-rw-and-sed trick the previous single-key CtrlKeyController
 * used, generalized to let the user pick which key sources it: the Currency
 * key (scancode 5, normally keycode `4`) or the Convenience key (scancode 110,
 * normally `FUNCTION`) - these are the only two spare/remappable keys on this
 * keyboard's layout.
 *
 * Persistence writes /data/adb/service.d/key_remap.sh (generated from
 * assets/key_remap_template.sh for the selected source). Live apply runs the
 * same sequence directly (setenforce 0 -> remount /vendor rw -> sed -> setenforce 1),
 * then forces the running InputReader to actually pick up the change - see
 * [RELOAD_INPUT_DEVICE_CMD].
 */
object KeyRemapController {

    const val KEY_REMAP_ENABLED = "key_remap_enabled"
    const val KEY_REMAP_SOURCE = "key_remap_source"

    private const val SCRIPT_NAME = "key_remap.sh"
    private const val TARGET = "/data/adb/service.d/$SCRIPT_NAME"
    private const val TEMPLATE_ASSET = "key_remap_template.sh"
    private const val KEYLAYOUT = "/vendor/usr/keylayout/stmpe.kl"

    // Editing stmpe.kl alone doesn't take effect live - confirmed on-device that
    // InputReader keeps using the keylayout it parsed when the device was first
    // opened, and unlike q25's Q25_keyboard driver, unbinding/rebinding this
    // stmpe-keypad i2c driver does NOT tear down and recreate /dev/input/eventN
    // (its inode/timestamp survive unbind+bind unchanged), so that trick - which
    // works for q25 - is a no-op here. What *does* force a live reload: writing
    // "remove" then "add" to the input device's own uevent file, which is the
    // same remove/add cycle InputReader's device-hotplug watcher reacts to for a
    // real unplug/replug. Confirmed on-device: the physical key reports the
    // stale keycode before this and the remapped one immediately after.
    private const val RELOAD_INPUT_DEVICE_CMD =
        "for d in /sys/class/input/event*; do " +
            "if [ \"\$(cat \"\$d/device/name\" 2>/dev/null)\" = stmpe_keypad ]; then " +
            "echo remove > \"\$d/uevent\"; sleep 1; echo add > \"\$d/uevent\"; " +
            "fi; " +
            "done"

    enum class SourceKey(
        val scancode: Int,
        val originalKeycode: String,
        @StringRes val labelRes: Int,
        @StringRes val descriptionRes: Int
    ) {
        CURRENCY(5, "4", R.string.key_remap_currency_label, R.string.key_remap_currency_desc),
        CONVENIENCE(110, "FUNCTION", R.string.key_remap_convenience_label, R.string.key_remap_convenience_desc)
    }

    fun isEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_REMAP_ENABLED, false)

    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) =
        prefs.edit().putBoolean(KEY_REMAP_ENABLED, enabled).apply()

    fun getSourceKey(prefs: SharedPreferences): SourceKey {
        val raw = prefs.getString(KEY_REMAP_SOURCE, SourceKey.CONVENIENCE.name) ?: SourceKey.CONVENIENCE.name
        return try { SourceKey.valueOf(raw) } catch (_: Exception) { SourceKey.CONVENIENCE }
    }

    fun setSourceKey(prefs: SharedPreferences, key: SourceKey) =
        prefs.edit().putString(KEY_REMAP_SOURCE, key.name).apply()

    fun isPersisted(): Boolean = AssetInstaller.fileExists(TARGET)

    /** Reads the live keymap to see which source (if any) is currently remapped to Ctrl. */
    fun currentLiveSource(): SourceKey? {
        val out = RootShell.run("cat $KEYLAYOUT 2>/dev/null").outString
        return SourceKey.entries.firstOrNull { key ->
            Regex("""key\s+${key.scancode}\s+CTRL_LEFT""").containsMatchIn(out)
        }
    }

    /**
     * Applies the currently-selected settings both live and to the boot script:
     * first reverts every known source back to its original keycode (a no-op
     * for whichever one isn't currently remapped), so switching sources - or
     * disabling - never leaves a stale double-remap from a previous selection,
     * then applies the chosen source if enabled.
     */
    fun applySettings(context: Context, prefs: SharedPreferences): ShellResult {
        val enabled = isEnabled(prefs)
        val source = getSourceKey(prefs)

        // Clean up the old single-key ctrl_key.sh from the predecessor
        // CtrlKeyController, if it's still installed from before this feature
        // replaced it, so the two can't double-remap against each other.
        AssetInstaller.removeFile("/data/adb/service.d/ctrl_key.sh")

        val revertAll = SourceKey.entries.joinToString(" ; ") { key ->
            "nsenter -t 1 -m -- sed -E -i " +
                "'s/key ${key.scancode}[[:space:]]+CTRL_LEFT/key ${key.scancode} ${key.originalKeycode}/' $KEYLAYOUT"
        }

        return if (enabled) {
            val result = AssetInstaller.installFromAsset(context, TEMPLATE_ASSET, TARGET) { raw ->
                raw.replace("__SCANCODE__", source.scancode.toString())
                    .replace("__ORIGINAL_KEYCODE__", source.originalKeycode)
            }
            RootShell.run(
                "setenforce 0 && " +
                    "nsenter -t 1 -m -- mount -o rw,remount /vendor && " +
                    "$revertAll ; " +
                    "nsenter -t 1 -m -- sed -E -i " +
                    "'s/key ${source.scancode}[[:space:]]+${source.originalKeycode}/key ${source.scancode} CTRL_LEFT/' $KEYLAYOUT ; " +
                    "setenforce 1 ; " +
                    RELOAD_INPUT_DEVICE_CMD
            )
            result
        } else {
            RootShell.run(
                "setenforce 0 && " +
                    "nsenter -t 1 -m -- mount -o rw,remount /vendor && " +
                    "$revertAll ; " +
                    "setenforce 1 ; " +
                    RELOAD_INPUT_DEVICE_CMD
            )
            AssetInstaller.removeFile(TARGET)
        }
    }
}
