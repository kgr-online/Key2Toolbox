package com.kgr.key2toolbox.service

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.kgr.key2toolbox.R
import com.kgr.key2toolbox.modules.SlimRecentsController
import com.kgr.key2toolbox.modules.SlimRecentsController.SlimTask
import com.kgr.key2toolbox.modules.ToolbeltController
import java.util.concurrent.Executors

/**
 * Standalone "Grid" Recents: a full-screen TYPE_ACCESSIBILITY_OVERLAY window that reproduces the look of the
 * launcher's tablet Overview (the layout [com.kgr.key2toolbox.xposed.RecentsHookInit] forces through LSPosed), with
 * no hook at all. Used for the Grid mode whenever the LSPosed module is not detected; with it, the launcher draws
 * the grid itself.
 *
 * Layout, modelled on Launcher3's tablet grid:
 *  - the most recent task is the large "focused" tile, at the right edge, and the view opens scrolled to it;
 *  - the older tasks extend to its LEFT in two rows, newest nearest the focused tile, each placed in the row
 *    that is currently shorter (so the rows stay balanced and fill column by column); swiping right reveals
 *    them, and a "Close all" pill sits at the far left end;
 *  - every tile is a header (icon + app name) over a rounded snapshot with the screen's aspect ratio;
 *  - tap = resume that app, swipe a tile up = dismiss it.
 *
 * Tasks, snapshots and task switching come from [SlimRecentsController], the same source the other overlay
 * modes use. This file is deliberately separate from [SlimRecentsOverlayController] (vertical list / quilt):
 * the scroll axis, geometry and gestures differ, and the two share nothing but the data layer.
 */
object GridRecentsOverlayController {

    private const val CORNER_DP = 22
    private const val HEADER_DP = 30
    private const val GAP_DP = 12
    private const val PAD_DP = 16

    /** Share of the usable height taken by the tile band (focused tile and the two rows). */
    private const val BAND_FRACTION = 0.80f

    private const val ENTRANCE_MS = 340L
    private const val EXPAND_MS = 230L
    private const val CLOSE_MS = 170L
    private const val CLOSE_SCALE = 0.96f

    /** Default-style marker for [hide]: grow the focused (newest) tile, as Masonry does on Back. Null = plain fade. */
    const val EXPAND_HERO = Int.MIN_VALUE

    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var windowManager: WindowManager? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var root: FrameLayout? = null
    private var scroller: HorizontalScrollView? = null
    private var content: FrameLayout? = null

    private var currentTasks: List<SlimTask> = emptyList()
    private var snapshots: Map<Int, Bitmap> = emptyMap()
    private val tileViews = LinkedHashMap<Int, View>()
    private val thumbViews = HashMap<Int, ImageView>()
    private val headerViews = HashMap<Int, View>()
    private val tileDist = HashMap<Int, Int>()
    private var closeAllView: View? = null
    private var pendingEntrance = false

    /** App that was in front when Recents opened (null if unknown); see [wireTile]. */
    private var frontPackage: String? = null

    fun isShowing(): Boolean = root != null

    // ------------------------------------------------------------------ safety wrappers

    // An uncaught exception on any thread kills the whole process (and with it every other overlay window
    // this app owns), so every listener and background action goes through one of these.
    private fun runSafely(block: () -> Unit) {
        ioExecutor.execute {
            try { block() } catch (t: Throwable) { Log.e("Key2Toolbox", "GridRecents background action failed", t) }
        }
    }

    private fun safeUi(block: () -> Unit) {
        try { block() } catch (t: Throwable) { Log.e("Key2Toolbox", "GridRecents UI action failed", t) }
    }

    /**
     * Length for a view-property animation: base x the user's setting x the system animator scale, with the
     * duration scale ViewPropertyAnimator applies on its own divided out. 0 means "no animation at all".
     */
    private fun animMs(ctx: Context, baseMs: Long): Long {
        val sp = ctx.getSharedPreferences(ToolbeltController.PREFS, Context.MODE_PRIVATE)
        val user = SlimRecentsController.animDurationPercent(sp) / 100f
        val sys = Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val applied = ValueAnimator.getDurationScale()
        if (user <= 0f || sys <= 0f || applied <= 0f) return 0L
        return (baseMs * user * sys / applied).toLong()
    }

    /** Reserved height (px) of the toolbelt, or 0 when it is off - the tile band keeps clear of it. */
    private fun beltInsetPx(ctx: Context): Int {
        val sp = ctx.getSharedPreferences(ToolbeltController.PREFS, Context.MODE_PRIVATE)
        if (!ToolbeltController.isEnabled(sp)) return 0
        return (ToolbeltController.reservedDp(sp) * ctx.resources.displayMetrics.density).toInt()
    }

    // ------------------------------------------------------------------ geometry

    /**
     * Placement of one tile: [x]/[y] = top-left of the tile (header included), [w] x [h] = the snapshot area,
     * [r] = distance from the content's right edge to the tile's right edge. Everything is anchored to the
     * right edge (the newest task lives there), so [r] is stable when the content width changes.
     */
    private class Slot(val x: Int, val y: Int, val w: Int, val h: Int, val r: Int)

    private class Geometry(val slots: List<Slot>, val contentWidth: Int, val closeX: Int, val closeY: Int)

    private fun statusBarHeight(ctx: Context): Int {
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else 0
    }

    private fun computeGeometry(ctx: Context, count: Int): Geometry {
        val dm = ctx.resources.displayMetrics
        fun px(dp: Int) = (dp * dm.density).toInt()
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val header = px(HEADER_DP)
        val gap = px(GAP_DP)
        val pad = px(PAD_DP)
        val top = statusBarHeight(ctx)
        val usable = screenH - top - px(24) - beltInsetPx(ctx)
        val band = (usable * BAND_FRACTION).toInt()
        val bandTop = top + (usable - band) / 2
        val aspect = screenW.toFloat() / screenH

        val focusH = (band - header).coerceAtLeast(px(80))
        val focusW = (focusH * aspect).toInt()
        val rowH = ((band - gap - 2 * header) / 2).coerceAtLeast(px(60))
        val rowW = (rowH * aspect).toInt()
        val closeW = px(150)

        // First pass in distance-from-the-right coordinates: the focused tile touches the right padding, the
        // rest grow leftwards. Row choice mirrors Launcher3: whichever row is currently shorter, top on a tie.
        val rs = ArrayList<Int>(count)
        if (count > 0) rs.add(pad)
        val start = pad + focusW + gap
        val rowEnd = intArrayOf(start, start)
        val rowOf = IntArray(count)
        for (i in 1 until count) {
            val row = if (rowEnd[0] <= rowEnd[1]) 0 else 1
            rowOf[i] = row
            rs.add(rowEnd[row])
            rowEnd[row] += rowW + gap
        }
        val end = if (count > 1) maxOf(rowEnd[0], rowEnd[1]) else start
        // Never narrower than the screen, so the focused tile stays on the right edge even with a single task.
        val contentW = maxOf(end + closeW + pad, screenW)

        val slots = ArrayList<Slot>(count)
        if (count > 0) slots.add(Slot(contentW - pad - focusW, bandTop, focusW, focusH, pad))
        for (i in 1 until count) {
            val row = rowOf[i]
            slots.add(Slot(contentW - rs[i] - rowW, bandTop + row * (header + rowH + gap), rowW, rowH, rs[i]))
        }
        return Geometry(slots, contentW, pad, bandTop + band / 2)
    }

    // ------------------------------------------------------------------ show / hide

    /**
     * Shows (or refreshes) the grid. [tasks] and the snapshots must already be loaded off the main thread;
     * snapshots stream in afterwards through [fillSnapshots]. [frontPkg] is the app that was in front when
     * Recents was opened, read before this window existed.
     */
    fun show(svc: AccessibilityService, tasks: List<SlimTask>, frontPkg: String? = null, backdrop: Bitmap? = null) = safeUi {
        Log.d("Key2Toolbox", "GridRecents.show: ${tasks.size} tasks")
        if (root == null) frontPackage = frontPkg
        if (root != null) rebuild(svc, tasks) else attach(svc, tasks, backdrop)
    }

    fun fillSnapshots(map: Map<Int, Bitmap>) = safeUi {
        snapshots = snapshots + map
        map.forEach { (id, bmp) ->
            thumbViews[id]?.let { applySnapshot(it, bmp) }
        }
    }

    /**
     * Closes the overlay. [expandTaskId]: that tile grows to cover the screen as the window fades, as the system
     * does when an app opens from Overview (null = plain fade). [animate] = false removes it at once (screen
     * off, teardown). [isShowing] turns false immediately either way.
     */
    fun hide(animate: Boolean = true, expandTaskId: Int? = null) = safeUi {
        val wanted = if (expandTaskId == EXPAND_HERO) currentTasks.firstOrNull()?.taskId else expandTaskId
        val v = root
        val tiles = HashMap(tileViews)
        val thumbs = HashMap(thumbViews)
        val headers = HashMap(headerViews)
        val closeAll = closeAllView
        val params = windowParams
        val shots = snapshots // before clearState(): only a tile with a real snapshot may expand (see below)
        clearState()
        if (v == null) return@safeUi
        val wm = windowManager
        val remove: () -> Unit = { OverlayWindow.removeSoftly(wm, v, params) }
        v.animate().cancel()
        if (!animate || animMs(v.context, CLOSE_MS) <= 0L) { remove(); return@safeUi }
        // The app in front (and the home screen) have no stored snapshot, so their tile is a dark placeholder;
        // expanding it would cover the screen with a black rectangle. Plain fade in that case.
        val canExpand = wanted != null && shots.containsKey(wanted)
        val target = if (canExpand) tiles[wanted] else null
        val targetThumb = if (canExpand) thumbs[wanted] else null
        if (target != null && targetThumb != null) {
            expandAndFade(v, tiles, headers, closeAll, target, targetThumb, remove)
        } else {
            v.animate().alpha(0f).scaleX(CLOSE_SCALE).scaleY(CLOSE_SCALE)
                .setDuration(animMs(v.context, CLOSE_MS))
                .setInterpolator(PathInterpolator(0.3f, 0f, 0.8f, 0.15f))
                .withEndAction { safeUi(remove) }.start()
        }
    }

    private fun clearState() {
        root = null; scroller = null; content = null; windowParams = null
        tileViews.clear(); thumbViews.clear(); headerViews.clear(); tileDist.clear(); closeAllView = null
        snapshots = emptyMap(); pendingEntrance = false
    }

    // ------------------------------------------------------------------ window

    private fun attach(svc: AccessibilityService, tasks: List<SlimTask>, backdrop: Bitmap?) {
        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val container = FrameLayout(svc).apply {
            setBackgroundColor(SlimRecentsController.scrimColor(svc))
            clipChildren = false // the expanding tile grows past its slot
        }
        OverlayWindow.addBackdrop(svc, container, backdrop)
        val area = FrameLayout(svc).apply { clipChildren = false }
        val hsv = HorizontalScrollView(svc).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            clipChildren = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(area, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Tap on empty space closes, like Back. Tiles consume their own touches first, so only taps that land on
        // the background reach this listener; returning false keeps real scrolls and flings working.
        val slop = ViewConfiguration.get(svc).scaledTouchSlop
        var downX = 0f; var downY = 0f; var moved = false
        hsv.setOnTouchListener { _, ev ->
            try {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; moved = false }
                    MotionEvent.ACTION_MOVE ->
                        if (kotlin.math.abs(ev.rawX - downX) > slop || kotlin.math.abs(ev.rawY - downY) > slop) moved = true
                    MotionEvent.ACTION_UP -> if (!moved) hide(expandTaskId = EXPAND_HERO)
                }
            } catch (t: Throwable) {
                Log.e("Key2Toolbox", "GridRecents background touch failed", t)
            }
            false
        }
        container.addView(hsv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { fitInsetsTypes = 0; fitInsetsSides = 0 }
        }
        try { wm.addView(container, lp) } catch (_: Exception) { return }
        windowManager = wm
        windowParams = lp
        root = container; scroller = hsv; content = area
        pendingEntrance = true
        buildTiles(svc, tasks)
    }

    private fun rebuild(svc: AccessibilityService, tasks: List<SlimTask>) {
        pendingEntrance = false
        buildTiles(svc, tasks)
    }

    // ------------------------------------------------------------------ tiles

    private fun buildTiles(svc: AccessibilityService, tasks: List<SlimTask>) {
        val area = content ?: return
        currentTasks = tasks
        area.removeAllViews()
        tileViews.clear(); thumbViews.clear(); headerViews.clear(); tileDist.clear(); closeAllView = null

        if (tasks.isEmpty()) {
            area.addView(TextView(svc).apply {
                text = svc.getString(R.string.recents_slim_empty)
                setTextColor(Color.LTGRAY); textSize = 16f; gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(svc.resources.displayMetrics.widthPixels, ViewGroup.LayoutParams.MATCH_PARENT))
            return
        }

        val geo = computeGeometry(svc, tasks.size)
        val header = (HEADER_DP * svc.resources.displayMetrics.density).toInt()
        tasks.forEachIndexed { i, task ->
            val slot = geo.slots[i]
            val tile = buildTile(svc, task, slot, header)
            tileViews[task.taskId] = tile
            tileDist[task.taskId] = slot.r
            area.addView(tile, FrameLayout.LayoutParams(slot.w, slot.h + header).apply {
                leftMargin = slot.x; topMargin = slot.y
            })
        }
        val pill = buildCloseAll(svc)
        closeAllView = pill
        area.addView(pill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = geo.closeX
            topMargin = geo.closeY - (24 * svc.resources.displayMetrics.density).toInt()
        })
        area.minimumWidth = geo.contentWidth

        // Open scrolled to the newest task (right end), before the first frame is drawn.
        area.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                area.viewTreeObserver.removeOnPreDrawListener(this)
                scroller?.let { it.scrollTo(maxOf(area.width - it.width, 0), 0) }
                return true
            }
        })

        // Entrance runs once, on the first layout pass, before the first frame is drawn.
        if (pendingEntrance) {
            area.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    area.viewTreeObserver.removeOnPreDrawListener(this)
                    if (pendingEntrance) { pendingEntrance = false; safeUi { playEntrance(svc) } }
                    return true
                }
            })
        }
    }

    private fun buildTile(svc: AccessibilityService, task: SlimTask, slot: Slot, headerPx: Int): View {
        val density = svc.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()
        val tile = FrameLayout(svc).apply { clipChildren = false; isClickable = true }

        val head = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(2), 0, px(2), 0)
        }
        head.addView(ImageView(svc).apply { task.icon?.let { setImageDrawable(it) } },
            LinearLayout.LayoutParams(px(22), px(22)).apply { rightMargin = px(8) })
        head.addView(TextView(svc).apply {
            text = task.label
            setTextColor(Color.WHITE); textSize = 12.5f; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tile.addView(head, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, headerPx))
        headerViews[task.taskId] = head

        val radius = SlimRecentsController.gridCornerPx(svc).toFloat()
        val thumb = ImageView(svc).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            // The placeholder colour lives in a drawable, but the rounded clip must NOT depend on it: when the
            // snapshot arrives the placeholder is cleared, and an outline taken from the background would
            // fall back to a plain rectangle (square corners).
            background = GradientDrawable().apply { cornerRadius = radius; setColor(Color.rgb(30, 30, 30)) }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            clipToOutline = true
            snapshots[task.taskId]?.let { applySnapshot(this, it) }
        }
        thumbViews[task.taskId] = thumb
        tile.addView(thumb, FrameLayout.LayoutParams(slot.w, slot.h).apply { topMargin = headerPx })

        wireTile(svc, tile, task)
        return tile
    }

    /** Shows [bmp] in [thumb] and clears the placeholder colour, keeping the rounded outline intact. */
    private fun applySnapshot(thumb: ImageView, bmp: Bitmap) {
        thumb.setImageBitmap(bmp)
        (thumb.background as? GradientDrawable)?.setColor(Color.TRANSPARENT)
    }

    private fun buildCloseAll(svc: AccessibilityService): View {
        val density = svc.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()
        return TextView(svc).apply {
            text = svc.getString(R.string.recents_slim_close_all)
            setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
            setPadding(px(24), px(12), px(24), px(12))
            background = GradientDrawable().apply { cornerRadius = px(CORNER_DP).toFloat(); setColor(Color.argb(230, 140, 30, 30)) }
            isClickable = true
            setOnClickListener {
                safeUi {
                    val all = currentTasks
                    runSafely { SlimRecentsController.dismissAll(all) }
                    hide()
                }
            }
        }
    }

    // ------------------------------------------------------------------ gestures

    /**
     * Tap resumes the task; a vertical drag upward past 35% of the tile height, or a fast upward fling, dismisses
     * it (like the stock Overview), otherwise it springs back. Horizontal drags are left to the scroller.
     */
    private fun wireTile(svc: AccessibilityService, tile: View, task: SlimTask) {
        tile.setOnClickListener {
            safeUi {
                // Already in front (the newest task and the app we came from): skip `am start`, which the system
                // would log as an activity restart attempt right as the overlay fades. Both conditions are needed
                // so a stale package can never swallow a real app switch.
                val alreadyFront = task.packageName == frontPackage && task.taskId == currentTasks.firstOrNull()?.taskId
                if (!alreadyFront) runSafely { SlimRecentsController.resumeTask(task) }
                hide(expandTaskId = task.taskId)
            }
        }
        val slop = ViewConfiguration.get(svc).scaledTouchSlop
        var downX = 0f; var downY = 0f; var dragging = false
        var vt: VelocityTracker? = null
        tile.setOnTouchListener { v, ev ->
            try {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX; downY = ev.rawY; dragging = false
                        vt = VelocityTracker.obtain().apply { addMovement(ev) }
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        vt?.addMovement(ev)
                        val dx = ev.rawX - downX
                        val dy = ev.rawY - downY
                        if (!dragging && dy < -slop && kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                            dragging = true
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        if (dragging) {
                            val up = minOf(dy, 0f)
                            v.translationY = up
                            v.alpha = (1f - kotlin.math.abs(up) / v.height.coerceAtLeast(1)).coerceIn(0.2f, 1f)
                        }
                        dragging
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        var vy = 0f
                        vt?.apply { addMovement(ev); computeCurrentVelocity(1000); vy = yVelocity; recycle() }
                        vt = null
                        if (dragging) {
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                            val h = v.height.coerceAtLeast(1)
                            if (-v.translationY > h * 0.35f || vy < -1200f) dismissTile(svc, task, v)
                            else v.animate().translationY(0f).alpha(1f).setDuration(150).start()
                        }
                        dragging
                    }
                    else -> false
                }
            } catch (t: Throwable) {
                Log.e("Key2Toolbox", "GridRecents tile touch failed", t)
                false
            }
        }
    }

    /** Removes one tile: the task is closed in the background and the rest of the grid reflows into the gap. */
    private fun dismissTile(svc: AccessibilityService, task: SlimTask, tile: View) = safeUi {
        runSafely { SlimRecentsController.dismissTask(task) }
        val remaining = currentTasks.filter { it.taskId != task.taskId }
        if (remaining.isEmpty()) { hide(); return@safeUi }
        val removedFocused = currentTasks.firstOrNull()?.taskId == task.taskId
        val area = content ?: return@safeUi
        val oldDist = HashMap(tileDist)
        val oldY = tileViews.mapValues { (_, v) -> (v.layoutParams as FrameLayout.LayoutParams).topMargin }

        area.removeView(tile)
        tileViews.remove(task.taskId); thumbViews.remove(task.taskId); headerViews.remove(task.taskId); tileDist.remove(task.taskId)
        currentTasks = remaining

        // A new focused tile changes size, so that case rebuilds; otherwise the others slide into place.
        if (removedFocused) { buildTiles(svc, remaining); return@safeUi }

        val geo = computeGeometry(svc, remaining.size)
        val dur = animMs(svc, 180L)
        remaining.forEachIndexed { i, t ->
            val v = tileViews[t.taskId] ?: return@forEachIndexed
            val lp = v.layoutParams as FrameLayout.LayoutParams
            val slot = geo.slots[i]
            val oy = oldY[t.taskId] ?: slot.y
            lp.leftMargin = slot.x; lp.topMargin = slot.y
            v.layoutParams = lp
            // Tiles are anchored to the right edge, so the slide is in distance-from-the-right space (the
            // content width itself shrinks): a tile that moves from r_old to r_new starts r_new - r_old away.
            val dx = (slot.r - (oldDist[t.taskId] ?: slot.r)).toFloat()
            tileDist[t.taskId] = slot.r
            if (dur > 0L) {
                v.translationX = dx; v.translationY = (oy - slot.y).toFloat()
                v.animate().translationX(0f).translationY(0f).setDuration(dur)
                    .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f)).start()
            }
        }
        closeAllView?.let { c ->
            val lp = c.layoutParams as FrameLayout.LayoutParams
            lp.leftMargin = geo.closeX
            c.layoutParams = lp
        }
        area.minimumWidth = geo.contentWidth
    }

    // ------------------------------------------------------------------ animations

    /** Transform that makes [thumb] (inside its tile) cover the whole screen: uniform scale + translation. */
    private class Cover(val scale: Float, val dx: Float, val dy: Float, val pivotX: Float, val pivotY: Float)

    @Suppress("UNUSED_PARAMETER")
    private fun cover(ctx: Context, tile: View, thumb: View): Cover? {
        if (thumb.width <= 0 || thumb.height <= 0) return null
        val dm = ctx.resources.displayMetrics
        val loc = IntArray(2)
        thumb.getLocationOnScreen(loc)
        val cx = loc[0] + thumb.width / 2f
        val cy = loc[1] + thumb.height / 2f
        val scale = maxOf(dm.widthPixels / thumb.width.toFloat(), dm.heightPixels / thumb.height.toFloat())
        return Cover(scale, dm.widthPixels / 2f - cx, dm.heightPixels / 2f - cy,
            thumb.left + thumb.width / 2f, thumb.top + thumb.height / 2f)
    }

    /**
     * Entrance: the focused tile starts as the full-screen app and shrinks into its slot while the other tiles
     * rise in one after another and the scrim fades up. Does nothing when animations are off.
     */
    private fun playEntrance(svc: AccessibilityService) {
        val window = root ?: return
        val dur = animMs(svc, ENTRANCE_MS)
        if (dur <= 0L) return
        val ease = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        val density = svc.resources.displayMetrics.density
        val firstId = currentTasks.firstOrNull()?.taskId
        val hero = firstId?.let { tileViews[it] }
        val thumb = firstId?.let { thumbViews[it] }

        var order = 0
        tileViews.forEach { (id, v) ->
            if (id == firstId) return@forEach
            v.alpha = 0f; v.translationX = -40 * density
            v.animate().alpha(1f).translationX(0f)
                .setStartDelay(dur / 5 + order * 24L).setDuration(dur).setInterpolator(ease).start()
            order++
        }
        closeAllView?.let { it.alpha = 0f; it.animate().alpha(1f).setStartDelay(dur / 2).setDuration(dur / 2).start() }
        OverlayWindow.backdropOf(window)?.let { it.alpha = 0f; it.animate().alpha(1f).setDuration(dur).start() }
        (window.background as? ColorDrawable)?.let { bg ->
            val full = bg.alpha
            bg.alpha = 0
            ValueAnimator.ofInt(0, full).apply {
                duration = dur
                addUpdateListener { bg.alpha = it.animatedValue as Int }
                start()
            }
        }
        val t = if (hero != null && thumb != null) cover(svc, hero, thumb) else null
        if (hero == null || t == null) return
        hero.translationZ = 8f
        hero.pivotX = t.pivotX; hero.pivotY = t.pivotY
        hero.scaleX = t.scale; hero.scaleY = t.scale
        hero.translationX = t.dx; hero.translationY = t.dy
        firstId.let { headerViews[it] }?.apply { alpha = 0f; animate().alpha(1f).setStartDelay(dur / 2).setDuration(dur / 2).start() }
        hero.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
            .setDuration(dur).setInterpolator(ease)
            .withEndAction { hero.translationZ = 0f }.start()
    }

    /** Exit for a tapped tile: it grows to cover the screen, the others and the scrim fade, then the window goes. */
    private fun expandAndFade(
        window: View, tiles: Map<Int, View>, headers: Map<Int, View>, closeAll: View?,
        target: View, thumb: View, remove: () -> Unit,
    ) {
        val ctx = window.context
        val t = cover(ctx, target, thumb)
        if (t == null) { remove(); return }
        val dur = animMs(ctx, EXPAND_MS)
        val ease = PathInterpolator(0.2f, 0f, 0f, 1f)
        tiles.values.filter { it !== target }.forEach { it.animate().alpha(0f).setDuration(dur / 2).start() }
        closeAll?.animate()?.alpha(0f)?.setDuration(dur / 2)?.start()
        headers.entries.firstOrNull { tiles[it.key] === target }?.value?.animate()?.alpha(0f)?.setDuration(dur / 2)?.start()
        target.translationZ = 8f
        target.pivotX = t.pivotX; target.pivotY = t.pivotY
        target.animate().scaleX(t.scale).scaleY(t.scale).translationX(t.dx).translationY(t.dy)
            .setDuration(dur).setInterpolator(ease).start()
        val fadeStart = (dur * 0.4f).toLong()
        window.animate().alpha(0f).setStartDelay(fadeStart).setDuration(dur - fadeStart + animMs(ctx, 60L))
            .withEndAction { safeUi(remove) }.start()
        OverlayWindow.backdropOf(window)?.animate()?.alpha(0f)?.setDuration(dur)?.start()
        (window.background as? ColorDrawable)?.let { bg ->
            ValueAnimator.ofInt(bg.alpha, 0).apply {
                duration = dur
                addUpdateListener { bg.alpha = it.animatedValue as Int }
                start()
            }
        }
    }
}
