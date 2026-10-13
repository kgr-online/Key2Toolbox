package com.kgr.key2toolbox.core

import android.os.Build

/**
 * System and OS detection helpers to ensure backwards compatibility with LineageOS 22 (Android 15)
 * while targeting fixes for LineageOS 23 (Android 16).
 */
object Platform {

    /**
     * Reads a system property via getprop.
     * Works both in standard application context and inside hooked processes (e.g. Launcher3 under Xposed).
     */
    fun getprop(key: String): String = try {
        // Fast path using reflection on android.os.SystemProperties if available
        val spClass = Class.forName("android.os.SystemProperties")
        val getMethod = spClass.getMethod("get", String::class.java, String::class.java)
        getMethod.invoke(null, key, "") as? String ?: ""
    } catch (_: Throwable) {
        try {
            Runtime.getRuntime().exec(arrayOf("getprop", key))
                .inputStream.bufferedReader().readText().trim()
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * LineageOS version string, e.g. "23.2", "22.2", "22.1", or empty string if not LineageOS.
     */
    val lineageVersion: String by lazy {
        getprop("ro.lineage.build.version").ifEmpty {
            getprop("ro.lineage.version")
        }
    }

    /**
     * True if running on LineageOS 23 (or Android 16 / SDK >= 36 with LineageOS build).
     */
    val isLineage23: Boolean by lazy {
        val ver = lineageVersion
        when {
            ver.startsWith("23") -> true
            Build.VERSION.SDK_INT >= 36 -> true
            Build.VERSION.RELEASE.startsWith("16") -> true
            else -> false
        }
    }

    /**
     * True if running on LineageOS 22 (Android 15 / SDK 35).
     */
    val isLineage22: Boolean by lazy {
        val ver = lineageVersion
        when {
            ver.startsWith("22") -> true
            Build.VERSION.SDK_INT == 35 -> true
            Build.VERSION.RELEASE.startsWith("15") -> true
            else -> false
        }
    }
}
