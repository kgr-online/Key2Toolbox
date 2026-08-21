package com.kgr.key2toolbox.modules

import com.topjohnwu.superuser.Shell

/**
 * Root operations for Play Store tagging.
 * Uses Shell.cmd() directly (rather than RootShell.run()) because the logging
 * callback pattern needs the raw libsu API. The Shell instance is shared with
 * RootShell since Shell.setDefaultBuilder() is called once at app startup.
 */
object PlayStoreTaggerManager {

    private const val PLAY_STORE = "com.android.vending"

    fun setPlayInstaller(packageName: String, log: (String) -> Unit = {}): String? =
        reinstall(packageName, PLAY_STORE, log)

    fun clearInstaller(packageName: String, log: (String) -> Unit = {}): String? =
        reinstall(packageName, installer = null, log)

    private fun reinstall(packageName: String, installer: String?, log: (String) -> Unit): String? {
        val pathResult = Shell.cmd("pm path $packageName").exec()
        if (!pathResult.isSuccess || pathResult.out.isEmpty()) {
            return "Could not find APK path for $packageName"
        }

        val apkPaths = pathResult.out
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }

        if (apkPaths.isEmpty()) return "No APK paths found"

        log("Found ${apkPaths.size} APK(s)")
        apkPaths.forEach { log("  $it") }

        return if (apkPaths.size == 1) {
            installSingle(apkPaths[0], installer, log)
        } else {
            installSplit(packageName, apkPaths, installer, log)
        }
    }

    private fun installSingle(apkPath: String, installer: String?, log: (String) -> Unit): String? {
        val iFlag = if (installer != null) "-i $installer " else ""
        log("Installing single APK${if (installer != null) " with -i $installer" else " (no installer tag)"}")
        val result = Shell.cmd(
            "pm install ${iFlag}--dont-kill -r \"$apkPath\""
        ).exec()
        result.out.forEach { log(it) }
        return if (result.isSuccess || result.out.any { it.contains("Success", ignoreCase = true) }) null
        else result.out.joinToString("\n").ifBlank { "Install failed" }
    }

    private fun installSplit(packageName: String, apkPaths: List<String>, installer: String?, log: (String) -> Unit): String? {
        val iFlag = if (installer != null) "-i $installer " else ""
        val sizeResult = Shell.cmd(
            "stat -c %s ${apkPaths.joinToString(" ")} | awk '{s+=\$1} END{print s}'"
        ).exec()
        val totalSize = sizeResult.out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
        log("Split APK total size: $totalSize bytes")

        val createResult = Shell.cmd(
            "pm install-create ${iFlag}--dont-kill -r -S $totalSize"
        ).exec()
        val sessionLine = createResult.out.firstOrNull { it.contains("Success") }
            ?: return "Failed to create session: ${createResult.out.joinToString("\n")}"

        val sessionId = Regex("\\[(\\d+)\\]").find(sessionLine)?.groupValues?.get(1)
            ?: return "Could not parse session ID"

        log("Session created: $sessionId")

        apkPaths.forEachIndexed { index, path ->
            val apkSize = Shell.cmd("stat -c %s \"$path\"").exec()
                .out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
            log("Writing split_$index (${apkSize} bytes)")
            val writeResult = Shell.cmd(
                "pm install-write -S $apkSize $sessionId split_$index \"$path\""
            ).exec()
            writeResult.out.forEach { log(it) }
            if (!writeResult.isSuccess && !writeResult.out.any { it.contains("Success", ignoreCase = true) }) {
                Shell.cmd("pm install-abandon $sessionId").exec()
                return "Failed to write split_$index"
            }
        }

        log("Committing session…")
        val commitResult = Shell.cmd("pm install-commit $sessionId").exec()
        commitResult.out.forEach { log(it) }
        return if (commitResult.isSuccess || commitResult.out.any { it.contains("Success", ignoreCase = true) }) null
        else {
            Shell.cmd("pm install-abandon $sessionId").exec()
            "Commit failed: ${commitResult.out.joinToString("\n")}"
        }
    }

    /**
     * Reads the real installer package for one app via `dumpsys package`.
     *
     * Two things drove this away from the previous approach:
     *  - `cmd package get-install-source` doesn't exist on this ROM
     *    ("Unknown command"), so it always silently returned null.
     *  - PackageManager.getInstallSourceInfo(), called in-process from the
     *    app itself, was independently confirmed (via logging) to return
     *    incorrect data on this ROM even when the equivalent root-shell
     *    read is correct - the same in-process-vs-shell read discrepancy
     *    already hit with the accessibility-service status check.
     *
     * `dumpsys package <pkg> | grep installerPackageName=` was verified
     * directly against known F-Droid/Aurora installs and matches reality.
     */
    fun getInstaller(packageName: String): String? {
        val result = Shell.cmd("dumpsys package $packageName | grep -m1 installerPackageName=").exec()
        if (!result.isSuccess) return null
        val line = result.out.firstOrNull() ?: return null
        val value = line.substringAfter("installerPackageName=").trim()
        return value.takeIf { it.isNotBlank() && it != "null" }
    }

    /**
     * Bulk version of [getInstaller] for populating the full app list without
     * spawning one root shell call per app. `dumpsys package -a` includes
     * every package's block in one dump; this walks it once and records the
     * installerPackageName seen under each `Package [pkg]` header.
     */
    fun getAllInstallers(): Map<String, String?> {
        val result = Shell.cmd("dumpsys package -a").exec()
        if (!result.isSuccess) return emptyMap()

        val installers = mutableMapOf<String, String?>()
        var currentPkg: String? = null
        val pkgHeader = Regex("""Package \[([^]]+)]""")

        for (line in result.out) {
            pkgHeader.find(line)?.let { currentPkg = it.groupValues[1] }
            val trimmed = line.trim()
            if (currentPkg != null && trimmed.startsWith("installerPackageName=")) {
                val value = trimmed.substringAfter("installerPackageName=").trim()
                installers[currentPkg!!] = value.takeIf { it.isNotBlank() && it != "null" }
            }
        }
        return installers
    }
}
