package com.kgr.key2toolbox.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.kgr.key2toolbox.R
import com.kgr.key2toolbox.modules.SlimRecentsController
import com.kgr.key2toolbox.modules.SlimRecentsController.SlimTask
import java.util.concurrent.Executors

/**
 * "Slim List" Recents: a full-screen TYPE_ACCESSIBILITY_OVERLAY window (same
 * window trick as [ToolbeltOverlayController]) showing a vertical, thumbnail-
 * free list of running tasks. Entirely standalone - no launcher hook, no
 * RecentsView involvement; showing/hiding this window is the whole feature,
 * since nothing about the real foreground app ever changes while it's open.
 *
 * Tap a row to resume that task (see [SlimRecentsController.resumeTask] for
 * why that's not a plain relaunch). Swipe a row away to dismiss that task.
 * Tap the empty background, or Back, to close the list with no change
 * underneath.
 */
object SlimRecentsOverlayController {

    /** Row corner radius, matching stock Overview card rounding. */
    private const val CORNER_RADIUS_DP = 20

    // Resume / dismiss issue root shell commands - never run those on the
    // main thread that's servicing row touch events.
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var windowManager: WindowManager? = null
    private var root: FrameLayout? = null
    private var currentTasks: List<SlimTask> = emptyList()
    private var menuScrim: View? = null
    private var menuCard: View? = null

    fun isShowing(): Boolean = root != null

    /**
     * Dispatches [block] on [ioExecutor], catching anything it throws. An
     * uncaught exception on ANY thread - not just main - kills the whole
     * Android process by default, which would tear down every window this
     * process owns, including the unrelated Toolbelt overlay. A failed
     * resume/dismiss should just fail quietly, never take the app down.
     */
    private fun runSafely(block: () -> Unit) {
        ioExecutor.execute {
            try {
                block()
            } catch (t: Throwable) {
                Log.e("Key2Toolbox", "SlimRecents background action failed", t)
            }
        }
    }

    /**
     * Same idea as [runSafely] but for code that must run on the main thread
     * (all view construction/touch handling here does) - an uncaught
     * exception here is just as fatal to the whole process as one in a
     * background executor. Every listener and public entry point below goes
     * through this; a failed UI action should degrade to "nothing visibly
     * happened", never take the Toolbelt overlay down with it.
     */
    private fun safeUi(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e("Key2Toolbox", "SlimRecents UI action failed", t)
        }
    }

    private fun openAppInfo(svc: AccessibilityService, pkg: String) {
        try {
            svc.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }

    /**
     * Small anchored quick-actions card, same idea as stock Overview's icon-tap
     * menu - but that one is private launcher UI (TaskMenuView), not something
     * a third-party app can invoke, so this is our own. Lives as a couple of
     * extra children on [root] (drawn last = on top of everything), not a
     * PopupWindow - a PopupWindow's own focus/token handling gets awkward
     * stacked inside a TYPE_ACCESSIBILITY_OVERLAY, and this needs nothing that
     * a plain view can't already do. Currently just "App info"; "Split screen"
     * needs its own investigation first (see conversation) before it can be
     * added here as a second row.
     */
    private fun showIconMenu(svc: AccessibilityService, anchor: View, task: SlimTask) {
        closeIconMenu()
        val container = root ?: return
        val density = svc.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()

        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val screenW = svc.resources.displayMetrics.widthPixels
        val cardWidthGuess = px(160)
        val left = loc[0].coerceIn(0, (screenW - cardWidthGuess).coerceAtLeast(0))
        val top = loc[1] + anchor.height

        val scrim = View(svc).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            setOnClickListener { safeUi { closeIconMenu() } }
        }
        container.addView(
            scrim,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val card = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = px(CORNER_RADIUS_DP).toFloat()
                setColor(Color.argb(250, 45, 45, 45))
            }
        }
        val appInfoRow = TextView(svc).apply {
            text = svc.getString(R.string.recents_slim_app_info)
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(px(20), px(14), px(20), px(14))
            isClickable = true
            background = TypedValue().let { out ->
                if (svc.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
                    svc.getDrawable(out.resourceId)
                } else null
            }
            setOnClickListener {
                safeUi {
                    closeIconMenu()
                    openAppInfo(svc, task.packageName)
                    hide()
                }
            }
        }
        card.addView(appInfoRow)

        container.addView(
            card,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = left
                topMargin = top
            }
        )
        menuScrim = scrim
        menuCard = card
    }

    private fun closeIconMenu() {
        menuCard?.let { (it.parent as? ViewGroup)?.removeView(it) }
        menuScrim?.let { (it.parent as? ViewGroup)?.removeView(it) }
        menuCard = null
        menuScrim = null
    }

    /** [tasks] must already be fetched off the main thread - see [SlimRecentsController.listTasks]. */
    fun show(svc: AccessibilityService, tasks: List<SlimTask>) = safeUi {
        if (root != null) {
            rebuildRows(svc, tasks)
        } else {
            attach(svc, tasks)
        }
    }

    fun hide() = safeUi {
        closeIconMenu()
        val v = root
        root = null
        if (v != null) {
            try {
                windowManager?.removeView(v)
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    // ------------------------------------------------------------- internals

    private fun attach(svc: AccessibilityService, tasks: List<SlimTask>) {
        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val container = FrameLayout(svc).apply {
            setBackgroundColor(Color.argb(200, 0, 0, 0))
        }

        val list = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * svc.resources.displayMetrics.density).toInt()
            val bottomClearance = (88 * svc.resources.displayMetrics.density).toInt() // room for the Close All button
            setPadding(pad, pad, pad, bottomClearance)
        }
        val scroller = ScrollView(svc).apply {
            isFillViewport = true
            addView(list, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // Tap empty space (anywhere not on a row - rows get first dispatch as
        // children and consume their own touch sequence) to close, same as
        // Back. A plain OnClickListener on `container` doesn't work here: this
        // fullscreen ScrollView sits on top of it and claims every touch for
        // its own gesture handling before a parent's click listener would ever
        // see it, even over the "empty" unscrollable area. Returning false
        // lets a genuine scroll/fling still reach ScrollView's own handling.
        val bgSlop = ViewConfiguration.get(svc).scaledTouchSlop
        var bgDownX = 0f
        var bgDownY = 0f
        var bgMoved = false
        scroller.setOnTouchListener { _, ev ->
            try {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        bgDownX = ev.rawX; bgDownY = ev.rawY; bgMoved = false
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (kotlin.math.abs(ev.rawX - bgDownX) > bgSlop ||
                            kotlin.math.abs(ev.rawY - bgDownY) > bgSlop
                        ) bgMoved = true
                        false
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!bgMoved) hide()
                        false
                    }
                    else -> false
                }
            } catch (t: Throwable) {
                Log.e("Key2Toolbox", "SlimRecents background touch failed", t)
                false
            }
        }
        container.addView(
            scroller,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val density = svc.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()
        val closeAll = TextView(svc).apply {
            text = svc.getString(R.string.recents_slim_close_all)
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(px(24), px(12), px(24), px(12))
            background = GradientDrawable().apply {
                cornerRadius = px(CORNER_RADIUS_DP).toFloat()
                setColor(Color.argb(230, 140, 30, 30))
            }
            isClickable = true
            setOnClickListener {
                safeUi {
                    val tasks = currentTasks
                    runSafely { SlimRecentsController.dismissAll(tasks) }
                    hide()
                }
            }
        }
        container.addView(
            closeAll,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = px(24)
            }
        )

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                fitInsetsSides = 0
            }
        }

        try {
            wm.addView(container, lp)
        } catch (_: Exception) {
            return
        }
        windowManager = wm
        root = container
        buildRows(svc, list, tasks)
    }

    private fun rebuildRows(svc: AccessibilityService, tasks: List<SlimTask>) {
        val scroller = root?.getChildAt(0) as? ScrollView ?: return
        val list = scroller.getChildAt(0) as? LinearLayout ?: return
        buildRows(svc, list, tasks)
    }

    private fun buildRows(svc: AccessibilityService, list: LinearLayout, tasks: List<SlimTask>) {
        closeIconMenu()
        currentTasks = tasks
        list.removeAllViews()
        val density = svc.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()

        if (tasks.isEmpty()) {
            val empty = TextView(svc).apply {
                text = svc.getString(R.string.recents_slim_empty)
                setTextColor(Color.LTGRAY)
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, px(32), 0, 0)
            }
            list.addView(empty)
            return
        }

        tasks.forEach { task ->
            list.addView(
                buildRow(svc, task),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = px(4) }
            )
        }
    }

    private fun buildRow(svc: AccessibilityService, task: SlimTask): View {
        val density = svc.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()

        val row = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = px(CORNER_RADIUS_DP).toFloat()
                setColor(Color.argb(230, 30, 30, 30))
            }
            setPadding(px(16), px(14), px(16), px(14))
            isClickable = true
        }

        val icon = ImageView(svc).apply {
            task.icon?.let { setImageDrawable(it) }
            isClickable = true
            background = TypedValue().let { out ->
                if (svc.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true)) {
                    svc.getDrawable(out.resourceId)
                } else null
            }
            setOnClickListener { safeUi { showIconMenu(svc, this, task) } }
        }
        row.addView(icon, LinearLayout.LayoutParams(px(40), px(40)).apply { rightMargin = px(16) })

        val label = TextView(svc).apply {
            text = task.label
            setTextColor(Color.WHITE)
            textSize = 16f
        }
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Explicit close button - claims its own tap area the same way the
        // icon does, so it doesn't also trigger the row's resume click.
        val closeButton = TextView(svc).apply {
            text = "\u2715"
            setTextColor(Color.LTGRAY)
            textSize = 18f
            gravity = Gravity.CENTER
            isClickable = true
            background = TypedValue().let { out ->
                if (svc.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true)) {
                    svc.getDrawable(out.resourceId)
                } else null
            }
            setOnClickListener {
                safeUi {
                    runSafely { SlimRecentsController.dismissTask(task) }
                    (row.parent as? ViewGroup)?.removeView(row)
                }
            }
        }
        row.addView(closeButton, LinearLayout.LayoutParams(px(40), px(40)).apply { leftMargin = px(8) })

        // Anywhere on the row other than the icon resumes the task - the icon
        // claims taps that start on it (see openAppInfo above), so this only
        // fires for the label/background area.
        row.setOnClickListener {
            safeUi {
                runSafely { SlimRecentsController.resumeTask(task) }
                hide()
            }
        }

        // Swipe-to-dismiss: horizontal drag follows the finger; release past a
        // width-fraction threshold or a fast fling closes the task and removes
        // the row, otherwise it springs back. A plain tap (never crosses slop)
        // falls through to the click listener above.
        val slop = ViewConfiguration.get(svc).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var moved = false
        var vt: VelocityTracker? = null
        row.setOnTouchListener { v, ev ->
            try {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX
                        downY = ev.rawY
                        moved = false
                        vt = VelocityTracker.obtain().apply { addMovement(ev) }
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        vt?.addMovement(ev)
                        val dx = ev.rawX - downX
                        val dy = ev.rawY - downY
                        if (!moved && kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                            moved = true
                            // Claim the gesture before the parent ScrollView's own
                            // vertical-scroll interception kicks in. Without this, a
                            // real swipe's inevitable small vertical wobble gets
                            // intercepted by the list as a scroll before the row
                            // ever accumulates enough horizontal movement to count -
                            // the row springs back immediately because it never
                            // actually saw the drag through.
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        if (moved) {
                            v.translationX = dx
                            val w = v.width.coerceAtLeast(1)
                            v.alpha = (1f - kotlin.math.abs(dx) / w).coerceIn(0.2f, 1f)
                        }
                        moved
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        var vx = 0f
                        vt?.apply { addMovement(ev); computeCurrentVelocity(1000); vx = xVelocity; recycle() }
                        vt = null
                        if (moved) {
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                            val w = v.width.coerceAtLeast(1)
                            val dismiss = kotlin.math.abs(v.translationX) > w * 0.4f || kotlin.math.abs(vx) > 1200f
                            if (dismiss) {
                                runSafely { SlimRecentsController.dismissTask(task) }
                                (v.parent as? ViewGroup)?.removeView(v)
                            } else {
                                v.animate().translationX(0f).alpha(1f).setDuration(150).start()
                            }
                        }
                        moved
                    }
                    else -> false
                }
            } catch (t: Throwable) {
                Log.e("Key2Toolbox", "SlimRecents row touch failed", t)
                false
            }
        }

        return row
    }
}
