package com.kgr.key2toolbox.core

import android.content.Context
import java.io.File

/**
 * Installs scripts bundled in app/src/main/assets/ to arbitrary root-owned
 * paths (primarily /data/adb/service.d/).
 *
 * KNOWN RISK: writing to /data/adb/service.d/ from a root shell spawned
 * post-boot has previously failed with "Permission denied" even for `su -c`
 * (a filesystem-encryption-context mismatch with the shell that originally
 * created files there). `install -m 755` from an interactive root session is
 * the method known to work from Termux. This wrapper uses the same approach
 * via libsu's root shell, but ALWAYS verify the result with [fileExists] /
 * [readFile] after writing - if it silently fails, fall back to exporting the
 * file (see MainActivity's "Export scripts" option, if added) and installing
 * it manually via Termux.
 */
object AssetInstaller {

    fun installFromAsset(
        context: Context,
        assetName: String,
        targetPath: String,
        transform: ((String) -> String)? = null
    ): ShellResult {
        val raw = context.assets.open(assetName).bufferedReader().use { it.readText() }
        val content = transform?.invoke(raw) ?: raw

        val tmp = File(context.filesDir, assetName)
        tmp.writeText(content)

        return RootShell.run("install -m 755 '${tmp.absolutePath}' '$targetPath'")
    }

    fun removeFile(targetPath: String): ShellResult =
        RootShell.run("rm -f '$targetPath'")

    fun fileExists(path: String): Boolean =
        RootShell.run("[ -f '$path' ] && echo yes || echo no").outString.trim() == "yes"

    fun readFile(path: String): String =
        RootShell.run("cat '$path' 2>/dev/null").outString

    /**
     * Whether the file installed at [targetPath] is byte-for-byte (modulo trailing
     * whitespace) what [installFromAsset] would write today for [assetName] with the
     * same [transform]. Used to catch a stale on-device copy left over from an older
     * app version or a changed parameter - a bare "does the file exist / is the daemon
     * running" check can't see that.
     */
    fun matchesAsset(
        context: Context,
        assetName: String,
        targetPath: String,
        transform: ((String) -> String)? = null
    ): Boolean {
        val raw = context.assets.open(assetName).bufferedReader().use { it.readText() }
        val expected = (transform?.invoke(raw) ?: raw).trimEnd()
        return readFile(targetPath).trimEnd() == expected
    }
}
