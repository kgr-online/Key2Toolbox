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
 * When more than one distinct color is active at once, [KEY_CYCLE_MODE]
 * decides what happens:
 *  - off (default): whichever managed notification was posted most recently
 *    wins the LED outright.
 *  - on: the LED cycles through every distinct active color in turn
 *    ([CYCLE_HOLD_MS] each), via a coroutine loop rather than the kernel
 *    trigger, since sysfs timer triggers can't natively chain multiple colors.
 *
 * The LED blinks (kernel timer trigger) rather than staying solid, using the
 * user-configured on/off length. By default it's suppressed while the screen
 * is on (the LED is only useful when you're not already looking at the
 * screen); [KEY_FLASH_WHILE_SCREEN_ON] overrides that.
 *
 * One-shot root writes happen on a single background thread so they never
 * block the listener's binder callbacks; the cycle loop runs on its own
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

        /** How long each color holds the LED while cycling through multiple. */
        private const val CYCLE_HOLD_MS = 2000L

        fun colorKey(packageName: String) = "$COLOR_KEY_PREFIX$packageName"
    }

    private var prefs: SharedPreferences? = null
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val cycleScope = CoroutineScope(SupervisorJob())
    private var cycleJob: Job? = null
    private var cycleColors: List<Int> = emptyList()
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
        stopCycle()
        worker.execute { LedNotifyManager.off() }
        unregisterScreenReceiver()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        stopCycle()
        cycleScope.cancel()
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
    private fun flashLengthMs(): Int =
        prefs?.getInt(KEY_FLASH_LENGTH_MS, DEFAULT_FLASH_LENGTH_MS) ?: DEFAULT_FLASH_LENGTH_MS
    private fun cycleModeEnabled(): Boolean = prefs?.getBoolean(KEY_CYCLE_MODE, false) ?: false

    private fun colorFor(packageName: String): Int? {
        val value = prefs?.getInt(colorKey(packageName), Int.MIN_VALUE) ?: Int.MIN_VALUE
        return if (value == Int.MIN_VALUE) null else value
    }

    /** Re-derives what the LED should show from the current set of active notifications. */
    private fun recompute() {
        if (!enabled()) {
            stopCycle()
            LedNotifyManager.off()
            return
        }
        if (screenOn && !flashWhileScreenOn()) {
            stopCycle()
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
                stopCycle()
                LedNotifyManager.off()
            }
            distinctColors.size == 1 || !cycleModeEnabled() -> {
                stopCycle()
                val ms = flashLengthMs()
                LedNotifyManager.setBlinking(distinctColors.first(), ms, ms)
            }
            else -> startOrUpdateCycle(distinctColors.toList())
        }
    }

    /** Starts a coroutine that walks through [colors] forever, or leaves an equivalent one running. */
    private fun startOrUpdateCycle(colors: List<Int>) {
        if (colors == cycleColors && cycleJob?.isActive == true) return
        cycleJob?.cancel()
        cycleColors = colors
        cycleJob = cycleScope.launch {
            while (isActive) {
                for (color in colors) {
                    if (!isActive) break
                    val ms = flashLengthMs()
                    LedNotifyManager.setBlinking(color, ms, ms)
                    delay(CYCLE_HOLD_MS)
                }
            }
        }
    }

    private fun stopCycle() {
        cycleJob?.cancel()
        cycleJob = null
        cycleColors = emptyList()
    }
}
