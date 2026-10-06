package com.kgr.key2toolbox.modules

import android.content.Context
import com.kgr.key2toolbox.core.RootShell
import com.kgr.key2toolbox.modules.GestureSettings.Zone
import com.kgr.key2toolbox.service.Key2AccessibilityService
import java.util.concurrent.Executors

/**
 * Switches the system's edge-back gesture (gesture navigation) off on one side while a custom edge strip is shown
 * there, so the two do not fire together.
 *
 * SystemUI sizes each back-gesture zone as the default inset times `Settings.Secure.back_gesture_inset_scale_left` /
 * `_right`; a scale of 0 gives a zone of zero width, i.e. no gesture on that edge. Writing that setting needs root.
 *
 * The user's own value is saved the first time we override it (`sysback_orig_<side>`, "null" = it was unset) and
 * written back as soon as the strip is gone: lockscreen, screen off, an excluded app, the toggle turned off, the
 * accessibility service stopping. The saved value is also what makes [sync] correct after the process died while an
 * override was in place.
 */
object SystemBackGesture {

    private val io = Executors.newSingleThreadExecutor()

    /** Zones whose override is currently in place, as far as this process knows (null until the first sync). */
    @Volatile private var applied: Set<Zone>? = null

    private fun secureKey(zone: Zone) =
        if (zone == Zone.LEFT) "back_gesture_inset_scale_left" else "back_gesture_inset_scale_right"

    /**
     * Not under the "gest_" prefix: the service rebuilds the strips on any change to a "gest_" key, and writing the
     * saved original from here must not trigger that.
     */
    private fun origKey(zone: Zone) = "sysback_orig_" + if (zone == Zone.LEFT) "left" else "right"

    /** Earlier name of the saved original; migrated so an override already in place is still restored. */
    private fun legacyOrigKey(zone: Zone) = "gest_sys_orig_" + if (zone == Zone.LEFT) "left" else "right"

    private fun prefs(context: Context) =
        context.getSharedPreferences(Key2AccessibilityService.PREFS, Context.MODE_PRIVATE)

    /** Disables the system gesture on exactly the zones in [off] and restores every other one. Off the main thread. */
    fun sync(context: Context, off: Set<Zone>) {
        val ctx = context.applicationContext
        io.execute { try { syncNow(ctx, off) } catch (_: Throwable) { } }
    }

    /** Restores every side and waits for it (service teardown). Blocking: runs root commands. */
    fun restoreAll(context: Context) {
        try { syncNow(context.applicationContext, emptySet()) } catch (_: Throwable) { }
    }

    @Synchronized
    private fun syncNow(context: Context, off: Set<Zone>) {
        if (applied == off) return
        val p = prefs(context)
        for (zone in Zone.entries) {
            val key = secureKey(zone)
            val saved = origKey(zone)
            if (p.contains(legacyOrigKey(zone))) {
                if (!p.contains(saved)) p.edit().putString(saved, p.getString(legacyOrigKey(zone), "null")).apply()
                p.edit().remove(legacyOrigKey(zone)).apply()
            }
            val pending = p.contains(saved)
            if (zone in off) {
                if (!pending) {
                    val cur = RootShell.run("settings get secure $key").outString.trim()
                    p.edit().putString(saved, cur.ifEmpty { "null" }).apply()
                }
                RootShell.run("settings put secure $key 0")
            } else if (pending) {
                val orig = p.getString(saved, "null") ?: "null"
                RootShell.run(if (orig == "null") "settings delete secure $key" else "settings put secure $key $orig")
                p.edit().remove(saved).apply()
            }
        }
        applied = off
    }
}
