package com.kgr.key2toolbox.modules

import android.content.Context
import com.kgr.key2toolbox.core.AssetInstaller
import com.kgr.key2toolbox.core.RootShell
import com.kgr.key2toolbox.core.ShellResult

/**
 * ZRAM compression algorithm + size control: Off / 2GB / 3GB / 4GB.
 *
 * Persistence writes /data/adb/service.d/zram_size.sh from zram_template.sh
 * with __ALGO__ and __SIZE_MB__ substituted. Live apply runs
 * swapoff -> reset -> comp_algorithm -> disksize -> mkswap -> swapon
 * directly, which can briefly disable swap - the UI should warn before
 * doing this live.
 */
object ZramController {

    private const val SCRIPT_NAME = "zram_size.sh"
    private const val TARGET = "/data/adb/service.d/$SCRIPT_NAME"
    private const val TEMPLATE_ASSET = "zram_template.sh"

    const val DEFAULT_ALGORITHM = "zstd"

    enum class Size(val mb: Int, val label: String) {
        OFF(0, "Off"),
        GB1_5(1536, "1.5 GB"),
        GB2(2048, "2 GB"),
        GB3(3072, "3 GB"),
        GB4(4096, "4 GB");

        companion object {
            /** The bucket closest to [mb] (within ~64 MB), or null if nothing lines up. */
            fun nearest(mb: Int): Size? {
                if (mb <= 0) return OFF
                return entries.filter { it.mb > 0 }
                    .minByOrNull { kotlin.math.abs(it.mb - mb) }
                    ?.takeIf { kotlin.math.abs(it.mb - mb) <= 64 }
            }
        }
    }

    /**
     * The ZRAM config the ROM itself ships, read live via root: the fstab `zramsize=`, the
     * `comp_algorithm` write in an init .rc, and the last `swappiness` write in the Qualcomm
     * post-boot script. Each field is null when it can't be parsed. This is *not* the app's
     * persisted config - it's what you get if the app leaves ZRAM alone.
     */
    data class RomDefaults(val sizeMb: Int?, val algorithm: String?, val swappiness: Int?) {
        val size: Size? get() = sizeMb?.let { Size.nearest(it) }
    }

    fun romDefaults(): RomDefaults {
        val fstab = RootShell.run(
            "cat /vendor/etc/fstab.* /odm/etc/fstab.* /system/etc/fstab.* 2>/dev/null"
        ).outString
        val sizeMb = Regex("""zramsize=(\d+)""").find(fstab)
            ?.groupValues?.get(1)?.toLongOrNull()
            ?.let { (it / 1024 / 1024).toInt() }

        // Match only a real `write ... comp_algorithm <algo>` line, not the "read-only"
        // in an adjacent comment.
        val algorithm = RootShell.run(
            "grep -hoE 'comp_algorithm[[:space:]]+(lzo-rle|lzo|lz4hc|lz4|zstd|deflate|842)' " +
                "/vendor/etc/init/hw/*.rc /odm/etc/init/hw/*.rc /system/etc/init/hw/*.rc 2>/dev/null"
        ).outString.trim().lines().lastOrNull { it.isNotBlank() }
            ?.substringAfterLast(' ')?.takeIf { it.isNotBlank() }

        val swappiness = RootShell.run(
            "grep -hoE 'echo[[:space:]]+[0-9]+[[:space:]]*>[[:space:]]*/proc/sys/vm/swappiness' " +
                "/vendor/bin/init.qcom.post_boot.sh /vendor/bin/*.sh 2>/dev/null"
        ).outString.trim().lines().lastOrNull { it.isNotBlank() }
            ?.let { Regex("""(\d+)""").find(it)?.value?.toIntOrNull() }

        return RomDefaults(sizeMb, algorithm, swappiness)
    }

    fun isPersisted(): Boolean = AssetInstaller.fileExists(TARGET)

    /** Inspects the persisted script (if any) to determine which size it targets. */
    fun persistedSize(): Size? {
        val content = AssetInstaller.readFile(TARGET)
        if (content.isBlank()) return null
        return Size.entries.firstOrNull { it.mb > 0 && content.contains("${it.mb}m") }
    }

    /** Inspects the persisted script (if any) to determine which algorithm it sets. */
    fun persistedAlgorithm(): String? {
        val content = AssetInstaller.readFile(TARGET)
        return Regex("""echo\s+(\S+)\s*>\s*/sys/block/zram0/comp_algorithm""")
            .find(content)?.groupValues?.get(1)
    }

    /** Inspects the persisted script (if any) to determine which swappiness it targets. */
    fun persistedSwappiness(): Int? {
        val content = AssetInstaller.readFile(TARGET)
        return Regex("""echo\s+(\d+)\s*>\s*/proc/sys/vm/swappiness""")
            .find(content)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Current live swappiness, or null if it cannot be read. */
    fun currentLiveSwappiness(): Int? {
        val out = RootShell.run("cat /proc/sys/vm/swappiness 2>/dev/null").outString.trim()
        return out.toIntOrNull()
    }

    /** Current live zram0 disksize in bytes, or null if zram0 isn't active/present. */
    fun currentLiveSizeBytes(): Long? {
        val out = RootShell.run("cat /sys/block/zram0/disksize 2>/dev/null").outString.trim()
        return out.toLongOrNull()
    }

    /**
     * The live zram0 size as the closest [Size] bucket, or null if zram0 isn't active or
     * doesn't line up with any bucket. Used to seed the UI when the app hasn't persisted
     * anything.
     */
    fun currentLiveSize(): Size? {
        val mb = (currentLiveSizeBytes() ?: return null) / 1024 / 1024
        return Size.nearest(mb.toInt())
    }

    /**
     * Algorithms this kernel supports, read from comp_algorithm. The kernel
     * reports them space-separated with the currently active one in
     * brackets, e.g. "lzo lzo-rle [zstd] lz4 lz4hc deflate 842".
     */
    fun availableAlgorithms(): List<String> {
        val raw = RootShell.run("cat /sys/block/zram0/comp_algorithm 2>/dev/null").outString.trim()
        if (raw.isBlank()) return listOf(DEFAULT_ALGORITHM)
        return raw.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map { it.trim('[', ']') }
    }

    /** The algorithm currently active on the live zram0 device (the bracketed one), if any. */
    fun currentAlgorithm(): String? {
        val raw = RootShell.run("cat /sys/block/zram0/comp_algorithm 2>/dev/null").outString.trim()
        return raw.split(Regex("\\s+")).firstOrNull { it.startsWith("[") }?.trim('[', ']')
    }

    /**
     * Updates the persisted script for [size]/[algorithm]/[swappiness]. If [applyLive] is
     * true, also applies the change immediately (swapoff/reset/algo/resize/swapon/swappiness).
     * [algorithm] and [swappiness] are ignored when [size] is OFF.
     */
    fun setSize(context: Context, size: Size, algorithm: String, swappiness: Int, applyLive: Boolean): ShellResult {
        val persistResult = if (size == Size.OFF) {
            AssetInstaller.removeFile(TARGET)
        } else {
            AssetInstaller.installFromAsset(context, TEMPLATE_ASSET, TARGET) { raw ->
                raw.replace("__SIZE_MB__", size.mb.toString())
                    .replace("__ALGO__", algorithm)
                    .replace("__SWAPPINESS__", swappiness.toString())
            }
        }

        if (applyLive) {
            if (size == Size.OFF) {
                RootShell.run(
                    "swapoff /dev/block/zram0 2>/dev/null; " +
                        "echo 1 > /sys/block/zram0/reset 2>/dev/null"
                )
            } else {
                RootShell.run(
                    "swapoff /dev/block/zram0 2>/dev/null; " +
                        "echo 1 > /sys/block/zram0/reset; " +
                        "echo $algorithm > /sys/block/zram0/comp_algorithm; " +
                        "echo ${size.mb}m > /sys/block/zram0/disksize && " +
                        "mkswap /dev/block/zram0 && " +
                        "swapon /dev/block/zram0 && " +
                        "echo $swappiness > /proc/sys/vm/swappiness"
                )
            }
        } else if (size != Size.OFF) {
            // Apply swappiness live anyway as it's safe (unlike resetting ZRAM).
            RootShell.run("echo $swappiness > /proc/sys/vm/swappiness")
        }

        return persistResult
    }
}
