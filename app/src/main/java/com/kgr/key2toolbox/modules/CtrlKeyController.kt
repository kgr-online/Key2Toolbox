package com.kgr.key2toolbox.modules

import android.content.Context
import android.content.SharedPreferences
import com.kgr.key2toolbox.core.AssetInstaller
import com.kgr.key2toolbox.core.RootShell
import com.kgr.key2toolbox.core.ShellResult
import java.io.File

/**
 * Two physical-key fixes for the BlackBerry keyboard, both living in the device
 * keylayout that the built-in keyboard resolves to:
 *
 *  - Ctrl remap: a spare key -> CTRL_LEFT (a preference). Two candidates, the
 *    user picks one via [SourceKey]: the Convenience/Fn key (scancode 110,
 *    stock `FUNCTION`) or the Currency key (scancode 5, stock `4`).
 *  - SYM fix: key 100, `ALT_RIGHT` -> `SYM` (a correctness fix - AOSP's
 *    Generic.kl has no BlackBerry SYM key, so devices that fall back to it lose
 *    the physical SYM key entirely). No-op where the vendor keylayout already
 *    maps SYM natively.
 *
 * ## Why this used to brick the keyboard
 *
 * The old mechanism ran an in-place `sed -i` on the vendor keylayout file.
 * `sed -i` is not atomic; a reset caught mid-write left the keylayout as
 * garbage and the physical keyboard dead until it was rebuilt by hand (the
 * capacitive nav keys are a different input device and kept working). A boot
 * script re-ran the same `sed` every boot and never repaired the damage.
 *
 * ## What it does now
 *
 * Two device classes, auto-detected from the keyboard's live `KeyLayoutFile`
 * (via `dumpsys input`):
 *
 *  - [Mode.VENDOR_KL] - the keyboard resolves to a real file under
 *    `/vendor/usr/keylayout/`. Instead of editing it in place we ship a
 *    magic-mount **module** ([VENDOR_MODULE_ID]) that overlays that exact path
 *    with a file staged under `/data`. The real `/vendor` partition is never
 *    written. Staging is read-golden -> patch in memory -> validate -> atomic
 *    `install`. The module carries a `post-fs-data.sh` that self-heals its
 *    overlay from a bundled pristine copy (or disables itself so the stock
 *    file shows through) if it is ever found unusable at boot. On the first
 *    enable we also do one crash-safe live write + a `uevent` remove/add so
 *    the change takes effect without a reboot; later changes need a reboot
 *    (the module owns the path by then).
 *
 *  - [Mode.SYSTEM_MODULE] - no vendor keylayout file (the ROM falls back to
 *    `Generic.kl`). A full Generic-derived template is staged as a module at
 *    `/system/usr/keylayout/<device>.kl`. Read-modify-write on the staged file
 *    so the two toggles stay independent. Magic-mount applies it at boot only.
 */
object CtrlKeyController {

    enum class State { CTRL, FUNCTION, UNKNOWN }
    enum class SymState { FIXED, DEFAULT, UNKNOWN }
    enum class Mode { VENDOR_KL, SYSTEM_MODULE, UNKNOWN }

    /** The spare key the Ctrl remap sources from. `stockKeycode` is what that
     *  scancode maps to before we touch it. */
    enum class SourceKey(val scancode: Int, val stockKeycode: String) {
        FUNCTION(110, "FUNCTION"),
        CURRENCY(5, "4");

        companion object {
            fun fromName(v: String?): SourceKey =
                entries.firstOrNull { it.name == v } ?: FUNCTION
        }
    }

    const val PREFS = "key2tweaks"
    const val KEY_SOURCE = "ctrl_key_source" // SourceKey.name

    // --- legacy artefacts we take ownership of / clean up -----------------
    private const val LEGACY_BOOT_SCRIPT = "/data/adb/service.d/ctrl_key.sh"
    private const val LEGACY_SYSTEM_MODULE_ID = "k2tb_ctrlfix"

    // --- VENDOR_KL: crash-safe direct write + hardened boot script --------
    // (An APatch module overlay of /vendor was tried and does NOT mount on the
    //  Key2's setup - /vendor is a real partition APatch's magic-mount leaves
    //  alone - so the live file has to be edited directly, just safely.)
    private const val BOOT_SCRIPT = "/data/adb/service.d/key_remap.sh"
    private const val BOOT_SCRIPT_ASSET = "keyremap_boot.sh"
    private const val STOCK_KL_ASSET = "stmpe_stock.kl" // pristine QWERTY short-form, last-resort fallback
    private const val GOLDEN_DIR = "/data/adb/k2_kbd_backup"
    private const val GOLDEN_KL = "$GOLDEN_DIR/stmpe.stock.kl"
    private const val KL_SECTX = "u:object_r:vendor_keylayout_file:s0"

    // --- SYSTEM_MODULE: the Generic-derived template ---------------------
    private const val SYSTEM_MODULE_DIR = "/data/adb/modules/$LEGACY_SYSTEM_MODULE_ID"
    private const val SYSTEM_KEYLAYOUT_ASSET = "ctrl_key_layout.kl"

    private val KEY_100_LINE = Regex("""(?m)^key 100\s+\S+""")
    private val KEY_110_LINE = Regex("""(?m)^key 110\s+\S+""")

    // ---------------------------------------------------------- detection

    /** The `.kl` the built-in keyboard actually parses, straight from `dumpsys input`. */
    private fun liveKeyLayoutPath(): String? {
        val out = RootShell.run("dumpsys input").outString
        Regex("""built-in keyboard\)[\s\S]{0,600}?KeyLayoutFile:\s*(\S+\.kl)""")
            .find(out)?.groupValues?.get(1)?.let { return it }
        // Fallback: the IDC's keyboard.layout name under the standard dirs.
        val layoutName = Regex("""(?m)^\s*keyboard\.layout\s*=\s*(\S+)""")
            .find(RootShell.run("cat /vendor/usr/idc/*keypad*.idc 2>/dev/null").outString)
            ?.groupValues?.get(1)
        if (layoutName != null) {
            for (dir in listOf("/vendor/usr/keylayout", "/system/usr/keylayout")) {
                val p = "$dir/$layoutName.kl"
                if (AssetInstaller.fileExists(p)) return p
            }
        }
        return null
    }

    /** device name (for the SYSTEM_MODULE staged filename) from /proc/bus/input/devices. */
    private fun detectDeviceName(): String? {
        val out = RootShell.run("cat /proc/bus/input/devices 2>/dev/null").outString
        return Regex("""N: Name="([^"]*[sS][tT][mM][pP][eE][^"]*)"""").find(out)?.groupValues?.get(1)
    }

    data class Detected(val deviceName: String, val livePath: String, val mode: Mode)

    fun detect(): Detected? {
        val name = detectDeviceName() ?: "stmpe_keypad"
        val path = liveKeyLayoutPath()
        return when {
            path != null && path.startsWith("/vendor/") -> Detected(name, path, Mode.VENDOR_KL)
            path != null -> Detected(name, path, Mode.SYSTEM_MODULE)
            // No live path resolvable: if a vendor file for the device name exists, treat as VENDOR_KL.
            AssetInstaller.fileExists("/vendor/usr/keylayout/$name.kl") ->
                Detected(name, "/vendor/usr/keylayout/$name.kl", Mode.VENDOR_KL)
            else -> Detected(name, "/system/usr/keylayout/$name.kl", Mode.SYSTEM_MODULE)
        }
    }

    fun requiresReboot(): Boolean = detect()?.mode == Mode.SYSTEM_MODULE
    // VENDOR_KL applies live via the uevent reload - no reboot needed.

    fun isSymFixApplicable(): Boolean {
        val d = detect() ?: return false
        if (d.mode != Mode.SYSTEM_MODULE) {
            // Vendor keylayouts on this keyboard map SYM natively - nothing to fix.
            return !AssetInstaller.readFile(d.livePath).contains(Regex("""(?m)^key 100\s+SYM"""))
        }
        return true
    }

    // ------------------------------------------------------------- source key

    fun getSource(sp: SharedPreferences): SourceKey =
        SourceKey.fromName(sp.getString(KEY_SOURCE, SourceKey.FUNCTION.name))

    fun setSource(sp: SharedPreferences, key: SourceKey) =
        sp.edit().putString(KEY_SOURCE, key.name).apply()

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ Ctrl

    fun currentKeymapState(): State {
        val d = detect() ?: return State.UNKNOWN
        val live = AssetInstaller.readFile(d.livePath)
        return when {
            SourceKey.entries.any { live.contains(Regex("""(?m)^key ${it.scancode}\s+CTRL_LEFT""")) } -> State.CTRL
            live.isNotBlank() -> State.FUNCTION
            else -> State.UNKNOWN
        }
    }

    fun isPersisted(): Boolean {
        val d = detect() ?: return false
        return when (d.mode) {
            Mode.VENDOR_KL ->
                // Our hardened boot script is installed = the remap survives reboot.
                AssetInstaller.fileExists(BOOT_SCRIPT) &&
                    AssetInstaller.readFile(BOOT_SCRIPT).contains("CTRL_LEFT")
            Mode.SYSTEM_MODULE ->
                AssetInstaller.readFile(systemModuleKlPath(d))
                    .contains(Regex("""(?m)^key 110\s+CTRL_LEFT"""))
            Mode.UNKNOWN -> false
        }
    }

    fun applyOn(context: Context): ShellResult = reconcile(context, ctrl = true)
    fun applyOff(context: Context): ShellResult = reconcile(context, ctrl = false)

    // ------------------------------------------------------------------- SYM

    fun currentSymState(): SymState {
        val d = detect() ?: return SymState.UNKNOWN
        val live = AssetInstaller.readFile(d.livePath)
        return when {
            live.contains(Regex("""(?m)^key 100\s+SYM""")) -> SymState.FIXED
            live.contains(Regex("""(?m)^key 100\s+ALT_RIGHT""")) -> SymState.DEFAULT
            else -> SymState.UNKNOWN
        }
    }

    fun isSymPersisted(): Boolean {
        val d = detect() ?: return false
        if (d.mode == Mode.VENDOR_KL) return !isSymFixApplicable() // native = always "on"
        return AssetInstaller.readFile(systemModuleKlPath(d))
            .contains(Regex("""(?m)^key 100\s+SYM"""))
    }

    fun applySymOn(context: Context): ShellResult {
        if (!isSymFixApplicable()) return ShellResult(true, listOf("SYM already works natively - nothing to do"))
        return reconcile(context, sym = true)
    }

    fun applySymOff(context: Context): ShellResult {
        if (!isSymFixApplicable()) return ShellResult(false, listOf("SYM is native on this device and can't be turned off"))
        return reconcile(context, sym = false)
    }

    // --------------------------------------------------------- reconcile

    /**
     * Bring both toggles to their desired state in one pass. `ctrl` / `sym`
     * null = "leave as it currently is".
     */
    private fun reconcile(context: Context, ctrl: Boolean? = null, sym: Boolean? = null): ShellResult {
        val d = detect() ?: return ShellResult(false, listOf("Could not detect the keyboard keylayout"))
        return when (d.mode) {
            Mode.VENDOR_KL -> reconcileVendorKl(context, d, ctrl, sym)
            Mode.SYSTEM_MODULE -> reconcileSystemModule(context, d, ctrl, sym)
            Mode.UNKNOWN -> ShellResult(false, listOf("Unknown device - can't remap"))
        }
    }

    // --------------------------------------------------- VENDOR_KL mode
    //
    // The keyboard resolves to a real file under /vendor. An APatch module
    // overlay of /vendor was tried and does not mount on the Key2's setup, so
    // the live file is edited directly - but crash-safely (temp + validate +
    // atomic rename), with a golden backup and a self-healing boot script.
    // SELinux stays enforcing throughout (verified: the su domain can remount
    // /vendor rw, write the keylayout and relabel it).

    /** The pristine, un-remapped keylayout for this device. Captured once from
     *  the live file; falls back to the bundled QWERTY short-form only if the
     *  live file is already unusable and nothing was captured. */
    private fun goldenContent(context: Context, d: Detected): String {
        val cached = AssetInstaller.readFile(GOLDEN_KL)
        if (isSane(cached)) return cached

        val live = AssetInstaller.readFile(d.livePath)
        if (isSane(live)) {
            val reverted = SourceKey.entries.fold(live) { acc, k ->
                acc.replace(Regex("""(?m)^key ${k.scancode}\s+CTRL_LEFT"""), "key ${k.scancode} ${k.stockKeycode}")
            }
            RootShell.run("mkdir -p '$GOLDEN_DIR'")
            writeRootFile(reverted, GOLDEN_KL, "0640", null)
            return reverted
        }
        return context.assets.open(STOCK_KL_ASSET).bufferedReader().use { it.readText() }
    }

    private fun reconcileVendorKl(context: Context, d: Detected, ctrl: Boolean?, sym: Boolean?): ShellResult {
        val sp = prefs(context)
        val wantCtrl = ctrl ?: (currentKeymapState() == State.CTRL)
        val source = getSource(sp)

        // Build the desired keylayout from the pristine golden: revert every
        // Ctrl candidate, then set the chosen one; touch key 100 only if asked.
        var desired = goldenContent(context, d)
        desired = SourceKey.entries.fold(desired) { acc, k ->
            acc.replace(Regex("""(?m)^key ${k.scancode}\s+\S+"""), "key ${k.scancode} ${k.stockKeycode}")
        }
        if (wantCtrl) {
            desired = desired.replace(
                Regex("""(?m)^key ${source.scancode}\s+\S+"""), "key ${source.scancode} CTRL_LEFT"
            )
        }
        if (sym == true) desired = desired.replace(KEY_100_LINE, "key 100 SYM")
        if (sym == false) desired = desired.replace(KEY_100_LINE, "key 100 ALT_RIGHT")

        if (!isSane(desired) || (wantCtrl && !desired.contains("CTRL_LEFT"))) {
            return ShellResult(false, listOf("Refusing to apply - generated keylayout failed validation"))
        }

        // Live: crash-safe atomic write + force EventHub to re-read (no reboot).
        val live = writeVendorKlLive(desired, d.livePath)
        if (!live.success) return live
        reloadInputDevice(d.deviceName)

        // Persist across reboots via the hardened, self-healing boot script -
        // or remove it on a full revert. Retire the legacy in-place ctrl_key.sh.
        AssetInstaller.removeFile(LEGACY_BOOT_SCRIPT)
        val nothingOn = !wantCtrl && sym != true
        return if (nothingOn) {
            AssetInstaller.removeFile(BOOT_SCRIPT)
            ShellResult(true, listOf("Reverted to the stock keylayout"))
        } else {
            installBootScript(context, d, source)
        }
    }

    /** Writes the hardened boot script for the chosen source key. It self-heals
     *  from [GOLDEN_KL] and re-applies the remap through a temp file every boot. */
    private fun installBootScript(context: Context, d: Detected, source: SourceKey): ShellResult =
        AssetInstaller.installFromAsset(context, BOOT_SCRIPT_ASSET, BOOT_SCRIPT) { raw ->
            raw.replace("__SRC__", source.scancode.toString())
                .replace("__STOCK__", source.stockKeycode)
                .replace("__KL__", d.livePath)
                .replace("__GOLDEN__", GOLDEN_KL)
        }

    /** Crash-safe write of the live `/vendor` keylayout: base64 -> temp file ->
     *  validate -> atomic rename, inside the init mount namespace, /vendor rw
     *  only for the swap. SELinux stays enforcing. */
    private fun writeVendorKlLive(content: String, path: String): ShellResult {
        val tmp = "$path.k2new"
        val b64 = android.util.Base64.encodeToString(content.toByteArray(), android.util.Base64.NO_WRAP)
        val inner = buildString {
            append("mount -o rw,remount /vendor 2>/dev/null; ")
            append("echo $b64 | base64 -d > \"$tmp\" 2>/dev/null; ")
            append("if [ -s \"$tmp\" ] && grep -q \"^key 16[[:space:]]\" \"$tmp\" && grep -q \"^key 30[[:space:]]\" \"$tmp\"; then ")
            append("chmod 644 \"$tmp\"; chcon $KL_SECTX \"$tmp\" 2>/dev/null; mv -f \"$tmp\" \"$path\" && echo OK; ")
            append("else rm -f \"$tmp\"; fi; ")
            append("sync; mount -o ro,remount /vendor 2>/dev/null")
        }
        val res = RootShell.run("nsenter -t 1 -m -- sh -c '$inner'")
        return if (res.outString.contains("OK")) res
        else ShellResult(false, res.out.ifEmpty { listOf("keylayout write failed validation") })
    }

    /** Force EventHub to re-open the keyboard and re-parse its keylayout, no
     *  reboot: the same remove/add cycle a real replug triggers. Verified on the
     *  Key2 (`EventHub: Removed device ... / New device ... keyLayout=...`). */
    private fun reloadInputDevice(deviceName: String): ShellResult = RootShell.run(
        "for d in /sys/class/input/event*; do " +
            "if [ \"\$(cat \"\$d/device/name\" 2>/dev/null)\" = \"$deviceName\" ]; then " +
            "echo remove > \"\$d/uevent\"; sleep 1; echo add > \"\$d/uevent\"; " +
            "fi; done"
    )

    // ------------------------------------------------- SYSTEM_MODULE mode

    private fun systemModuleKlPath(d: Detected): String =
        "$SYSTEM_MODULE_DIR/system/usr/keylayout/${d.deviceName}.kl"

    private fun reconcileSystemModule(context: Context, d: Detected, ctrl: Boolean?, sym: Boolean?): ShellResult {
        val stagedPath = systemModuleKlPath(d)
        val current = if (AssetInstaller.fileExists(stagedPath)) {
            AssetInstaller.readFile(stagedPath)
        } else {
            context.assets.open(SYSTEM_KEYLAYOUT_ASSET).bufferedReader().use { it.readText() }
        }
        var updated = current
        if (ctrl == true) updated = updated.replace(KEY_110_LINE, "key 110   CTRL_LEFT")
        if (ctrl == false) updated = updated.replace(KEY_110_LINE, "key 110   FUNCTION")
        if (sym == true) updated = updated.replace(KEY_100_LINE, "key 100   SYM")
        if (sym == false) updated = updated.replace(KEY_100_LINE, "key 100   ALT_RIGHT")

        val mk = RootShell.run("mkdir -p '${stagedPath.substringBeforeLast('/')}'")
        if (!mk.success) return mk
        val w = writeRootFile(updated, stagedPath, "0644", null)
        if (!w.success) return w
        return AssetInstaller.installFromAsset(context, "ctrl_key_module.prop", "$SYSTEM_MODULE_DIR/module.prop")
    }

    // ------------------------------------------------------------ helpers

    private fun isSane(kl: String): Boolean =
        kl.isNotBlank() &&
            Regex("""(?m)^key 16\s""").containsMatchIn(kl) &&
            Regex("""(?m)^key 30\s""").containsMatchIn(kl)

    /** Write [content] to [path] as root via a temp file + `install` (atomic rename). */
    private fun writeRootFile(content: String, path: String, mode: String, sectx: String?): ShellResult {
        val b64 = android.util.Base64.encodeToString(content.toByteArray(), android.util.Base64.NO_WRAP)
        val tmp = "$path.k2tmp"
        val ctx = if (sectx != null) "chcon $sectx '$tmp' 2>/dev/null; " else ""
        return RootShell.run(
            "echo $b64 | base64 -d > '$tmp' && chmod $mode '$tmp' && ${ctx}mv -f '$tmp' '$path'"
        )
    }

    // --- deprecated shims (old call sites) ------------------------------
    @Deprecated("Use applyOn(context)", ReplaceWith("applyOn(context)"))
    fun applyLiveOn(): ShellResult = ShellResult(false, listOf("Use applyOn(context)"))

    @Deprecated("Use applyOff(context)", ReplaceWith("applyOff(context)"))
    fun applyLiveOff(): ShellResult = ShellResult(false, listOf("Use applyOff(context)"))
}
