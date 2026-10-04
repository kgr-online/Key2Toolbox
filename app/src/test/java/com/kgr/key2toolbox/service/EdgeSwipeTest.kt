package com.kgr.key2toolbox.service

import com.kgr.key2toolbox.service.EdgeSwipe.Edge
import com.kgr.key2toolbox.service.EdgeSwipe.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeSwipeTest {

    @Test fun leftEdgeInwardSwipeCompletes() {
        val s = EdgeSwipe(Edge.LEFT, 50f, holdEnabled = false)
        s.onDown(5f, 300f)
        assertEquals(Result.NONE, s.onMove(30f, 305f))
        assertEquals(Result.CROSSED, s.onMove(60f, 310f))
        assertEquals(Result.SWIPE, s.onUp())
    }

    @Test fun rightEdgeNeedsLeftwardMotion() {
        val s = EdgeSwipe(Edge.RIGHT, 50f, false)
        s.onDown(715f, 300f)
        assertEquals(Result.NONE, s.onMove(750f, 300f)) // outward does nothing
        assertEquals(Result.CROSSED, s.onMove(650f, 300f))
        assertEquals(Result.SWIPE, s.onUp())
    }

    @Test fun bottomEdgeNeedsUpwardMotion() {
        val s = EdgeSwipe(Edge.BOTTOM, 50f, true)
        s.onDown(360f, 715f)
        assertEquals(Result.NONE, s.onMove(360f, 760f))
        assertEquals(Result.CROSSED, s.onMove(365f, 650f))
        assertEquals(Result.SWIPE, s.onUp())
    }

    @Test fun shortSwipeIsIgnored() {
        val s = EdgeSwipe(Edge.LEFT, 50f, false)
        s.onDown(5f, 300f)
        s.onMove(40f, 300f)
        assertEquals(Result.NONE, s.onUp())
    }

    @Test fun scrollingAlongTheEdgeDoesNotFire() {
        val s = EdgeSwipe(Edge.LEFT, 50f, false)
        s.onDown(5f, 300f)
        // 60 px inward but 200 px along the edge: cross-axis ratio far above the limit.
        assertEquals(Result.NONE, s.onMove(65f, 500f))
        assertEquals(Result.NONE, s.onUp())
    }

    @Test fun comingBackCancelsTheSwipe() {
        val s = EdgeSwipe(Edge.LEFT, 50f, false)
        s.onDown(5f, 300f)
        assertEquals(Result.CROSSED, s.onMove(70f, 300f))
        assertEquals(Result.CANCELLED, s.onMove(10f, 300f))
        assertEquals(Result.NONE, s.onUp())
    }

    @Test fun holdFiresOnceAndSuppressesTheSwipeOnRelease() {
        val s = EdgeSwipe(Edge.BOTTOM, 50f, true)
        s.onDown(360f, 715f)
        s.onMove(360f, 640f)
        assertTrue(s.onHoldElapsed())
        assertFalse(s.onHoldElapsed())
        assertEquals(Result.NONE, s.onUp())
    }

    @Test fun holdBeforeCrossingDoesNothing() {
        val s = EdgeSwipe(Edge.BOTTOM, 50f, true)
        s.onDown(360f, 715f)
        s.onMove(360f, 700f)
        assertFalse(s.onHoldElapsed())
    }

    @Test fun holdDisabledOnLateralEdges() {
        val s = EdgeSwipe(Edge.LEFT, 50f, false)
        s.onDown(5f, 300f)
        s.onMove(70f, 300f)
        assertFalse(s.onHoldElapsed())
        assertEquals(Result.SWIPE, s.onUp())
    }

    @Test fun newGestureResetsState() {
        val s = EdgeSwipe(Edge.LEFT, 50f, false)
        s.onDown(5f, 300f); s.onMove(70f, 300f)
        s.onDown(5f, 300f)
        assertEquals(Result.NONE, s.onUp())
    }

    // --- directions on side edges ---

    private fun lateral() = EdgeSwipe(Edge.LEFT, 50f, holdEnabled = true, diagonals = true)

    @Test fun straightSideSwipeIsStraight() {
        val s = lateral(); s.onDown(5f, 300f)
        assertEquals(Result.CROSSED, s.onMove(80f, 310f))
        assertEquals(EdgeSwipe.Dir.STRAIGHT, s.direction())
    }

    @Test fun upwardTiltIsDiagA() {
        val s = lateral(); s.onDown(5f, 300f)
        s.onMove(80f, 200f)
        assertEquals(EdgeSwipe.Dir.DIAG_A, s.direction())
        assertEquals(Result.SWIPE, s.onUp())
    }

    @Test fun downwardTiltIsDiagB() {
        val s = lateral(); s.onDown(5f, 300f)
        s.onMove(80f, 400f)
        assertEquals(EdgeSwipe.Dir.DIAG_B, s.direction())
    }

    @Test fun rightEdgeDirectionsAreScreenRelative() {
        val s = EdgeSwipe(Edge.RIGHT, 50f, true, diagonals = true); s.onDown(715f, 300f)
        s.onMove(640f, 200f) // inward (left) and up
        assertEquals(EdgeSwipe.Dir.DIAG_A, s.direction())
    }

    @Test fun diagonalsAllowAWiderTiltThanPlainSwipes() {
        // 80 px inward, 150 px vertical: ratio 1.9 -> valid with diagonals, rejected without.
        val a = lateral(); a.onDown(5f, 300f)
        assertEquals(Result.CROSSED, a.onMove(85f, 450f))
        val b = EdgeSwipe(Edge.LEFT, 50f, true); b.onDown(5f, 300f)
        assertEquals(Result.NONE, b.onMove(85f, 450f))
    }

    @Test fun bottomIsAlwaysStraight() {
        val s = EdgeSwipe(Edge.BOTTOM, 50f, true); s.onDown(360f, 715f)
        s.onMove(450f, 640f)
        assertEquals(EdgeSwipe.Dir.STRAIGHT, s.direction())
    }

    @Test fun directionIsReadFromTheLatestPosition() {
        val s = lateral(); s.onDown(5f, 300f)
        s.onMove(80f, 200f)               // crosses, tilted up
        s.onMove(200f, 205f)              // then straightens out (ratio 0.49)
        assertEquals(EdgeSwipe.Dir.STRAIGHT, s.direction())
    }

    @Test fun holdWorksOnSideEdges() {
        val s = lateral(); s.onDown(5f, 300f); s.onMove(80f, 300f)
        assertTrue(s.onHoldElapsed())
        assertEquals(Result.NONE, s.onUp())
    }

    // --- progress and angle (for the arrow overlay) ---

    @Test fun fractionIsProgressOverDistance() {
        val s = EdgeSwipe(Edge.LEFT, 50f, true); s.onDown(5f, 300f)
        s.onMove(30f, 300f)
        assertEquals(0.5f, s.fraction(), 0.001f)
        s.onMove(105f, 300f)
        assertEquals(2f, s.fraction(), 0.001f)
        s.onMove(-20f, 300f)
        assertEquals(0f, s.fraction(), 0.001f) // never negative
    }

    @Test fun angleFollowsTheTravelDirection() {
        val s = lateral(); s.onDown(5f, 300f)
        s.onMove(105f, 200f) // right and up
        assertEquals(-45f, s.travelAngleDeg(), 0.5f)
        val b = EdgeSwipe(Edge.BOTTOM, 50f, true); b.onDown(360f, 715f)
        assertEquals(-90f, b.travelAngleDeg(), 0.001f) // idle: points inward
    }

    @Test fun crossedFlagTracksTheThreshold() {
        val s = lateral(); s.onDown(5f, 300f)
        assertFalse(s.isCrossed())
        s.onMove(80f, 300f)
        assertTrue(s.isCrossed())
        s.onMove(10f, 300f)
        assertFalse(s.isCrossed())
    }
}
