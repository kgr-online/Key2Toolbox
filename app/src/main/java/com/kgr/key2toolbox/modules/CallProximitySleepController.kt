package com.kgr.key2toolbox.modules

import android.content.Context
import com.kgr.key2toolbox.core.AssetInstaller
import com.kgr.key2toolbox.core.RootShell
import com.kgr.key2toolbox.core.ShellResult

/**
 * Forces the screen to sleep when the proximity sensor reports "near" during
 * an active call.
 *
 * On this device/ROM, PowerManager's own PROXIMITY_SCREEN_OFF_WAKE_LOCK path
 * is broken: the dialer correctly acquires/releases
 * InCallProximitySensorWakeLock (confirmed via `dumpsys power` wake-lock log
 * showing clean ACQ/REL cycling around real calls), and mProximityPositive
 * flips correctly - but the screen never actually blanks; mWakefulness stays
 * Awake for the whole call. This bypasses PowerManager's broken handling by
 * issuing KEYCODE_SLEEP directly via root once "near" has held for a short
 * debounce window, so ear/pocket contact during a call reliably sleeps the
 * screen (and keyboard) instead of leaving both live.
 *
 * Root-script based daemon (same shape as [BtIdleController]), so no
 * foreground-service / phone-state permissions are needed in-app.
 *
 * See call_proximity_sleep_template.sh for the mCallState grep that is NOT
 * yet verified on-device - confirm before shipping.
 */
object CallProximitySleepController {

    private const val SCRIPT_NAME = "call_proximity_sleep.sh"
    private const val TARGET = "/data/adb/service.d/$SCRIPT_NAME"
    private const val TEMPLATE_ASSET = "call_proximity_sleep_template.sh"
    private const val LOCK = "/data/adb/.call_proximity_sleep.lock"

    // Seconds between checks while idle (no call in progress).
    private const val POLL_INTERVAL_SEC = "2"
    // Seconds between proximity checks once a call is active - tighter,
    // since this is the window that actually matters.
    private const val CALL_POLL_INTERVAL_SEC = "0.3"

    // Consecutive "near" reads (at CALL_POLL_INTERVAL_SEC spacing) required
    // before we force sleep - guards against a brief hand movement near the
    // sensor mid-call triggering an unwanted sleep.
    const val DEFAULT_DEBOUNCE_MS = 400
    val DEBOUNCE_OPTIONS = listOf(200, 400, 600, 1000)

    private fun debounceTicks(debounceMs: Int): Int {
        val tickMs = (CALL_POLL_INTERVAL_SEC.toDouble() * 1000).toInt()
        return maxOf(1, (debounceMs + tickMs - 1) / tickMs) // ceil
    }

    fun isPersisted(): Boolean = AssetInstaller.fileExists(TARGET)

    /** Whether the watchdog daemon is currently running. */
    fun isRunning(): Boolean =
        RootShell.run("pgrep -f $SCRIPT_NAME >/dev/null 2>&1 && echo yes || echo no")
            .outString.trim() == "yes"

    /** The debounce (ms) the persisted script targets, or null if not installed. */
    fun persistedDebounceMs(): Int? {
        val content = AssetInstaller.readFile(TARGET)
        val ticks = Regex("""DEBOUNCE_TICKS=(\d+)""")
            .find(content)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val tickMs = (CALL_POLL_INTERVAL_SEC.toDouble() * 1000).toInt()
        return ticks * tickMs
    }

    /**
     * Whether the installed daemon is both alive AND running the script we'd
     * install today for [debounceMs] - see [AssetInstaller.matchesAsset] for
     * why a bare "is it running" check isn't enough.
     */
    fun isHealthy(context: Context, debounceMs: Int): Boolean =
        isRunning() && AssetInstaller.matchesAsset(context, TEMPLATE_ASSET, TARGET) { raw ->
            raw.replace("__POLL_INTERVAL_SEC__", POLL_INTERVAL_SEC)
                .replace("__CALL_POLL_INTERVAL_SEC__", CALL_POLL_INTERVAL_SEC)
                .replace("__DEBOUNCE_TICKS__", debounceTicks(debounceMs).toString())
        }

    /**
     * Enables (installs + launches) or disables (stops + removes) the
     * watchdog. Any running instance is stopped first so a changed
     * [debounceMs] takes effect immediately.
     */
    fun setEnabled(
        context: Context,
        enabled: Boolean,
        debounceMs: Int = DEFAULT_DEBOUNCE_MS
    ): ShellResult {
        RootShell.run("kill \$(pgrep -f $SCRIPT_NAME) 2>/dev/null; rm -f $LOCK")

        return if (enabled) {
            val result = AssetInstaller.installFromAsset(context, TEMPLATE_ASSET, TARGET) { raw ->
                raw.replace("__POLL_INTERVAL_SEC__", POLL_INTERVAL_SEC)
                    .replace("__CALL_POLL_INTERVAL_SEC__", CALL_POLL_INTERVAL_SEC)
                    .replace("__DEBOUNCE_TICKS__", debounceTicks(debounceMs).toString())
            }
            RootShell.run("nohup setsid sh $TARGET </dev/null >/dev/null 2>&1 &")
            result
        } else {
            AssetInstaller.removeFile(TARGET)
        }
    }
}
