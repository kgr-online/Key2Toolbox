package com.kgr.key2toolbox.modules

import com.kgr.key2toolbox.core.RootShell

/**
 * Manages settings in the k2prodfix Magisk module (id=bb-prodfix).
 * Reads/writes /data/adb/modules/bb-prodfix/system.prop and service.sh directly.
 * Changes to system.prop require a reboot; swappiness in service.sh takes effect
 * on next boot (or can be applied live via sysctl).
 */
object K2PFController {

    private const val MODULE_DIR = "/data/adb/modules/bb-prodfix"
    private const val SYSTEM_PROP = "$MODULE_DIR/system.prop"
    private const val SERVICE_SH = "$MODULE_DIR/service.sh"

    // ── Detection ─────────────────────────────────────────────────────────────

    fun isInstalled(): Boolean =
        RootShell.run("[ -d '$MODULE_DIR' ] && echo yes || echo no")
            .outString.trim() == "yes"

    // ── Readers ───────────────────────────────────────────────────────────────

    private fun readProp(): String =
        RootShell.run("cat '$SYSTEM_PROP' 2>/dev/null").outString

    private fun readService(): String =
        RootShell.run("cat '$SERVICE_SH' 2>/dev/null").outString

    /** true if the ro.product.* BB identity props are present */
    fun isProductPropsEnabled(): Boolean =
        readProp().contains("ro.product.brand=blackberry")

    /** Adds or removes the full ro.product.* BlackBerry identity block */
    fun setProductProps(enabled: Boolean) {
        setPropBlock(
            marker = "BlackBerry device identity",
            lines = listOf(
                "ro.product.brand=blackberry",
                "ro.product.device=bbf100",
                "ro.product.manufacturer=BlackBerry",
                "ro.product.model=KEY2",
                "ro.product.system.brand=blackberry",
                "ro.product.system.device=bbf100",
                "ro.product.system.manufacturer=BlackBerry",
                "ro.product.system.model=KEY2",
                "ro.product.system_ext.brand=blackberry",
                "ro.product.system_ext.device=bbf100",
                "ro.product.system_ext.manufacturer=BlackBerry",
                "ro.product.system_ext.model=KEY2",
                "ro.product.odm.brand=blackberry",
                "ro.product.odm.device=bbf100",
                "ro.product.odm.manufacturer=BlackBerry",
                "ro.product.odm.model=KEY2",
                "ro.product.vendor.brand=blackberry",
                "ro.product.vendor.device=bbf100",
                "ro.product.vendor.manufacturer=BlackBerry",
                "ro.product.vendor.model=KEY2",
                "ro.product.vendor_dlkm.brand=blackberry",
                "ro.product.vendor_dlkm.device=bbf100",
                "ro.product.vendor_dlkm.manufacturer=BlackBerry",
                "ro.product.vendor_dlkm.model=KEY2"
            ),
            enable = enabled
        )
    }

    /** true if the A2DP offload props are present and enabled */
    fun isA2dpOffloadEnabled(): Boolean {
        val content = readProp()
        return content.contains("vendor.audio.feature.a2dp_offload.enable=true") &&
            content.contains("ro.bluetooth.a2dp_offload.supported=1")
    }

    /** true if the higher volume step props are present */
    fun isVolumeStepsEnabled(): Boolean {
        val content = readProp()
        return content.contains("ro.config.vc_call_vol_steps=14") &&
            content.contains("ro.config.media_vol_steps=30")
    }

    /** true if the SurfaceFlinger triple-buffer prop is present */
    fun isTripleBufferingEnabled(): Boolean =
        readProp().contains("ro.surface_flinger.max_frame_buffer_acquired_buffers=3")

    /** true if the background app limit prop is present */
    fun isBgAppLimitEnabled(): Boolean =
        readProp().contains("ro.vendor.qti.sys.fw.bg_apps_limit=60")

    /** Swappiness value set in service.sh, or null if not present */
    fun serviceSwappiness(): Int? {
        val content = readService()
        return Regex("""sysctl\s+-w\s+vm\.swappiness=(\d+)""")
            .find(content)?.groupValues?.get(1)?.toIntOrNull()
    }

    // ── Writers ───────────────────────────────────────────────────────────────

    private fun setPropBlock(marker: String, lines: List<String>, enable: Boolean) {
        val current = readProp()
        val newContent = if (enable) {
            if (current.contains(lines.first())) current  // already there
            else current.trimEnd() + "\n\n# $marker\n" + lines.joinToString("\n") + "\n"
        } else {
            // Remove the block: strip the comment line and all matching prop lines
            var result = current
            result = result.replace("\n# $marker\n", "\n")
            lines.forEach { line -> result = result.replace("\n$line", "") }
            result
        }
        writeProp(newContent)
    }

    private fun writeProp(content: String) {
        // Write via tee to avoid shell quoting issues with special chars
        val escaped = content.replace("'", "'\\''")
        RootShell.run("printf '%s' '$escaped' > '$SYSTEM_PROP'")
    }

    fun setA2dpOffload(enabled: Boolean) {
        setPropBlock(
            marker = "Bluetooth A2DP offload",
            lines = listOf(
                "vendor.audio.feature.a2dp_offload.enable=true",
                "ro.bluetooth.a2dp_offload.supported=1",
                "persist.bluetooth.a2dp_offload.disabled=false",
                "persist.bt.a2dp.aac_disable=false"
            ),
            enable = enabled
        )
        // persist props can be applied live
        if (enabled) {
            RootShell.run("setprop vendor.audio.feature.a2dp_offload.enable true")
            RootShell.run("setprop ro.bluetooth.a2dp_offload.supported 1")
            RootShell.run("setprop persist.bluetooth.a2dp_offload.disabled false")
            RootShell.run("setprop persist.bt.a2dp.aac_disable false")
        }
    }

    fun setVolumeSteps(enabled: Boolean) {
        setPropBlock(
            marker = "Volume steps",
            lines = listOf(
                "ro.config.vc_call_vol_steps=14",
                "ro.config.media_vol_steps=30"
            ),
            enable = enabled
        )
        // ro.* props are read-only after boot — reboot required
    }

    fun setTripleBuffering(enabled: Boolean) {
        setPropBlock(
            marker = "SurfaceFlinger triple buffering",
            lines = listOf("ro.surface_flinger.max_frame_buffer_acquired_buffers=3"),
            enable = enabled
        )
    }

    fun setBgAppLimit(enabled: Boolean) {
        setPropBlock(
            marker = "Background app limit",
            lines = listOf("ro.vendor.qti.sys.fw.bg_apps_limit=60"),
            enable = enabled
        )
    }

    /**
     * Sets swappiness in k2prodfix's service.sh.
     * If [value] is null, removes the swappiness line entirely (Key2Toolbox takes over).
     * Also applies live via sysctl immediately.
     */
    fun setSwappiness(value: Int?) {
        val current = readService()
        val newContent = if (value == null) {
            // Remove swappiness line
            current.lines()
                .filter { !it.trim().startsWith("sysctl -w vm.swappiness") }
                .joinToString("\n")
                .trimEnd() + "\n"
        } else {
            if (current.contains("vm.swappiness")) {
                // Replace existing
                current.lines().joinToString("\n") { line ->
                    if (line.trim().startsWith("sysctl -w vm.swappiness"))
                        "sysctl -w vm.swappiness=$value"
                    else line
                }.trimEnd() + "\n"
            } else {
                // Append
                current.trimEnd() + "\nsysctl -w vm.swappiness=$value\n"
            }
        }
        val escaped = newContent.replace("'", "'\\''")
        RootShell.run("printf '%s' '$escaped' > '$SERVICE_SH'")
        // Apply live
        val liveValue = value ?: 60
        RootShell.run("sysctl -w vm.swappiness=$liveValue")
    }
}
