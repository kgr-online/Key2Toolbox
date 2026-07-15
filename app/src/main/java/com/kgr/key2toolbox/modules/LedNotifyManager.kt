package com.kgr.key2toolbox.modules

import android.util.Log
import com.kgr.key2toolbox.core.RootShell
import com.kgr.key2toolbox.core.ShellResult
import java.util.concurrent.Executors

/**
 * Root-level control of the notification RGB LED, bypassing LineageOS's own
 * per-app light-color settings entirely (that path quantizes arbitrary RGB
 * values down to whatever discrete colors its lookup table knows about, and
 * on the Key2 that table doesn't match the actual hardware - colors come out
 * wrong except for the few apps, like WhatsApp, that happen to pick a value
 * the table maps correctly).
 *
 * Writing directly to the LED class sysfs nodes sidesteps that layer, the
 * same way WhatsApp's own [android.app.NotificationChannel.setLightColor]
 * request reaches the driver unmodified.
 *
 * Two on-device layouts are supported, auto-detected the first time a color
 * is set:
 *  - "separate": three independent LED class devices, one per channel
 *    (`/sys/class/leds/red|green|blue/brightness`, each 0-255). Common on
 *    older Qualcomm QPNP flash-LED based notification LEDs.
 *  - "multicolor": a single combined LED class device using the kernel's
 *    multicolor framework (`/sys/class/leds/rgb/multi_intensity` as a
 *    space-separated "R G B" triple, gated by `/sys/class/leds/rgb/brightness`).
 *
 * If neither is found, [detectMode] reports [LedMode.NONE] and callers should
 * disable the feature rather than silently no-op every write.
 *
 * **Every write is serialized through [ledExecutor]**, a single dedicated
 * thread, regardless of which caller's thread invokes [setColor]/[off] - the
 * notification listener service's blink loop and the LED Notify screen's
 * per-app test-preview button both end up going through the exact same
 * queue. Without this, two callers writing to the LED at nearly the same
 * moment (e.g. testing a swatch in the UI while a real notification is
 * actively blinking) race on the same sysfs nodes, which produces
 * intermittent-looking corruption (stuck on, stuck off, rapid flicker) as
 * the two writers drift in and out of phase with each other.
 *
 * **Every write's latency is logged** (tag `LedNotifyManager`, view via
 * `adb logcat -s LedNotifyManager`) - blink timing is still reported as
 * uneven even after fixing the concurrency race above, and `RootShell`'s own
 * docs already flag that libsu may not reliably reuse a cached shell session
 * on FolkPatch/APatch's `su` ("each exec() may run in a fresh shell
 * invocation depending on libsu's pooling"). If per-write latency is itself
 * wildly inconsistent (say, swinging between 10ms and 300ms+), that's the
 * actual cause of the uneven blink - no amount of scheduling on the caller
 * side can smooth out a write whose own duration varies that much, it can
 * only avoid compounding the drift afterward. These logs are the way to
 * confirm or rule that out with real numbers instead of guessing further.
 *
 * NOTE: the exact node names/paths here are the common defaults for this
 * class of hardware. If `find /sys/class/leds -maxdepth 2` on the actual
 * Key2 shows different names, update [SEPARATE_NODES] / [MULTI_DIR] below -
 * everything else (detection caching, on/off, trigger reset) stays the same.
 */
object LedNotifyManager {

    enum class LedMode { SEPARATE, MULTICOLOR, NONE }

    private const val TAG = "LedNotifyManager"

    private const val RED_NODE = "/sys/class/leds/red"
    private const val GREEN_NODE = "/sys/class/leds/green"
    private const val BLUE_NODE = "/sys/class/leds/blue"
    private val SEPARATE_NODES = listOf(RED_NODE, GREEN_NODE, BLUE_NODE)

    private const val MULTI_DIR = "/sys/class/leds/rgb"

    @Volatile private var cachedMode: LedMode? = null

    // Single dedicated thread every LED write is funneled through, no matter
    // which caller/dispatcher/thread invokes setColor()/off() - see class doc.
    private val ledExecutor = Executors.newSingleThreadExecutor()

    /** Detects (and caches) which LED layout this device exposes. */
    fun detectMode(force: Boolean = false): LedMode {
        if (!force) cachedMode?.let { return it }

        val probe = timedRun("detect") {
            RootShell.run(
                "if [ -e $RED_NODE/brightness ] && [ -e $GREEN_NODE/brightness ] && " +
                    "[ -e $BLUE_NODE/brightness ]; then echo separate; " +
                    "elif [ -e $MULTI_DIR/multi_intensity ]; then echo multicolor; " +
                    "else echo none; fi"
            )
        }
        val mode = when (probe.outString.trim()) {
            "separate" -> LedMode.SEPARATE
            "multicolor" -> LedMode.MULTICOLOR
            else -> LedMode.NONE
        }
        cachedMode = mode
        return mode
    }

    fun isAvailable(): Boolean = detectMode() != LedMode.NONE

    /**
     * Lights the LED a solid [color] (0xRRGGBB). No-ops if no LED was
     * detected. Blocks the calling thread until the write has actually run
     * on [ledExecutor] - callers on a coroutine dispatcher should invoke this
     * from a background context (e.g. `Dispatchers.IO`), same as before.
     */
    fun setColor(color: Int) {
        ledExecutor.submit { setColorInternal(color) }.get()
    }

    /** Turns the LED off. Same threading contract as [setColor]. */
    fun off() {
        ledExecutor.submit { offInternal() }.get()
    }

    private fun setColorInternal(color: Int) {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        when (detectMode()) {
            LedMode.SEPARATE -> timedRun("setColor") {
                RootShell.run(
                    // Reset any active blink trigger first, so brightness holds
                    // steady instead of being overridden by a timer trigger.
                    SEPARATE_NODES.joinToString(" ; ") { "echo none > $it/trigger 2>/dev/null" } +
                        " ; echo $r > $RED_NODE/brightness" +
                        " ; echo $g > $GREEN_NODE/brightness" +
                        " ; echo $b > $BLUE_NODE/brightness"
                )
            }
            LedMode.MULTICOLOR -> timedRun("setColor") {
                RootShell.run(
                    "echo none > $MULTI_DIR/trigger 2>/dev/null ; " +
                        "echo \"$r $g $b\" > $MULTI_DIR/multi_intensity ; " +
                        "echo 255 > $MULTI_DIR/brightness"
                )
            }
            LedMode.NONE -> Unit
        }
    }

    private fun offInternal() {
        when (detectMode()) {
            LedMode.SEPARATE -> timedRun("off") {
                RootShell.run(
                    SEPARATE_NODES.joinToString(" ; ") { "echo none > $it/trigger 2>/dev/null" } +
                        " ; " + SEPARATE_NODES.joinToString(" ; ") { "echo 0 > $it/brightness" }
                )
            }
            LedMode.MULTICOLOR -> timedRun("off") {
                RootShell.run(
                    "echo none > $MULTI_DIR/trigger 2>/dev/null ; echo 0 > $MULTI_DIR/brightness"
                )
            }
            LedMode.NONE -> Unit
        }
    }

    /** Runs [block], logging how long the actual root shell call took. */
    private inline fun timedRun(label: String, block: () -> ShellResult): ShellResult {
        val start = System.nanoTime()
        val result = block()
        val ms = (System.nanoTime() - start) / 1_000_000
        Log.d(TAG, "$label took ${ms}ms success=${result.success}")
        return result
    }

    // NOTE: there is deliberately no setBlinking()/kernel-timer-trigger method
    // here. This device's QPNP RGB LED driver doesn't register the generic
    // Linux `timer` trigger at all - confirmed via
    // `cat /sys/class/leds/red/trigger`, whose available list is entirely
    // fixed hardware triggers (`rfkill-*`, `flash*_trigger`, `torch*_trigger`,
    // `switch*_trigger`, `*-online`, `battery-*`, `mmc*`, `bms-online`) with
    // no generic on/off timer among them. Writing `timer` to that node is
    // silently rejected (invalid argument), so a solid color is the only
    // thing this hardware can do on its own. Blinking is implemented in
    // software instead, in [com.kgr.key2toolbox.service.LedNotifyListenerService],
    // by alternating [setColor] and [off] on a coroutine timer.
}
