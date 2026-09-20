package com.kgr.key2toolbox.modules

import android.content.Context
import com.kgr.key2toolbox.core.AssetInstaller
import com.kgr.key2toolbox.core.RootShell
import com.kgr.key2toolbox.core.ShellResult

/**
 * DT2W (Double Tap to Wake).
 *
 * The KEY2 (athena) ships with at least two different touch-controller
 * families across production units, per the ROM maintainer's own
 * diagnostic script (athena-luna-panel-check.sh) and confirmed on-device:
 *  - FocalTech (focaltech_bbry driver, i2c client "focaltech_ts")
 *  - Synaptics DSX (synaptics_dsx_bbry driver, i2c client "dsx"/"dsx-i2c")
 *
 * Both drivers may be bound simultaneously (the kernel supports multiple
 * panel/touch variants), but only ONE actually has a registered, active
 * input device driving the display - determined per-unit, not something
 * we can hardcode. We detect which is live and target that one's own
 * gesture control node; the two use entirely different sysfs mechanics
 * (see FocalTechGesture / SynapticsGesture below).
 *
 * Must be applied while the screen is ON, so the driver configures gesture
 * detection as part of its normal suspend sequence. The value does not
 * persist across reboot, so persistence writes
 * /data/adb/service.d/dt2w.sh, which sleeps briefly then re-applies the
 * write (assumes screen is on shortly after boot).
 */
object Dt2wController {

    private const val SCRIPT_NAME = "dt2w.sh"
    private const val TARGET = "/data/adb/service.d/$SCRIPT_NAME"

    enum class State { ON, OFF, UNKNOWN }

    enum class ChipFamily { FOCALTECH, SYNAPTICS, UNKNOWN }

    private var resolvedChip: ChipFamily? = null

    /**
     * Detects which touch-controller family is actually live on this unit.
     * FocalTech is checked via its class-based sysfs device, which exists
     * unconditionally once fts_sysfs_init() runs during probe (source:
     * focaltech_bbry/focaltech_sysfs.c, tp_class_device_register()) -
     * independent of I2C bus/address, so this check alone is enough to
     * confirm FocalTech without needing to inspect /proc/bus/input/devices.
     * Falls back to searching for a Synaptics gesture-wake attribute.
     */
    private fun detectChip(): ChipFamily {
        resolvedChip?.let { return it }

        val hasFocalTech = RootShell.run(
            "[ -e ${FocalTechGesture.PATH} ] && echo yes"
        ).outString.trim() == "yes"
        if (hasFocalTech) {
            resolvedChip = ChipFamily.FOCALTECH
            return ChipFamily.FOCALTECH
        }

        if (SynapticsGesture.resolvePath() != null) {
            resolvedChip = ChipFamily.SYNAPTICS
            return ChipFamily.SYNAPTICS
        }

        return ChipFamily.UNKNOWN
    }

    /**
     * FocalTech gesture control (focaltech_bbry driver,
     * fts_wakeup_gesture_show/_store in focaltech_sysfs.c).
     *
     * Plain "0"/"1" on both read and write - no capability gating like
     * the Synaptics driver has. Uses the class-based path
     * (/sys/class/tp_device/tp_gesture/gesture_enable) rather than the
     * I2C-bus-relative one (/sys/bus/i2c/devices/4-0038/fts_wakeup_gesture)
     * since the class path doesn't depend on I2C bus/address numbering,
     * which has already proven unstable across kernel builds on this
     * device (see SynapticsGesture).
     *
     * Known caveat (not fixable from the app): dmesg on this device shows
     * frequent "fts_i2c_read_universal: i2c read error" /
     * "fts_gesture_read_data read touchdata failed" during gesture
     * polling on suspend/resume. The driver does correctly detect and
     * report double-taps when the read succeeds (confirmed via
     * gesture_id=0x24 / KEY_GESTURE_U in kernel logs, and the keylayout
     * for fts_input_device_B correctly flags that key WAKE) - so this
     * looks like an intermittent I2C reliability issue causing some
     * double-taps to be missed, not a userspace/app-level bug.
     */
    private object FocalTechGesture {
        const val PATH = "/sys/class/tp_device/tp_gesture/gesture_enable"

        fun currentState(): State {
            val out = RootShell.run("cat $PATH 2>/dev/null").outString.trim()
            return when (out) {
                "1" -> State.ON
                "0" -> State.OFF
                else -> State.UNKNOWN
            }
        }

        fun applyLiveOn(): ShellResult = RootShell.run("echo 1 > $PATH")
        fun applyLiveOff(): ShellResult = RootShell.run("echo 0 > $PATH")
    }

    /**
     * Synaptics DSX gesture control (synaptics_dsx_bbry driver,
     * synaptics_rmi4_wakeup_gesture_show/_store in synaptics_dsx_core.c).
     *
     * Neither the I2C bus/address nor the attribute name are stable
     * across kernel builds on this device:
     *  - 4.4-kernel ROM: i2c-4/4-0070, attribute "wake_gesture", plain 0/1
     *  - LOS 22.2/4.19-kernel ROM: i2c-1/1-0020, attribute
     *    "wakeup_gesture", multi-line status dump instead of a plain value
     * Resolved at runtime via find rather than hardcoded.
     *
     * KNOWN KERNEL ISSUE: the store function only applies a write when
     * rmi4_data->f11_wakeup_gesture || f12_wakeup_gesture is true. If
     * both are false (visible as f11_wake=0, f12_wake=0 in the status
     * dump), every write is a silent no-op - reports success, changes
     * nothing. See isGestureCapable(). NOTE: on this specific unit, the
     * Synaptics instance is bound but has no active input device at all
     * (FocalTech is the live touchscreen here) - this code path exists
     * for other KEY2 units where Synaptics is the active chip.
     */
    private object SynapticsGesture {
        private const val SEARCH_ROOT = "/sys/devices/platform/soc"
        private val ATTR_NAMES = listOf("wakeup_gesture", "wake_gesture")
        private var resolvedPath: String? = null

        fun resolvePath(): String? {
            resolvedPath?.let { return it }
            val nameClause = ATTR_NAMES.joinToString(" -o ") { "-name $it" }
            val out = RootShell.run(
                "find $SEARCH_ROOT -maxdepth 8 \\( $nameClause \\) 2>/dev/null | head -n1"
            ).outString.trim()
            if (out.isEmpty()) return null
            resolvedPath = out
            return out
        }

        fun currentState(): State {
            val path = resolvePath() ?: return State.UNKNOWN
            val out = RootShell.run("cat $path 2>/dev/null").outString

            when (out.trim()) {
                "1" -> return State.ON
                "0" -> return State.OFF
            }

            val doubleTapLine = out.lineSequence()
                .firstOrNull { it.trim().startsWith("wakeup_gesture.double_tap=") }
            return when (doubleTapLine?.substringAfter("=")?.trim()) {
                "1" -> State.ON
                "0" -> State.OFF
                else -> State.UNKNOWN
            }
        }

        fun isGestureCapable(): Boolean? {
            val path = resolvePath() ?: return null
            val out = RootShell.run("cat $path 2>/dev/null").outString
            val capLine = out.lineSequence()
                .firstOrNull { it.trim().startsWith("stay_awake=") }
                ?: return null
            val f11 = Regex("f11_wake=(\\d+)").find(capLine)?.groupValues?.get(1)
            val f12 = Regex("f12_wake=(\\d+)").find(capLine)?.groupValues?.get(1)
            if (f11 == null || f12 == null) return null
            return f11 != "0" || f12 != "0"
        }

        fun applyLiveOn(): ShellResult? = resolvePath()?.let { RootShell.run("echo 1 > $it") }
        fun applyLiveOff(): ShellResult? = resolvePath()?.let { RootShell.run("echo 0 > $it") }
    }

    private fun notFoundResult(): ShellResult = ShellResult(
        success = false,
        out = listOf("No supported gesture-wake touch controller found on this device")
    )

    fun currentState(): State = when (detectChip()) {
        ChipFamily.FOCALTECH -> FocalTechGesture.currentState()
        ChipFamily.SYNAPTICS -> SynapticsGesture.currentState()
        ChipFamily.UNKNOWN -> State.UNKNOWN
    }

    /**
     * Whether the driver reports gesture-wake capability. Only meaningful
     * for the Synaptics chip's capability-flag quirk (see SynapticsGesture);
     * FocalTech has no equivalent gating so this returns true when
     * FocalTech is detected, and null when nothing was detected at all.
     */
    fun isGestureCapable(): Boolean? = when (detectChip()) {
        ChipFamily.FOCALTECH -> true
        ChipFamily.SYNAPTICS -> SynapticsGesture.isGestureCapable()
        ChipFamily.UNKNOWN -> null
    }

    /** Enables DT2W immediately. Must be called with the screen ON. */
    fun applyLiveOn(): ShellResult = when (detectChip()) {
        ChipFamily.FOCALTECH -> FocalTechGesture.applyLiveOn()
        ChipFamily.SYNAPTICS -> SynapticsGesture.applyLiveOn() ?: notFoundResult()
        ChipFamily.UNKNOWN -> notFoundResult()
    }

    /** Disables DT2W immediately. */
    fun applyLiveOff(): ShellResult = when (detectChip()) {
        ChipFamily.FOCALTECH -> FocalTechGesture.applyLiveOff()
        ChipFamily.SYNAPTICS -> SynapticsGesture.applyLiveOff() ?: notFoundResult()
        ChipFamily.UNKNOWN -> notFoundResult()
    }

    fun isPersisted(): Boolean = AssetInstaller.fileExists(TARGET)

    /** Installs the boot-time script so DT2W survives reboots. */
    fun enablePersist(context: Context): ShellResult =
        AssetInstaller.installFromAsset(context, SCRIPT_NAME, TARGET)

    fun disablePersist(): ShellResult =
        AssetInstaller.removeFile(TARGET)
}
