package com.kgr.key2toolbox.service

/** The Recents overlay windows (vertical list / quilt, and grid); at most one is up at a time. */
object RecentsOverlays {
    fun isShowing(): Boolean =
        SlimRecentsOverlayController.isShowing() || GridRecentsOverlayController.isShowing()

    /**
     * Closes whichever is showing. [animate] = false removes it at once (screen off, teardown).
     * [expandTaskId] picks the tile that grows to full screen as the exit: the default grows the newest one
     * (Back), null is a plain fade (Home, Toolbelt presses). Both overlays share the same marker value.
     */
    fun hide(animate: Boolean = true, expandTaskId: Int? = SlimRecentsOverlayController.EXPAND_HERO) {
        if (SlimRecentsOverlayController.isShowing()) SlimRecentsOverlayController.hide(animate, expandTaskId)
        if (GridRecentsOverlayController.isShowing()) GridRecentsOverlayController.hide(animate, expandTaskId)
    }
}
