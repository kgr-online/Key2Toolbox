package com.kgr.key2toolbox.settings

import android.content.Context
import android.net.Uri
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
 * Exports/imports K2TB's SharedPreferences-backed modules ("key2tweaks" and
 * "led_notify") plus ZRAM's root-persisted script state, to/from a single
 * JSON document via Storage Access Framework Uris.
 *
 * Supports SELECTIVE backup/restore per [BackupModule] - the caller passes
 * which modules to include. "key2tweaks" is a single SharedPreferences file
 * shared by three modules (PinKeyboard, NavLock, ImeBlock), so individual
 * KEYS within it are filtered by [KEY2TWEAKS_MODULE_MAP] rather than the
 * whole file being an all-or-nothing unit.
 *
 * JSON shape unchanged from before - selection only affects which
 * keys/sections get written or applied, not the document structure:
 * {
 *   "app_version": "4.5-beta3",
 *   "exported_at": "2026-07-15T22:10:00Z",
 *   "prefs": {
 *     "key2tweaks": { "someKey": {"type": "boolean", "value": true}, ... },
 *     "led_notify": { "otherKey": {"type": "int", "value": 42}, ... }
 *   },
 *   "zram": { "size_mb": 3072, "algorithm": "zstd", "swappiness": 60 }
 * }
 *
 * K2ProdFix and Play Store Tagger are intentionally NOT supported here
 * (K2ProdFix: excluded by design; Play Store Tagger: separate app sandbox).
 */
object SettingsBackup {

    /** Modules the backup/restore UI lets the user select individually. */
    enum class BackupModule(val label: String) {
        PIN_KEYBOARD("PIN Keyboard"),
        NAV_LOCK("Nav Lock"),
        IME_BLOCK("ImeBlock"),
        LED_NOTIFY("LED Notify"),
        ZRAM("ZRAM")
    }

    /** Which BackupModule owns each key in the shared "key2tweaks" prefs file. */
    private val KEY2TWEAKS_MODULE_MAP: Map<String, BackupModule> = mapOf(
        Key2AccessibilityService.KEY_NAV_LOCK to BackupModule.NAV_LOCK,
        Key2AccessibilityService.KEY_NAV_GESTURE to BackupModule.NAV_LOCK,
        Key2AccessibilityService.KEY_NAV_ALWAYS_OFF to BackupModule.NAV_LOCK,
        Key2AccessibilityService.KEY_PIN_INPUT to BackupModule.PIN_KEYBOARD,
        Key2AccessibilityService.KEY_IME_BLOCK to BackupModule.IME_BLOCK,
        Key2AccessibilityService.KEY_IME_BLOCK_APPS to BackupModule.IME_BLOCK,
        Key2AccessibilityService.KEY_IME_SAVED to BackupModule.IME_BLOCK
    )

    private const val KEY2TWEAKS_PREFS = "key2tweaks"
    private const val LED_NOTIFY_PREFS = "led_notify"

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

        return ImportResult.Success(restoredKeys, zramRestored)
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
            val zramRestored: Boolean = false
        ) : ImportResult()
        data class Failure(val message: String) : ImportResult()
    }
}
