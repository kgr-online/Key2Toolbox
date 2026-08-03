package com.kgr.key2toolbox.modules

import android.content.Context
import com.kgr.key2toolbox.core.AssetInstaller
import com.kgr.key2toolbox.core.RootShell
import com.kgr.key2toolbox.core.ShellResult
import java.io.File

/**
 * Two independent physical-key fixes for the BlackBerry keyboard, both living
 * in the same device keylayout file:
 *
 *  - Ctrl remap: key 110, FUNCTION -> CTRL_LEFT (a preference - user's choice).
 *  - SYM fix: key 100, ALT_RIGHT -> SYM (a correctness fix - AOSP's Generic.kl
 *    has no concept of a BlackBerry SYM key, so devices that fall back to it
 *    lose the physical SYM key's "open symbol picker" behavior entirely).
 *
 * Two device/kernel generations, auto-detected via /proc/bus/input/devices:
 *
 *  - OLD (4.19 kernel): input device "stmpe". ROM ships
 *    /vendor/usr/keylayout/stmpe.kl which ALREADY has key 100 = SYM natively
 *    - nothing to fix there. Ctrl remap applies live via sed + a
 *    /data/adb/service.d boot script, exactly the original working mechanism.
 *    [applySymOn]/[applySymOff] are no-ops on this mode (see [isSymFixApplicable]).
 *
 *  - NEW (4.4 kernel, e.g. LOS 22.2 BBF100): input device "stmpe_keypad", ROM
 *    ships NO device-specific keylayout file (falls back to Generic.kl, which
 *    has key 100 = ALT_RIGHT and key 110 = FUNCTION - both wrong for this
 *    keyboard). We ship a full Generic.kl-derived template as an app asset and
 *    stage it as a Magisk-style module. Each toggle does a READ-MODIFY-WRITE
 *    on the CURRENTLY STAGED file (falling back to the pristine asset template
 *    if no module is staged yet) so that toggling one key never disturbs the
 *    other's state. Magisk/APatch/FolkPatch magic-mount modules at BOOT ONLY -
 *    check [requiresReboot] after either toggle to know whether to prompt.
 */
object CtrlKeyController {

    enum class State { CTRL, FUNCTION, UNKNOWN }
    enum class SymState { FIXED, DEFAULT, UNKNOWN }
    enum class Mode { OLD_VENDOR_SED, NEW_MODULE, UNKNOWN }

    // --- OLD (4.19) constants - unchanged from the original working version ---
    private const val OLD_SCRIPT_NAME = "ctrl_key.sh"
    private const val OLD_PERSIST_TARGET = "/data/adb/service.d/$OLD_SCRIPT_NAME"

    // --- NEW (4.4+) constants ---
    private const val MODULE_ID = "k2tb_ctrlfix"
    private const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"
    private const val MODULE_PROP_ASSET = "ctrl_key_module.prop"
    // Pristine Generic.kl-derived template - key 100 = ALT_RIGHT, key 110 = FUNCTION.
    // Toggles patch this (or whatever's currently staged) rather than shipping
    // a pre-patched file, so the two toggles stay independent of each other.
    private const val MODULE_KEYLAYOUT_ASSET = "ctrl_key_layout.kl"

    private val KEY_100_LINE = Regex("""(?m)^key 100\s+\S+""")
    private val KEY_110_LINE = Regex("""(?m)^key 110\s+\S+""")

    /**
     * Reads /proc/bus/input/devices and returns the exact device name of
     * the stmpe keypad (e.g. "stmpe" or "stmpe_keypad"), or null if this
     * device doesn't have one.
     */
    private fun detectDeviceName(): String? {
        val out = RootShell.run("cat /proc/bus/input/devices 2>/dev/null").outString
        val regex = Regex("""N: Name="([^"]*[sS][tT][mM][pP][eE][^"]*)"""")
        return regex.find(out)?.groupValues?.get(1)
    }

    private fun liveKeylayoutPath(deviceName: String, mode: Mode): String = when (mode) {
        Mode.OLD_VENDOR_SED -> "/vendor/usr/keylayout/$deviceName.kl"
        Mode.NEW_MODULE -> "/system/usr/keylayout/$deviceName.kl"
        Mode.UNKNOWN -> ""
    }

    private fun moduleStagedPath(deviceName: String): String =
        "$MODULE_DIR/system/usr/keylayout/$deviceName.kl"

    fun detect(): Pair<String, Mode>? {
        val name = detectDeviceName() ?: return null
        val oldPath = "/vendor/usr/keylayout/$name.kl"
        val mode = if (AssetInstaller.fileExists(oldPath)) Mode.OLD_VENDOR_SED else Mode.NEW_MODULE
        return name to mode
    }

    /** True if this device needs a reboot for a toggle to actually take effect. */
    fun requiresReboot(): Boolean = detect()?.second == Mode.NEW_MODULE

    /** True if the SYM fix is a meaningful toggle on this device (false on OLD - it's already native). */
    fun isSymFixApplicable(): Boolean = detect()?.second == Mode.NEW_MODULE

    // ------------------------------------------------------------------ Ctrl

    fun currentKeymapState(): State {
        val (name, mode) = detect() ?: return State.UNKNOWN
        val out = RootShell.run("grep '^key 110' '${liveKeylayoutPath(name, mode)}' 2>/dev/null").outString
        return when {
            out.contains("CTRL_LEFT") -> State.CTRL
            out.contains("FUNCTION") -> State.FUNCTION
            else -> State.UNKNOWN
        }
    }

    fun isPersisted(): Boolean {
        val (name, mode) = detect() ?: return false
        return when (mode) {
            Mode.OLD_VENDOR_SED -> AssetInstaller.fileExists(OLD_PERSIST_TARGET)
            Mode.NEW_MODULE -> {
                val staged = AssetInstaller.readFile(moduleStagedPath(name))
                staged.contains(Regex("""(?m)^key 110\s+CTRL_LEFT"""))
            }
            Mode.UNKNOWN -> false
        }
    }

    fun applyOn(context: Context): ShellResult {
        val (name, mode) = detect()
            ?: return ShellResult(false, listOf("Could not detect stmpe keypad input device"))
        return when (mode) {
            Mode.OLD_VENDOR_SED -> {
                val path = liveKeylayoutPath(name, mode)
                val live = RootShell.run(
                    "setenforce 0 && " +
                        "nsenter -t 1 -m -- mount -o rw,remount /vendor && " +
                        "nsenter -t 1 -m -- sed -i s/FUNCTION/CTRL_LEFT/ '$path' ; " +
                        "setenforce 1"
                )
                if (!live.success) return live
                AssetInstaller.installFromAsset(context, OLD_SCRIPT_NAME, OLD_PERSIST_TARGET)
            }
            Mode.NEW_MODULE -> patchModule(context, name) { it.replace(KEY_110_LINE, "key 110   CTRL_LEFT") }
            Mode.UNKNOWN -> ShellResult(false, listOf("Unknown device/kernel - can't apply Ctrl remap"))
        }
    }

    fun applyOff(context: Context): ShellResult {
        val (name, mode) = detect()
            ?: return ShellResult(false, listOf("Could not detect stmpe keypad input device"))
        return when (mode) {
            Mode.OLD_VENDOR_SED -> {
                val path = liveKeylayoutPath(name, mode)
                val live = RootShell.run(
                    "setenforce 0 && " +
                        "nsenter -t 1 -m -- mount -o rw,remount /vendor && " +
                        "nsenter -t 1 -m -- sed -i s/CTRL_LEFT/FUNCTION/ '$path' ; " +
                        "setenforce 1"
                )
                if (!live.success) return live
                AssetInstaller.removeFile(OLD_PERSIST_TARGET)
            }
            Mode.NEW_MODULE -> patchModule(context, name) { it.replace(KEY_110_LINE, "key 110   FUNCTION") }
            Mode.UNKNOWN -> ShellResult(false, listOf("Unknown device/kernel - can't remove Ctrl remap"))
        }
    }

    // ------------------------------------------------------------------- SYM

    fun currentSymState(): SymState {
        val (name, mode) = detect() ?: return SymState.UNKNOWN
        val out = RootShell.run("grep '^key 100' '${liveKeylayoutPath(name, mode)}' 2>/dev/null").outString
        return when {
            out.contains("SYM") -> SymState.FIXED
            out.contains("ALT_RIGHT") -> SymState.DEFAULT
            else -> SymState.UNKNOWN
        }
    }

    fun isSymPersisted(): Boolean {
        val (name, mode) = detect() ?: return false
        if (mode == Mode.OLD_VENDOR_SED) return true // native, always "on"
        val staged = AssetInstaller.readFile(moduleStagedPath(name))
        return staged.contains(Regex("""(?m)^key 100\s+SYM"""))
    }

    /** No-op on OLD devices (SYM is already native there) - check [isSymFixApplicable] first. */
    fun applySymOn(context: Context): ShellResult {
        val (name, mode) = detect()
            ?: return ShellResult(false, listOf("Could not detect stmpe keypad input device"))
        if (mode != Mode.NEW_MODULE) {
            return ShellResult(true, listOf("SYM already works natively on this device - nothing to do"))
        }
        return patchModule(context, name) { it.replace(KEY_100_LINE, "key 100   SYM") }
    }

    /** No-op on OLD devices - see [applySymOn]. */
    fun applySymOff(context: Context): ShellResult {
        val (name, mode) = detect()
            ?: return ShellResult(false, listOf("Could not detect stmpe keypad input device"))
        if (mode != Mode.NEW_MODULE) {
            return ShellResult(false, listOf("SYM is native on this device and can't be turned off"))
        }
        return patchModule(context, name) { it.replace(KEY_100_LINE, "key 100   ALT_RIGHT") }
    }

    // --------------------------------------------------------- Shared module

    /**
     * Reads whatever's currently staged for this module (or the pristine
     * asset template if nothing's staged yet), applies [edit] to it, and
     * writes the result back - preserving whatever the OTHER key's current
     * setting is.
     */
    private fun patchModule(context: Context, deviceName: String, edit: (String) -> String): ShellResult {
        val stagedPath = moduleStagedPath(deviceName)
        val current = if (AssetInstaller.fileExists(stagedPath)) {
            AssetInstaller.readFile(stagedPath)
        } else {
            context.assets.open(MODULE_KEYLAYOUT_ASSET).bufferedReader().use { it.readText() }
        }
        val updated = edit(current)

        val mkdir = RootShell.run("mkdir -p '$MODULE_DIR/system/usr/keylayout'")
        if (!mkdir.success) return mkdir

        val tmp = File(context.filesDir, MODULE_KEYLAYOUT_ASSET)
        tmp.writeText(updated)
        val write = RootShell.run("install -m 644 '${tmp.absolutePath}' '$stagedPath'")
        if (!write.success) return write

        return AssetInstaller.installFromAsset(context, MODULE_PROP_ASSET, "$MODULE_DIR/module.prop")
    }

    // --- Original method names kept so existing call sites still compile.
    // These only support OLD devices; update call sites to use
    // applyOn(context)/applyOff(context) for both device generations. ---

    @Deprecated("Use applyOn(context) - supports both device generations", ReplaceWith("applyOn(context)"))
    fun applyLiveOn(): ShellResult {
        val (name, mode) = detect()
            ?: return ShellResult(false, listOf("Could not detect stmpe keypad input device"))
        if (mode != Mode.OLD_VENDOR_SED) {
            return ShellResult(false, listOf("This device needs applyOn(context) - live-only apply isn't supported here"))
        }
        val path = liveKeylayoutPath(name, mode)
        return RootShell.run(
            "setenforce 0 && " +
                "nsenter -t 1 -m -- mount -o rw,remount /vendor && " +
                "nsenter -t 1 -m -- sed -i s/FUNCTION/CTRL_LEFT/ '$path' ; " +
                "setenforce 1"
        )
    }

    @Deprecated("Use applyOff(context) - supports both device generations", ReplaceWith("applyOff(context)"))
    fun applyLiveOff(): ShellResult {
        val (name, mode) = detect()
            ?: return ShellResult(false, listOf("Could not detect stmpe keypad input device"))
        if (mode != Mode.OLD_VENDOR_SED) {
            return ShellResult(false, listOf("This device needs applyOff(context) - live-only apply isn't supported here"))
        }
        val path = liveKeylayoutPath(name, mode)
        return RootShell.run(
            "setenforce 0 && " +
                "nsenter -t 1 -m -- mount -o rw,remount /vendor && " +
                "nsenter -t 1 -m -- sed -i s/CTRL_LEFT/FUNCTION/ '$path' ; " +
                "setenforce 1"
        )
    }

    @Deprecated("Use applyOn(context)", ReplaceWith("applyOn(context)"))
    fun enablePersist(context: Context): ShellResult =
        AssetInstaller.installFromAsset(context, OLD_SCRIPT_NAME, OLD_PERSIST_TARGET)

    @Deprecated("Use applyOff(context)", ReplaceWith("applyOff(context)"))
    fun disablePersist(): ShellResult =
        AssetInstaller.removeFile(OLD_PERSIST_TARGET)
}
