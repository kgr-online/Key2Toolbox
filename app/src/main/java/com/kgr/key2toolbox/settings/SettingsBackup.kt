package com.kgr.key2toolbox.settings

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports/imports K2TB's two SharedPreferences files ("key2tweaks" and "led_notify")
 * to/from a single JSON document via Storage Access Framework Uris.
 *
 * JSON shape:
 * {
 *   "app_version": "4.5-beta3",
 *   "exported_at": "2026-07-15T22:10:00Z",
 *   "prefs": {
 *     "key2tweaks": { "someKey": {"type": "boolean", "value": true}, ... },
 *     "led_notify": { "otherKey": {"type": "int", "value": 42}, ... }
 *   }
 * }
 *
 * Each value is tagged with its type so booleans/ints/longs/floats/string-sets
 * round-trip correctly — JSON numbers alone can't distinguish Int from Float,
 * and JSON has no native Set<String> type.
 */
object SettingsBackup {

    // Add any future pref files here — export/import will pick them up automatically.
    private val PREF_FILES = listOf(
        "key2tweaks", // Key2AccessibilityService.PREFS (ImeBlock, NavLock, PinKeyboard)
        "led_notify"  // LedNotifyListenerService.PREFS
    )

    fun exportToJson(context: Context, appVersion: String): JSONObject {
        val prefsJson = JSONObject()

        for (prefName in PREF_FILES) {
            val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
            val entryJson = JSONObject()

            for ((key, value) in prefs.all) {
                val entry = JSONObject()
                when (value) {
                    is Boolean -> {
                        entry.put("type", "boolean")
                        entry.put("value", value)
                    }
                    is Int -> {
                        entry.put("type", "int")
                        entry.put("value", value)
                    }
                    is Long -> {
                        entry.put("type", "long")
                        entry.put("value", value)
                    }
                    is Float -> {
                        entry.put("type", "float")
                        entry.put("value", value.toDouble())
                    }
                    is String -> {
                        entry.put("type", "string")
                        entry.put("value", value)
                    }
                    is Set<*> -> {
                        entry.put("type", "stringset")
                        entry.put("value", JSONArray(value.toList()))
                    }
                    else -> continue // unknown type, skip rather than corrupt the backup
                }
                entryJson.put(key, entry)
            }
            prefsJson.put(prefName, entryJson)
        }

        val root = JSONObject()
        root.put("app_version", appVersion)
        root.put(
            "exported_at",
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
        )
        root.put("prefs", prefsJson)
        return root
    }

    /**
     * Applies a previously exported JSON document back into SharedPreferences.
     * Unknown pref file names in the JSON (e.g. from a newer app version) are
     * skipped rather than crashing, so backups stay forward-compatible-ish.
     * Existing keys not present in the backup are left untouched (this is a
     * merge/restore, not a wipe-then-restore).
     */
    fun importFromJson(context: Context, root: JSONObject): ImportResult {
        val prefsJson = root.optJSONObject("prefs")
            ?: return ImportResult.Failure("JSON has no \"prefs\" section — not a K2TB backup file?")

        var restoredKeys = 0
        var skippedFiles = 0

        for (prefName in PREF_FILES) {
            val entryJson = prefsJson.optJSONObject(prefName)
            if (entryJson == null) {
                skippedFiles++
                continue
            }

            val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
            val editor = prefs.edit()

            val keys = entryJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val entry = entryJson.getJSONObject(key)
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
                    else -> continue // unrecognized type tag, skip that one key
                }
                restoredKeys++
            }
            editor.apply()
        }

        return ImportResult.Success(restoredKeys, skippedFiles)
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
        data class Success(val restoredKeys: Int, val skippedFiles: Int) : ImportResult()
        data class Failure(val message: String) : ImportResult()
    }
}
