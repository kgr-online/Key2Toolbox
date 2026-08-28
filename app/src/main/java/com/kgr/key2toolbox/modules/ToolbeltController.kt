package com.kgr.key2toolbox.modules

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.DrawableRes
import com.kgr.key2toolbox.R
import com.kgr.key2toolbox.core.RootShell
import org.json.JSONArray
import org.json.JSONObject

/**
 * BlackBerry Q20-style "toolbelt": a fixed row of five icons pinned to the bottom
 * of the screen that replaces the system navigation (the gesture pill or the
 * 3-button bar).
 *
 * Split, like [RecentsController], between two worlds:
 *
 *  - The belt window itself, its icons and its tap handling all live in-process
 *    in [com.kgr.key2toolbox.service.ToolbeltOverlayController], driven by the
 *    running [com.kgr.key2toolbox.service.Key2AccessibilityService] (only an
 *    AccessibilityService Context can add a TYPE_ACCESSIBILITY_OVERLAY window).
 *    That code reads the config straight from SharedPreferences ("key2tweaks").
 *
 *  - Hiding the real navigation bar / neutralising the bottom gesture is done by
 *    the LSPosed hook [com.kgr.key2toolbox.xposed.NavBarHookInit] inside
 *    `com.android.systemui`. It can't see our SharedPreferences, so the enabled
 *    state is mirrored into a world-readable [android.provider.Settings.Global]
 *    key ([GLOBAL_ACTIVE]) written with root here; the hook reads it back with no
 *    permission.
 */
object ToolbeltController {

    // --- SharedPreferences (in-process, shared with the accessibility service) ---
    const val PREFS = "key2tweaks"
    const val KEY_ENABLED = "toolbelt_enabled"
    const val KEY_SLOTS = "toolbelt_slots"                 // JSON array, 5 entries
    const val KEY_AUTOHIDE_FULLSCREEN = "toolbelt_autohide_fullscreen"
    const val KEY_COLLAPSIBLE = "toolbelt_collapsible"     // opt-in: allow hide/show (restarts launcher)
    const val KEY_COLLAPSED = "toolbelt_collapsed"         // current hidden state, persisted
    const val KEY_HEIGHT_DP = "toolbelt_height_dp"         // belt height / reserved inset, dp
    const val KEY_ICON_SCALE = "toolbelt_icon_scale"       // icon size, percent of row height
    const val KEY_HAPTIC = "toolbelt_haptic"               // 0 off / 1 light / 2 medium / 3 strong
    const val KEY_COLOR_MODE = "toolbelt_color_mode"       // 0 fixed / 1 material-you / 2 follow-app

    // --- Settings.Global mirror (read by the launcher hook, written with root) ---
    const val GLOBAL_ACTIVE = "key2_toolbelt_active"       // 1 = replace the nav bar
    const val GLOBAL_NAV_MODE = "key2_toolbelt_nav_mode"   // last seen navigation_mode (0/1/2)
    const val GLOBAL_INSET_PX = "key2_toolbelt_inset_px"   // bottom inset the hook reserves for the belt

    const val SLOT_COUNT = 5

    /** Belt height in dp = the bottom inset reserved from apps while it is shown. */
    const val BELT_TOTAL_DP = 54

    /** Grab-handle strip added above the icons when the belt is collapsible, dp. */
    const val HANDLE_DP = 8

    /** Reserved inset / touch target while collapsed - just the grab strip, dp. */
    const val COLLAPSED_DP = 26

    /** Everything a slot's tap can be wired to. */
    enum class ToolbeltAction(val id: String) {
        NONE("none"),
        HOME("home"),
        BACK("back"),
        RECENTS("recents"),
        NOTIFICATIONS("notifications"),
        QUICK_SETTINGS("quick_settings"),
        POWER_DIALOG("power_dialog"),
        SCREENSHOT("screenshot"),
        LOCK_SCREEN("lock_screen"),
        SPLIT_SCREEN("split_screen"),
        VOICE_ASSIST("voice_assist"),
        /** Open the default phone app (ACTION_DIAL). */
        DIALER("dialer"),
        /** Switch back to the most recently used app. */
        LAST_APP("last_app"),
        /** Collapse / expand the belt (only when "Collapsible" is on). */
        TOGGLE_BELT("toggle_belt"),
        /** Home normally; ends the call while one is in progress. */
        HANGUP_OR_HOME("hangup_or_home"),
        HANGUP("hangup"),
        /** Launch an installed app; the target package is the slot's arg. */
        LAUNCH_APP("launch_app");

        companion object {
            fun fromId(v: String?): ToolbeltAction =
                entries.firstOrNull { it.id == v } ?: NONE
        }
    }

    /** The bundled icon set a slot can display. */
    enum class ToolbeltIcon(val id: String, @DrawableRes val res: Int) {
        NONE("none", R.drawable.ic_toolbelt_blank),
        // phone / logo / back / hangup are the real BlackBerry Q20 key etchings
        // from art/toolbelt-glyphs/ (white variants; the belt tints them anyway).
        PHONE("phone", R.drawable.ic_toolbelt_answer),
        LOGO("logo", R.drawable.ic_toolbelt_logo),
        SQUARE("square", R.drawable.ic_toolbelt_square),
        ARROW("arrow", R.drawable.ic_toolbelt_arrow),
        BACK("back", R.drawable.ic_toolbelt_back),
        HANGUP("hangup", R.drawable.ic_toolbelt_hangup),
        HOME("home", R.drawable.ic_toolbelt_home),
        MENU("menu", R.drawable.ic_toolbelt_menu),
        BELL("bell", R.drawable.ic_toolbelt_bell),
        GEAR("gear", R.drawable.ic_toolbelt_gear),
        ASSIST("assist", R.drawable.ic_toolbelt_assist),
        GRID("grid", R.drawable.ic_toolbelt_grid);

        companion object {
            fun fromId(v: String?): ToolbeltIcon =
                entries.firstOrNull { it.id == v } ?: NONE
        }
    }

    data class Slot(
        val icon: ToolbeltIcon,
        val tap: ToolbeltAction,
        val doubleTap: ToolbeltAction,
        val longTap: ToolbeltAction,
        /** Package to launch when the matching action is LAUNCH_APP. */
        val tapArg: String? = null,
        val doubleArg: String? = null,
        val longArg: String? = null,
    )

    /**
     * Q20 belt defaults:
     *  - phone: open the dialer; long-press = voice assist
     *  - BlackBerry logo: Recents; double = quick settings
     *  - centre: Home
     *  - back: Back; long-press = switch to the last app
     *  - hangup: Home, or end-call while in a call (the Q20 "end" key)
     */
    val DEFAULT_SLOTS: List<Slot> = listOf(
        Slot(ToolbeltIcon.PHONE, ToolbeltAction.DIALER, ToolbeltAction.NONE, ToolbeltAction.VOICE_ASSIST),
        Slot(ToolbeltIcon.LOGO, ToolbeltAction.RECENTS, ToolbeltAction.NONE, ToolbeltAction.QUICK_SETTINGS),
        Slot(ToolbeltIcon.HOME, ToolbeltAction.HOME, ToolbeltAction.NONE, ToolbeltAction.NOTIFICATIONS),
        Slot(ToolbeltIcon.BACK, ToolbeltAction.BACK, ToolbeltAction.NONE, ToolbeltAction.LAST_APP),
        Slot(ToolbeltIcon.HANGUP, ToolbeltAction.HANGUP_OR_HOME, ToolbeltAction.NONE, ToolbeltAction.NONE),
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun isEnabled(sp: SharedPreferences): Boolean = sp.getBoolean(KEY_ENABLED, false)

    fun autoHideInFullscreen(sp: SharedPreferences): Boolean =
        sp.getBoolean(KEY_AUTOHIDE_FULLSCREEN, true)

    fun isCollapsible(sp: SharedPreferences): Boolean = sp.getBoolean(KEY_COLLAPSIBLE, false)

    fun isCollapsed(sp: SharedPreferences): Boolean =
        isCollapsible(sp) && sp.getBoolean(KEY_COLLAPSED, false)

    fun setCollapsed(context: Context, collapsed: Boolean) {
        prefs(context).edit().putBoolean(KEY_COLLAPSED, collapsed).apply()
    }

    /** Belt height in dp (also the reserved bottom inset). User-adjustable. */
    fun heightDp(sp: SharedPreferences): Int =
        sp.getInt(KEY_HEIGHT_DP, BELT_TOTAL_DP).coerceIn(36, 88)

    /** Icon size as a fraction of the belt row height (0.5 .. 1.0). */
    fun iconScale(sp: SharedPreferences): Float =
        (sp.getInt(KEY_ICON_SCALE, 78) / 100f).coerceIn(0.4f, 1f)

    /** 0 off, 1 light, 2 medium, 3 strong. */
    fun hapticLevel(sp: SharedPreferences): Int =
        sp.getInt(KEY_HAPTIC, 2).coerceIn(0, 3)

    /** 0 fixed black, 1 Material You, 2 transparent (app's window background shows through). */
    fun colorMode(sp: SharedPreferences): Int = sp.getInt(KEY_COLOR_MODE, 0).coerceIn(0, 2)

    /**
     * [bar background, icon tint] for the current colour mode. Called from the
     * accessibility service context. All modes reserve the belt height as a
     * bottom inset - transparent mode just doesn't paint over it, so the app's
     * own window background fills the strip.
     */
    fun beltColors(context: Context): IntArray {
        val fixedBar = android.graphics.Color.rgb(10, 10, 10)
        val fixedIcon = android.graphics.Color.WHITE
        return when (colorMode(prefs(context))) {
            1 -> {
                val night = (context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                try {
                    if (night) intArrayOf(
                        context.getColor(android.R.color.system_neutral1_800),
                        context.getColor(android.R.color.system_neutral1_50),
                    ) else intArrayOf(
                        context.getColor(android.R.color.system_neutral2_100),
                        context.getColor(android.R.color.system_neutral1_900),
                    )
                } catch (_: Throwable) {
                    intArrayOf(fixedBar, fixedIcon)
                }
            }
            // Transparent: a whisper of scrim so white icons stay readable on a
            // light app background, otherwise the app's window bg shows through.
            2 -> intArrayOf(android.graphics.Color.argb(0x1F, 0, 0, 0), android.graphics.Color.WHITE)
            else -> intArrayOf(fixedBar, fixedIcon)
        }
    }

    /**
     * Enable / disable the whole module. Persists the toggle and pushes the
     * world-readable mirror the launcher hook reads. Restarting the launcher so
     * the hook re-evaluates is left to the caller.
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        pushGlobalActive(enabled)
    }

    fun pushGlobalActive(active: Boolean) {
        RootShell.run("settings put global $GLOBAL_ACTIVE ${if (active) 1 else 0}")
    }

    /**
     * Total reserved height in dp: 0 when off, the grab strip when collapsed, or
     * the belt (+ handle if collapsible) when shown. Every colour mode reserves
     * the space.
     */
    fun reservedDp(sp: SharedPreferences): Int = when {
        !isEnabled(sp) -> 0
        isCollapsed(sp) -> COLLAPSED_DP
        isCollapsible(sp) -> heightDp(sp) + HANDLE_DP
        else -> heightDp(sp)
    }

    /**
     * Push the bottom inset the launcher hook reserves for the belt and return
     * whether it changed (the taskbar only re-reads this on recreation, so the
     * caller restarts the launcher only when this returns true).
     */
    fun pushInset(context: Context): Boolean {
        val px = (reservedDp(prefs(context)) * context.resources.displayMetrics.density).toInt()
        val cur = RootShell.run("settings get global $GLOBAL_INSET_PX").outString.trim().toIntOrNull()
        if (cur == px) return false
        RootShell.run("settings put global $GLOBAL_INSET_PX $px")
        return true
    }

    /** Records the live navigation_mode so the hook knows whether to kill the edge gesture too. */
    fun syncNavMode(context: Context): Int {
        val mode = try {
            android.provider.Settings.Secure.getInt(
                context.contentResolver, "navigation_mode", 0
            )
        } catch (_: Exception) {
            0
        }
        RootShell.run("settings put global $GLOBAL_NAV_MODE $mode")
        return mode
    }

    // --- slot config (de)serialisation ------------------------------------

    fun getSlots(sp: SharedPreferences): List<Slot> {
        val raw = sp.getString(KEY_SLOTS, null) ?: return DEFAULT_SLOTS
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Slot>(SLOT_COUNT)
            for (i in 0 until SLOT_COUNT) {
                val o = arr.optJSONObject(i)
                if (o == null) {
                    out.add(DEFAULT_SLOTS[i])
                } else {
                    out.add(
                        Slot(
                            icon = ToolbeltIcon.fromId(o.optString("icon")),
                            tap = ToolbeltAction.fromId(o.optString("tap")),
                            doubleTap = ToolbeltAction.fromId(o.optString("double")),
                            longTap = ToolbeltAction.fromId(o.optString("long")),
                            tapArg = o.optString("tapArg").ifEmpty { null },
                            doubleArg = o.optString("doubleArg").ifEmpty { null },
                            longArg = o.optString("longArg").ifEmpty { null },
                        )
                    )
                }
            }
            out
        } catch (_: Exception) {
            DEFAULT_SLOTS
        }
    }

    fun getSlots(context: Context): List<Slot> = getSlots(prefs(context))

    fun setSlots(context: Context, slots: List<Slot>) {
        val arr = JSONArray()
        slots.take(SLOT_COUNT).forEach { s ->
            arr.put(
                JSONObject()
                    .put("icon", s.icon.id)
                    .put("tap", s.tap.id)
                    .put("double", s.doubleTap.id)
                    .put("long", s.longTap.id)
                    .put("tapArg", s.tapArg ?: "")
                    .put("doubleArg", s.doubleArg ?: "")
                    .put("longArg", s.longArg ?: "")
            )
        }
        prefs(context).edit().putString(KEY_SLOTS, arr.toString()).apply()
    }

    fun resetSlots(context: Context) = setSlots(context, DEFAULT_SLOTS)

    /**
     * Overridden to return true by [com.kgr.key2toolbox.xposed.NavBarHookInit]
     * when it loads in our own process, so the UI can tell the user whether the
     * LSPosed side is actually active. Keep the body a plain `return false`.
     */
    @JvmStatic
    fun isXposedActive(): Boolean = false

    /** Restart the launcher so NavBarHookInit's taskbar hooks re-run the inset math. */
    fun restartLauncher(): Boolean {
        val pid = RootShell.run("pidof com.android.launcher3").outString.trim()
            .ifEmpty { RootShell.run("pidof org.lineageos.trebuchet").outString.trim() }
        if (pid.isEmpty()) return false
        return RootShell.run("kill -9 $pid").success
    }
}
