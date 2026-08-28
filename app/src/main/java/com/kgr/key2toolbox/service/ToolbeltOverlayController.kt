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
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
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
        container.setBackgroundColor(barColor)

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

        // Toggle the belt from the grab strip. A plain click (most reliable) or a
        // long-press expands it while collapsed; a downward drag collapses it
        // while shown. Small threshold so it's easy to trigger.
        val threshold = 8f * density
        val stripDetector = GestureDetector(svc, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float
            ): Boolean {
                val start = e1 ?: return false
                val moved = e2.rawY - start.rawY
                if (collapsed && moved < -threshold) { buzz(0); setCollapsed(false); return true }
                if (!collapsed && moved > threshold) { buzz(0); setCollapsed(true); return true }
                return false
            }
            override fun onLongPress(e: MotionEvent) {
                buzz(0); setCollapsed(!collapsed)
            }
        })
        strip.setOnClickListener { buzz(0); setCollapsed(!collapsed) }
        strip.setOnTouchListener { _, ev -> stripDetector.onTouchEvent(ev); false }

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

            val detector = GestureDetector(svc, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    fire(slot.tap, slot.tapArg); return true
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
                    MotionEvent.ACTION_DOWN -> v.isPressed = true
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.isPressed = false
                }
                detector.onTouchEvent(ev)
            }
        }
    }

    private fun fire(action: ToolbeltAction, arg: String?, longPress: Boolean = false) {
        if (action == ToolbeltAction.NONE) return
        buzz(if (longPress) 1 else 0)
        if (action == ToolbeltAction.TOGGLE_BELT) setCollapsed(!collapsed)
        else actionHandler?.invoke(action, arg)
    }

    private fun applyLayoutState(animate: Boolean) {
        val container = root ?: return
        val belt = beltRow ?: return
        val strip = grabStrip ?: return
        val lp = params ?: return
        val wm = windowManager ?: return

        val hidden = fullscreenHidden || imeHidden
        val density = container.resources.displayMetrics.density
        val beltPx = beltDp * density
        val handlePx = handleDp * density
        val collapsedPx = ToolbeltController.COLLAPSED_DP * density

        // Belt row translation: fully off when hidden, or tucked below the strip
        // when collapsed.
        val beltTy = when {
            hidden -> beltPx + handlePx
            collapsed -> beltPx
            else -> 0f
        }
        if (animate) belt.animate().translationY(beltTy).setDuration(150).start()
        else belt.translationY = beltTy

        strip.visibility = if (collapsible && !hidden) View.VISIBLE else View.GONE
        handleView?.visibility = if (collapsible && !hidden) View.VISIBLE else View.GONE
        applyColors()

        val desired = when {
            hidden -> 1
            collapsed -> collapsedPx.toInt()
            else -> (beltPx + handlePx).toInt()
        }
        // Collapsed: the strip fills the whole (short) window so the entire
        // bottom edge is tappable, and the handle pill centres in it. Shown:
        // the strip is the thin band above the icons, pill near the top.
        strip.layoutParams = (strip.layoutParams as FrameLayout.LayoutParams).apply {
            height = if (collapsed) desired else handlePx.toInt().coerceAtLeast(1)
        }
        handleView?.let { pill ->
            pill.layoutParams = (pill.layoutParams as FrameLayout.LayoutParams).apply {
                gravity = Gravity.CENTER_HORIZONTAL or
                    (if (collapsed) Gravity.CENTER_VERTICAL else Gravity.TOP)
                topMargin = if (collapsed) 0 else (3 * density).toInt()
            }
        }
        if (lp.height != desired) {
            lp.height = desired
            try {
                wm.updateViewLayout(container, lp)
            } catch (_: IllegalArgumentException) {
            }
        }
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
