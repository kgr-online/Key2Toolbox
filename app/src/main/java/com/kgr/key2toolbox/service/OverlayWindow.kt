package com.kgr.key2toolbox.service

import android.view.View
import android.view.WindowManager

/** Window-level helpers shared by the Recents overlays (Slim List / Masonry and Grid). */
object OverlayWindow {

    private const val BACKDROP_TAG = "k2tb_backdrop"

    /**
     * Puts the blurred still of the screen (see [com.kgr.key2toolbox.modules.FrostBlur]) behind everything else in
     * [container]. It is an ordinary view, so it fades with the window; the Masonry / Grid entrance and exit fade it
     * together with the scrim through [backdropOf].
     */
    fun addBackdrop(ctx: android.content.Context, container: android.widget.FrameLayout, picture: android.graphics.Bitmap?) {
        if (picture == null) return
        val v = android.widget.ImageView(ctx).apply {
            setImageBitmap(picture)
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            tag = BACKDROP_TAG
        }
        container.addView(v, 0, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun backdropOf(window: View): View? = window.findViewWithTag(BACKDROP_TAG)

    /**
     * Removes [v]'s window without letting the system show a stale frame. A plain removeView can keep drawing the
     * window's last presented buffer for a moment (seen on Android 15 as a flash of the whole Recents list after the
     * fade). Window alpha is applied by the compositor rather than from a buffer, so setting it to 0 first hides the
     * surface at once; the actual removal follows a few frames later.
     */
    fun removeSoftly(wm: WindowManager?, v: View, lp: WindowManager.LayoutParams?) {
        try {
            if (lp != null) {
                lp.alpha = 0f
                wm?.updateViewLayout(v, lp)
            }
        } catch (_: Exception) {
            // window already gone
        }
        v.postDelayed({
            try {
                wm?.removeView(v)
            } catch (_: IllegalArgumentException) {
            }
        }, 48)
    }
}
