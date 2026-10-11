package com.kgr.key2toolbox.modules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrostBlurTest {

    private fun rgb(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test fun aFlatImageStaysFlat() {
        val px = IntArray(20 * 12) { rgb(100) }
        FrostBlur.boxPass(px, 20, 12, 3, horizontal = true)
        FrostBlur.boxPass(px, 20, 12, 3, horizontal = false)
        assertTrue(px.all { it == rgb(100) })
    }

    @Test fun aBoxPassSpreadsAStepWithoutChangingTheTotal() {
        // A bright column in a dark row: after blurring it is dimmer but wider, and the average level is kept.
        val w = 21
        val px = IntArray(w) { if (it == 10) rgb(210) else rgb(0) }
        val before = px.sumOf { it and 0xFF }
        FrostBlur.boxPass(px, w, 1, 2, horizontal = true)
        val after = px.sumOf { it and 0xFF }
        assertTrue(px[10] and 0xFF < 210)
        assertTrue(px[8] and 0xFF > 0 && px[12] and 0xFF > 0)
        assertTrue("energy kept within rounding", kotlin.math.abs(before - after) <= w)
    }

    @Test fun alphaIsKeptOpaque() {
        val px = IntArray(10 * 10) { rgb(50) }
        FrostBlur.boxPass(px, 10, 10, 2, horizontal = true)
        assertEquals(0xFF, px[55] ushr 24)
    }
}
