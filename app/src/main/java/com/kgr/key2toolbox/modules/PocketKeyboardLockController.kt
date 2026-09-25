package com.kgr.key2toolbox.modules

import android.content.Context
import com.kgr.key2toolbox.core.AssetInstaller
import com.kgr.key2toolbox.core.RootShell
import com.kgr.key2toolbox.core.ShellResult
import com.kgr.key2toolbox.service.Key2AccessibilityService

/**
 * Disables the physical keyboard while the keyguard is showing.
 *
 * Physical D-pad keys (DPAD_UP/DOWN/LEFT/RIGHT/CENTER, mapped in
 * stmpe_keypad.kl) can navigate focus on the lockscreen and activate
 * whatever's focused - including the emergency-call button - with zero
 * touchscreen involvement. The screen and keyboard also stay fully live
 * in-pocket on this device (see [CallProximitySleepController] for the
 * separate, related in-call proximity bug), so this is a real path to an
 * accidental dial.
 *
 * chmod's the keypad's /dev/input/eventN node to 000 while locked, restoring
 * 660 on unlock. The node is resolved by name (stmpe_keypad) at every check
 * rather than hardcoded, since hardware node paths have moved across kernel
 * updates on this device before (same reasoning as Dt2wController's
 * touchscreen node resolution).
 *
 * Hard safety rule: if "Lockscreen PIN on Keyboard" is enabled, this MUST
 * NEVER disable the keypad, or the user is locked out of their own unlock
 * method. Rather than duplicating that feature's on/off state, the watchdog
 * script reads Key2AccessibilityService's own SharedPreferences XML
 * directly via root - single source of truth, and referencing PREFS /
 * KEY_PIN_INPUT as Kotlin symbols here means this stays correct even if
 * those literal string values ever change.
 *
 * Deliberately NOT also checking whether the accessibility service itself
 * is enabled: if the pref is true but the service happens to be off, the
 * safe failure mode is to skip blocking (keyboard stays live) rather than
 * risk blocking based on a stale read - never the reverse.
 *
 * See pocket_kbd_lock_template.sh for the is_locked() check that is NOT yet
 * verified on-device - confirm before shipping.
 */
object PocketKeyboardLockController {

    private const val SCRIPT_NAME = "pocket_kbd_lock.sh"
    private const val TARGET = "/data/adb/service.d/$SCRIPT_NAME"
    private const val TEMPLATE_ASSET = "pocket_kbd_lock_template.sh"
    private const val LOCK = "/data/adb/.pocket_kbd_lock.lock"

    private const val POLL_INTERVAL_SEC = "1"

    private fun pinPrefsFile(context: Context): String =
        "/data/data/${context.packageName}/shared_prefs/${Key2AccessibilityService.PREFS}.xml"

    // Reused by setEnabled()'s immediate-restore step and available for any
    // caller (e.g. a settings screen "force restore" button) that wants a
    // one-off restore without touching the daemon's own running state.
    private fun restoreKeypadCommand(): String =
        "node=\$(grep -A6 'Name=\"stmpe_keypad\"' /proc/bus/input/devices 2>/dev/null " +
            "| grep '^H: Handlers=' | grep -o 'event[0-9]*' | head -n1); " +
            "[ -n \"\$node\" ] && chmod 660 \"/dev/input/\$node\" 2>/dev/null"

    fun isPersisted(): Boolean = AssetInstaller.fileExists(TARGET)

    /** Whether the watchdog daemon is currently running. */
    fun isRunning(): Boolean =
        RootShell.run("pgrep -f $SCRIPT_NAME >/dev/null 2>&1 && echo yes || echo no")
            .outString.trim() == "yes"

    /**
     * Whether the installed daemon is both alive AND running the script we'd
     * install today - see [AssetInstaller.matchesAsset] for why a bare
     * "is it running" check isn't enough.
     */
    fun isHealthy(context: Context): Boolean =
        isRunning() && AssetInstaller.matchesAsset(context, TEMPLATE_ASSET, TARGET) { raw ->
            raw.replace("__POLL_INTERVAL_SEC__", POLL_INTERVAL_SEC)
                .replace("__PIN_PREFS_FILE__", pinPrefsFile(context))
                .replace("__PIN_INPUT_KEY__", Key2AccessibilityService.KEY_PIN_INPUT)
        }

    /**
     * Enables (installs + launches) or disables (stops + removes) the
     * watchdog. Any running instance is stopped first, and the keypad node
     * is unconditionally restored to 660 before doing anything else - if we
     * were mid-"locked" when killed, a stale chmod 000 must never survive.
     */
    fun setEnabled(context: Context, enabled: Boolean): ShellResult {
        RootShell.run("kill \$(pgrep -f $SCRIPT_NAME) 2>/dev/null; rm -f $LOCK; ${restoreKeypadCommand()}")

        return if (enabled) {
            val result = AssetInstaller.installFromAsset(context, TEMPLATE_ASSET, TARGET) { raw ->
                raw.replace("__POLL_INTERVAL_SEC__", POLL_INTERVAL_SEC)
                    .replace("__PIN_PREFS_FILE__", pinPrefsFile(context))
                    .replace("__PIN_INPUT_KEY__", Key2AccessibilityService.KEY_PIN_INPUT)
            }
            RootShell.run("nohup setsid sh $TARGET </dev/null >/dev/null 2>&1 &")
            result
        } else {
            AssetInstaller.removeFile(TARGET)
        }
    }
}
