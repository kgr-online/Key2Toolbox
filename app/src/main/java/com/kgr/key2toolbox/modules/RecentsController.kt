package com.kgr.key2toolbox.modules

import android.content.Context
import android.provider.Settings
import com.kgr.key2toolbox.core.RootShell

/**
 * Two-row Grid Overview (Recents) for the Key2's LineageOS launcher (AOSP
 * Launcher3, packaged as `com.android.launcher3`). The layout change happens
 * inside the launcher via the LSPosed hook
 * [com.kgr.key2toolbox.xposed.RecentsHookInit]; this controller only owns the
 * two `Settings.Global` keys the hook reads and the launcher restart that makes
 * a change take effect.
 *
 * `settings put global` needs root. The keys are world-readable, so the hook
 * (running in the launcher process) reads them back with no permission.
 */
object RecentsController {

    private const val LAYOUT_MODE_KEY = "key2_recents_layout_mode"
    private const val SCRIM_ALPHA_KEY = "key2_recents_scrim_alpha"

    // LineageOS 22 on the Key2 ships AOSP Launcher3 under its own package name
    // (there is no separate org.lineageos.trebuchet package here).
    private const val LAUNCHER_PKG = "com.android.launcher3"

    enum class LayoutMode(val value: Int) {
        STOCK(0),

        /** The only launcher-hooked mode: forces Launcher3's tablet two-row grid
         *  Overview via [com.kgr.key2toolbox.xposed.RecentsHookInit]. */
        GRID(1),

        /**
         * Standalone vertical card list: each row carries its app's last
         * snapshot and a staggered height, drawn by
         * [com.kgr.key2toolbox.service.SlimRecentsOverlayController]. Was a
         * launcher hook (Grid + staggered tiles) through 5.3.5; now an overlay
         * like [SLIM_LIST], with no launcher involvement.
         */
        MASONRY(2),

        /**
         * Standalone vertical task list, thumbnail-free - see
         * [com.kgr.key2toolbox.service.SlimRecentsOverlayController]. Like
         * [MASONRY], never touches the launcher process; showing it is
         * intercepted in Key2AccessibilityService before GLOBAL_ACTION_RECENTS
         * would fire.
         */
        SLIM_LIST(3);

        /** True for the two modes drawn as our own overlay (no launcher hook). */
        val isOverlay: Boolean get() = this == MASONRY || this == SLIM_LIST

        companion object {
            fun fromValue(v: Int?): LayoutMode = entries.firstOrNull { it.value == v } ?: STOCK
        }
    }

    /**
     * Overridden to return true by [com.kgr.key2toolbox.xposed.RecentsHookInit]
     * when it loads in our own process, so the UI can tell the user whether the
     * LSPosed module is actually enabled. Keep the body a plain `return false`.
     */
    @JvmStatic
    fun isXposedActive(): Boolean = false

    fun getLayoutMode(): LayoutMode = LayoutMode.fromValue(
        RootShell.run("settings get global $LAYOUT_MODE_KEY").outString.trim().toIntOrNull()
    )

    /**
     * Non-root read of the mode - the `Settings.Global` key is world-readable,
     * so this is instant and safe to call on every "open Recents" (the root
     * variant spins up a `su` shell, ~100 ms, on a latency-sensitive path).
     */
    fun getLayoutMode(context: Context): LayoutMode = LayoutMode.fromValue(
        runCatching { Settings.Global.getInt(context.contentResolver, LAYOUT_MODE_KEY) }.getOrNull()
    )

    fun setLayoutMode(mode: LayoutMode) {
        RootShell.run("settings put global $LAYOUT_MODE_KEY ${mode.value}")
        restartLauncher()
    }

    /** 0f = fully transparent Overview background, 1f = stock opacity. */
    fun getScrimAlpha(): Float =
        RootShell.run("settings get global $SCRIM_ALPHA_KEY").outString.trim().toFloatOrNull() ?: 1f

    fun setScrimAlpha(alpha: Float) {
        RootShell.run("settings put global $SCRIM_ALPHA_KEY ${alpha.coerceIn(0f, 1f)}")
    }

    /** Hard-kills the launcher so it respawns and re-reads the config on next Home. */
    fun restartLauncher(): Boolean {
        val pid = RootShell.run("pidof $LAUNCHER_PKG").outString.trim()
        if (pid.isEmpty()) return false
        return RootShell.run("kill -9 $pid").success
    }

    fun restartSystemUi(): Boolean {
        val pid = RootShell.run("pidof com.android.systemui").outString.trim()
        if (pid.isEmpty()) return false
        return RootShell.run("kill -9 $pid").success
    }
}
