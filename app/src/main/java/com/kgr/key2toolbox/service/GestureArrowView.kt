package com.kgr.key2toolbox.service

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.kgr.key2toolbox.modules.GestureSettings.ArrowStyle
import kotlin.math.exp
import kotlin.math.min

/**
 * Arrow feedback for the edge gestures: a badge that slides out of the edge following the finger, points along the
 * real direction of travel (so a diagonal swipe tilts it), changes to the active colour once the swipe is past its
 * threshold ("release now to fire"), pops when armed, and on release either flies off (fired) or retracts
 * (cancelled). Its look comes from an [ArrowStyle]. It lives in one full-screen, non-touchable overlay window, so it
 * never takes a touch. The same view also renders the settings screen's static preview ([showPreview]).
 */
class GestureArrowView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val chevron = Path()

    private var style = ArrowStyle()
    private var edge = EdgeSwipe.Edge.LEFT
    private var px = 0f                // finger position (screen px)
    private var py = 0f
    private var fraction = 0f          // progress / threshold
    private var rotation = 0f          // arrow orientation in degrees
    private var armed = false
    private var pop = 1f               // scale pulse when arming
    private var exit = 0f              // 0..1 while leaving
    private var fired = false
    private var active = false
    private var ending = false         // end() already called for this touch: later calls are ignored
    private var preview = false
    private var anim: ValueAnimator? = null

    /** Duration scaled by the style's speed (200% = half the time). */
    private fun ms(base: Long) = (base * 100f / style.speedPct).toLong().coerceAtLeast(1L)

    /** A new touch on a strip: the arrow starts hidden (fraction 0) and grows as the finger moves in. */
    fun begin(edge: EdgeSwipe.Edge, x: Float, y: Float, style: ArrowStyle) {
        anim?.cancel()
        this.style = style
        this.edge = edge; px = x; py = y; fraction = 0f; armed = false; pop = 1f; exit = 0f; fired = false
        active = true; ending = false; preview = false
        rotation = baseAngle()
        invalidate()
    }

    fun update(x: Float, y: Float, fraction: Float, angleDeg: Float, armed: Boolean) {
        if (!active || preview) return
        px = x; py = y; this.fraction = fraction
        val base = baseAngle()
        if (style.followTilt) {
            // Keep the arrow within 60 degrees of the inward direction, whatever the finger does.
            var delta = angleDeg - base
            while (delta > 180f) delta -= 360f
            while (delta < -180f) delta += 360f
            rotation = base + delta.coerceIn(-60f, 60f)
        } else rotation = base
        if (armed && !this.armed) pulse()
        this.armed = armed
        invalidate()
    }

    /** The finger was lifted (or the hold fired): fly off if [fired], otherwise retract. */
    fun end(fired: Boolean) {
        if (!active || ending || preview) return
        ending = true
        this.fired = fired
        if (fired) pulse()
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms(if (fired) 220L else 160L)
            interpolator = DecelerateInterpolator()
            addUpdateListener { exit = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { active = false; invalidate() }
            })
            start()
        }
    }

    /** Static rendering for the settings screen: one badge at the centre, in the inactive or active colour. */
    fun showPreview(style: ArrowStyle, armed: Boolean) {
        this.style = style; this.armed = armed; preview = true; rotation = 0f; invalidate()
    }

    private fun pulse() {
        ValueAnimator.ofFloat(1f, 1.22f, 1f).apply {
            duration = ms(200L)
            interpolator = OvershootInterpolator()
            addUpdateListener { pop = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun baseAngle() = when (edge) { EdgeSwipe.Edge.LEFT -> 0f; EdgeSwipe.Edge.RIGHT -> 180f; EdgeSwipe.Edge.BOTTOM -> -90f }

    override fun onDraw(c: Canvas) {
        if (preview) { drawBadge(c, width / 2f, height / 2f, 1f, 1f, 0f); return }
        if (!active || (fraction <= 0f && exit == 0f)) return
        val w = width.toFloat(); val h = height.toFloat()
        val r = style.sizeDp * d
        val f = min(fraction, 1f)
        // Slide-out: eased up to the threshold, then a soft rubber band beyond it.
        var off = style.travelDp * d * (1f - (1f - f) * (1f - f))
        if (fraction > 1f) off += 6f * d * (1f - exp(-(fraction - 1f) * 2f))
        if (exit > 0f && !fired) off *= 1f - exit
        val cx: Float; val cy: Float
        when (edge) {
            EdgeSwipe.Edge.LEFT -> { cx = -r + off; cy = py.coerceIn(r, h - r) }
            EdgeSwipe.Edge.RIGHT -> { cx = w + r - off; cy = py.coerceIn(r, h - r) }
            EdgeSwipe.Edge.BOTTOM -> { cx = px.coerceIn(r, w - r); cy = h + r - off }
        }
        val a = (min(fraction * 2.5f, 1f) * (1f - exit)).coerceIn(0f, 1f)
        val s = pop * (if (fired) 1f + 0.35f * exit else 1f)
        drawBadge(c, cx, cy, s, a, rotation)
    }

    private fun drawBadge(c: Canvas, cx: Float, cy: Float, scale: Float, a: Float, rot: Float) {
        val r = style.sizeDp * d
        val bare = !style.showBadge
        // Chevron units: designed for a 22 dp badge, scaled with the radius (a bare arrow is drawn a bit larger).
        val u = (r / 22f) * (if (bare) 1.35f else 1f)
        val opacity = style.opacityPct / 100f
        stroke.strokeWidth = style.thicknessDp * d
        // Badge: inactive/active colour. Bare arrow: arrow colour, active colour once armed.
        val badgeColor = if (armed) style.activeColor else style.inactiveColor
        val arrowColor = if (bare && armed) style.activeColor else style.arrowColor
        fill.color = withAlpha(badgeColor, a * opacity)
        stroke.color = withAlpha(arrowColor, a * (if (bare) opacity.coerceAtLeast(0.6f) else 1f))
        chevron.reset()
        chevron.moveTo(-5f * u, -9f * u); chevron.lineTo(5f * u, 0f); chevron.lineTo(-5f * u, 9f * u)
        c.save()
        c.translate(cx, cy)
        c.scale(scale, scale)
        if (!bare) c.drawCircle(0f, 0f, r, fill)
        c.rotate(rot)
        c.drawPath(chevron, stroke)
        c.restore()
    }

    private fun withAlpha(rgb: Int, a: Float) = Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(rgb), Color.green(rgb), Color.blue(rgb))
}
