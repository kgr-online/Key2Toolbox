package com.kgr.key2toolbox.modules

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A frosted-glass backdrop made once, in software, from a screenshot - instead of asking the compositor to blur
 * whatever is behind the Recents window every frame. The compositor's blur flickered on this GPU (Adreno 512), stuck
 * until the window was removed and vanished in one frame; a still picture has none of that, fades with the rest of the
 * window and does not need the system's cross-window blur (so it also works under Battery Saver).
 *
 * The picture is shrunk to a few dozen pixels, blurred there with three box passes (which approximate a gaussian) and
 * shown stretched to the screen, so the cost is a few milliseconds whatever the radius. Call off the main thread.
 */
object FrostBlur {

    /**
     * [src] is the screenshot, [percent] the setting (1% = 1 dp of blur radius at screen scale, as the compositor
     * blur had it), [density] the display density and [screenWidthPx] the real screen width ([src] may be smaller).
     */
    fun blur(src: Bitmap, percent: Int, density: Float, screenWidthPx: Int): Bitmap {
        val radiusFull = (percent * density).coerceAtLeast(1f)
        // Work at a size where the radius is a handful of pixels.
        val targetW = (screenWidthPx * 4f / radiusFull).roundToInt().coerceIn(48, src.width)
        val scale = targetW.toFloat() / src.width
        val targetH = max(1, (src.height * scale).roundToInt())

        // Halve until close to the target, so scaling down does not alias.
        var cur = src
        while (cur.width / 2 >= targetW * 2) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, max(1, cur.height / 2), true)
            if (cur !== src) cur.recycle()
            cur = next
        }
        val small = Bitmap.createScaledBitmap(cur, targetW, targetH, true).copy(Bitmap.Config.ARGB_8888, true)
        if (cur !== src) cur.recycle()

        val r = (radiusFull * targetW / screenWidthPx).roundToInt().coerceIn(1, max(1, targetW / 3))
        val px = IntArray(targetW * targetH)
        small.getPixels(px, 0, targetW, 0, 0, targetW, targetH)
        repeat(3) {
            boxPass(px, targetW, targetH, r, horizontal = true)
            boxPass(px, targetW, targetH, r, horizontal = false)
        }
        small.setPixels(px, 0, targetW, 0, 0, targetW, targetH)
        return small
    }

    /** One box blur of radius [r] along one axis, edges clamped, on packed ARGB ints. */
    internal fun boxPass(px: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val line = IntArray(len)
        val size = 2 * r + 1
        for (l in 0 until lines) {
            for (i in 0 until len) line[i] = if (horizontal) px[l * w + i] else px[i * w + l]
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (k in -r..r) {
                val c = line[k.coerceIn(0, len - 1)]
                a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
            for (i in 0 until len) {
                val out = ((a / size) shl 24) or ((rr / size) shl 16) or ((g / size) shl 8) or (b / size)
                if (horizontal) px[l * w + i] = out else px[i * w + l] = out
                val add = line[(i + r + 1).coerceIn(0, len - 1)]
                val sub = line[(i - r).coerceIn(0, len - 1)]
                a += (add ushr 24) - (sub ushr 24)
                rr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                g += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                b += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }
}
