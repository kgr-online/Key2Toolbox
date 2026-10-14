package com.kgr.key2toolbox.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import java.util.concurrent.Executors

/**
 * What is on the screen around the Toolbelt, read with the accessibility service's `takeScreenshot` (no root).
 * It serves the "follow app" colour and the icon contrast of a translucent bar. Everything
 * here runs off the main thread; callbacks arrive on [worker] and must be posted by the caller.
 *
 * The screenshot contains our own overlay, so only the rows just ABOVE the belt are read (they belong to the app).
 */
object BeltBackdrop {

    private val worker = Executors.newSingleThreadExecutor()

    /** Full-screen screenshot as a software bitmap, or null on any failure. */
    fun capture(svc: AccessibilityService, cb: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { cb(null); return }
        try {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(shot: AccessibilityService.ScreenshotResult) {
                    worker.execute {
                        val bmp = try {
                            val hw = Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace)
                            val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            hw?.recycle()
                            soft
                        } catch (t: Throwable) {
                            Log.w("Key2Toolbox", "belt capture decode failed", t); null
                        } finally {
                            shot.hardwareBuffer.close()
                        }
                        cb(bmp)
                    }
                }
                override fun onFailure(errorCode: Int) { cb(null) }
            })
        } catch (t: Throwable) {
            Log.w("Key2Toolbox", "belt capture unavailable", t)
            cb(null)
        }
    }

    /** Mean opaque colour of the rows [y0, y1) of [src] (clamped), measured on a small copy. */
    fun meanColor(src: Bitmap, y0: Int, y1: Int): Int {
        val top = y0.coerceIn(0, src.height - 1)
        val bottom = y1.coerceIn(top + 1, src.height)
        val band = Bitmap.createBitmap(src, 0, top, src.width, bottom - top)
        val small = Bitmap.createScaledBitmap(band, 48, 4, true)
        val px = IntArray(48 * 4)
        small.getPixels(px, 0, 48, 0, 0, 48, 4)
        var r = 0L; var g = 0L; var b = 0L
        for (c in px) { r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF }
        val n = px.size
        if (small !== band) small.recycle()
        if (band !== src) band.recycle()
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }
}
