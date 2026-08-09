package com.kgr.key2toolbox.modules

import android.content.Context
import android.content.pm.PackageManager
import com.kgr.key2toolbox.core.RootShell
import com.kgr.key2toolbox.core.ShellResult
import org.json.JSONObject
import java.io.File

/**
 * Unified read/write for the two hide-lists K2TB can safely own end-to-end:
 *
 *  - Magisk's own DenyList, managed via the `magisk --denylist` CLI
 *    (stable, documented interface - see `magisk --denylist ls/add/rm/status`).
 *  - Zygisk-Hide's own config.json (this project's fork of Zygisk Assistant),
 *    a flat `{ "pkg": true }` map read fresh by its companion on every app
 *    launch - see config.hpp. Only truthy entries mean "hide"; toggling an
 *    app off REMOVES its key entirely rather than setting it to false.
 *
 * HMA-OSS is deliberately NOT integrated here. Its live config lives at a
 * randomized `/data/misc/hide_my_applist_<suffix>/config.json` path with a
 * real nested schema (hook items, templates) and is designed to be written
 * through a Binder IPC interface rather than as a stable on-disk format.
 * Direct read/write would be fragile against HMA-OSS updates, so instead
 * this controller only knows how to jump to its manager app - see
 * [hmaOssComponent].
 *
 * Everything here is gated behind [isEnabled] at the UI layer: this module
 * only understands Magisk + Zygisk-Hide, not every root/hide combination
 * (FolkPatch, APatch, HMA's own denylist, etc.), so it defaults OFF and
 * never touches state unless the person explicitly opts in via the master
 * toggle at the top of the screen.
 */
object DenylistController {

    private const val ZYGISK_HIDE_CONFIG_PATH = "/data/adb/modules/zygisk-hide/config.json"
    private const val ZYGISK_HIDE_MODULE_DIR = "/data/adb/modules/zygisk-hide"
    private const val HMA_OSS_MODULE_DIR = "/data/adb/modules/hma_oss_zygisk"
    private const val HMA_OSS_PACKAGE = "org.frknkrc44.hma_oss"
    private const val HMA_OSS_MAIN_ACTIVITY = "org.frknkrc44.hma_oss.ui.activity.MainActivity"

    data class DenylistEntry(
        val magiskDenied: Boolean,
        val zygiskHideHidden: Boolean
    )

    // ------------------------------------------------------------ Master switch

    private const val PREFS_NAME = "denylist_manager"
    private const val KEY_ENABLED = "enabled"

    /**
     * Whether K2TB should actively manage Magisk/Zygisk-Hide from this screen.
     * Defaults OFF: this module intentionally only understands Magisk +
     * Zygisk-Hide, not FolkPatch/APatch/HMA/other denylist mechanisms, so
     * anyone using those independently shouldn't have K2TB touching state
     * without opting in first.
     */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    // --------------------------------------------------------- Availability

    fun isMagiskAvailable(): Boolean =
        RootShell.run("command -v magisk").success

    fun isZygiskHideInstalled(): Boolean =
        RootShell.run("[ -d '$ZYGISK_HIDE_MODULE_DIR' ]").success

    fun isHmaOssInstalled(): Boolean =
        RootShell.run("[ -d '$HMA_OSS_MODULE_DIR' ]").success

    // ------------------------------------------------------- Magisk DenyList

    /** True if DenyList enforcement is globally on ("Denylist is enforced"). */
    fun isMagiskDenylistEnforced(): Boolean =
        RootShell.run("magisk --denylist status").outString.contains("is enforced")

    /**
     * All (package, process) pairs currently on Magisk's DenyList. A package
     * with multiple isolated processes (e.g. WebView/service subprocesses)
     * appears once per process line - see `magisk --denylist ls` output
     * format, pipe-delimited: pkg|process.
     */
    fun magiskDenylistEntries(): List<Pair<String, String>> {
        val out = RootShell.run("magisk --denylist ls").outString
        return out.lineSequence()
            .mapNotNull { line ->
                val pipe = line.indexOf('|')
                if (pipe < 0) return@mapNotNull null
                val pkg = line.substring(0, pipe).trim()
                val proc = line.substring(pipe + 1).trim()
                if (pkg.isEmpty()) null else pkg to proc
            }
            .toList()
    }

    /** True if [packageName] has ANY process entry on Magisk's DenyList. */
    fun isOnMagiskDenylist(packageName: String, entries: List<Pair<String, String>>? = null): Boolean =
        (entries ?: magiskDenylistEntries()).any { it.first == packageName }

    /**
     * Every process name [packageName] declares in its manifest (activities,
     * services, providers, receivers), plus the app's default process -
     * i.e. every pkg|process pair Magisk could ever end up needing for this
     * app, discovered up front rather than waiting for each sub-process to
     * register itself. Read via PackageManager rather than shell/dumpsys
     * since it's the authoritative, structured source for this.
     */
    fun declaredProcesses(context: Context, packageName: String): Set<String> {
        val pm = context.packageManager
        val flags = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_PROVIDERS or
            PackageManager.GET_RECEIVERS

        val processes = mutableSetOf<String>()
        try {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(packageName, flags)
            info.applicationInfo?.processName?.let { processes.add(it) }
            info.activities?.forEach { it.processName?.let(processes::add) }
            info.services?.forEach { it.processName?.let(processes::add) }
            info.providers?.forEach { it.processName?.let(processes::add) }
            info.receivers?.forEach { it.processName?.let(processes::add) }
        } catch (e: PackageManager.NameNotFoundException) {
            processes.add(packageName) // fall back to just the base package
        }
        return processes
    }

    /**
     * Adds [packageName] AND every sub-process it declares to Magisk's
     * DenyList in one call - this is K2TB's default add behavior. Trimming
     * back to a subset of processes is left to Magisk's own DenyList UI,
     * per the person's preference to keep K2TB's own toggle simple (all or
     * nothing) and use Magisk directly for exceptions.
     */
    fun addToMagiskDenylist(context: Context, packageName: String): ShellResult {
        val processes = declaredProcesses(context, packageName)
        var last = ShellResult(true, emptyList())
        for (proc in processes) {
            last = RootShell.run("magisk --denylist add '$packageName' '$proc'")
            if (!last.success) return last
        }
        return last
    }

    /** Removes ALL process entries for [packageName] from Magisk's DenyList. */
    fun removeFromMagiskDenylist(packageName: String, entries: List<Pair<String, String>>? = null): ShellResult {
        val procs = (entries ?: magiskDenylistEntries()).filter { it.first == packageName }
        if (procs.isEmpty()) return ShellResult(true, listOf("$packageName was not on the DenyList"))
        var last = ShellResult(true, emptyList())
        for ((pkg, proc) in procs) {
            last = RootShell.run("magisk --denylist rm '$pkg' '$proc'")
            if (!last.success) return last
        }
        return last
    }

    // ------------------------------------------------------------- Zygisk-Hide

    /** Reads config.json fresh. Returns an empty map if the file is missing/empty/malformed. */
    fun zygiskHideConfig(): Map<String, Boolean> {
        val raw = RootShell.run("cat '$ZYGISK_HIDE_CONFIG_PATH' 2>/dev/null").outString.trim()
        if (raw.isEmpty()) return emptyMap()
        return try {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { obj.optBoolean(it, false) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun isZygiskHideHidden(packageName: String, config: Map<String, Boolean>? = null): Boolean =
        (config ?: zygiskHideConfig())[packageName] == true

    /**
     * Sets [packageName]'s hidden state in Zygisk-Hide's config.json.
     * Read-modify-write against whatever's currently on disk (mirrors
     * CtrlKeyController's patchModule pattern) so concurrent WebUI edits
     * aren't clobbered by a stale in-memory copy. Turning OFF removes the
     * key entirely, matching what the WebUI itself does - see config.hpp.
     */
    fun setZygiskHideHidden(context: Context, packageName: String, hidden: Boolean): ShellResult {
        val current = zygiskHideConfig().toMutableMap()
        if (hidden) {
            current[packageName] = true
        } else {
            current.remove(packageName)
        }

        val json = JSONObject()
        current.forEach { (pkg, value) -> json.put(pkg, value) }

        val tmp = File(context.filesDir, "zygisk_hide_config_tmp.json")
        tmp.writeText(json.toString())
        val write = RootShell.run("install -m 644 '${tmp.absolutePath}' '$ZYGISK_HIDE_CONFIG_PATH'")
        tmp.delete()
        return write
    }

    // --------------------------------------------------------------- HMA-OSS

    /** Component to launch via an explicit Intent from the UI layer (see DenylistScreen). */
    fun hmaOssComponent(): Pair<String, String> = HMA_OSS_PACKAGE to HMA_OSS_MAIN_ACTIVITY

    // ------------------------------------------------------------- Combined

    /**
     * Convenience for the UI: current state across both owned backends for
     * one package, given pre-fetched snapshots (avoids re-shelling out per
     * row when rendering a full app list).
     */
    fun entryFor(
        packageName: String,
        magiskEntries: List<Pair<String, String>>,
        zygiskHideConfig: Map<String, Boolean>
    ): DenylistEntry = DenylistEntry(
        magiskDenied = isOnMagiskDenylist(packageName, magiskEntries),
        zygiskHideHidden = isZygiskHideHidden(packageName, zygiskHideConfig)
    )
}
