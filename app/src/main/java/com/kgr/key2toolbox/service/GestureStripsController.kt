package com.kgr.key2toolbox.service

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.kgr.key2toolbox.modules.GestureSettings
import com.kgr.key2toolbox.modules.GestureSettings.Zone
import com.kgr.key2toolbox.modules.SystemBackGesture

/**
 * Custom edge gestures: thin TYPE_ACCESSIBILITY_OVERLAY strips on the side edges, each driving an [EdgeSwipe]
 * recognizer. Each gesture (straight / diagonal up / diagonal down), plain or held, runs a configurable
 * [GestureSettings.Action]; the defaults are swipe = Back, hold = Previous app. (Q25 Toolbox also has a bottom strip;
 * it is not ported: on the Key2 the bottom edge belongs to the Toolbelt.)
 *
 * The strips take the touches that land on them (a tap there is not forwarded to the app underneath), which is
 * why they are thin and why each zone can be switched off on its own. They are not shown on the lockscreen or
 * with the screen off. Everything runs on the main thread; [reconcile] is idempotent and rebuilds from prefs.
 */
object GestureStripsController {

    private val main = Handler(Looper.getMainLooper())
    private val strips = ArrayList<View>()
    /** The shared arrow overlay (null unless a zone has its arrow on). */
    private var arrow: GestureArrowView? = null
    private var wm: WindowManager? = null
    private var appContext: Context? = null
    private var lastEnsure = 0L
    private var serviceRef: java.lang.ref.WeakReference<Key2AccessibilityService>? = null

    /**
     * While the screen is on but the strips are not allowed yet (locked), look again every second and rebuild as soon
     * as they are. Relying on broadcasts or accessibility events alone left the strips missing after an unlock whenever
     * the last event arrived before the keyguard counted as gone. Stops by itself when the screen is off or the strips
     * come up.
     */
    private val recheck = object : Runnable {
        override fun run() {
            val s = serviceRef?.get() ?: return
            val pm = s.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) return // screen off: SCREEN_ON rebuilds
            if (allowed(s)) { android.util.Log.d("Key2Toolbox", "gesture strips: keyguard gone, rebuilding"); reconcile(s) }
            else main.postDelayed(this, 1000L)
        }
    }

    private fun scheduleRecheck() {
        main.removeCallbacks(recheck)
        main.postDelayed(recheck, 1000L)
    }

    /** Whether the strips may be up right now: screen on and keyguard gone. */
    private fun allowed(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return pm.isInteractive && !km.isKeyguardLocked
    }

    // Foreground app, so the strips can be switched off in the excluded ones.
    @Volatile private var foreground: String? = null
    @Volatile private var excludedNow = false

    /** Called by the service when the foreground app changes: rebuilds only if its excluded status flipped. */
    fun onForegroundChanged(service: Key2AccessibilityService, pkg: String?) {
        foreground = pkg
        val ex = pkg != null && pkg in GestureSettings.excludedApps(service)
        if (ex != excludedNow) reconcile(service)
    }

    /**
     * Rebuilds the strips after an unlock. ACTION_USER_PRESENT is not reliable for this: on the Key2 the system sends it
     * but it never reached our receiver, and SCREEN_ON arrives while the keyguard still counts as locked, so after an
     * unlock (especially a biometric one) nothing rebuilt the strips until a setting changed. KeyguardManager's locked-state
     * listener is not an option: it needs a permission only privileged apps can hold (it crashed the service).
     *
     * So this is a safety net, called from the service's accessibility events (main thread): if a strip should be up but
     * none is - whatever removed them or kept them from being built - rebuild. Cheap when strips exist, and at most once a second
     * otherwise; does nothing while the strips are not allowed (locked, screen off, excluded app, all sides off).
     */
    fun ensure(service: Key2AccessibilityService) {
        if (strips.isNotEmpty()) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastEnsure < 1000L) return
        lastEnsure = now
        if (!allowed(service)) return
        if (foreground?.let { it in GestureSettings.excludedApps(service) } == true) return
        if (Zone.entries.none { GestureSettings.get(service, it).mode == GestureSettings.Mode.CUSTOM }) return
        android.util.Log.d("Key2Toolbox", "gesture strips missing while allowed: rebuilding")
        reconcile(service)
    }

    /** Rebuilds the strips from the saved settings (adds, resizes or removes them). */
    fun reconcile(service: Key2AccessibilityService): Boolean = main.post {
        appContext = service.applicationContext
        serviceRef = java.lang.ref.WeakReference(service)
        main.removeCallbacks(recheck)
        removeAll()
        excludedNow = foreground?.let { it in GestureSettings.excludedApps(service) } == true
        if (excludedNow || !allowed(service)) {
            SystemBackGesture.sync(service, emptySet())
            // Screen on but locked: keep looking until it is allowed (an excluded app is handled by the foreground event).
            if (!excludedNow && (service.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) scheduleRecheck()
            return@post
        }
        val left = GestureSettings.get(service, Zone.LEFT)
        val right = GestureSettings.get(service, Zone.RIGHT)
        val on = listOf(left, right).filter { it.mode == GestureSettings.Mode.CUSTOM }
        if (on.isEmpty()) { SystemBackGesture.sync(service, emptySet()); return@post }

        val manager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = manager
        if (on.any { it.arrow }) addArrow(service, manager)
        val bounds = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) manager.currentWindowMetrics.bounds
            else android.graphics.Rect(0, 0, service.resources.displayMetrics.widthPixels, service.resources.displayMetrics.heightPixels)
        val dp = service.resources.displayMetrics.density
        val tint = GestureSettings.showStrips(service)

        // The system back gesture is switched off exactly on the sides whose strip is up (and asked for it).
        val sysOff = HashSet<Zone>()
        // Each side strip has its own size; the strip is as tall as its own length setting says.
        for ((edge, cfg, gravity) in listOf(
            Triple(EdgeSwipe.Edge.LEFT, left, Gravity.START or Gravity.CENTER_VERTICAL),
            Triple(EdgeSwipe.Edge.RIGHT, right, Gravity.END or Gravity.CENTER_VERTICAL),
        )) {
            if (cfg.mode != GestureSettings.Mode.CUSTOM) continue
            val added = add(service, manager, edge, cfg, (cfg.thicknessDp * dp).toInt(), (bounds.height() * cfg.lengthPct / 100f).toInt(), gravity, tint)
            val zone = if (edge == EdgeSwipe.Edge.LEFT) Zone.LEFT else Zone.RIGHT
            if (added && GestureSettings.systemBackOff(service, zone)) sysOff += zone
        }
        SystemBackGesture.sync(service, sysOff)
    }

    fun hide(): Boolean = main.post {
        main.removeCallbacks(recheck)
        removeAll()
        appContext?.let { SystemBackGesture.sync(it, emptySet()) }
    }

    private fun removeAll() {
        val manager = wm
        arrow = null
        for (v in strips) try { manager?.removeView(v) } catch (_: Exception) { }
        strips.clear()
    }

    /** Full-screen, non-touchable window for the arrow: touches pass straight through it. */
    private fun addArrow(service: Key2AccessibilityService, manager: WindowManager) {
        val view = GestureArrowView(service)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { title = "Key2 gesture arrow" }
        try {
            manager.addView(view, lp)
            strips.add(view)
            arrow = view
        } catch (t: Throwable) {
            android.util.Log.e("Key2Toolbox", "gesture arrow failed", t)
        }
    }

    private fun add(
        service: Key2AccessibilityService, manager: WindowManager, edge: EdgeSwipe.Edge,
        cfg: GestureSettings.Config, w: Int, h: Int, gravity: Int, tint: Boolean,
    ): Boolean {
        val view = StripView(service, edge, cfg, tint)
        val lp = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            title = "Key2 gesture strip $edge"
        }
        try {
            manager.addView(view, lp)
            strips.add(view)
            return true
        } catch (t: Throwable) {
            android.util.Log.e("Key2Toolbox", "gesture strip $edge failed", t)
            return false
        }
    }

    private fun vibrator(ctx: Context): android.os.Vibrator =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator)

    fun hasAmplitudeControl(ctx: Context) = vibrator(ctx).hasAmplitudeControl()

    /** One pulse of [ms] at [strengthPct] (amplitude is ignored by motors without amplitude control). */
    fun vibrate(ctx: Context, ms: Int, strengthPct: Int) {
        if (ms <= 0) return
        try {
            val v = vibrator(ctx)
            val amp = if (v.hasAmplitudeControl()) (strengthPct * 255 / 100).coerceIn(1, 255)
                      else android.os.VibrationEffect.DEFAULT_AMPLITUDE
            v.vibrate(android.os.VibrationEffect.createOneShot(ms.toLong(), amp))
        } catch (_: Throwable) { }
    }

    /** Plays the configured tick, a pause, then the action pulse (the settings screen's "Test" button). */
    fun testVibration(ctx: Context) {
        val cfg = GestureSettings.vibration(ctx)
        vibrate(ctx, cfg.tickMs, cfg.strengthPct)
        main.postDelayed({ vibrate(ctx, cfg.actionMs, cfg.strengthPct) }, 1500)
    }

    /** Runs [a] through the service (it knows about our Recents overlay). */
    private fun perform(service: Key2AccessibilityService, a: GestureSettings.Action) = service.performEdgeAction(a)

    @SuppressLint("ViewConstructor")
    private class StripView(
        private val service: Key2AccessibilityService,
        private val edgeOf: EdgeSwipe.Edge,
        private val cfg: GestureSettings.Config,
        tint: Boolean,
    ) : View(service) {
        private val zone = if (edgeOf == EdgeSwipe.Edge.LEFT) Zone.LEFT else Zone.RIGHT
        // Side edges tell straight from diagonal; every zone can have a held variant.
        private val swipe = EdgeSwipe(edgeOf, cfg.distanceDp * service.resources.displayMetrics.density,
            holdEnabled = true, diagonals = true)
        private val holdTimer = Runnable {
            // A hold with nothing assigned does not consume the gesture: releasing then runs the swipe action.
            val a = GestureSettings.binding(service, zone, swipe.direction(), hold = true)
            if (a != GestureSettings.Action.NONE && swipe.onHoldElapsed()) {
                buzz(tick = false); perform(service, a)
                if (cfg.arrow) arrow?.end(true)
            }
        }

        /** Armed = releasing now would do something (a swipe action, or a hold action about to fire). */
        private fun armed(): Boolean {
            if (!swipe.isCrossed()) return false
            val d = swipe.direction()
            return GestureSettings.binding(service, zone, d, false) != GestureSettings.Action.NONE ||
                GestureSettings.binding(service, zone, d, true) != GestureSettings.Action.NONE
        }

        init {
            // Invisible by default; the tint option makes the strip visible so the user can place it.
            setBackgroundColor(if (tint) Color.argb(90, 0, 150, 255) else Color.TRANSPARENT)
        }

        /** [ms] short = threshold tick, longer = action. Direct vibrator: the system haptic is inaudible on this motor. */
        private fun buzz(tick: Boolean) {
            if (!cfg.haptic) return
            val v = GestureSettings.vibration(service)
            vibrate(service, if (tick) v.tickMs else v.actionMs, v.strengthPct)
        }

        // Absolute screen coordinates: the view itself is tiny, and the finger leaves it during the swipe.
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { swipe.onDown(e.rawX, e.rawY); if (cfg.arrow) arrow?.begin(edgeOf, e.rawX, e.rawY, GestureSettings.arrowStyle(service)) }
                MotionEvent.ACTION_MOVE -> when (swipe.onMove(e.rawX, e.rawY)) {
                    EdgeSwipe.Result.CROSSED -> {
                        buzz(tick = true) // marks the distance: from here on, releasing completes the gesture
                        postDelayed(holdTimer, GestureSettings.HOLD_MS)
                    }
                    EdgeSwipe.Result.CANCELLED -> removeCallbacks(holdTimer)
                    else -> {}
                }.also { if (cfg.arrow) arrow?.update(e.rawX, e.rawY, swipe.fraction(), swipe.travelAngleDeg(), armed()) }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(holdTimer)
                    if (swipe.onUp() == EdgeSwipe.Result.SWIPE) {
                        val a = GestureSettings.binding(service, zone, swipe.direction(), hold = false)
                        if (a != GestureSettings.Action.NONE) { buzz(tick = false); perform(service, a) }
                        if (cfg.arrow) arrow?.end(a != GestureSettings.Action.NONE)
                    } else if (cfg.arrow) arrow?.end(false)
                }
                MotionEvent.ACTION_CANCEL -> { removeCallbacks(holdTimer); if (cfg.arrow) arrow?.end(false) }
            }
            return true
        }
    }
}
