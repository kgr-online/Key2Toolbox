package com.kgr.key2toolbox.service

import android.content.BroadcastReceiver
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import com.kgr.key2toolbox.core.RootShell
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
 * **[wakeLock] is held for as long as the blink loop is running.** Confirmed
 * via logcat timing (see LedNotifyManager) that individual root-shell writes
 * are consistently fast (single-digit ms) and the fixed-rate scheduler was
 * landing right on target - so the remaining unevenness wasn't the write
 * latency or the scheduling math. It turned out to correlate with whether the
 * device was plugged in: Doze mode suspends CPU timing precision once the
 * screen's off, the device is on battery, and it's been idle a while (but
 * explicitly does *not* engage while charging) - so `delay()` calls in the
 * blink loop were firing late and in bursts once Doze kicked in, which is
 * exactly the "runs fine for a while, then stuck on/stuck off/flickers"
 * pattern. A partial wake lock, held only while a blink loop is actually
 * active, keeps the CPU responsive enough for the timer without needing to
 * exempt the whole app from battery optimization.
 *
 * By default the LED is suppressed while the screen is on (it's only useful
 * when you're not already looking at the screen); [KEY_FLASH_WHILE_SCREEN_ON]
 * overrides that.
 *
 * **DND / LOS Modes:** because this feature drives the LED directly via root
 * sysfs writes, it never passes through LineageOS's own per-app light-color
 * pipeline - which also means it never automatically inherits any
 * suppression a LOS Mode applies through that pipeline. What every DND-style
 * mode *does* reliably do, however it's triggered (manual toggle, schedule,
 * or a LOS Mode configured to enable DND), is move the system's
 * [NotificationManager.getCurrentInterruptionFilter] away from
 * [NotificationManager.INTERRUPTION_FILTER_ALL]. [respectDnd] checks that
 * value directly in [recompute] so the LED honors DND state regardless of
 * what triggered it. [KEY_RESPECT_DND] lets a user opt out, since wanting
 * the LED to *still* flash during DND (silent-but-still-visible) is a
 * legitimate use case too - it defaults to on.
 *
 * **Minimum importance threshold:** some apps post-then-immediately-retract
 * their own notifications for low-importance channels - observed with a
 * WhatsApp Business conversation whose notifications consistently lived
 * ~2.5s before an app-initiated `REASON_APP_CANCEL`, always on a
 * `IMPORTANCE_LOW` channel. That's a real, deterministic app behavior
 * (likely tied to a muted-in-app conversation), not a timing race, so
 * debouncing wouldn't help - the notification simply doesn't live long
 * enough to be worth lighting for in the first place. [KEY_MIN_IMPORTANCE]
 * (default [NotificationManager.IMPORTANCE_DEFAULT], adjustable in Settings)
 * filters these out in [recompute] before a color is ever assigned. An
 * unknown/unavailable importance always passes the filter rather than
 * silently suppressing the LED.
 *
 * **Battery saver:** [respectBatterySaver] checks [PowerManager.isPowerSaveMode]
 * directly in [recompute], same shape as the DND check above - it doesn't
 * matter whether battery saver was turned on manually, on a schedule, or
 * automatically at a low-battery threshold. [KEY_RESPECT_BATTERY_SAVER]
 * defaults to on; a user who wants the LED to keep working even under
 * battery saver can opt out.
 *
 * **Notification Acknowledgement:** replicates factory 8.1 KEY2 behavior -
 * once you've turned the screen on, seen a notification on the lock screen,
 * and turned the screen back off with the power button, that notification's
 * LED doesn't re-trigger. A screen timeout (walked away without checking)
 * does *not* count as acknowledgement, and neither does an update to the
 * notification's content afterward or an unrelated new notification that
 * happens to reuse the same key - see [acknowledgedKeys],
 * [lastSleepReason], and the suppression check in [recompute].
 * [KEY_ACK_ON_SCREEN_OFF] defaults to off, since it changes existing blink
 * behavior.
 */
class LedNotifyListenerService : NotificationListenerService() {

    companion object {
        const val PREFS = "led_notify"
        const val KEY_ENABLED = "enabled"
        const val KEY_FLASH_WHILE_SCREEN_ON = "flash_while_screen_on"
        const val KEY_FLASH_LENGTH_MS = "flash_length_ms"
        const val DEFAULT_FLASH_LENGTH_MS = 500
        const val KEY_CYCLE_MODE = "cycle_mode"
        const val KEY_RESPECT_DND = "respect_dnd"
        const val DEFAULT_RESPECT_DND = true
        const val KEY_RESPECT_BATTERY_SAVER = "respect_battery_saver"
        const val DEFAULT_RESPECT_BATTERY_SAVER = true
        const val KEY_ACK_ON_SCREEN_OFF = "ack_on_screen_off"
        const val DEFAULT_ACK_ON_SCREEN_OFF = false
        private const val SLEEP_REASON_POWER_BUTTON = "power_button"
        const val KEY_MIN_IMPORTANCE = "min_importance"
        const val DEFAULT_MIN_IMPORTANCE = NotificationManager.IMPORTANCE_DEFAULT
        const val COLOR_KEY_PREFIX = "color_"

        // Safety ceiling on the wake lock so a coroutine leak (bug elsewhere)
        // can't hold it forever - it gets renewed well before this while a
        // blink is genuinely still running (see startOrUpdateBlink).
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L // 10 minutes

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
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var screenOn = true
    private var screenReceiver: BroadcastReceiver? = null
    private var dndReceiver: BroadcastReceiver? = null
    private var batterySaverReceiver: BroadcastReceiver? = null

    // Notification Acknowledgement: key -> postTime, snapshotted from
    // activeNotifications the moment the screen turns off via a power-button
    // press (never via timeout - see lastSleepReason()). A notification is
    // only suppressed from the LED while its key AND postTime both still
    // match the snapshot; an app-side update or a genuinely new notification
    // that happens to reuse the same key naturally falls out of suppression.
    // Pruned against current actives on every recompute() so it never grows
    // unbounded and self-corrects if an event is ever missed - same
    // philosophy as the rest of this class.
    private val acknowledgedKeys = mutableMapOf<String, Long>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        screenOn = pm?.isInteractive ?: true

        val rx = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val turningOn = intent?.action == Intent.ACTION_SCREEN_ON
                screenOn = turningOn
                if (!turningOn && ackOnScreenOff()) {
                    // Root shell call, so keep it off the main thread - runs
                    // on the same single-thread executor as everything else
                    // here, ahead of the recompute() it gates.
                    scope.launch {
                        if (lastSleepReason() == SLEEP_REASON_POWER_BUTTON) {
                            acknowledgeActiveNotifications()
                        }
                        recompute()
                    }
                } else {
                    scope.launch { recompute() }
                }
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

        // Recompute immediately when DND is toggled - otherwise a blink
        // already in progress would keep running until the next
        // notification post/remove event happened to trigger recompute().
        val dndRx = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                scope.launch { recompute() }
            }
        }
        try {
            ContextCompat.registerReceiver(
                this,
                dndRx,
                IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            dndReceiver = dndRx
        } catch (_: Exception) {
            // DND toggles just won't update live until the next notification
            // event; feature still works.
        }

        // Recompute immediately when battery saver is toggled - same
        // reasoning as the DND receiver above.
        val batterySaverRx = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                scope.launch { recompute() }
            }
        }
        try {
            ContextCompat.registerReceiver(
                this,
                batterySaverRx,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            batterySaverReceiver = batterySaverRx
        } catch (_: Exception) {
            // Battery saver toggles just won't update live until the next
            // notification event; feature still works.
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
        acknowledgedKeys.clear()
        scope.launch { LedNotifyManager.off() }
        unregisterScreenReceiver()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        stopBlink()
        acknowledgedKeys.clear()
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
        dndReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        dndReceiver = null
        batterySaverReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        batterySaverReceiver = null
    }

    private fun enabled(): Boolean = prefs?.getBoolean(KEY_ENABLED, false) ?: false
    private fun flashWhileScreenOn(): Boolean =
        prefs?.getBoolean(KEY_FLASH_WHILE_SCREEN_ON, false) ?: false
    private fun flashLengthMs(): Long =
        (prefs?.getInt(KEY_FLASH_LENGTH_MS, DEFAULT_FLASH_LENGTH_MS) ?: DEFAULT_FLASH_LENGTH_MS).toLong()
    private fun cycleModeEnabled(): Boolean = prefs?.getBoolean(KEY_CYCLE_MODE, false) ?: false
    private fun respectDnd(): Boolean = prefs?.getBoolean(KEY_RESPECT_DND, DEFAULT_RESPECT_DND) ?: DEFAULT_RESPECT_DND
    private fun respectBatterySaver(): Boolean =
        prefs?.getBoolean(KEY_RESPECT_BATTERY_SAVER, DEFAULT_RESPECT_BATTERY_SAVER) ?: DEFAULT_RESPECT_BATTERY_SAVER
    private fun ackOnScreenOff(): Boolean =
        prefs?.getBoolean(KEY_ACK_ON_SCREEN_OFF, DEFAULT_ACK_ON_SCREEN_OFF) ?: DEFAULT_ACK_ON_SCREEN_OFF
    private fun minImportance(): Int =
        prefs?.getInt(KEY_MIN_IMPORTANCE, DEFAULT_MIN_IMPORTANCE) ?: DEFAULT_MIN_IMPORTANCE

    /**
     * Ranking-derived importance for [sbn], or null if it can't be determined
     * (e.g. ranking data not yet available). Null is treated as "allow" by
     * callers - an unknown importance shouldn't silently suppress the LED.
     */
    private fun importanceOf(sbn: StatusBarNotification): Int? {
        val rm = currentRanking ?: return null
        val out = Ranking()
        return if (rm.getRanking(sbn.key, out)) out.importance else null
    }

    /**
     * True if the system is currently in any DND-style state - manual toggle,
     * schedule, or a LOS Mode configured to enable it - regardless of which
     * of those triggered it. See class doc "DND / LOS Modes".
     */
    private fun dndActive(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        return nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    /** True if the system is currently in battery saver / power save mode. */
    private fun powerSaveActive(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isPowerSaveMode
    }

    /**
     * The system's recorded reason for the most recent screen-off, read via
     * root shell since `dumpsys` isn't accessible to a normal app process -
     * same approach as the DND fallback mentioned in the class doc. Confirmed
     * against real device output: `mLastSleepReason=power_button` vs
     * `mLastSleepReason=timeout` are both plain, stable field values in the
     * top-level "Power Manager State:" block. Returns null if the field
     * can't be found (older/different ROM) - callers treat that as "not a
     * power button press" rather than guessing.
     */
    private fun lastSleepReason(): String? {
        val out = RootShell.run("dumpsys power | grep mLastSleepReason").outString
        return Regex("""mLastSleepReason=(\S+)""").find(out)?.groupValues?.get(1)
    }

    /** Snapshots every currently-active notification's key+postTime as acknowledged. */
    private fun acknowledgeActiveNotifications() {
        val actives = try {
            activeNotifications
        } catch (_: Exception) {
            return // not connected yet
        }
        actives.forEach { sbn -> acknowledgedKeys[sbn.key] = sbn.postTime }
    }

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
        if (respectDnd() && dndActive()) {
            stopBlink()
            LedNotifyManager.off()
            return
        }
        if (respectBatterySaver() && powerSaveActive()) {
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

        // Self-correcting: drop any acknowledged entry whose notification is
        // no longer active, so the map never grows unbounded.
        acknowledgedKeys.keys.retainAll(actives.map { it.key }.toSet())

        // Most-recent-first, then dedupe by color so a cycle never repeats a
        // shade just because two apps happen to share it.
        val distinctColors = LinkedHashSet<Int>()
        val threshold = minImportance()
        actives.sortedByDescending { it.postTime }.forEach { sbn ->
            val importance = importanceOf(sbn)
            // Unknown importance (null) always passes - see importanceOf().
            if (importance != null && importance < threshold) return@forEach
            // Already seen on the lock screen and acknowledged with the
            // power button - same key AND same postTime as the snapshot
            // (an update or a reused key on a new notification isn't this).
            if (acknowledgedKeys[sbn.key] == sbn.postTime) return@forEach
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
        acquireWakeLock()
        // null represents an "off" phase, inserted after every color.
        val phases: List<Int?> = colors.flatMap { listOf(it, null) }

        blinkJob = scope.launch {
            var nextTick = System.currentTimeMillis()
            var lastWakeLockRenewal = System.currentTimeMillis()
            while (isActive) {
                for (phase in phases) {
                    if (!isActive) break
                    if (phase != null) LedNotifyManager.setColor(phase) else LedNotifyManager.off()

                    // Renew the wake lock's safety timeout periodically so a
                    // genuinely long-running blink (someone leaves a
                    // notification unread for hours) doesn't lose it and fall
                    // back into Doze-affected timing partway through.
                    val now = System.currentTimeMillis()
                    if (now - lastWakeLockRenewal > WAKE_LOCK_TIMEOUT_MS / 2) {
                        acquireWakeLock()
                        lastWakeLockRenewal = now
                    }

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
        releaseWakeLock()
    }

    private fun acquireWakeLock() {
        val current = wakeLock
        if (current != null) {
            // Already held - just extend the safety timeout.
            try {
                current.acquire(WAKE_LOCK_TIMEOUT_MS)
            } catch (_: Exception) {
            }
            return
        }
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        try {
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "K2TB:LedNotifyBlink")
            lock.setReferenceCounted(false)
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            wakeLock = lock
        } catch (_: Exception) {
            // Missing permission or unsupported - blink still runs, just
            // subject to Doze timing on battery.
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            try {
                if (it.isHeld) it.release()
            } catch (_: Exception) {
            }
        }
        wakeLock = null
    }
}
