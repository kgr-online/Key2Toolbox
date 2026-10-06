package com.kgr.key2toolbox.service

import android.view.View
import android.view.WindowManager

/** Window-level helpers shared by the Recents overlays (Slim List / Masonry and Grid). */
object OverlayWindow {

    /**
     * Turns the blur-behind off in one step when the exit starts. The blur belongs to the window (the compositor
     * applies it), not to the view tree, so fading the root view's alpha leaves it at full strength until the window
     * is removed. A per-frame ramp costs a layout pass and a blur recompute every frame (jank on this GPU), so it is
     * a single update; the scrim is still almost opaque at that moment, so the change is hardly visible.
     */
    fun blurOff(wm: WindowManager?, v: View, lp: WindowManager.LayoutParams?) {
        if (wm == null || lp == null || lp.blurBehindRadius <= 0) return
        try {
            lp.blurBehindRadius = 0
            wm.updateViewLayout(v, lp)
        } catch (_: Exception) {
            // window already gone
        }
    }

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
