package com.kgr.key2toolbox.service

import kotlin.math.abs

/**
 * Pure (Android-free) recognizer for one edge strip, so the gesture rules can be unit-tested.
 *
 * Progress is measured toward the screen interior: +x from the left edge, -x from the right edge, -y from the
 * bottom edge. A swipe counts once progress reaches [distancePx] AND it is mostly along the progress axis (the
 * cross-axis travel stays below a ratio of the progress), so scrolling along an edge does not fire. Falling back
 * under half the distance cancels it again (the finger came back).
 *
 * With [diagonals] (side edges) the swipe is also classified by its angle, at the moment of asking
 * ([direction]), from where the finger is then: close to the progress axis = [Dir.STRAIGHT], clearly tilted =
 * [Dir.DIAG_A] (toward the top of the screen) or [Dir.DIAG_B] (toward the bottom). Without it (bottom edge) every
 * valid swipe is [Dir.STRAIGHT].
 *
 * Feed it down/move/up; the caller owns the hold timer (see [onHoldElapsed]).
 */
class EdgeSwipe(
    private val edge: Edge,
    private val distancePx: Float,
    /** Whether a held swipe is a distinct gesture. */
    private val holdEnabled: Boolean,
    private val diagonals: Boolean = false,
) {
    enum class Edge { LEFT, RIGHT, BOTTOM }
    enum class Dir { STRAIGHT, DIAG_A, DIAG_B }

    /** What the caller should do after a touch event. */
    enum class Result { NONE, CROSSED, SWIPE, CANCELLED }

    private var x0 = 0f
    private var y0 = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var crossed = false
    private var holdFired = false

    fun onDown(x: Float, y: Float) {
        x0 = x; y0 = y; lastX = x; lastY = y
        crossed = false; holdFired = false
    }

    /** [CROSSED] when the swipe just became valid (start the hold timer); [CANCELLED] if it was undone. */
    fun onMove(x: Float, y: Float): Result {
        lastX = x; lastY = y
        if (holdFired) return Result.NONE
        val p = progress(x, y)
        val cross = abs(crossAxis(x, y))
        if (!crossed) {
            if (p >= distancePx && cross <= maxRatio() * p) { crossed = true; return Result.CROSSED }
        } else if (p < distancePx / 2f) {
            crossed = false
            return Result.CANCELLED
        }
        return Result.NONE
    }

    /** Progress toward the interior as a fraction of the trigger distance (1 = threshold reached). */
    fun fraction(): Float = if (distancePx <= 0f) 0f else (progress(lastX, lastY) / distancePx).coerceAtLeast(0f)

    /** Whether the swipe is currently past the threshold (releasing now would complete it). */
    fun isCrossed(): Boolean = crossed

    /**
     * Angle of the finger's travel in screen degrees (0 = +x, 90 = down, as Canvas rotates), for orienting an
     * arrow: 0 when nothing has moved yet. Callers clamp it around the edge's inward direction.
     */
    fun travelAngleDeg(): Float {
        val dx = lastX - x0
        val dy = lastY - y0
        if (dx == 0f && dy == 0f) return when (edge) { Edge.LEFT -> 0f; Edge.RIGHT -> 180f; Edge.BOTTOM -> -90f }
        return Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat()
    }

    /** Direction of the swipe as of the last position. */
    fun direction(): Dir {
        if (!diagonals) return Dir.STRAIGHT
        val p = progress(lastX, lastY)
        if (p <= 0f) return Dir.STRAIGHT
        val c = crossAxis(lastX, lastY)
        if (abs(c) < STRAIGHT_RATIO * p) return Dir.STRAIGHT
        return if (c < 0f) Dir.DIAG_A else Dir.DIAG_B
    }

    /** True once if the hold timer fires while the swipe is still valid and a hold gesture exists. */
    fun onHoldElapsed(): Boolean {
        if (!holdEnabled || !crossed || holdFired) return false
        holdFired = true
        return true
    }

    /** [SWIPE] if releasing now completes a plain swipe (not already consumed as a hold). */
    fun onUp(): Result = if (crossed && !holdFired) Result.SWIPE else Result.NONE

    private fun maxRatio() = if (diagonals) MAX_CROSS_RATIO_DIAG else MAX_CROSS_RATIO

    private fun progress(x: Float, y: Float) = when (edge) {
        Edge.LEFT -> x - x0
        Edge.RIGHT -> x0 - x
        Edge.BOTTOM -> y0 - y
    }

    private fun crossAxis(x: Float, y: Float) = when (edge) {
        Edge.LEFT, Edge.RIGHT -> y - y0
        Edge.BOTTOM -> x - x0
    }

    companion object {
        /** Max cross-axis / progress for a valid swipe without diagonals (about 56 degrees). */
        const val MAX_CROSS_RATIO = 1.5f
        /** With diagonals the tilt may go further (about 63 degrees) before the swipe is rejected. */
        const val MAX_CROSS_RATIO_DIAG = 2.0f
        /** Below this cross/progress ratio (about 31 degrees) a side swipe counts as straight. */
        const val STRAIGHT_RATIO = 0.6f
    }
}
