package com.kgr.key2toolbox.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.kgr.key2toolbox.modules.ToolbeltController
import com.kgr.key2toolbox.modules.ToolbeltController.ToolbeltAction

/**
 * Renders the Q20-style toolbelt as a single TYPE_ACCESSIBILITY_OVERLAY window
 * pinned to the bottom of the screen (same window trick as [TickerOverlayController]).
 *
 * The launcher hook ([com.kgr.key2toolbox.xposed.NavBarHookInit]) reserves the
 * belt height as a bottom inset so app content ends above it.
 *
 * - Not collapsible (default): a fixed bar, always shown while enabled.
 * - Collapsible (opt-in): a small grab strip sits above the icons. Swiping down
 *   on it - or a slot bound to TOGGLE_BELT - collapses the belt to just that
 *   strip; a tap or swipe-up on the strip brings it back. Each toggle writes the
 *   pref, and the service restarts the launcher to re-negotiate the reserved
 *   inset (this build's taskbar won't do it live).
 * - Either way the belt slides fully away for a fullscreen app or the keyboard.
 */
object ToolbeltOverlayController {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var root: FrameLayout? = null
    private var beltRow: LinearLayout? = null
    private var grabStrip: View? = null
    private var handleView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var actionHandler: ((ToolbeltAction, String?) -> Unit)? = null
    private var service: AccessibilityService? = null

    private var collapsible = false
    private var collapsed = false
    private var fullscreenHidden = false
    private var imeHidden = false
    private var autoHidePref = true

    // Live config, refreshed from prefs on every refresh().
    private var beltDp = ToolbeltController.BELT_TOTAL_DP
    private var iconScale = 0.78f
    private var hapticLevel = 2
    private var colorMode = 0
    private var barColor = Color.rgb(10, 10, 10)
    private var iconColor = Color.WHITE

    private val handleDp get() = if (collapsible) ToolbeltController.HANDLE_DP else 0

    private var vibrator: Vibrator? = null

    // Pull-to-grab: live translationY of the belt row (0 = fully shown) and the
    // running settle spring.
    private var dragTy = 0f
    private var springCb: Choreographer.FrameCallback? = null
    private var springVel = 0f

    /** Recompute + apply the bar / icon colours (cheap - no window rebuild). */
    private fun applyColors() {
        val svc = service ?: return
        val c = ToolbeltController.beltColors(svc)
        barColor = c[0]; iconColor = c[1]
        // Only the belt row carries the colour, so the layers don't stack (which
        // would double a translucent scrim). The container stays transparent.
        root?.setBackgroundColor(Color.TRANSPARENT)
        val visible = !(fullscreenHidden || imeHidden)
        beltRow?.setBackgroundColor(if (visible) barColor else Color.TRANSPARENT)
        grabStrip?.setBackgroundColor(if (visible && collapsed) barColor else Color.TRANSPARENT)
        beltRow?.let { row ->
            for (i in 0 until row.childCount) {
                (row.getChildAt(i) as? ImageView)?.setColorFilter(iconColor)
            }
        }
    }

    /** kind: 0 = tap, 1 = long-press. Scaled by [hapticLevel]. */
    private fun buzz(kind: Int) {
        if (hapticLevel == 0) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            val effect = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val id = when (hapticLevel) {
                    1 -> VibrationEffect.EFFECT_TICK
                    3 -> VibrationEffect.EFFECT_HEAVY_CLICK
                    else -> if (kind == 1) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_CLICK
                }
                VibrationEffect.createPredefined(id)
            } else {
                val ms = (if (kind == 1) 30L else 12L) * hapticLevel
                VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            v.vibrate(effect)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ API

    fun refresh(
        svc: AccessibilityService,
        onAction: (ToolbeltAction, String?) -> Unit,
        rebuild: Boolean = false,
    ) {
        service = svc
        actionHandler = onAction
        val sp = svc.getSharedPreferences(ToolbeltController.PREFS, Context.MODE_PRIVATE)
        if (!ToolbeltController.isEnabled(sp)) {
            hide()
            return
        }
        autoHidePref = ToolbeltController.autoHideInFullscreen(sp)
        collapsed = ToolbeltController.isCollapsed(sp)
        val newDp = ToolbeltController.heightDp(sp)
        val newScale = ToolbeltController.iconScale(sp)
        val newCollapsible = ToolbeltController.isCollapsible(sp)
        val newColorMode = ToolbeltController.colorMode(sp)
        val geometryChanged = newDp != beltDp || newScale != iconScale ||
            newCollapsible != collapsible || newColorMode != colorMode
        beltDp = newDp
        iconScale = newScale
        collapsible = newCollapsible
        hapticLevel = ToolbeltController.hapticLevel(sp)
        colorMode = newColorMode
        if (geometryChanged) hide() // a size / mode change needs a fresh window
        mainHandler.post {
            ensureAttached(svc, sp, rebuild)
            applyColors()
        }
    }

    fun hide() {
        mainHandler.post {
            val v = root ?: return@post
            root = null; beltRow = null; grabStrip = null; handleView = null; params = null
            try {
                windowManager?.removeView(v)
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    fun setImeVisible(visible: Boolean) {
        if (imeHidden == visible) return
        imeHidden = visible
        mainHandler.post { applyLayoutState(animate = true) }
    }

    fun setForegroundFullscreen(fullscreen: Boolean) {
        val want = fullscreen && autoHidePref
        if (fullscreenHidden == want) return
        fullscreenHidden = want
        mainHandler.post { applyLayoutState(animate = true) }
    }

    // ------------------------------------------------------------- internals

    private fun setCollapsed(value: Boolean) {
        if (!collapsible || collapsed == value) return
        collapsed = value
        service?.let { ToolbeltController.setCollapsed(it, value) } // -> prefListener -> inset + launcher restart
        applyLayoutState(animate = true)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureAttached(
        svc: AccessibilityService,
        sp: android.content.SharedPreferences,
        rebuild: Boolean,
    ) {
        if (root != null) {
            if (rebuild) rebuildBelt(svc, sp)
            applyLayoutState(animate = false)
            return
        }

        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = svc.resources.displayMetrics.density
        fun px(dp: Number) = (dp.toFloat() * density).toInt()

        if (vibrator == null) {
            vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                (svc.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as? android.os.VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                svc.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }

        val container = FrameLayout(svc)
        container.setBackgroundColor(Color.TRANSPARENT)

        val belt = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(barColor)
        }
        container.addView(
            belt,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(beltDp)).apply {
                gravity = Gravity.BOTTOM
            }
        )

        // Grab strip: full-width touch target. When expanded it's a thin band
        // above the icons; when collapsed it fills the whole (short) window so
        // any touch along the very bottom edge brings the belt back.
        val strip = View(svc).apply {
            visibility = if (collapsible) View.VISIBLE else View.GONE
        }
        container.addView(
            strip,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(ToolbeltController.HANDLE_DP))
                .apply { gravity = Gravity.TOP }
        )

        val handlePill = View(svc).apply {
            background = GradientDrawable().apply {
                cornerRadius = px(2).toFloat()
                setColor(Color.argb(170, 235, 235, 235))
            }
            visibility = if (collapsible) View.VISIBLE else View.GONE
        }
        container.addView(
            handlePill,
            FrameLayout.LayoutParams(px(48), px(4)).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = px(3)
            }
        )
        handleView = handlePill

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            px(beltDp + handleDp),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                fitInsetsSides = 0
            }
        }

        // Pull-to-grab: the belt row follows the finger between "shown" (ty 0) and
        // "collapsed" (ty = belt height), with an elastic overshoot past either
        // end and a spring settle on release; a tap toggles. The same handler
        // runs on the thin strip and on the handle pill.
        val slop = ViewConfiguration.get(svc).scaledTouchSlop
        var downRawY = 0f
        var downTy = 0f
        var moved = false
        var vt: VelocityTracker? = null
        var lastDetent = -1
        val gripTouch = View.OnTouchListener { v, ev ->
            val d = v.resources.displayMetrics.density
            val beltPx = beltDp * d
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    springCancel()
                    beginDrag()
                    downRawY = ev.rawY
                    downTy = dragTy
                    moved = false
                    lastDetent = if (collapsed) 1 else 0
                    vt = VelocityTracker.obtain().apply { addMovement(ev) }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(ev)
                    if (!moved && kotlin.math.abs(ev.rawY - downRawY) > slop) moved = true
                    var ty = downTy + (ev.rawY - downRawY)
                    ty = when {
                        ty < 0f -> ty * 0.35f
                        ty > beltPx -> beltPx + (ty - beltPx) * 0.35f
                        else -> ty
                    }
                    applyBeltTy(ty)
                    val detent = if (ty > beltPx * 0.5f) 1 else 0
                    if (detent != lastDetent) { lastDetent = detent; buzz(0) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    var vy = 0f
                    vt?.apply { addMovement(ev); computeCurrentVelocity(1000); vy = yVelocity; recycle() }
                    vt = null
                    if (!moved && ev.actionMasked == MotionEvent.ACTION_UP) {
                        buzz(0)
                        collapsed = !collapsed
                        service?.let { ToolbeltController.setCollapsed(it, collapsed) }
                        settle(0f)
                    } else {
                        val wantCollapsed = when {
                            ev.actionMasked == MotionEvent.ACTION_CANCEL -> collapsed
                            vy < -1200f -> false
                            vy > 1200f -> true
                            else -> dragTy > beltPx * 0.5f
                        }
                        if (wantCollapsed != collapsed) {
                            collapsed = wantCollapsed
                            service?.let { ToolbeltController.setCollapsed(it, wantCollapsed) }
                            buzz(0)
                        }
                        settle(vy)
                    }
                    true
                }
                else -> true
            }
        }
        strip.setOnTouchListener(gripTouch)
        handlePill.setOnTouchListener(gripTouch)

        try {
            wm.addView(container, lp)
        } catch (_: Exception) {
            return
        }
        windowManager = wm
        root = container
        beltRow = belt
        grabStrip = strip
        params = lp

        rebuildBelt(svc, sp)
        applyLayoutState(animate = false)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun rebuildBelt(svc: AccessibilityService, sp: android.content.SharedPreferences) {
        val belt = beltRow ?: return
        belt.removeAllViews()
        val density = svc.resources.displayMetrics.density
        val rowPx = beltDp * density
        val pad = (rowPx * (1f - iconScale) / 2f).toInt().coerceAtLeast(0)

        ToolbeltController.getSlots(sp).forEachIndexed { index, slot ->
            val icon = ImageView(svc).apply {
                setImageResource(slot.icon.res)
                setColorFilter(iconColor)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(pad, pad, pad, pad)
                background = rippleBackground()
                isClickable = true
                contentDescription = "toolbelt-slot-${index + 1}-${slot.tap.id}"
            }
            belt.addView(icon, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

            // A slot with no double-tap action fires on tap-up (no wait). Only
            // slots that actually use double-tap pay the ~300 ms disambiguation
            // delay of onSingleTapConfirmed.
            val hasDouble = slot.doubleTap != ToolbeltAction.NONE
            val detector = GestureDetector(svc, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    if (!hasDouble) fire(slot.tap, slot.tapArg)
                    return true
                }
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (hasDouble) fire(slot.tap, slot.tapArg)
                    return true
                }
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    fire(slot.doubleTap, slot.doubleArg); return true
                }
                override fun onLongPress(e: MotionEvent) {
                    fire(slot.longTap, slot.longArg, longPress = true)
                }
            })
            icon.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { v.isPressed = true; buzz(0) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.isPressed = false
                }
                detector.onTouchEvent(ev)
            }
        }
    }

    private fun fire(action: ToolbeltAction, arg: String?, longPress: Boolean = false) {
        if (action == ToolbeltAction.NONE) return
        // Taps already buzzed on ACTION_DOWN; a long-press gets a second, firmer
        // buzz to confirm the hold registered.
        if (longPress) buzz(1)
        if (action == ToolbeltAction.TOGGLE_BELT) setCollapsed(!collapsed)
        else actionHandler?.invoke(action, arg)
    }

    private val density0 get() = root?.resources?.displayMetrics?.density ?: 2.75f
    private fun fullHPx() = ((beltDp + handleDp) * density0).toInt().coerceAtLeast(1)
    private fun collapsedHPx() = (ToolbeltController.COLLAPSED_DP * density0).toInt().coerceAtLeast(1)

    /** Rigid belt-row translationY target for the current state (0 = fully shown). */
    private fun tyRest(): Float {
        val d = density0
        return when {
            fullscreenHidden || imeHidden -> (beltDp + handleDp) * d
            collapsed -> beltDp * d
            else -> 0f
        }
    }

    /** Move belt row, grip strip and handle pill together (mid-drag and mid-spring). */
    private fun applyBeltTy(ty: Float) {
        dragTy = ty
        beltRow?.translationY = ty
        grabStrip?.translationY = ty
        handleView?.translationY = ty
    }

    private fun setWindowHeight(h: Int) {
        val c = root ?: return
        val lp = params ?: return
        if (lp.height == h) return
        lp.height = h
        try { windowManager?.updateViewLayout(c, lp) } catch (_: IllegalArgumentException) {}
    }

    private fun stripHeight(h: Int) {
        val s = grabStrip ?: return
        (s.layoutParams as? FrameLayout.LayoutParams)?.let {
            if (it.height != h) { it.height = h; s.layoutParams = it }
        }
    }

    /**
     * Grow the window to full height and put the grip strip into its thin,
     * translatable form so the belt row has room to follow the finger / spring.
     * The current on-screen position is preserved by re-asserting it as a
     * translation ([dragTy] already tracks it).
     */
    private fun beginDrag() {
        if (fullscreenHidden || imeHidden) return
        setWindowHeight(fullHPx())
        stripHeight((handleDp * density0).toInt().coerceAtLeast(1))
        applyBeltTy(dragTy)
    }

    private fun applyLayoutState(animate: Boolean) {
        val strip = grabStrip ?: return
        val hidden = fullscreenHidden || imeHidden

        strip.visibility = if (collapsible && !hidden) View.VISIBLE else View.GONE
        handleView?.visibility = if (collapsible && !hidden) View.VISIBLE else View.GONE
        applyColors()

        if (!animate) {
            springCancel()
            applyRestLayout()
            return
        }
        beginDrag()          // hold the window open for the animation
        settle(0f)
    }

    private fun settle(initialVel: Float) {
        springTo(tyRest(), initialVel) { applyRestLayout() }
    }

    /** Snap window / strip / translations to the tidy resting layout for the state. */
    private fun applyRestLayout() {
        val d = density0
        val beltPx = beltDp * d
        val handlePx = handleDp * d
        when {
            fullscreenHidden || imeHidden -> {
                applyBeltTy(beltPx + handlePx)
                setWindowHeight(1)
            }
            collapsed && collapsible -> {
                val ch = collapsedHPx()
                stripHeight(ch)
                setWindowHeight(ch)
                beltRow?.translationY = beltPx
                grabStrip?.translationY = 0f
                handleView?.translationY = 0f
                dragTy = beltPx
            }
            else -> {
                stripHeight(handlePx.toInt().coerceAtLeast(1))
                setWindowHeight(fullHPx())
                applyBeltTy(0f)
            }
        }
    }

    private fun springCancel() {
        springCb?.let { Choreographer.getInstance().removeFrameCallback(it) }
        springCb = null
    }

    /**
     * Damped spring driving [dragTy] to [target] (px), seeded with [initialVel]
     * (px/s). `dampingRatio < 1` gives it a small, pleasant overshoot on the way
     * in. Runs on the Choreographer so it stays in step with the display.
     */
    private fun springTo(target: Float, initialVel: Float, onEnd: () -> Unit) {
        springCancel()
        springVel = initialVel
        val stiffness = 950f
        val dampingRatio = 0.75f
        val critical = 2f * kotlin.math.sqrt(stiffness)
        var lastNs = 0L
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(now: Long) {
                if (springCb !== this) return
                val dt = if (lastNs == 0L) 0.016f
                else ((now - lastNs) / 1_000_000_000f).coerceIn(0.001f, 0.032f)
                lastNs = now
                val x = dragTy
                val accel = -stiffness * (x - target) - dampingRatio * critical * springVel
                springVel += accel * dt
                val nx = x + springVel * dt
                if (kotlin.math.abs(nx - target) < 0.5f && kotlin.math.abs(springVel) < 3f) {
                    applyBeltTy(target)
                    springCb = null
                    onEnd()
                    return
                }
                applyBeltTy(nx)
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        springCb = cb
        Choreographer.getInstance().postFrameCallback(cb)
    }

    private fun rippleBackground(): android.graphics.drawable.Drawable {
        val ctx = beltRow?.context ?: return GradientDrawable()
        val out = TypedValue()
        return if (ctx.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, out, true
            )
        ) {
            ctx.getDrawable(out.resourceId) ?: GradientDrawable()
        } else {
            GradientDrawable()
        }
    }
}
