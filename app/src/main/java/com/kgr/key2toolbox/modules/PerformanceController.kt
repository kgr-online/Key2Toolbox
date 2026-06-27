package com.kgr.key2toolbox.modules

import android.content.Context
import com.kgr.key2toolbox.core.AssetInstaller
import com.kgr.key2toolbox.core.RootShell
import com.kgr.key2toolbox.core.ShellResult

/**
 * Controller for CPU and input boost performance tuning parameters.
 * Persists settings to /data/adb/service.d/cpu_performance.sh.
 */
object PerformanceController {

    private const val SCRIPT_NAME = "cpu_performance.sh"
    private const val TARGET = "/data/adb/service.d/$SCRIPT_NAME"
    private const val TEMPLATE_ASSET = "performance_template.sh"

    const val DEFAULT_UP_RATE_LIMIT = 500
    const val TUNED_UP_RATE_LIMIT = 2000

    const val DEFAULT_INPUT_BOOST_FREQ = 1401600
    const val TUNED_INPUT_BOOST_FREQ = 1113600

    const val DEFAULT_INPUT_BOOST_MS = 40
    const val TUNED_INPUT_BOOST_MS = 20

    fun isPersisted(): Boolean = AssetInstaller.fileExists(TARGET)

    fun persistedUpRateLimit(): Int? {
        val content = AssetInstaller.readFile(TARGET)
        if (content.isBlank()) return null
        return Regex("""echo\s+(\d+)\s*>\s*"\${'$'}POLICY0/up_rate_limit_us"""")
            .find(content)?.groupValues?.get(1)?.toIntOrNull()
    }

    fun persistedInputBoostFreq(): Int? {
        val content = AssetInstaller.readFile(TARGET)
        if (content.isBlank()) return null
        val match = Regex("""echo\s+"0:(\d+)[^"]*"\s*>\s*"\${'$'}INPUT_BOOST_FREQ"""")
            .find(content)?.groupValues?.get(1)?.toIntOrNull()
        if (match == null && content.contains("0:0")) {
            return 0
        }
        return match
    }

    fun persistedInputBoostMs(): Int? {
        val content = AssetInstaller.readFile(TARGET)
        if (content.isBlank()) return null
        return Regex("""echo\s+(\d+)\s*>\s*"\${'$'}INPUT_BOOST_MS"""")
            .find(content)?.groupValues?.get(1)?.toIntOrNull()
    }

    fun currentLiveUpRateLimit(): Int? {
        val out = RootShell.run("cat /sys/devices/system/cpu/cpufreq/policy0/schedutil/up_rate_limit_us 2>/dev/null").outString.trim()
        return out.toIntOrNull()
    }

    fun currentLiveInputBoostFreq(): Int? {
        val out = RootShell.run("cat /sys/devices/system/cpu/cpu_boost/input_boost_freq 2>/dev/null").outString.trim()
        if (out.isBlank()) return null
        val match = Regex("""0:(\d+)""").find(out)?.groupValues?.get(1)?.toIntOrNull()
        if (match == null && out.contains("0:0")) {
            return 0
        }
        return match
    }

    fun currentLiveInputBoostMs(): Int? {
        val out = RootShell.run("cat /sys/devices/system/cpu/cpu_boost/input_boost_ms 2>/dev/null").outString.trim()
        return out.toIntOrNull()
    }

    fun setSettings(
        context: Context,
        enabled: Boolean,
        upRateLimit: Int,
        inputBoostFreq: Int,
        inputBoostMs: Int,
        applyLive: Boolean
    ): ShellResult {
        val result = if (enabled) {
            val freqStr = if (inputBoostFreq == 0) {
                "0:0 1:0 2:0 3:0 4:0 5:0 6:0 7:0"
            } else {
                "0:$inputBoostFreq 1:$inputBoostFreq 2:$inputBoostFreq 3:$inputBoostFreq 4:0 5:0 6:0 7:0"
            }

            AssetInstaller.installFromAsset(context, TEMPLATE_ASSET, TARGET) { raw ->
                raw.replace("__LITTLE_UP_RATE_LIMIT__", upRateLimit.toString())
                    .replace("__INPUT_BOOST_FREQ__", freqStr)
                    .replace("__INPUT_BOOST_MS__", inputBoostMs.toString())
            }
        } else {
            AssetInstaller.removeFile(TARGET)
        }

        if (applyLive) {
            val liveUpRateLimit = if (enabled) upRateLimit else DEFAULT_UP_RATE_LIMIT
            val liveBoostFreqVal = if (enabled) inputBoostFreq else DEFAULT_INPUT_BOOST_FREQ
            val liveBoostMs = if (enabled) inputBoostMs else DEFAULT_INPUT_BOOST_MS

            val freqStr = if (liveBoostFreqVal == 0) {
                "0:0 1:0 2:0 3:0 4:0 5:0 6:0 7:0"
            } else {
                "0:$liveBoostFreqVal 1:$liveBoostFreqVal 2:$liveBoostFreqVal 3:$liveBoostFreqVal 4:0 5:0 6:0 7:0"
            }

            RootShell.run(
                "echo $liveUpRateLimit > /sys/devices/system/cpu/cpufreq/policy0/schedutil/up_rate_limit_us; " +
                "echo \"$freqStr\" > /sys/devices/system/cpu/cpu_boost/input_boost_freq; " +
                "echo $liveBoostMs > /sys/devices/system/cpu/cpu_boost/input_boost_ms"
            )
        }

        return result
    }
}
