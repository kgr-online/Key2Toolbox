package com.kgr.key2toolbox.modules

import android.content.Context
import android.os.Build
import com.kgr.key2toolbox.core.AssetInstaller
import com.kgr.key2toolbox.core.RootShell
import com.kgr.key2toolbox.core.ShellResult
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Installs and drives the zygisk-detach Zygisk module (upstream: j-hc, forked
 * to kgr-online/zygisk-detach). The module hooks libbinder inside the Play
 * Store process and hides listed packages from
 * IPackageManager.getApplicationEnabledSetting, so the Store treats them as
 * absent and stops updating them.
 *
 * There is NO separate manager app: K2TB is the manager. The detach list is
 * read and written through the module's own non-interactive CLI
 * (`detach list | detachall | reset`), the same commands the upstream manager
 * app shells out to. The list itself (/data/adb/zygisk-detach/detach.bin) is a
 * packed binary format, so it is never edited directly. Two CLI behaviours
 * shape the API here:
 *
 *  - `detachall` REPLACES the whole list, so [applyDetached] always takes the
 *    complete desired set. An empty set maps to `reset` because `detachall`
 *    with no arguments is an error.
 *  - `detachall` truncates detach.bin BEFORE it serializes, and panics on a
 *    package name over 128 characters (the packed length must fit a byte).
 *    A bad name would therefore silently drop the rest of the list, so names
 *    are validated up front and the CLI is never called with an invalid one.
 *
 * Every change makes the CLI kill the Play Store, and the hook re-reads the
 * list when the Store next starts - so once the module has loaded, changes
 * apply immediately without a reboot. Only the module install/enable/disable
 * itself needs one - see [requiresReboot].
 *
 * The module zip is BUNDLED as an asset (zygisk_detach_module.zip) rather than
 * downloaded, so enabling works offline and is pinned to a known-good build.
 * To ship a rebuilt fork, drop a new zip in assets - [enable] re-deploys
 * whenever the bundled versionCode is higher than the installed one.
 *
 * Deliberate departures from the AdBlock/CtrlKey conventions:
 *
 *  - MODULE_ID is "zygisk-detach", NOT namespaced to k2tb_*. The module's own
 *    tooling and any other manager app (com.jhc.detach) hard-code
 *    /data/adb/modules/zygisk-detach/detach, so the id and path stay fixed.
 *
 *  - Installed by writing straight into /data/adb/modules, not through
 *    `magisk --install-module` / `apd`. Those stage under modules_update and
 *    only move into place at the next boot, so the CLI would not exist until
 *    then. This replicates the relevant steps of the zip's customize.sh
 *    instead: bin/<arch>/detach -> detach, exec bits, and the
 *    /data/adb/zygisk-detach data dir. Its KernelSU-only ksu_profile step is
 *    skipped.
 *
 *  - PERSIST is owned by the module, not K2TB: the detach list survives
 *    [install] and [disable] because neither touches it.
 */
object ZygiskDetachController {

    private const val MODULE_ID = "zygisk-detach"
    private const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"
    private const val DETACH_BIN = "$MODULE_DIR/detach"
    private const val MODULE_PROP = "$MODULE_DIR/module.prop"
    private const val DISABLE_FLAG = "$MODULE_DIR/disable"
    private const val PERSIST = "/data/adb/zygisk-detach"

    /**
     * Boot id at the time of the last change that only takes effect at boot
     * (install/upgrade/enable/disable). Lives in MODULE_DIR so a reinstall
     * resets it. See [requiresReboot].
     */
    private const val BOOT_MARK = "$MODULE_DIR/.k2tb_boot_id"
    private const val BOOT_ID = "/proc/sys/kernel/random/boot_id"

    private const val PLAY_STORE = "com.android.vending"

    private const val ASSET_MODULE_ZIP = "zygisk_detach_module.zip"

    // ------------------------------------------------------------- Status

    /** module.prop is written last by [install], so its presence means a complete deploy. */
    fun isInstalled(): Boolean = AssetInstaller.fileExists(MODULE_PROP)

    /** Installed and not flagged `disable` (the same flag Magisk's own module toggle uses). */
    fun isEnabled(): Boolean = isInstalled() && !AssetInstaller.fileExists(DISABLE_FLAG)

    /** e.g. "v1.23.2", or null if not installed. */
    fun installedVersion(): String? =
        if (isInstalled()) parseProp(AssetInstaller.readFile(MODULE_PROP), "version") else null

    private fun installedVersionCode(): Int =
        if (isInstalled()) parseProp(AssetInstaller.readFile(MODULE_PROP), "versionCode")?.toIntOrNull() ?: 0 else 0

    /**
     * True when the on-disk state differs from what the running Zygisk hook
     * reflects: something was installed/enabled/disabled since the current
     * boot. Detected by comparing the boot id recorded at change time with the
     * current one - deliberately not by inspecting mounts or process maps,
     * which vary by root implementation and don't tell "loaded" from "stale".
     */
    fun requiresReboot(): Boolean = isInstalled() && RootShell.run(
        "[ \"\$(cat '$BOOT_MARK' 2>/dev/null)\" = \"\$(cat $BOOT_ID)\" ] && echo yes || echo no"
    ).outString.trim() == "yes"

    /**
     * Whether Zygisk is available for the hook to load into: true = on,
     * false = definitely off, null = can't tell (non-Magisk root, no Zygisk
     * Next present). Counts either Magisk's built-in Zygisk setting or an
     * enabled Zygisk Next module.
     */
    fun zygiskEnabled(): Boolean? {
        val out = RootShell.run(
            "if [ -d /data/adb/modules/zygisksu ] && [ ! -e /data/adb/modules/zygisksu/disable ]; then echo on; " +
                "elif command -v magisk >/dev/null 2>&1; then " +
                "magisk --sqlite \"SELECT value FROM settings WHERE key='zygisk'\" 2>/dev/null " +
                "| grep -q 'value=1' && echo on || echo off; " +
                "else echo unknown; fi"
        ).outString.trim()
        return when (out) {
            "on" -> true
            "off" -> false
            else -> null
        }
    }

    /**
     * True when Magisk's DenyList is enforced AND lists the Play Store. Zygisk
     * modules are not loaded into enforced DenyList apps, so in that state the
     * hook silently never runs. (The module's own post-fs-data.sh clears this
     * entry at boot, but anything that re-adds it afterwards wins.)
     */
    fun playStoreDenylisted(): Boolean = RootShell.run(
        "magisk --denylist status >/dev/null 2>&1 " +
            "&& magisk --denylist ls 2>/dev/null | grep -q '^$PLAY_STORE' && echo yes || echo no"
    ).outString.trim() == "yes"

    /** Removes the Store from the DenyList and force-stops it so the next start is specialized with the hook. */
    fun removePlayStoreFromDenylist(): ShellResult =
        RootShell.run("magisk --denylist rm $PLAY_STORE && am force-stop $PLAY_STORE")

    // ------------------------------------------------------------ Enable

    /**
     * Turns the feature on: (re)deploys the module if it's missing or older
     * than the bundled one, clears the `disable` flag, and records a pending
     * reboot if anything actually changed.
     */
    fun enable(context: Context): ShellResult {
        val wasActive = isEnabled()
        val needsInstall = !isInstalled() || installedVersionCode() < bundledVersionCode(context)
        if (needsInstall) {
            val installed = install(context)
            if (!installed.success) return installed
        }
        val cleared = RootShell.run("rm -f '$DISABLE_FLAG'")
        if (!cleared.success) return cleared
        // install() already recorded the boot mark; only enabling a previously-disabled module still needs one.
        return if (!wasActive && !needsInstall) markPendingBoot() else RootShell.run("true")
    }

    /** Flags the module `disable` (the hook unloads at next boot). The detach list is kept. */
    fun disable(): ShellResult {
        if (!isInstalled()) return RootShell.run("true")
        val flagged = RootShell.run("touch '$DISABLE_FLAG'")
        if (!flagged.success) return flagged
        return markPendingBoot()
    }

    private fun markPendingBoot(): ShellResult = RootShell.run("cat $BOOT_ID > '$BOOT_MARK'")

    // ------------------------------------------------------------ Install

    /**
     * Deploys the bundled module zip. Extraction happens in-process (the zip
     * is small and Android's toybox `unzip` availability varies), staged in
     * cacheDir, then root copies it into place with the ownership, modes and
     * SELinux label Magisk's own installer would give it. Only the parts the
     * device needs are staged: this ABI's `detach` binary, the .so libs
     * under zygisk/ (all ABIs - Zygisk picks by zygote), post-fs-data.sh and webroot/.
     * module.prop goes in LAST so a half-deployed module is never picked up
     * mid-write. Wipes and replaces MODULE_DIR; PERSIST is untouched.
     */
    fun install(context: Context): ShellResult {
        val stage = File(context.cacheDir, "zygisk_detach_stage")
        val propTmp = File(context.cacheDir, "zygisk_detach_module.prop")
        stage.deleteRecursively()
        stage.mkdirs()
        try {
            stageModule(context, stage, propTmp)?.let { return fail(it) }

            val result = RootShell.run(
                listOf(
                    "rm -rf '$MODULE_DIR'",
                    "mkdir -p '$MODULE_DIR' '$PERSIST'",
                    "cp -r '${stage.absolutePath}'/* '$MODULE_DIR/'",
                    "chown -R 0:0 '$MODULE_DIR'",
                    "find '$MODULE_DIR' -type d -exec chmod 755 {} \\;",
                    "find '$MODULE_DIR' -type f -exec chmod 644 {} \\;",
                    "chmod 755 '$DETACH_BIN' '$MODULE_DIR/post-fs-data.sh'",
                    "install -m 644 '${propTmp.absolutePath}' '$MODULE_PROP'",
                    "(chcon -R u:object_r:system_file:s0 '$MODULE_DIR' 2>/dev/null || true)",
                    "cat $BOOT_ID > '$BOOT_MARK'"
                ).joinToString(" && ")
            )
            return result
        } finally {
            stage.deleteRecursively()
            propTmp.delete()
        }
    }

    /** Returns an error message, or null on success. */
    private fun stageModule(context: Context, stage: File, propTmp: File): String? {
        val arch = archDir()
        var sawDetach = false
        var sawProp = false
        context.assets.open(ASSET_MODULE_ZIP).use { raw ->
            ZipInputStream(raw).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val name = entry.name
                    val dest: File? = when {
                        entry.isDirectory || name.contains("..") -> null
                        name == "module.prop" -> propTmp.also { sawProp = true }
                        name == "post-fs-data.sh" -> File(stage, name)
                        name.startsWith("zygisk/") && name.endsWith(".so") -> File(stage, name)
                        name.startsWith("webroot/") -> File(stage, name)
                        name == "bin/$arch/detach" -> File(stage, "detach").also { sawDetach = true }
                        else -> null
                    }
                    if (dest != null) {
                        dest.parentFile?.mkdirs()
                        dest.outputStream().use { out -> zin.copyTo(out) }
                    }
                    entry = zin.nextEntry
                }
            }
        }
        if (!sawProp) return "bundled module zip has no module.prop"
        if (!sawDetach) return "bundled module zip has no bin/$arch/detach for this device"
        return null
    }

    /** Magisk's ARCH naming, which is what the zip's bin/ directories are keyed by. */
    private fun archDir(): String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "armeabi-v7a", "armeabi" -> "arm"
        "x86_64" -> "x64"
        "x86" -> "x86"
        else -> "arm64"
    }

    private fun bundledProp(context: Context, key: String): String? {
        context.assets.open(ASSET_MODULE_ZIP).use { raw ->
            ZipInputStream(raw).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    if (entry.name == "module.prop") {
                        return parseProp(zin.readBytes().toString(Charsets.UTF_8), key)
                    }
                    entry = zin.nextEntry
                }
            }
        }
        return null
    }

    private fun bundledVersionCode(context: Context): Int =
        bundledProp(context, "versionCode")?.toIntOrNull() ?: 0

    // ------------------------------------------------------- Detach list

    /**
     * Package names currently on the detach list, or null if it can't be read
     * (module missing, or detach.bin corrupted - the CLI exits non-zero and
     * `reset` via [applyDetached] with an empty set recovers it). An absent or
     * empty list is an empty list, not null.
     */
    fun detachedPackages(): List<String>? {
        val result = RootShell.run("'$DETACH_BIN' list")
        if (!result.success) return null
        return result.out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Installed package names, read through the root shell (in-process
     * PackageManager listings are unreliable on this ROM). [includeSystem]
     * false lists user-installed apps only (`pm list packages -3`).
     */
    fun installedPackages(includeSystem: Boolean): List<String> =
        RootShell.run(if (includeSystem) "pm list packages" else "pm list packages -3").out
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

    /**
     * Sets the detach list to exactly [packages] (replacing whatever was
     * there) and restarts the Play Store so it re-reads it. An empty set
     * clears the list. Nothing is written if any name is invalid - see the
     * class doc for why that must be checked here rather than left to the CLI.
     */
    fun applyDetached(packages: Collection<String>): ShellResult {
        val names = packages.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        names.firstOrNull { !isValidPackageName(it) }?.let { return fail("Invalid package name: $it") }
        if (names.isEmpty()) return RootShell.run("'$DETACH_BIN' reset")
        return RootShell.run(
            "'$DETACH_BIN' detachall " + names.joinToString(" ") { "'${shellEscape(it)}'" }
        )
    }

    /** ASCII letters, digits, '_' and '.', at most 128 characters (the CLI packs each name into a length byte as 2n-1). */
    private fun isValidPackageName(name: String): Boolean = PACKAGE_NAME.matches(name)

    private val PACKAGE_NAME = Regex("[A-Za-z0-9_.]{1,128}")

    // --------------------------------------------------------------- Utils

    private fun parseProp(text: String, key: String): String? =
        text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?.trim()
            ?.ifBlank { null }

    /** Reports a Kotlin-side failure through the shell so callers get the same result type as every other step. */
    private fun fail(message: String): ShellResult =
        RootShell.run("echo '${shellEscape(message)}'; false")

    private fun shellEscape(s: String): String = s.replace("'", "'\\''")
}
