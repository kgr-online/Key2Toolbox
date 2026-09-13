package com.kgr.key2toolbox.modules

import android.content.Context
import com.kgr.key2toolbox.core.AssetInstaller
import com.kgr.key2toolbox.core.RootShell
import com.kgr.key2toolbox.core.ShellResult

/**
 * DT2W (Double Tap to Wake), via the touchscreen's gesture-wake sysfs
 * attribute (synaptics_dsx driver).
 *
 * Must be applied while the screen is ON, so the driver configures gesture
 * detection as part of its normal suspend sequence. The value does not
 * persist across reboot, so persistence writes
 * /data/adb/service.d/dt2w.sh, which sleeps briefly then re-applies the
 * write (assumes screen is on shortly after boot).
 *
 * Neither the I2C bus/address nor the attribute name are stable across
 * kernel builds:
 *  - On the 4.4-kernel ROM the touchscreen was at i2c-4/4-0070 and the
 *    attribute was named "wake_gesture".
 *  - On the LOS 22.2 / 4.19-kernel ROM (post kernel 4.4->4.19 update) it
 *    moved to i2c-1/1-0020 and the attribute is named "wakeup_gesture".
 * Rather than hardcode either, we search for the attribute under
 * input/inputN wherever it currently lives, accepting both known names.
 * Result is cached for the process lifetime.
 */
object Dt2wController {

    private const val SCRIPT_NAME = "dt2w.sh"
    private const val TARGET = "/data/adb/service.d/$SCRIPT_NAME"
    private const val SEARCH_ROOT = "/sys/devices/platform/soc"

    // Known attribute names across kernel/driver builds, most recent first.
    private val ATTR_NAMES = listOf("wakeup_gesture", "wake_gesture")

    enum class State { ON, OFF, UNKNOWN }

    private var resolvedPath: String? = null

    /**
     * Locates the gesture-wake sysfs attribute by searching under
     * SEARCH_ROOT for any of the known attribute names, rather than
     * assuming a fixed I2C bus/address or attribute name.
     */
    private fun resolvePath(): String? {
        resolvedPath?.let { return it }
        val nameClause = ATTR_NAMES.joinToString(" -o ") { "-name $it" }
        val out = RootShell.run(
            "find $SEARCH_ROOT -maxdepth 8 \\( $nameClause \\) 2>/dev/null | head -n1"
        ).outString.trim()
        if (out.isEmpty()) return null
        resolvedPath = out
        return out
    }

    /**
     * Reads the live gesture-wake sysfs value.
     *
     * On the pre-rebuild (4.4 kernel) driver this attribute was a plain
     * "0" or "1". On the post-rebuild (4.19 kernel) driver, reading it
     * instead returns a multi-line status dump, e.g.:
     *
     *   bdata->wg_enabled=0
     *   wakeup_gesture.swipe=0
     *   wakeup_gesture.double_tap=0
     *
     *   power_state=4, next=0, suspend=1
     *   stay_awake=0, f11_wake=0, f12_wake=0
     *
     * We handle both: try a plain 0/1 match first, then fall back to
     * parsing the "wakeup_gesture.double_tap=" line from the dump.
     */
    fun currentState(): State {
        val path = resolvePath() ?: return State.UNKNOWN
        val out = RootShell.run("cat $path 2>/dev/null").outString

        val trimmed = out.trim()
        when (trimmed) {
            "1" -> return State.ON
            "0" -> return State.OFF
        }

        val doubleTapLine = out.lineSequence()
            .firstOrNull { it.trim().startsWith("wakeup_gesture.double_tap=") }
        val value = doubleTapLine?.substringAfter("=")?.trim()
        return when (value) {
            "1" -> State.ON
            "0" -> State.OFF
            else -> State.UNKNOWN
        }
    }

    private fun notFoundResult(): ShellResult = ShellResult(
        success = false,
        out = listOf("gesture-wake attribute not found under $SEARCH_ROOT")
    )

    /** Enables DT2W immediately. Must be called with the screen ON. */
    fun applyLiveOn(): ShellResult {
        val path = resolvePath() ?: return notFoundResult()
        return RootShell.run("echo 1 > $path")
    }

    /** Disables DT2W immediately. */
    fun applyLiveOff(): ShellResult {
        val path = resolvePath() ?: return notFoundResult()
        return RootShell.run("echo 0 > $path")
    }

    fun isPersisted(): Boolean = AssetInstaller.fileExists(TARGET)

    /** Installs the boot-time script so DT2W survives reboots. */
    fun enablePersist(context: Context): ShellResult =
        AssetInstaller.installFromAsset(context, SCRIPT_NAME, TARGET)

    fun disablePersist(): ShellResult =
        AssetInstaller.removeFile(TARGET)
}
