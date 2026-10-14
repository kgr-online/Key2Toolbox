package com.kgr.key2toolbox.modules

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import com.kgr.key2toolbox.core.AssetInstaller
import com.kgr.key2toolbox.core.RootShell

/**
 * Root path for the lockscreen PIN digits: a daemon in /data/adb/service.d (from pin_keys_template.sh) that reads the
 * keyboard node and injects the digit for W E R / S D F / Z X C / Q.
 *
 * Why it exists next to the accessibility path: the accessibility service is not direct-boot aware, so after a reboot
 * it does not run until the first unlock, which is exactly when the PIN is needed; and every key press in every app
 * costs the service a binder round trip. With root available the daemon types the letter digits and the accessibility
 * path leaves those keys to it ([isActive], [isLetterDigit]); without root nothing changes.
 *
 * Limits: the daemon only observes (it cannot swallow a key), so raw digits, Enter and Delete stay on the
 * accessibility path.
 */
object PinKeysRoot {

    const val KEY_PIN_INPUT = "pin_input_enabled"
    /** Set once the daemon has been verified running; the accessibility service reads it per key (a pref read, no shell). */
    const val KEY_ROOT_ACTIVE = "pin_keys_root_active"

    private const val SCRIPT_NAME = "pin_keys.sh"
    private const val TARGET = "/data/adb/service.d/$SCRIPT_NAME"
    private const val TEMPLATE_ASSET = "pin_keys_template.sh"
    private const val LOCK = "/data/adb/.pin_keys.lock"

    fun pinWanted(prefs: SharedPreferences) = prefs.getBoolean(KEY_PIN_INPUT, true)

    /** The letter keys the daemon turns into digits; the accessibility path skips them while the daemon is active. */
    fun isLetterDigit(keyCode: Int) = when (keyCode) {
        KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_R,
        KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_F,
        KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_C -> true
        else -> false
    }

    fun isActive(prefs: SharedPreferences?) = prefs?.getBoolean(KEY_ROOT_ACTIVE, false) ?: false

    private fun isRunning(): Boolean =
        RootShell.run("pgrep -f $SCRIPT_NAME >/dev/null 2>&1 && echo yes || echo no").outString.trim() == "yes"

    /**
     * Brings the daemon in line with the pref: installed and running when PIN input is wanted, removed otherwise, and
     * restarted when the installed script is stale. Blocking (root shell): call off the main thread. Also publishes
     * [KEY_ROOT_ACTIVE]. A no-op returning false when root is unavailable.
     */
    @Synchronized
    fun reconcile(context: Context, prefs: SharedPreferences): Boolean {
        if (!RootShell.isRootAvailable()) {
            prefs.edit().putBoolean(KEY_ROOT_ACTIVE, false).apply()
            return false
        }
        if (!pinWanted(prefs)) {
            remove(prefs)
            return false
        }
        val healthy = isRunning() && AssetInstaller.matchesAsset(context, TEMPLATE_ASSET, TARGET)
        if (!healthy) {
            // kill + pgrep: pkill was unreliable on this toybox (see BtIdleController). setsid: survive the libsu session.
            RootShell.run("kill \$(pgrep -f $SCRIPT_NAME) 2>/dev/null; rm -f $LOCK")
            AssetInstaller.installFromAsset(context, TEMPLATE_ASSET, TARGET)
            RootShell.run("nohup setsid sh $TARGET </dev/null >/dev/null 2>&1 &")
        }
        val up = isRunning()
        prefs.edit().putBoolean(KEY_ROOT_ACTIVE, up).apply()
        return up
    }

    /** Stops and removes the daemon. */
    fun remove(prefs: SharedPreferences) {
        RootShell.run("kill \$(pgrep -f $SCRIPT_NAME) 2>/dev/null; rm -f $LOCK")
        AssetInstaller.removeFile(TARGET)
        prefs.edit().putBoolean(KEY_ROOT_ACTIVE, false).apply()
    }
}
