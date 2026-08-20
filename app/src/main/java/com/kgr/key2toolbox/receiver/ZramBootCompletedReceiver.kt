package com.kgr.key2toolbox.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kgr.key2toolbox.core.RootShell
import com.kgr.key2toolbox.modules.ZramController

/**
 * Reapplies the persisted ZRAM swappiness value after boot fully completes.
 *
 * `zram_size.sh` runs from /data/adb/service.d, which executes at the
 * late_start service stage - before ROM boot finishes. LineageOS/AOSP's own
 * init.rc unconditionally writes a default swappiness (60) in its `on boot`
 * trigger, which fires later, on sys.boot_completed=1. That write happens
 * after service.d has already run, silently reverting whatever swappiness
 * the user persisted (size and comp_algorithm are unaffected, since the ROM
 * doesn't re-touch those).
 *
 * BOOT_COMPLETED is broadcast to apps only after that "on boot" trigger has
 * finished, so reapplying here - rather than from the service.d script - is
 * guaranteed to run last.
 */
class ZramBootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!ZramController.isPersisted()) return

        val swappiness = ZramController.persistedSwappiness() ?: return
        if (!RootShell.isRootAvailable()) return

        RootShell.run("echo $swappiness > /proc/sys/vm/swappiness")
    }
}
