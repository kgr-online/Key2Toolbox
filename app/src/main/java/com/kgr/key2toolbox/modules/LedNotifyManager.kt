package com.kgr.key2toolbox.modules

import com.kgr.key2toolbox.core.RootShell

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
 * NOTE: the exact node names/paths here are the common defaults for this
 * class of hardware. If `find /sys/class/leds -maxdepth 2` on the actual
 * Key2 shows different names, update [SEPARATE_NODES] / [MULTI_DIR] below -
 * everything else (detection caching, on/off, trigger reset) stays the same.
 */
object LedNotifyManager {

    enum class LedMode { SEPARATE, MULTICOLOR, NONE }

    private const val RED_NODE = "/sys/class/leds/red"
    private const val GREEN_NODE = "/sys/class/leds/green"
    private const val BLUE_NODE = "/sys/class/leds/blue"
    private val SEPARATE_NODES = listOf(RED_NODE, GREEN_NODE, BLUE_NODE)

    private const val MULTI_DIR = "/sys/class/leds/rgb"

    @Volatile private var cachedMode: LedMode? = null

    /** Detects (and caches) which LED layout this device exposes. */
    fun detectMode(force: Boolean = false): LedMode {
        if (!force) cachedMode?.let { return it }

        val probe = RootShell.run(
            "if [ -e $RED_NODE/brightness ] && [ -e $GREEN_NODE/brightness ] && " +
                "[ -e $BLUE_NODE/brightness ]; then echo separate; " +
                "elif [ -e $MULTI_DIR/multi_intensity ]; then echo multicolor; " +
                "else echo none; fi"
        )
        val mode = when (probe.outString.trim()) {
            "separate" -> LedMode.SEPARATE
            "multicolor" -> LedMode.MULTICOLOR
            else -> LedMode.NONE
        }
        cachedMode = mode
        return mode
    }

    fun isAvailable(): Boolean = detectMode() != LedMode.NONE

    /** Lights the LED a solid [color] (0xRRGGBB). No-ops if no LED was detected. */
    fun setColor(color: Int) {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        when (detectMode()) {
            LedMode.SEPARATE -> RootShell.run(
                // Reset any active blink trigger first, so brightness holds
                // steady instead of being overridden by a timer trigger.
                SEPARATE_NODES.joinToString(" ; ") { "echo none > $it/trigger 2>/dev/null" } +
                    " ; echo $r > $RED_NODE/brightness" +
                    " ; echo $g > $GREEN_NODE/brightness" +
                    " ; echo $b > $BLUE_NODE/brightness"
            )
            LedMode.MULTICOLOR -> RootShell.run(
                "echo none > $MULTI_DIR/trigger 2>/dev/null ; " +
                    "echo \"$r $g $b\" > $MULTI_DIR/multi_intensity ; " +
                    "echo 255 > $MULTI_DIR/brightness"
            )
            LedMode.NONE -> Unit
        }
    }

    /** Turns the LED off. */
    fun off() {
        when (detectMode()) {
            LedMode.SEPARATE -> RootShell.run(
                SEPARATE_NODES.joinToString(" ; ") { "echo none > $it/trigger 2>/dev/null" } +
                    " ; " + SEPARATE_NODES.joinToString(" ; ") { "echo 0 > $it/brightness" }
            )
            LedMode.MULTICOLOR -> RootShell.run(
                "echo none > $MULTI_DIR/trigger 2>/dev/null ; echo 0 > $MULTI_DIR/brightness"
            )
            LedMode.NONE -> Unit
        }
    }

    /**
     * Blinks the LED [color] using the kernel's built-in "timer" trigger
     * (`delay_on`/`delay_off` in ms) rather than a software loop - this way
     * the pattern keeps running even if the app process is later killed by
     * doze/battery optimization, since the kernel driver owns the timing.
     *
     * Write order matters here: the LED core snapshots whatever brightness is
     * *currently set* as the trigger's "on" level at the moment `trigger` is
     * switched to `timer`. Writing the color after activating the trigger
     * captures stale (usually 0, leftover from a prior [off]) brightness as
     * the on-level, so the LED never actually blinks - it just shows solid
     * color from the direct write while the trigger silently blinks between
     * 0 and 0. So: color first (while still on trigger=none), *then* switch
     * to `timer`, *then* configure timing.
     *
     * On the "separate" layout the same on/off timing is written to all three
     * channel nodes so R/G/B toggle in lockstep and the blended color holds
     * steady while lit. If a device's driver doesn't expose delay_on/delay_off
     * (some multicolor implementations don't), those writes silently fail and
     * the LED falls back to solid - still correct, just not blinking.
     */
    fun setBlinking(color: Int, onMs: Int, offMs: Int) {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        when (detectMode()) {
            LedMode.SEPARATE -> RootShell.run(
                // 1) Solid color first, with trigger still "none".
                SEPARATE_NODES.joinToString(" ; ") { "echo none > $it/trigger 2>/dev/null" } +
                    " ; echo $r > $RED_NODE/brightness" +
                    " ; echo $g > $GREEN_NODE/brightness" +
                    " ; echo $b > $BLUE_NODE/brightness" +
                    // 2) Now activate the timer trigger - it snapshots the
                    //    color we just set as its "on" level.
                    " ; " + SEPARATE_NODES.joinToString(" ; ") { "echo timer > $it/trigger 2>/dev/null" } +
                    // 3) Configure on/off timing.
                    " ; " + SEPARATE_NODES.joinToString(" ; ") {
                        "echo $onMs > $it/delay_on 2>/dev/null ; echo $offMs > $it/delay_off 2>/dev/null"
                    }
            )
            LedMode.MULTICOLOR -> RootShell.run(
                "echo none > $MULTI_DIR/trigger 2>/dev/null ; " +
                    "echo \"$r $g $b\" > $MULTI_DIR/multi_intensity ; " +
                    "echo 255 > $MULTI_DIR/brightness ; " +
                    "echo timer > $MULTI_DIR/trigger 2>/dev/null ; " +
                    "echo $onMs > $MULTI_DIR/delay_on 2>/dev/null ; " +
                    "echo $offMs > $MULTI_DIR/delay_off 2>/dev/null"
            )
            LedMode.NONE -> Unit
        }
    }
}
