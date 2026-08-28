package com.kgr.key2toolbox.settings

import android.content.Context
import android.net.Uri
import com.kgr.key2toolbox.R
import com.kgr.key2toolbox.modules.AdBlockController
import com.kgr.key2toolbox.modules.AutoFocusController
import com.kgr.key2toolbox.modules.BatteryUsageController
import com.kgr.key2toolbox.modules.BtIdleController
import com.kgr.key2toolbox.modules.ExtraDimController
import com.kgr.key2toolbox.modules.LocationIdleController
import com.kgr.key2toolbox.modules.TelemetryController
import com.kgr.key2toolbox.modules.TickerController
import com.kgr.key2toolbox.modules.ZramController
import com.kgr.key2toolbox.service.Key2AccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports/imports K2TB's SharedPreferences-backed modules ("key2tweaks",
 * "led_notify", "ticker_notifications") plus root-persisted script state
 * (ZRAM, BtIdle, LocationIdle, Extra Dim's night schedule, Global Telemetry
 * Block), to/from a single JSON document via Storage Access Framework Uris.
 *
 * Supports SELECTIVE backup/restore per [BackupModule] - the caller passes
 * which modules to include. "key2tweaks" is a single SharedPreferences file
 * shared by many modules (PinKeyboard, NavLock, ImeBlock, Calculator,
 * ImeSuggestions, ChatComposer, InCallShortcuts, AutoFocus, BatteryUsage),
 * so individual KEYS within it are filtered by [KEY2TWEAKS_MODULE_MAP]
 * rather than the whole file being an all-or-nothing unit.
 *
 * JSON shape - selection only affects which keys/sections get written or
 * applied, not the document structure:
 * {
 *   "app_version": "5.0",
 *   "exported_at": "2026-08-27T22:10:00Z",
 *   "prefs": {
 *     "key2tweaks": { "someKey": {"type": "boolean", "value": true}, ... },
 *     "led_notify": { "otherKey": {"type": "int", "value": 42}, ... },
 *     "ticker_notifications": { "enabled": {"type": "boolean", "value": true}, ... }
 *   },
 *   "zram": { "size_mb": 3072, "algorithm": "zstd", "swappiness": 60 },
 *   "adblock": {
 *     "enabled": true,
 *     "sources": ["https://..."],
 *     "user_added": ["127.0.0.1 ads.example.com"],
 *     "wildcard_added": ["*.doubleclick.net"],
 *     "user_removed": ["reddit.com"],
 *     "whitelist": ["*.reddit.com"]
 *   },
 *   "bt_idle": { "timeout_min": 15 },
 *   "location_idle": { "timeout_min": 15 },
 *   "extra_dim_schedule": { "start_minutes": 1320, "end_minutes": 420 },
 *   "telemetry": { "enabled": true }
 * }
 *
 * K2ProdFix and Play Store Tagger are intentionally NOT supported here
 * (K2ProdFix: excluded by design; Play Store Tagger: separate app sandbox).
 * TickerColorResolver/TickerController/TickerFilter are logic helpers with
 * no independent state of their own - covered by ticker_notifications.
 * Extra Dim's live on/off + dim level (Settings.Secure writes, applied
 * immediately) are intentionally out of scope, same as ZRAM's live state -
 * only what's persisted across reboot is backed up.
 */
object SettingsBackup {

    /** Modules the backup/restore UI lets the user select individually. */
    enum class BackupModule(@androidx.annotation.StringRes val labelRes: Int) {
        PIN_KEYBOARD(R.string.settings_backup_module_pin_keyboard),
        NAV_LOCK(R.string.settings_backup_module_nav_lock),
        IME_BLOCK(R.string.settings_backup_module_ime_block),
        LED_NOTIFY(R.string.settings_backup_module_led_notify),
        ZRAM(R.string.settings_backup_module_zram),
        AD_BLOCK(R.string.settings_backup_module_adblock),
        CALCULATOR(R.string.title_calculator),
        IME_SUGGESTIONS(R.string.title_ime_suggestions),
        CHAT_COMPOSER(R.string.title_chat_composer),
        IN_CALL_SHORTCUTS(R.string.title_in_call_shortcuts),
        AUTO_FOCUS(R.string.title_auto_focus),
        BATTERY_USAGE(R.string.title_battery_usage),
        BT_IDLE(R.string.title_bt_idle),
        LOCATION_IDLE(R.string.title_location_idle),
        EXTRA_DIM(R.string.title_extra_dim),
        TELEMETRY(R.string.title_telemetry),
        TICKER_NOTIFICATIONS(R.string.title_ticker_notifications)
    }

    /** Which BackupModule owns each key in the shared "key2tweaks" prefs file. */
    private val KEY2TWEAKS_MODULE_MAP: Map<String, BackupModule> = mapOf(
        Key2AccessibilityService.KEY_NAV_LOCK to BackupModule.NAV_LOCK,
        Key2AccessibilityService.KEY_NAV_GESTURE to BackupModule.NAV_LOCK,
        Key2AccessibilityService.KEY_NAV_ALWAYS_OFF to BackupModule.NAV_LOCK,
        Key2AccessibilityService.KEY_PIN_INPUT to BackupModule.PIN_KEYBOARD,
        Key2AccessibilityService.KEY_IME_BLOCK to BackupModule.IME_BLOCK,
        Key2AccessibilityService.KEY_IME_BLOCK_APPS to BackupModule.IME_BLOCK,
        Key2AccessibilityService.KEY_IME_SAVED to BackupModule.IME_BLOCK,
        Key2AccessibilityService.KEY_IME_SUGGESTIONS to BackupModule.IME_SUGGESTIONS,
        Key2AccessibilityService.KEY_CHAT_COMPOSER to BackupModule.CHAT_COMPOSER,
        Key2AccessibilityService.KEY_CALCULATOR to BackupModule.CALCULATOR,
        Key2AccessibilityService.KEY_IN_CALL_SHORTCUTS to BackupModule.IN_CALL_SHORTCUTS,
        AutoFocusController.KEY_AUTO_FOCUS to BackupModule.AUTO_FOCUS,
        AutoFocusController.KEY_AUTO_FOCUS_APPS to BackupModule.AUTO_FOCUS,
        BatteryUsageController.KEY_RESET_THRESHOLD to BackupModule.BATTERY_USAGE
    )

    private const val KEY2TWEAKS_PREFS = "key2tweaks"
    private const val LED_NOTIFY_PREFS = "led_notify"
    private const val TICKER_PREFS = "ticker_notifications"

    // Mirrors TickerSettings' own (private) KEY_ENABLED - restoring this key needs
    // TickerController.setEnabled() rather than a bare pref write, since that's also
    // what grants/revokes the root-level notification listener access; see below.
    private const val TICKER_KEY_ENABLED = "enabled"

    // AdBlockController.PERSISTED_DATA_FILES entries, mapped to their JSON key names.
    private val ADBLOCK_FILE_TO_JSON_KEY = mapOf(
        "sources.txt" to "sources",
        "user_added.txt" to "user_added",
        "wildcard_added.txt" to "wildcard_added",
        "user_removed.txt" to "user_removed",
        "whitelist.txt" to "whitelist"
    )

    private fun prefValueToJson(value: Any?): JSONObject? {
        val entry = JSONObject()
        when (value) {
            is Boolean -> entry.put("type", "boolean").put("value", value)
            is Int -> entry.put("type", "int").put("value", value)
            is Long -> entry.put("type", "long").put("value", value)
            is Float -> entry.put("type", "float").put("value", value.toDouble())
            is String -> entry.put("type", "string").put("value", value)
            is Set<*> -> entry.put("type", "stringset").put("value", JSONArray(value.toList()))
            else -> return null // unknown type, skip rather than corrupt the backup
        }
        return entry
    }

    /**
     * @param modules which modules to include. Defaults to all supported
     * modules (unchanged behavior from before selective backup existed).
     */
    fun exportToJson(
        context: Context,
        appVersion: String,
        modules: Set<BackupModule> = BackupModule.entries.toSet()
    ): JSONObject {
        val prefsJson = JSONObject()

        // key2tweaks: filter individual KEYS by which module owns them.
        val key2tweaks = context.getSharedPreferences(KEY2TWEAKS_PREFS, Context.MODE_PRIVATE)
        val key2tweaksJson = JSONObject()
        for ((key, value) in key2tweaks.all) {
            val owner = KEY2TWEAKS_MODULE_MAP[key] ?: continue // unrecognized key, skip
            if (owner !in modules) continue
            val entry = prefValueToJson(value) ?: continue
            key2tweaksJson.put(key, entry)
        }
        if (key2tweaksJson.length() > 0) {
            prefsJson.put(KEY2TWEAKS_PREFS, key2tweaksJson)
        }

        // led_notify: whole file is one module, included entirely or not at all.
        if (BackupModule.LED_NOTIFY in modules) {
            val ledPrefs = context.getSharedPreferences(LED_NOTIFY_PREFS, Context.MODE_PRIVATE)
            val ledJson = JSONObject()
            for ((key, value) in ledPrefs.all) {
                val entry = prefValueToJson(value) ?: continue
                ledJson.put(key, entry)
            }
            prefsJson.put(LED_NOTIFY_PREFS, ledJson)
        }

        // ticker_notifications: whole file is one module, same shape as led_notify.
        if (BackupModule.TICKER_NOTIFICATIONS in modules) {
            val tickerPrefs = context.getSharedPreferences(TICKER_PREFS, Context.MODE_PRIVATE)
            val tickerJson = JSONObject()
            for ((key, value) in tickerPrefs.all) {
                val entry = prefValueToJson(value) ?: continue
                tickerJson.put(key, entry)
            }
            prefsJson.put(TICKER_PREFS, tickerJson)
        }

        val root = JSONObject()
        root.put("app_version", appVersion)
        root.put(
            "exported_at",
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
        )
        root.put("prefs", prefsJson)

        // ZRAM isn't a SharedPreferences module - only include it if selected AND
        // a script is actually persisted, and only if all three values parsed cleanly.
        if (BackupModule.ZRAM in modules && ZramController.isPersisted()) {
            val sizeMb = ZramController.persistedSize()?.mb
            val algorithm = ZramController.persistedAlgorithm()
            val swappiness = ZramController.persistedSwappiness()
            if (sizeMb != null && algorithm != null && swappiness != null) {
                val zramJson = JSONObject()
                zramJson.put("size_mb", sizeMb)
                zramJson.put("algorithm", algorithm)
                zramJson.put("swappiness", swappiness)
                root.put("zram", zramJson)
            }
        }

        // AdBlock: only include if selected AND the module has actually been installed
        // (nothing persisted to back up otherwise). Blob files are stored as line arrays
        // rather than raw strings so the JSON stays diffable/readable.
        if (BackupModule.AD_BLOCK in modules && AdBlockController.isInstalled()) {
            val adBlockJson = JSONObject()
            adBlockJson.put("enabled", AdBlockController.isEnabled())
            for ((fileName, jsonKey) in ADBLOCK_FILE_TO_JSON_KEY) {
                val lines = AdBlockController.readPersistedFile(fileName)
                    .split("\n")
                    .filter { it.isNotBlank() }
                adBlockJson.put(jsonKey, JSONArray(lines))
            }
            root.put("adblock", adBlockJson)
        }

        // BtIdle / LocationIdle: root-persisted watchdog scripts, a single timeout
        // value each - same "only if actually persisted" gating as ZRAM.
        if (BackupModule.BT_IDLE in modules && BtIdleController.isPersisted()) {
            BtIdleController.persistedTimeout()?.let { timeout ->
                root.put("bt_idle", JSONObject().put("timeout_min", timeout))
            }
        }
        if (BackupModule.LOCATION_IDLE in modules && LocationIdleController.isPersisted()) {
            LocationIdleController.persistedTimeout()?.let { timeout ->
                root.put("location_idle", JSONObject().put("timeout_min", timeout))
            }
        }

        // Extra Dim: only the night-schedule watchdog is backup-worthy - see class doc.
        if (BackupModule.EXTRA_DIM in modules && ExtraDimController.isScheduleEnabled()) {
            val schedJson = JSONObject()
            schedJson.put("start_minutes", ExtraDimController.persistedStartMinutes())
            schedJson.put("end_minutes", ExtraDimController.persistedEndMinutes())
            root.put("extra_dim_schedule", schedJson)
        }

        // Global Telemetry Block: on/off only, no tunables.
        if (BackupModule.TELEMETRY in modules && TelemetryController.isPersisted()) {
            root.put("telemetry", JSONObject().put("enabled", true))
        }

        return root
    }

    /**
     * Applies a previously exported JSON document back into SharedPreferences,
     * restricted to [modules]. Unknown pref file names / keys in the JSON
     * (e.g. from a newer app version) are skipped rather than crashing.
     * Existing keys not present in the backup (or not selected) are left
     * untouched - this is a merge/restore, not a wipe-then-restore.
     */
    fun importFromJson(
        context: Context,
        root: JSONObject,
        modules: Set<BackupModule> = BackupModule.entries.toSet()
    ): ImportResult {
        val prefsJson = root.optJSONObject("prefs")
            ?: return ImportResult.Failure("JSON has no \"prefs\" section — not a K2TB backup file?")

        var restoredKeys = 0
        val scriptModulesRestored = mutableSetOf<BackupModule>()

        // key2tweaks: filter individual KEYS by which module owns them.
        val key2tweaksJson = prefsJson.optJSONObject(KEY2TWEAKS_PREFS)
        if (key2tweaksJson != null) {
            val prefs = context.getSharedPreferences(KEY2TWEAKS_PREFS, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            val keys = key2tweaksJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val owner = KEY2TWEAKS_MODULE_MAP[key] ?: continue
                if (owner !in modules) continue
                val entry = key2tweaksJson.getJSONObject(key)
                if (applyPrefEntry(editor, key, entry)) restoredKeys++
            }
            editor.apply()
        }

        // led_notify: whole file is one module.
        if (BackupModule.LED_NOTIFY in modules) {
            val ledJson = prefsJson.optJSONObject(LED_NOTIFY_PREFS)
            if (ledJson != null) {
                val prefs = context.getSharedPreferences(LED_NOTIFY_PREFS, Context.MODE_PRIVATE)
                val editor = prefs.edit()
                val keys = ledJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val entry = ledJson.getJSONObject(key)
                    if (applyPrefEntry(editor, key, entry)) restoredKeys++
                }
                editor.apply()
            }
        }

        // ticker_notifications: whole file is one module. The "enabled" key is applied
        // via TickerController.setEnabled() instead of a bare pref write, since that
        // call also grants/revokes the root-level notification listener access - a
        // plain SharedPreferences restore would leave the toggle on but the listener
        // never actually bound.
        if (BackupModule.TICKER_NOTIFICATIONS in modules) {
            val tickerJson = prefsJson.optJSONObject(TICKER_PREFS)
            if (tickerJson != null) {
                val prefs = context.getSharedPreferences(TICKER_PREFS, Context.MODE_PRIVATE)
                val editor = prefs.edit()
                val keys = tickerJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key == TICKER_KEY_ENABLED) continue
                    val entry = tickerJson.getJSONObject(key)
                    if (applyPrefEntry(editor, key, entry)) restoredKeys++
                }
                editor.apply()

                val enabledEntry = tickerJson.optJSONObject(TICKER_KEY_ENABLED)
                if (enabledEntry != null && enabledEntry.optString("type") == "boolean") {
                    try {
                        TickerController.setEnabled(context, enabledEntry.getBoolean("value"))
                        restoredKeys++
                    } catch (e: Exception) {
                        // Prefs already restored - listener grant failing shouldn't fail
                        // the whole import.
                    }
                }
            }
        }

        // ZRAM: only touch it if selected AND the backup actually has a "zram"
        // section - absence means "leave whatever's currently configured alone".
        var zramRestored = false
        if (BackupModule.ZRAM in modules) {
            val zramJson = root.optJSONObject("zram")
            if (zramJson != null) {
                val sizeMb = zramJson.optInt("size_mb", -1)
                val algorithm = zramJson.optString("algorithm", "")
                val swappiness = zramJson.optInt("swappiness", -1)
                val size = ZramController.Size.entries.firstOrNull { it.mb == sizeMb }

                if (size != null && size != ZramController.Size.OFF && algorithm.isNotBlank() && swappiness >= 0) {
                    try {
                        // applyLive = false: this restores after a clean flash, so just
                        // persist the boot script - let it take effect on next reboot
                        // rather than swapping ZRAM off/on live mid-restore.
                        ZramController.setSize(context, size, algorithm, swappiness, applyLive = false)
                        zramRestored = true
                    } catch (e: Exception) {
                        // Prefs already restored successfully by this point - don't fail
                        // the whole import over ZRAM, just report it wasn't restored.
                        zramRestored = false
                    }
                }
            }
        }

        // AdBlock: only touch it if selected AND the backup has an "adblock" section.
        // Installs the module first if it isn't present on this device yet (a fresh
        // flash won't have it) - in that case the caller should tell the user a
        // reboot is needed, same as a manual install from the AdBlock screen.
        var adBlockRestored = false
        var adBlockNeedsReboot = false
        if (BackupModule.AD_BLOCK in modules) {
            val adBlockJson = root.optJSONObject("adblock")
            if (adBlockJson != null) {
                try {
                    if (!AdBlockController.isInstalled()) {
                        val install = AdBlockController.install(context)
                        if (!install.success) throw RuntimeException(install.outString)
                        adBlockNeedsReboot = true
                    }
                    for ((fileName, jsonKey) in ADBLOCK_FILE_TO_JSON_KEY) {
                        val arr = adBlockJson.optJSONArray(jsonKey) ?: continue
                        val content = (0 until arr.length()).joinToString("\n") { arr.getString(it) }
                        val write = AdBlockController.writePersistedFileRaw(context, fileName, content)
                        if (!write.success) throw RuntimeException(write.outString)
                    }
                    val recompile = AdBlockController.recompile()
                    if (!recompile.success) throw RuntimeException(recompile.outString)
                    AdBlockController.setEnabled(adBlockJson.optBoolean("enabled", true))
                    adBlockRestored = true
                } catch (e: Exception) {
                    adBlockRestored = false
                }
            }
        }

        // BtIdle / LocationIdle: setEnabled() installs the script AND launches the
        // watchdog live (no applyLive toggle to defer with, unlike ZRAM) - restoring
        // these takes effect immediately, not on next reboot.
        if (BackupModule.BT_IDLE in modules) {
            root.optJSONObject("bt_idle")?.let { j ->
                val timeout = j.optInt("timeout_min", -1)
                if (timeout > 0) {
                    try {
                        BtIdleController.setEnabled(context, true, timeout)
                        scriptModulesRestored += BackupModule.BT_IDLE
                    } catch (e: Exception) {
                        // Leave it unset - don't fail the whole import.
                    }
                }
            }
        }
        if (BackupModule.LOCATION_IDLE in modules) {
            root.optJSONObject("location_idle")?.let { j ->
                val timeout = j.optInt("timeout_min", -1)
                if (timeout > 0) {
                    try {
                        LocationIdleController.setEnabled(context, true, timeout)
                        scriptModulesRestored += BackupModule.LOCATION_IDLE
                    } catch (e: Exception) {
                        // Leave it unset - don't fail the whole import.
                    }
                }
            }
        }

        // Extra Dim: only the night schedule is restored - see class doc.
        if (BackupModule.EXTRA_DIM in modules) {
            root.optJSONObject("extra_dim_schedule")?.let { j ->
                val startMin = j.optInt("start_minutes", -1)
                val endMin = j.optInt("end_minutes", -1)
                if (startMin in 0..1439 && endMin in 0..1439) {
                    try {
                        ExtraDimController.setScheduleEnabled(context, true, startMin, endMin)
                        scriptModulesRestored += BackupModule.EXTRA_DIM
                    } catch (e: Exception) {
                        // Leave it unset - don't fail the whole import.
                    }
                }
            }
        }

        // Global Telemetry Block: on/off only.
        if (BackupModule.TELEMETRY in modules) {
            root.optJSONObject("telemetry")?.let { j ->
                if (j.optBoolean("enabled", false)) {
                    try {
                        TelemetryController.setEnabled(context, true)
                        scriptModulesRestored += BackupModule.TELEMETRY
                    } catch (e: Exception) {
                        // Leave it unset - don't fail the whole import.
                    }
                }
            }
        }

        return ImportResult.Success(
            restoredKeys, zramRestored, adBlockRestored, adBlockNeedsReboot, scriptModulesRestored
        )
    }

    /** Applies one {"type", "value"} entry to the editor. Returns true if applied. */
    private fun applyPrefEntry(editor: android.content.SharedPreferences.Editor, key: String, entry: JSONObject): Boolean {
        when (entry.optString("type")) {
            "boolean" -> editor.putBoolean(key, entry.getBoolean("value"))
            "int" -> editor.putInt(key, entry.getInt("value"))
            "long" -> editor.putLong(key, entry.getLong("value"))
            "float" -> editor.putFloat(key, entry.getDouble("value").toFloat())
            "string" -> editor.putString(key, entry.getString("value"))
            "stringset" -> {
                val arr = entry.getJSONArray("value")
                val set = (0 until arr.length()).map { arr.getString(it) }.toSet()
                editor.putStringSet(key, set)
            }
            else -> return false // unrecognized type tag
        }
        return true
    }

    fun writeToUri(context: Context, uri: Uri, json: JSONObject) {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(json.toString(2).toByteArray())
        } ?: error("Could not open output stream for $uri")
    }

    fun readFromUri(context: Context, uri: Uri): JSONObject {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input)).readText()
        } ?: error("Could not open input stream for $uri")
        return JSONObject(text)
    }

    sealed class ImportResult {
        data class Success(
            val restoredKeys: Int,
            val zramRestored: Boolean = false,
            val adBlockRestored: Boolean = false,
            val adBlockNeedsReboot: Boolean = false,
            val scriptModulesRestored: Set<BackupModule> = emptySet()
        ) : ImportResult()
        data class Failure(val message: String) : ImportResult()
    }
}
