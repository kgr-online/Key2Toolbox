package com.kgr.key2toolbox.modules

import android.content.Context
import com.kgr.key2toolbox.core.AssetInstaller
import com.kgr.key2toolbox.core.RootShell
import com.kgr.key2toolbox.core.ShellResult

/**
 * Convenience key -> Ctrl remap, key 110 on the stmpe keypad driver.
 *
 * Two device/kernel generations behave differently and are auto-detected
 * at runtime via /proc/bus/input/devices:
 *
 *  - OLD (4.19 kernel, e.g. original Key2 ROM): input device reports as
 *    "stmpe", ROM ships /vendor/usr/keylayout/stmpe.kl with key 110 =
 *    FUNCTION. We sed-patch it live (this boot, no reboot needed) and
 *    persist via a /data/adb/service.d boot script that reapplies the sed
 *    on every boot. Exactly the original working mechanism, unchanged.
 *
 *  - NEW (4.4 kernel, e.g. LOS 22.2 BBF100): input device reports as
 *    "stmpe_keypad", and the ROM ships NO device-specific keylayout file at
 *    all (falls back to Generic.kl, key 110 = INSERT). There is no reliable
 *    live-patch here - /system can't be remounted rw the way /vendor could
 *    on the old kernel. Instead we ship a full Generic.kl-derived keylayout
 *    (key 110 -> CTRL_LEFT) as a Magisk-style module at
 *    /data/adb/modules/k2tb_ctrlfix/system/usr/keylayout/<name>.kl.
 *    Magisk/APatch/FolkPatch magic-mount modules at BOOT ONLY - toggling
 *    this stages the change but the user must reboot for it to take
 *    effect. Always check [requiresReboot] after [applyOn]/[applyOff] to
 *    know whether to prompt the user.
 */
object CtrlKeyController {

    enum class State { CTRL, FUNCTION, UNKNOWN }
    enum class Mode { OLD_VENDOR_SED, NEW_MODULE, UNKNOWN }

    // --- OLD (4.19) constants - unchanged from the original working version ---
    private const val OLD_SCRIPT_NAME = "ctrl_key.sh"
    private const val OLD_PERSIST_TARGET = "/data/adb/service.d/$OLD_SCRIPT_NAME"

    // --- NEW (4.4+) constants ---
    private const val MODULE_ID = "k2tb_ctrlfix"
    private const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"
    private const val MODULE_PROP_ASSET = "ctrl_key_module.prop"
    private const val MODULE_KEYLAYOUT_ASSET = "ctrl_key_layout.kl"

    /**
     * Reads /proc/bus/input/devices and returns the exact device name of
     * the stmpe keypad (e.g. "stmpe" or "stmpe_keypad"), or null if this
     * device doesn't have one (shouldn't happen on a real Key2, but keeps
     * this safe on emulators/other hardware).
     */
    private fun detectDeviceName(): String? {
        val out = RootShell.run("cat /proc/bus/input/devices 2>/dev/null").outString
        val regex = Regex("""N: Name="([^"]*[sS][tT][mM][pP][eE][^"]*)"""")
        return regex.find(out)?.groupValues?.get(1)
    }

    /** Path to the live keylayout file the system actually reads, once applied. */
    private fun liveKeylayoutPath(deviceName: String, mode: Mode): String = when (mode) {
        Mode.OLD_VENDOR_SED -> "/vendor/usr/keylayout/$deviceName.kl"
        Mode.NEW_MODULE -> "/system/usr/keylayout/$deviceName.kl"
        Mode.UNKNOWN -> ""
    }

    private fun moduleStagedPath(deviceName: String): String =
        "$MODULE_DIR/system/usr/keylayout/$deviceName.kl"

    /**
     * Detects device name + which persistence mode this device/kernel
     * needs. Does a couple of root shell calls - fine to call from a
     * settings screen, avoid calling in a tight loop.
     */
    fun detect(): Pair<String, Mode>? {
        val name = detectDeviceName() ?: return null
        val oldPath = "/vendor/usr/keylayout/$name.kl"
        val mode = if (AssetInstaller.fileExists(oldPath)) Mode.OLD_VENDOR_SED else Mode.NEW_MODULE
        return name to mode
    }

    /** True if this device needs a reboot for a toggle to actually take effect. */
    fun requiresReboot(): Boolean = detect()?.second == Mode.NEW_MODULE

    /** Reads the live keymap to see whether key 110 is currently CTRL_LEFT or FUNCTION/INSERT. */
    fun currentKeymapState(): State {
        val (name, mode) = detect() ?: return State.UNKNOWN
        val path = liveKeylayoutPath(name, mode)
        val out = RootShell.run("grep '^key 110' '$path' 2>/dev/null").outString
        return when {
            out.contains("CTRL_LEFT") -> State.CTRL
            out.contains("FUNCTION") || out.contains("INSERT") -> State.FUNCTION
            else -> State.UNKNOWN
        }
    }

    /**
     * True if the remap is set up to survive a reboot. On NEW_MODULE
     * devices this reflects the STAGED module, which may not be live yet
     * if the user hasn't rebooted since toggling - pair with
     * [currentKeymapState] if you need to distinguish "will apply next
     * boot" from "already active".
     */
    fun isPersisted(): Boolean {
        val (name, mode) = detect() ?: return false
        return when (mode) {
            Mode.OLD_VENDOR_SED -> AssetInstaller.fileExists(OLD_PERSIST_TARGET)
            Mode.NEW_MODULE -> AssetInstaller.fileExists(moduleStagedPath(name))
            Mode.UNKNOWN -> false
        }
    }

    /**
     * Turns the remap ON.
     *  - OLD devices: applies immediately (live sed) AND installs the boot
     *    script - no reboot needed, matches original behavior exactly.
     *  - NEW devices: stages the Magisk module only. [requiresReboot] will
     *    be true afterward - nothing changes on the keyboard until reboot.
     */
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
            Mode.NEW_MODULE -> installModule(context, name)
            Mode.UNKNOWN -> ShellResult(false, listOf("Unknown device/kernel - can't apply Ctrl remap"))
        }
    }

    /** Turns the remap OFF. Same live-vs-staged distinction as [applyOn]. */
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
            Mode.NEW_MODULE -> removeModule()
            Mode.UNKNOWN -> ShellResult(false, listOf("Unknown device/kernel - can't remove Ctrl remap"))
        }
    }

    private fun installModule(context: Context, deviceName: String): ShellResult {
        val mkdir = RootShell.run("mkdir -p '$MODULE_DIR/system/usr/keylayout'")
        if (!mkdir.success) return mkdir

        val keylayout = AssetInstaller.installFromAsset(
            context, MODULE_KEYLAYOUT_ASSET, moduleStagedPath(deviceName)
        )
        if (!keylayout.success) return keylayout

        return AssetInstaller.installFromAsset(context, MODULE_PROP_ASSET, "$MODULE_DIR/module.prop")
    }

    private fun removeModule(): ShellResult = RootShell.run("rm -rf '$MODULE_DIR'")

    // --- Original method names kept so existing call sites still compile.
    // These only support OLD devices (matches their original scope); update
    // call sites to use applyOn(context)/applyOff(context) to support both. ---

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
