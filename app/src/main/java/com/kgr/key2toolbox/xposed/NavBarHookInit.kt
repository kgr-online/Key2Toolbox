package com.kgr.key2toolbox.xposed

import android.content.Context
import android.graphics.Insets
import android.provider.Settings
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed-only hook that hides the on-screen navigation bar and disables the
 * **bottom** swipe-up gesture (home / recents / quickswitch), so the Key2Toolbox
 * "toolbelt" ([com.kgr.key2toolbox.service.ToolbeltOverlayController]) can stand
 * in for it. The **edge** back-gesture (swipe in from the left/right edge) is
 * deliberately left alone.
 *
 * Gated live on the world-readable `Settings.Global` key
 * [com.kgr.key2toolbox.modules.ToolbeltController.GLOBAL_ACTIVE] (written with
 * root by the app), like [RecentsHookInit] gates on its own key. Flag 0 -> every
 * hook falls through to stock behaviour.
 *
 * **This device's layout, verified on the Key2 (LineageOS 22.2, Android 15) by
 * decompiling TrebuchetQuickStep.apk + `dumpsys window windows`:** there is no
 * SystemUI navigation-bar window at all. The nav bar is the **Launcher3 Taskbar**
 * (`com.android.launcher3`, `ty=NAVIGATION_BAR`), which also publishes the
 * `navigationBars` inset (67px in gesture mode), and the bottom swipe-up is the
 * launcher's `TouchInteractionService` "swipe-up" input monitor. So every hook
 * lives in the launcher process:
 *
 *  1. `TaskbarStashController.get{Content,Tappable}HeightToReportToApps` /
 *     `getTouchableHeight` -> the belt height (read live from `Settings.Global`
 *     `key2_toolbelt_inset_px`, which the app sets to the full belt height, or
 *     just the handle strip while the belt is collapsed), and
 *     `TaskbarInsetsController.getInsetsForGravity{,WithCutout}` -> the same as a
 *     bottom inset. So app content ends above the belt instead of being hidden
 *     behind it. The taskbar only re-reads this on recreation, so the app
 *     restarts the launcher when the height / enabled state changes; collapsing
 *     the belt does not touch the inset (the belt just tucks its icons away
 *     behind an opaque strip).
 *  2. `TouchInteractionService.onInputEvent` is skipped, killing the bottom
 *     swipe-up (home/recents/quickswitch/assistant-corner). The edge back-gesture
 *     is SystemUI's `EdgeBackGestureHandler`, a different input monitor, and is
 *     not touched.
 *
 * Class / method names drift between builds - every hook is wrapped and logs
 * whether it attached.  `adb logcat | grep Key2Toolbox-Xposed`
 */
class NavBarHookInit : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "Key2Toolbox-Xposed"
        private const val SELF_PKG = "com.kgr.key2toolbox"

        private val LAUNCHER_PKGS = setOf(
            "com.android.launcher3",
            "org.lineageos.trebuchet",
        )

        const val PREF_ACTIVE = "key2_toolbelt_active"
        const val PREF_INSET_PX = "key2_toolbelt_inset_px"

        private const val TASKBAR_STASH = "com.android.launcher3.taskbar.TaskbarStashController"
        private const val TASKBAR_INSETS = "com.android.launcher3.taskbar.TaskbarInsetsController"
        private const val TOUCH_INTERACTION = "com.android.quickstep.TouchInteractionService"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when {
            lpparam.packageName == SELF_PKG -> selfProbe(lpparam.classLoader)
            lpparam.packageName in LAUNCHER_PKGS -> {
                XposedBridge.log("[$TAG] loaded in launcher, installing toolbelt nav hooks")
                hookTaskbarInsets(lpparam.classLoader)
                hookBottomSwipeGesture(lpparam.classLoader)
            }
        }
    }

    // --- config --------------------------------------------------------

    private fun active(): Boolean {
        val ctx = currentApplication() ?: return false
        return try {
            Settings.Global.getInt(ctx.contentResolver, PREF_ACTIVE, 0) == 1
        } catch (t: Throwable) {
            false
        }
    }

    /** Bottom inset (px) to reserve for the belt, or -1 when the module is off. */
    private fun beltInsetPx(): Int {
        val ctx = currentApplication() ?: return -1
        return try {
            if (Settings.Global.getInt(ctx.contentResolver, PREF_ACTIVE, 0) != 1) return -1
            val fallback = (54 * ctx.resources.displayMetrics.density).toInt()
            Settings.Global.getInt(ctx.contentResolver, PREF_INSET_PX, fallback)
        } catch (t: Throwable) {
            -1
        }
    }

    // --- taskbar (= nav bar + its inset on this device) --------------

    private fun hookTaskbarInsets(cl: ClassLoader) {
        var stashHooked = 0
        runCatching {
            val stash = XposedHelpers.findClass(TASKBAR_STASH, cl)
            val beltHeight = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val px = beltInsetPx()
                    if (px >= 0) param.result = px
                }
            }
            for (m in listOf(
                "getContentHeightToReportToApps",
                "getTappableHeightToReportToApps",
                "getTouchableHeight",
            )) {
                runCatching { XposedHelpers.findAndHookMethod(stash, m, beltHeight); stashHooked++ }
            }
        }
        var insetsHooked = 0
        runCatching {
            val insets = XposedHelpers.findClass(TASKBAR_INSETS, cl)
            val beltInset = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val px = beltInsetPx()
                    if (px >= 0 && param.result is Insets) {
                        param.result = Insets.of(0, 0, 0, px)
                    }
                }
            }
            for (m in listOf("getInsetsForGravity", "getInsetsForGravityWithCutout")) {
                runCatching { XposedBridge.hookAllMethods(insets, m, beltInset); insetsHooked++ }
            }
        }
        XposedBridge.log("[$TAG] taskbar hooks: stash=$stashHooked insets=$insetsHooked")
    }

    // --- bottom swipe-up gesture ------------------------------------

    /**
     * `TouchInteractionService.onInputEvent(InputEvent)` is the single entry
     * point for the launcher's "swipe-up" input monitor (home / recents /
     * quickswitch / assistant corner). Skip it entirely while the belt is
     * active. The edge back-gesture is handled elsewhere and is unaffected.
     */
    private fun hookBottomSwipeGesture(cl: ClassLoader) {
        var hooked = false
        runCatching {
            val tis = XposedHelpers.findClass(TOUCH_INTERACTION, cl)
            XposedBridge.hookAllMethods(tis, "onInputEvent", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (active()) param.result = null
                }
            })
            hooked = true
        }
        XposedBridge.log("[$TAG] TouchInteractionService.onInputEvent hook attached=$hooked")
    }

    // --- helpers -----------------------------------------------------

    private fun selfProbe(cl: ClassLoader) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "com.kgr.key2toolbox.modules.ToolbeltController", cl, "isXposedActive",
                XC_MethodReplacement.returnConstant(true)
            )
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
