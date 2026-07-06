package com.kgr.key2toolbox.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import com.kgr.key2toolbox.modules.LedNotifyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Watches posted/cleared notifications system-wide and, for apps the user has
 * assigned a color to in the LED Notify screen, drives the LED directly via
 * [LedNotifyManager] - bypassing LineageOS's own per-app light-color settings,
 * whose color quantization doesn't match this device's LED hardware.
 *
 * State is recomputed from scratch (via [getActiveNotifications]) on every
 * post/remove rather than incrementally tracked, so it self-corrects if an
 * event is ever missed.
 *
 * The LED blinks in **software**: this device's LED driver doesn't expose a
 * generic kernel timer trigger (confirmed via `cat .../trigger` - only fixed
 * hardware triggers are listed, no `timer`), so [blinkJob] drives it directly:
 *  - One active color (or [KEY_CYCLE_MODE] off): alternates that color and
 *    off, each held for the configured flash length - a real blink.
 *  - Multiple distinct colors with [KEY_CYCLE_MODE] on: each color gets its
 *    own on/off blink in turn - green, off, blue, off, green, off, ... -
 *    rather than swapping directly between colors with no gap.
 * Each phase is scheduled against a fixed-rate clock (`nextTick += period`)
 * rather than a plain `delay(period)` after each write, so root-shell latency
 * doesn't compound into drift over time.
 *
 * **Everything here - `recompute()`, the blink loop, and one-shot writes -
 * runs on a single dedicated thread ([executor]/[executorDispatcher]).** This
 * matters because [LedNotifyManager]'s writes are plain blocking calls, not
 * suspending ones: if `recompute()` restarts the blink while the *previous*
 * blink coroutine happens to be mid-write, cancellation can't interrupt a
 * blocking call, so for a moment both the old and new loop would be alive at
 * once. On a multi-threaded dispatcher that means two threads racing to write
 * the same LED concurrently - which looks exactly like intermittent
 * corruption (blink runs fine for a while, then gets stuck on, stuck off, or
 * flickers rapidly, in a repeating pattern) as the two loops drift in and out
 * of phase with each other. Pinning everything to one thread makes that
 * structurally impossible: a new coroutine can't even begin running until the
 * old one's blocking call returns and it hits a suspension point where the
 * cancellation actually takes effect.
 *
 * The tradeoff versus a hardware trigger: this stops if the app process is
 * killed (doze/battery optimization) until the next notification event
 * restarts it, since there's no kernel-side timer keeping it alive independently.
 *
 * By default the LED is suppressed while the screen is on (it's only useful
 * when you're not already looking at the screen); [KEY_FLASH_WHILE_SCREEN_ON]
 * overrides that.
 */
class LedNotifyListenerService : NotificationListenerService() {

    companion object {
        const val PREFS = "led_notify"
        const val KEY_ENABLED = "enabled"
        const val KEY_FLASH_WHILE_SCREEN_ON = "flash_while_screen_on"
        const val KEY_FLASH_LENGTH_MS = "flash_length_ms"
        const val DEFAULT_FLASH_LENGTH_MS = 500
        const val KEY_CYCLE_MODE = "cycle_mode"
        const val COLOR_KEY_PREFIX = "color_"

        fun colorKey(packageName: String) = "$COLOR_KEY_PREFIX$packageName"
    }

    private var prefs: SharedPreferences? = null

    // Single dedicated thread for every LED write and every recompute() call -
    // see the class doc above for why this can't be a multi-threaded dispatcher.
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val executorDispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + executorDispatcher)

    private var blinkJob: Job? = null
    private var blinkColors: List<Int> = emptyList()
    @Volatile private var screenOn = true
    private var screenReceiver: BroadcastReceiver? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        screenOn = pm?.isInteractive ?: true

        val rx = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                screenOn = intent?.action == Intent.ACTION_SCREEN_ON
                scope.launch { recompute() }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        try {
            ContextCompat.registerReceiver(this, rx, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            screenReceiver = rx
        } catch (_: Exception) {
            // Screen-state toggle just won't update live; feature still works.
        }

        scope.launch { recompute() }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (!enabled()) return
        scope.launch { recompute() }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (!enabled()) return
        scope.launch { recompute() }
    }

    override fun onListenerDisconnected() {
        stopBlink()
        scope.launch { LedNotifyManager.off() }
        unregisterScreenReceiver()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        stopBlink()
        scope.cancel()
        executor.shutdownNow()
        unregisterScreenReceiver()
        super.onDestroy()
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        screenReceiver = null
    }

    private fun enabled(): Boolean = prefs?.getBoolean(KEY_ENABLED, false) ?: false
    private fun flashWhileScreenOn(): Boolean =
        prefs?.getBoolean(KEY_FLASH_WHILE_SCREEN_ON, false) ?: false
    private fun flashLengthMs(): Long =
        (prefs?.getInt(KEY_FLASH_LENGTH_MS, DEFAULT_FLASH_LENGTH_MS) ?: DEFAULT_FLASH_LENGTH_MS).toLong()
    private fun cycleModeEnabled(): Boolean = prefs?.getBoolean(KEY_CYCLE_MODE, false) ?: false

    private fun colorFor(packageName: String): Int? {
        val value = prefs?.getInt(colorKey(packageName), Int.MIN_VALUE) ?: Int.MIN_VALUE
        return if (value == Int.MIN_VALUE) null else value
    }

    /** Re-derives what the LED should show from the current set of active notifications. */
    private fun recompute() {
        if (!enabled()) {
            stopBlink()
            LedNotifyManager.off()
            return
        }
        if (screenOn && !flashWhileScreenOn()) {
            stopBlink()
            LedNotifyManager.off()
            return
        }

        val actives = try {
            activeNotifications
        } catch (_: Exception) {
            return // not connected yet
        }

        // Most-recent-first, then dedupe by color so a cycle never repeats a
        // shade just because two apps happen to share it.
        val distinctColors = LinkedHashSet<Int>()
        actives.sortedByDescending { it.postTime }.forEach { sbn ->
            colorFor(sbn.packageName)?.let { distinctColors.add(it) }
        }

        when {
            distinctColors.isEmpty() -> {
                stopBlink()
                LedNotifyManager.off()
            }
            distinctColors.size == 1 || !cycleModeEnabled() -> {
                startOrUpdateBlink(listOf(distinctColors.first()))
            }
            else -> startOrUpdateBlink(distinctColors.toList())
        }
    }

    /**
     * Starts a coroutine that shows [colors] forever, or leaves an equivalent
     * one already running. Every color gets its own on/off blink - green,
     * off, blue, off, green, off, ... - rather than swapping directly
     * between colors with no gap.
     *
     * The "already running" check compares [colors] as a *set*, not an
     * ordered list: `actives.sortedByDescending { postTime }` can reorder
     * which color leads depending on notification update timing even when
     * the underlying set of active colors hasn't actually changed, and
     * restarting the loop over a pure reorder would just reintroduce the
     * same race this method exists to avoid.
     */
    private fun startOrUpdateBlink(colors: List<Int>) {
        if (colors.toSet() == blinkColors.toSet() && blinkJob?.isActive == true) return
        blinkJob?.cancel()
        blinkColors = colors
        // null represents an "off" phase, inserted after every color.
        val phases: List<Int?> = colors.flatMap { listOf(it, null) }

        blinkJob = scope.launch {
            var nextTick = System.currentTimeMillis()
            while (isActive) {
                for (phase in phases) {
                    if (!isActive) break
                    if (phase != null) LedNotifyManager.setColor(phase) else LedNotifyManager.off()

                    val period = flashLengthMs()
                    nextTick += period
                    val wait = nextTick - System.currentTimeMillis()
                    if (wait > 0) {
                        delay(wait)
                    } else {
                        // A write took longer than the configured period (e.g.
                        // the root shell was briefly slow) - resync instead of
                        // trying to catch up, so one slow write doesn't cause a
                        // burst of instant phase changes right after.
                        nextTick = System.currentTimeMillis()
                    }
                }
            }
        }
    }

    private fun stopBlink() {
        blinkJob?.cancel()
        blinkJob = null
        blinkColors = emptyList()
    }
}
