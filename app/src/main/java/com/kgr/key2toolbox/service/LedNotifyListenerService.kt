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
 * hardware triggers are listed, no `timer`), so [blinkJob] just alternates
 * [LedNotifyManager.setColor] and [LedNotifyManager.off] on a coroutine timer
 * instead. This is the same mechanism whether there's one active color or
 * several:
 *  - One color (or [KEY_CYCLE_MODE] off): that color blinks on repeat.
 *  - Multiple distinct colors with [KEY_CYCLE_MODE] on: the loop walks
 *    through each color in turn, blinking each once per pass - so with two
 *    colors you see A-blink, B-blink, A-blink, B-blink, ... rather than a
 *    long solid hold per color.
 * The tradeoff versus a hardware trigger: this stops if the app process is
 * killed (doze/battery optimization) until the next notification event
 * restarts it, since there's no kernel-side timer keeping it alive independently.
 *
 * By default the LED is suppressed while the screen is on (it's only useful
 * when you're not already looking at the screen); [KEY_FLASH_WHILE_SCREEN_ON]
 * overrides that.
 *
 * One-shot root writes happen on a single background thread so they never
 * block the listener's binder callbacks; the blink loop runs on its own
 * coroutine scope so it isn't blocked waiting on those one-shot writes either.
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
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val blinkScope = CoroutineScope(SupervisorJob())
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
                worker.execute { recompute() }
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

        worker.execute { recompute() }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (!enabled()) return
        worker.execute { recompute() }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (!enabled()) return
        worker.execute { recompute() }
    }

    override fun onListenerDisconnected() {
        stopBlink()
        worker.execute { LedNotifyManager.off() }
        unregisterScreenReceiver()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        stopBlink()
        blinkScope.cancel()
        unregisterScreenReceiver()
        worker.shutdown()
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
     * Starts a coroutine that blinks through [colors] forever (one on/off
     * pulse per color per pass), or leaves an equivalent one already running.
     */
    private fun startOrUpdateBlink(colors: List<Int>) {
        if (colors == blinkColors && blinkJob?.isActive == true) return
        blinkJob?.cancel()
        blinkColors = colors
        blinkJob = blinkScope.launch {
            while (isActive) {
                for (color in colors) {
                    if (!isActive) break
                    LedNotifyManager.setColor(color)
                    delay(flashLengthMs())
                    if (!isActive) break
                    LedNotifyManager.off()
                    delay(flashLengthMs())
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
