package com.kgr.key2toolbox.modules

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.kgr.key2toolbox.core.RootShell
import com.kgr.key2toolbox.service.Key2AccessibilityService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Event-driven keyguard-state feed for the pocket keyboard lock daemon
 * (pocket_kbd_lock_template.sh).
 *
 * The daemon's previous design polled `dumpsys window policy` once a second,
 * 24/7 - measured on-device at 176 ms of CPU per second (10.6 s/min including
 * the dumpsys children), which kept system_server churning and cut the
 * platform to one short suspend cycle every ~6 s: several %/h of continuous
 * battery drain, day and night.
 *
 * This writer is the fix's event side: on every keyguard/screen transition
 * (SCREEN_OFF, SCREEN_ON, USER_PRESENT) it asks [KeyguardManager] directly and
 * writes "<locked|unlocked> <elapsedRealtime-seconds>" to the daemon's state
 * file via root. The daemon reads that file with shell builtins (zero forks)
 * while the stamp is fresh, and falls back to a 5 s dumpsys cadence only when
 * the stamp goes stale (app process dead, accessibility service disabled, or
 * the root write failing).
 *
 * Registered from [Key2AccessibilityService] (the always-alive host this app
 * already requires for its other features). The same PIN-input safety rule
 * as the daemon applies here: while "Lockscreen PIN on Keyboard" is enabled,
 * this never writes "locked", so the keypad can't be disabled out from under
 * the user's own unlock method.
 */
object KeyguardStateWriter {

    private const val TAG = "Key2Toolbox"
    private const val STATE_FILE = "/data/adb/.k2tb_keyguard_state"

    /**
     * At SCREEN_OFF the keyguard flag settles asynchronously (and a
     * lock-delay may still be running), so the first write right after the
     * event can be premature. Re-write once more after this delay.
     */
    private const val LOCK_SETTLE_DELAY_MS = 2500L

    private val handler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var receiver: BroadcastReceiver? = null

    fun register(context: Context) {
        if (receiver != null) return
        val app = context.applicationContext
        val km = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                handler.removeCallbacksAndMessages(null)
                when (intent.action) {
                    Intent.ACTION_USER_PRESENT -> write(app, km.isKeyguardLocked)
                    Intent.ACTION_SCREEN_ON -> {
                        // Write immediately, then re-write: a bouncer shown
                        // on wake can be dismissed, changing the state again.
                        write(app, km.isKeyguardLocked)
                        scheduleSettleWrite(app, km)
                    }
                    Intent.ACTION_SCREEN_OFF ->
                        // Keyguard settles shortly after sleep; a write taken
                        // in the same instant can still read unlocked.
                        scheduleSettleWrite(app, km)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        app.registerReceiver(r, filter)
        receiver = r
        // Seed the daemon with the current state on (re)connect.
        write(app, km.isKeyguardLocked)
    }

    fun unregister(context: Context) {
        receiver?.let {
            try {
                context.applicationContext.unregisterReceiver(it)
            } catch (_: Exception) {}
        }
        receiver = null
        handler.removeCallbacksAndMessages(null)
        // Deliberately no farewell write: the stamp simply goes stale and the
        // daemon falls back to its own keyguard check. Writing here would
        // either duplicate the app-dead case or freeze a state that's about
        // to change, and staleness is the honest signal.
    }

    /**
     * One-off immediate write, used when the PIN-input preference changes so
     * the daemon doesn't serve a decision made under the old preference until
     * the next screen/keyguard event.
     */
    fun writeNow(context: Context) {
        val app = context.applicationContext
        val km = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        write(app, km.isKeyguardLocked)
    }

    private fun scheduleSettleWrite(app: Context, km: KeyguardManager) {
        handler.postDelayed({ write(app, km.isKeyguardLocked) }, LOCK_SETTLE_DELAY_MS)
    }

    private fun write(context: Context, keyguardLocked: Boolean) {
        executor.execute {
            try {
                // Same hard safety rule as the daemon: never report "locked"
                // while the PIN keyboard is the user's unlock method.
                val pinEnabled = context
                    .getSharedPreferences(Key2AccessibilityService.PREFS, Context.MODE_PRIVATE)
                    .getBoolean(Key2AccessibilityService.KEY_PIN_INPUT, true)
                val locked = keyguardLocked && !pinEnabled
                val stamp = SystemClock.elapsedRealtime() / 1000
                RootShell.run(
                    "echo '${if (locked) "locked" else "unlocked"} $stamp' > $STATE_FILE"
                )
            } catch (t: Throwable) {
                Log.w(TAG, "keyguard state write failed", t)
            }
        }
    }
}
