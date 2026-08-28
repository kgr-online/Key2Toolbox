package com.kgr.key2toolbox.xposed

import android.content.Context
import android.graphics.Color
import android.provider.Settings
import android.view.View
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed-only hook that forces the two-row Grid Overview (Recents) layout in the
 * Key2's LineageOS 22.2 launcher (AOSP Launcher3 / QuickStep, packaged as
 * `com.android.launcher3` / TrebuchetQuickStep).
 *
 * How it works on this build (verified by decompiling TrebuchetQuickStep.apk):
 *
 *  - Overview task geometry (`BaseContainerInterface.calculateCarouselTaskSize`,
 *    the grid-size math, ~15 read sites in `RecentsView`) branches on the plain
 *    `DeviceProfile.isTablet` field, NOT on `RecentsView.showAsGrid()`. Forcing
 *    only `showAsGrid()` does nothing visible.
 *  - `isTablet` is derived, early in the `DeviceProfile` constructor and before
 *    any of that geometry is computed, from
 *    `DisplayController.Info.isTablet(WindowBounds)` (`smallestWidthDp >= 600`).
 *    So the one effective lever is that source method: return `true` while Grid
 *    mode is active and every downstream metric is computed on the tablet path.
 *  - There is no `enableGridOnlyOverview` flag on this build (no
 *    `com.android.launcher3.Flags` class), so nothing else to flip.
 *
 * Trade-off: `isTablet` also drives the persistent taskbar and hotseat sizing on
 * the same `DeviceProfile`. [suppressTaskbar] undoes the taskbar; the hotseat /
 * workspace may still shift slightly. A truly surgical fix would patch only the
 * overview read sites (what q25toolbox does with a bind-mounted, smali-patched
 * APK) - Xposed can't intercept individual field reads, so this is as close as a
 * pure hook gets.
 *
 * Config (world-readable `Settings.Global`, written with root by
 * [com.kgr.key2toolbox.modules.RecentsController]):
 *   - key2_recents_layout_mode : 0 = Stock, 1 = Grid, 2 = Masonry
 *   - key2_recents_scrim_alpha : 0.0 .. 1.0  (Overview background opacity)
 *
 * Masonry is Grid plus per-tile height variation ([mosaicTileHeights]): same
 * two-row scrollable layout, but each task box is shortened by a fixed factor
 * keyed on its task id, and Launcher3's own grid code re-centres it vertically,
 * giving a staggered-mosaic look without touching the scroll / dismiss maths.
 *
 * Debug on device:  `adb logcat | grep Key2Toolbox-Xposed`
 */
class RecentsHookInit : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "Key2Toolbox-Xposed"
        private const val SELF_PKG = "com.kgr.key2toolbox"

        const val PREF_RECENTS_MODE = "key2_recents_layout_mode"
        const val PREF_SCRIM_ALPHA = "key2_recents_scrim_alpha"

        const val MODE_STOCK = 0
        const val MODE_GRID = 1
        const val MODE_MASONRY = 2

        /** Per-tile height multipliers for Masonry, indexed by task id modulo size. */
        private val MOSAIC_FACTORS = floatArrayOf(1.0f, 0.78f, 0.93f, 0.70f, 0.86f, 0.74f)

        // LineageOS 22 ships AOSP Launcher3 under its own package name; the
        // Trebuchet name is kept only as a guard against a future rename.
        private val TARGET_PACKAGES = setOf(
            "com.android.launcher3",
            "org.lineageos.trebuchet"
        )
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == SELF_PKG) {
            selfProbe(lpparam.classLoader)
            return
        }
        if (lpparam.packageName !in TARGET_PACKAGES) return

        XposedBridge.log("[$TAG] loaded in ${lpparam.packageName}, installing Recents hooks")
        val cl = lpparam.classLoader
        forceOverviewTablet(cl)
        fixupOverviewDeviceProfile(cl)
        forceShowAsGrid(cl)
        mosaicTileHeights(cl)
        squareTaskCorners(cl)
        scaleOverviewScrim(cl)
    }

    // --- config -----------------------------------------------------------

    private fun mode(context: Context): Int = try {
        Settings.Global.getInt(context.contentResolver, PREF_RECENTS_MODE, MODE_STOCK)
    } catch (t: Throwable) {
        MODE_STOCK
    }

    /** Grid or Masonry: both need the tablet two-row Overview path. */
    private fun gridActive(): Boolean {
        val ctx = currentApplication() ?: return false
        return mode(ctx) != MODE_STOCK
    }

    private fun scrimAlpha(context: Context): Float = try {
        Settings.Global.getFloat(context.contentResolver, PREF_SCRIM_ALPHA, 1.0f)
    } catch (t: Throwable) {
        1.0f
    }

    // --- hooks ------------------------------------------------------------

    /**
     * The decisive lever: `DisplayController.Info.isTablet(WindowBounds)` -> true
     * while Grid mode is active, so the `DeviceProfile` constructor computes
     * tablet (two-row grid) Overview metrics.
     */
    private fun forceOverviewTablet(cl: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.launcher3.util.DisplayController\$Info", cl,
                "isTablet", "com.android.launcher3.util.WindowBounds",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (gridActive()) param.result = true
                    }
                }
            )
            XposedBridge.log("[$TAG] hooked DisplayController.Info.isTablet")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Info.isTablet hook failed: ${t.message}")
        }
    }

    /**
     * Two post-construction fixups on every `DeviceProfile`, applied only while
     * grid mode is active:
     *
     *  1. Taskbar: [forceOverviewTablet] also makes the profile mark the
     *     persistent taskbar present. Clear `isTaskbarPresent` / `taskbarHeight`
     *     so the floating tablet nav bar does not appear (both fields non-final
     *     on this build).
     *  2. Grid dimens: `task_thumbnail_icon_drawable_size_grid`,
     *     `overview_grid_row_spacing` and `overview_grid_side_margin` are all
     *     `0dp` in Launcher3's default (phone) resource bucket - real values
     *     exist only under `sw600dp`. Forcing the tablet path therefore lays the
     *     grid out with 0-size task icons (the reported "no app icons") and no
     *     row gap. Backfill the zeroed fields: the grid icon size from the
     *     non-grid one (44dp), spacing/margin from density-scaled defaults.
     */
    private fun fixupOverviewDeviceProfile(cl: ClassLoader) {
        val dp = try {
            XposedHelpers.findClass("com.android.launcher3.DeviceProfile", cl)
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] DeviceProfile not found: ${t.message}")
            return
        }
        val density = currentApplication()?.resources?.displayMetrics?.density ?: 2.75f
        fun px(dpValue: Int) = (dpValue * density).toInt()

        var n = 0
        for (ctor in dp.declaredConstructors) {
            try {
                XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!gridActive()) return
                        val o = param.thisObject
                        runCatching { XposedHelpers.setBooleanField(o, "isTaskbarPresent", false) }
                        runCatching { XposedHelpers.setIntField(o, "taskbarHeight", 0) }
                        runCatching {
                            if (XposedHelpers.getIntField(o, "overviewTaskIconDrawableSizeGridPx") <= 0) {
                                val nonGrid = XposedHelpers.getIntField(o, "overviewTaskIconDrawableSizePx")
                                XposedHelpers.setIntField(
                                    o, "overviewTaskIconDrawableSizeGridPx",
                                    if (nonGrid > 0) nonGrid else px(44)
                                )
                            }
                        }
                        runCatching {
                            if (XposedHelpers.getIntField(o, "overviewRowSpacing") <= 0)
                                XposedHelpers.setIntField(o, "overviewRowSpacing", px(24))
                        }
                        runCatching {
                            if (XposedHelpers.getIntField(o, "overviewGridSideMargin") <= 0)
                                XposedHelpers.setIntField(o, "overviewGridSideMargin", px(12))
                        }
                    }
                })
                n++
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] DeviceProfile ctor hook failed: ${t.message}")
            }
        }
        XposedBridge.log("[$TAG] overview profile fixup on $n DeviceProfile constructor(s)")
    }

    /**
     * `RecentsView.showAsGrid()` gates paging / clear-all placement. With
     * [forceOverviewTablet] it already returns true on its own, but pinning it
     * makes Stock mode deterministic and is cheap.
     */
    private fun forceShowAsGrid(cl: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.quickstep.views.RecentsView", cl, "showAsGrid",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val ctx = (param.thisObject as? View)?.context ?: return
                        param.result = mode(ctx) != MODE_STOCK
                    }
                }
            )
            XposedBridge.log("[$TAG] hooked RecentsView.showAsGrid")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] showAsGrid hook failed: ${t.message}")
        }
    }

    /**
     * Masonry: after `TaskView.updateTaskSize` has sized a task box to the
     * uniform grid rect, shorten it by [MOSAIC_FACTORS] keyed on the task id.
     * `RecentsView.updateTaskSize()` calls this for every task and then runs
     * `updateGridProperties()`, which re-derives each tile's vertical offset
     * from its own `LayoutParams.height` (it centres the tile in the full task
     * slot), so the shorter tiles simply end up staggered. Width is left alone,
     * so column positions, paging and swipe-to-dismiss are unchanged. The
     * focused large tile is skipped.
     */
    private fun mosaicTileHeights(cl: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.quickstep.views.TaskView", cl, "updateTaskSize",
                "android.graphics.Rect", "android.graphics.Rect", "android.graphics.Rect",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val tv = param.thisObject as? View ?: return
                        if (mode(tv.context) != MODE_MASONRY) return
                        if (XposedHelpers.callMethod(tv, "isLargeTile") == true) return
                        val lp = tv.layoutParams ?: return
                        if (lp.height <= 0) return
                        val id = (XposedHelpers.callMethod(tv, "getTaskViewId") as? Int) ?: return
                        val f = MOSAIC_FACTORS[((id % MOSAIC_FACTORS.size) + MOSAIC_FACTORS.size) % MOSAIC_FACTORS.size]
                        val newH = (lp.height * f).toInt()
                        if (newH <= 0 || newH == lp.height) return
                        lp.height = newH
                        tv.layoutParams = lp
                        runCatching { XposedHelpers.callMethod(tv, "updateThumbnailSize") }
                    }
                }
            )
            XposedBridge.log("[$TAG] hooked TaskView.updateTaskSize (masonry)")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] masonry hook failed: ${t.message}")
        }
    }

    /**
     * Masonry only: `com.android.quickstep.util.TaskCornerRadius.get(Context)`
     * feeds every task tile's corner radius. Return 0 so the mosaic tiles are
     * square-edged, closer to the BlackBerry productivity-tab look. Grid mode
     * keeps the stock rounded corners.
     */
    private fun squareTaskCorners(cl: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.quickstep.util.TaskCornerRadius", cl, "get",
                "android.content.Context",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ctx = param.args.getOrNull(0) as? Context ?: return
                        if (mode(ctx) == MODE_MASONRY) param.result = 0f
                    }
                }
            )
            XposedBridge.log("[$TAG] hooked TaskCornerRadius.get (masonry)")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] TaskCornerRadius hook failed: ${t.message}")
        }
    }

    /**
     * Overview background scrim opacity - scale the returned ARGB int's alpha by
     * the configured factor so the wallpaper shows through.
     *
     * On this build Overview is a separate `RecentsActivity` (FallbackRecentsView),
     * whose scrim comes from `com.android.quickstep.fallback.RecentsState
     * .getScrimColor(Context)` - NOT the in-launcher `OverviewState`. Both are
     * hooked (plus the older `views.RecentsState` name) so it works regardless
     * of which path the launcher takes; each has its own first-arg type.
     */
    private fun scaleOverviewScrim(cl: ClassLoader) {
        val scale = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val ctx = param.args.firstOrNull { it is Context } as? Context
                    ?: currentApplication() ?: return
                val factor = scrimAlpha(ctx)
                if (factor >= 0.999f) return
                val base = param.result as? Int ?: return
                val a = (Color.alpha(base) * factor).toInt().coerceIn(0, 255)
                param.result = Color.argb(a, Color.red(base), Color.green(base), Color.blue(base))
            }
        }
        val candidates = listOf(
            Triple("com.android.quickstep.fallback.RecentsState", "getScrimColor", "android.content.Context"),
            Triple("com.android.quickstep.views.RecentsState", "getScrimColor", "android.content.Context"),
            Triple("com.android.launcher3.uioverrides.states.OverviewState", "getWorkspaceScrimColor", "com.android.launcher3.Launcher"),
            Triple("com.android.launcher3.LauncherState", "getWorkspaceScrimColor", "com.android.launcher3.Launcher"),
        )
        for ((fqcn, method, param) in candidates) {
            try {
                XposedHelpers.findAndHookMethod(XposedHelpers.findClass(fqcn, cl), method, param, scale)
                XposedBridge.log("[$TAG] hooked $fqcn.$method")
            } catch (t: Throwable) {
                // not all launcher variants expose every one
            }
        }
        // The FallbackActivityInterface path that actually paints the RecentsActivity
        // scrim - hook by name only, whatever the state-class arg type is.
        runCatching {
            XposedBridge.hookAllMethods(
                XposedHelpers.findClass("com.android.quickstep.FallbackActivityInterface", cl),
                "getOverviewScrimColorForState", scale
            )
            XposedBridge.log("[$TAG] hooked FallbackActivityInterface.getOverviewScrimColorForState")
        }
    }

    // --- helpers --------------------------------------------------------

    /** Let the app's own UI detect that the module is enabled. */
    private fun selfProbe(cl: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.kgr.key2toolbox.modules.RecentsController", cl, "isXposedActive",
                XC_MethodReplacement.returnConstant(true)
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] self-probe hook failed: ${t.message}")
        }
    }

    private fun currentApplication(): Context? = try {
        val at = XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("android.app.ActivityThread", null),
            "currentActivityThread"
        )
        XposedHelpers.callMethod(at, "getApplication") as? Context
    } catch (t: Throwable) {
        null
    }
}
