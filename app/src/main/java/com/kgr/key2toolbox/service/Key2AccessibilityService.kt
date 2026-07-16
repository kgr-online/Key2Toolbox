package com.kgr.key2toolbox.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import com.kgr.key2toolbox.core.AssetInstaller
import com.kgr.key2toolbox.core.RootShell
import com.kgr.key2toolbox.inputfix.CalculatorInputFix
import com.kgr.key2toolbox.modules.AutoFocusController
import com.kgr.key2toolbox.modules.BatteryUsageController
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Combined accessibility service for the ported nozerorma Key2 Tweaks features.
 *
 * - Nav Lock: while the on-screen keyboard (IME) is visible, stops accidental
 *   Back / Home / Recents presses. Two modes:
 *     * Disable (root): cut the capacitive keys via the sysfs node
 *       /sys/class/input/eventN/device/0dbutton (1 = on, 0 = off), resolved by
 *       device name (synaptics_dsx_2) so it survives reboots.
 *     * Gesture (no root): keep the keys live but gate BACK in onKeyEvent - a
 *       single tap is swallowed; only a double-tap fires it. Only Back is
 *       gateable; Home/Recents are acted on by the window policy regardless of
 *       accessibility consumption.
 *
 * - PIN Input: on the lockscreen, maps physical-keyboard presses to taps on the
 *   SystemUI PIN pad so the PIN can be typed on the hardware keyboard.
 *
 * Each feature has an independent toggle stored in SharedPreferences ("key2tweaks").
 * Root writes go through RootShell (libsu) rather than a raw su process, to share
 * one root-execution path with the rest of the app.
 *
 * "Disable ALWAYS" additionally installs a /data/adb/service.d/ boot script
 * (nav_always_off.sh), since the sysfs node resets to its driver default
 * (enabled) on every reboot and this mode shouldn't depend on the
 * accessibility service starting up before the buttons get disabled.
 */
class Key2AccessibilityService : AccessibilityService() {

    companion object {
        const val PREFS = "key2tweaks"
        const val KEY_NAV_LOCK = "nav_lock_enabled"
        const val KEY_NAV_GESTURE = "nav_gesture_mode" // false=disable buttons, true=double-tap gate (Back)
        const val KEY_NAV_ALWAYS_OFF = "nav_always_off" // disable nav buttons permanently
        const val KEY_PIN_INPUT = "pin_input_enabled"
        const val KEY_IME_BLOCK = "ime_block_enabled"     // bypass IME in selected apps
        const val KEY_IME_BLOCK_APPS = "ime_block_apps"   // StringSet of package names
        const val KEY_IME_SAVED = "ime_block_saved_ime"   // IME to restore when leaving a blocked app
        const val KEY_CALCULATOR = "calculator_enabled"   // route number/operator keys to calculators
        const val KEY_IME_SUGGESTIONS = "ime_suggestions_enabled" // Ctrl+W/E/R picks IME suggestion 1/2/3
        const val KEY_IN_CALL_SHORTCUTS = "in_call_shortcuts_enabled"

        // Our do-nothing IME: while it's active, physical key presses go straight
        // to the app instead of being intercepted/translated by the normal keyboard.
        const val PASSTHRU_IME = "com.kgr.key2toolbox/.service.Key2PassthroughIme"
        // Key2 stock keyboard - the default to fall back to if we have nothing saved.
        private const val DEFAULT_IME_FALLBACK =
            "com.blackberry.keyboard/com.blackberry.inputmethod.core.BlackBerryIME"

        private const val LONG_PRESS_MS = 350L
        private const val DOUBLE_TAP_MS = 300L

        private const val ALWAYS_OFF_SCRIPT = "nav_always_off.sh"
        private const val ALWAYS_OFF_TARGET = "/data/adb/service.d/$ALWAYS_OFF_SCRIPT"

        // How often to re-assert the sysfs node while actively typing (IME visible) with
        // nav-lock desired - this window is short-lived (bounded by the typing session),
        // so a fast interval to quickly catch drift is cheap overall.
        private const val SELF_HEAL_INTERVAL_MS = 1500L

        // How often to re-assert otherwise (e.g. "Disable ALWAYS" mode, which is desired
        // indefinitely) - this used to reuse SELF_HEAL_INTERVAL_MS, which meant a root
        // shell exec every 1.5s *forever* for anyone with Disable ALWAYS on, showing up
        // as hundreds of `runRoot` calls per logcat buffer and real, measurable battery
        // drain overnight. The known reset trigger (driver resetting the sysfs node) is
        // tied to boot/wake, already covered by the screen-on retries below - this is
        // just a much cheaper defensive safety net for the unbounded-duration case.
        private const val SELF_HEAL_IDLE_INTERVAL_MS = 60_000L
    }

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var navDisabled = false // last state pushed to kernel
    @Volatile private var imeActive = false   // keyboard currently showing
    @Volatile private var imeBlockApplied = false // last show_ime value we pushed (true = suppressed)
    @Volatile private var foregroundPkg: String? = null // last seen foreground app package
    private val lastNavTap = HashMap<Int, Long>() // keycode -> last short-tap time
    private var prefs: SharedPreferences? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var selfHealRunning = false
    private val calculatorFix = CalculatorInputFix()
    private var consumedAutofocusKeycode = -1
    // Set right before a focus-and-type attempt starts waiting, so onAccessibilityEvent can
    // wake it the instant the target field actually gets input focus (see onKeyEvent).
    @Volatile private var focusLatch: CountDownLatch? = null

    // True once the configured reset threshold has been crossed for the current plug-in
    // session, so a single crossing only triggers one reset (not one per broadcast) until the
    // level drops back below the threshold (e.g. unplugged, or a fresh charge from lower).
    private var batteryThresholdArmed = false

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
        if (key == null) return@OnSharedPreferenceChangeListener
        if (key == KEY_NAV_LOCK || key == KEY_NAV_GESTURE || key == KEY_NAV_ALWAYS_OFF) {
            reconcileNav()
        }
        if (key == KEY_NAV_ALWAYS_OFF) {
            val alwaysOffNow = sp.getBoolean(KEY_NAV_ALWAYS_OFF, false)
            worker.execute { persistAlwaysOff(alwaysOffNow) }
        }
        if (key == KEY_IME_BLOCK || key == KEY_IME_BLOCK_APPS) {
            reconcileImeBlock()
        }
    }

    /** Installs or removes the boot script that disables nav buttons at startup. */
    private fun persistAlwaysOff(enabled: Boolean) {
        try {
            if (enabled) {
                AssetInstaller.installFromAsset(this, ALWAYS_OFF_SCRIPT, ALWAYS_OFF_TARGET)
            } else {
                AssetInstaller.removeFile(ALWAYS_OFF_TARGET)
            }
        } catch (_: Exception) {
            // Persistence failed; live toggle still works for this session.
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs = p
        p.registerOnSharedPreferenceChangeListener(prefListener)

        serviceInfo?.let { info ->
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            serviceInfo = info
        }

        // Seed navDisabled from the real kernel state and reconcile.
        forceReconcile()
        worker.execute {
            // Make sure the boot script matches the current pref, in case
            // it was enabled before this persistence logic existed, or the
            // install/removal previously failed silently.
            persistAlwaysOff(alwaysOff())

            // Seed imeBlockApplied from the live default IME, so a mid-session
            // restart while the passthrough IME is active still gets reconciled
            // (and restored) once the foreground app is known.
            val curIme = RootShell.run("settings get secure default_input_method")
                .outString.trim()
            imeBlockApplied = (curIme == PASSTHRU_IME)
        }

        val rx = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d("Key2Toolbox", "Screen state changed: ${intent?.action}")
                forceReconcile()
                mainHandler.postDelayed({ forceReconcile() }, 300)
                mainHandler.postDelayed({ forceReconcile() }, 600)
                mainHandler.postDelayed({ forceReconcile() }, 1200)
                mainHandler.postDelayed({ forceReconcile() }, 2500)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            ContextCompat.registerReceiver(this, rx, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            screenReceiver = rx
            Log.d("Key2Toolbox", "Successfully registered screenReceiver")
        } catch (e: Exception) {
            Log.e("Key2Toolbox", "Failed to register screenReceiver", e)
        }

        // Auto-resets battery usage stats once the level reaches the configured threshold while
        // charging - a substitute for BATTERY_STATUS_FULL, which this device's charging driver
        // never reports (see BatteryUsageController.resetStats doc).
        val battery = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level < 0 || scale <= 0) return
                val percent = level * 100 / scale
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
                val threshold = BatteryUsageController.getResetThreshold(this@Key2AccessibilityService)

                if (charging && percent >= threshold) {
                    if (!batteryThresholdArmed) {
                        batteryThresholdArmed = true
                        worker.execute { BatteryUsageController.resetStats() }
                    }
                } else if (percent < threshold) {
                    batteryThresholdArmed = false
                }
            }
        }
        try {
            ContextCompat.registerReceiver(
                this, battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
            )
            batteryReceiver = battery
        } catch (e: Exception) {
            Log.e("Key2Toolbox", "Failed to register batteryReceiver", e)
        }
    }

    /** Reads the current 0dbutton value for the synaptics_dsx_2 device, if found. */
    private fun readNavDisabledFromKernel(): Boolean? {
        val script =
            "for d in /sys/class/input/event*; do " +
                "if [ \"\$(cat \"\$d/device/name\" 2>/dev/null)\" = synaptics_dsx_2 ]; then " +
                "cat \"\$d/device/0dbutton\"; " +
                "fi; " +
                "done"
        return try {
            val result = RootShell.run(script)
            when (result.outString.trim()) {
                "0" -> true  // 0 = buttons disabled
                "1" -> false // 1 = buttons enabled
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun navLockEnabled() = prefs?.getBoolean(KEY_NAV_LOCK, true) ?: true
    private fun gestureMode() = prefs?.getBoolean(KEY_NAV_GESTURE, false) ?: false
    private fun alwaysOff() = prefs?.getBoolean(KEY_NAV_ALWAYS_OFF, false) ?: false
    private fun pinInputEnabled() = prefs?.getBoolean(KEY_PIN_INPUT, true) ?: true
    private fun imeBlockEnabled() = prefs?.getBoolean(KEY_IME_BLOCK, false) ?: false
    private fun imeBlockApps(): Set<String> =
        prefs?.getStringSet(KEY_IME_BLOCK_APPS, emptySet()) ?: emptySet()
    private fun calculatorEnabled() = prefs?.getBoolean(KEY_CALCULATOR, false) ?: false
    private fun imeSuggestionsEnabled() = prefs?.getBoolean(KEY_IME_SUGGESTIONS, false) ?: false
    private fun inCallShortcutsEnabled() = prefs?.getBoolean(KEY_IN_CALL_SHORTCUTS, false) ?: false

    // ---------------------------------------------------------------- Nav Lock

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Single windows() fetch shared by both checks below - this fires on essentially
        // every focus/window change system-wide (including this app's own tab switches,
        // since the service observes its own process too), so a second independent
        // cross-process windows() call here was doubling the per-event IPC cost and was
        // a direct contributor to UI jank during those very same tab transitions.
        val windowList: List<AccessibilityWindowInfo> = try {
            windows ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        imeActive = windowList.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        reconcileNav()

        val pkg = foregroundPackageFrom(windowList)
        if (pkg != null && pkg != foregroundPkg) {
            foregroundPkg = pkg
            reconcileImeBlock()
        }

        if (inCallShortcutsEnabled() && isGoogleDialerForeground()) {
            if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                autoOpenDialpad()
            }
        }

        // Wake up a pending auto-focus wait (see onKeyEvent) as soon as the field it's
        // waiting on actually receives input focus, instead of it finding out only on
        // its next poll tick.
        focusLatch?.let { latch ->
            if (event?.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                event?.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED ||
                event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                rootInActiveWindow?.let { r ->
                    try {
                        val focused = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        val isEditable = focused?.let { AutoFocusController.isEditableTextField(it) } ?: false
                        focused?.recycle()
                        if (isEditable) latch.countDown()
                    } finally {
                        r.recycle()
                    }
                }
            }
        }
    }

    private fun isGoogleDialerForeground(): Boolean {
        val pkg = foregroundPkg ?: return false
        return pkg == "com.google.android.dialer" || pkg == "com.google.android.apps.dialer"
    }

    /**
     * True only for the Dialpad tab's actual phone-number EditText, not other dialer-app
     * screens (Contacts search, Favorites/Home, Recents) that share the same foreground
     * package. Confirmed via uiautomator dump: resource-id "com.google.android.dialer:id/digits".
     */
    private fun isDialpadDigitsField(node: AccessibilityNodeInfo): Boolean {
        val id = node.viewIdResourceName ?: return false
        return id == "com.google.android.dialer:id/digits" || id == "com.google.android.apps.dialer:id/digits"
    }

    private fun isAutoFocusEnabledForForeground(): Boolean {
        val p = prefs ?: return false
        if (!AutoFocusController.isEnabled(p)) return false
        val pkg = foregroundPkg ?: return false
        return pkg in AutoFocusController.getSelectedApps(p)
    }

    private fun findCheckables(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        if (node.isCheckable) {
            list.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findCheckables(child, list)
            child.recycle()
        }
    }

    private fun autoOpenDialpad() {
        val root = rootInActiveWindow ?: return
        try {
            val checkables = mutableListOf<AccessibilityNodeInfo>()
            findCheckables(root, checkables)
            try {
                // Only the actual in-call screen has the full keypad+mute+speaker toggle
                // set, so this can't misfire on the pre-call dial-a-number screen's own
                // (unrelated) checkables.
                if (checkables.size >= 3) {
                    val keypadNode = checkables[0]
                    if (!keypadNode.isChecked) {
                        keypadNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                }
            } finally {
                checkables.forEach { it.recycle() }
            }
        } finally {
            root.recycle()
        }
    }

    private fun injectDialerKey(kcCode: Int) {
        worker.execute {
            RootShell.run("input keyevent $kcCode")
        }
    }

    // --------------------------------------------------------------- IME Block

    /**
     * The package of the focused/active TYPE_APPLICATION window - i.e. the app
     * behind any keyboard. Reading the application window (not the event source)
     * keeps this stable while the IME window comes and goes.
     */
    private fun foregroundPackageFrom(windowList: List<AccessibilityWindowInfo>): String? {
        for (w in windowList) {
            if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION && (w.isActive || w.isFocused)) {
                val root = w.root ?: continue
                val pkg = root.packageName?.toString()
                root.recycle()
                if (pkg != null) return pkg
            }
        }
        return null
    }

    /** Switch to / from the passthrough IME based on the current foreground app. */
    private fun reconcileImeBlock() {
        val desired = imeBlockEnabled() && foregroundPkg?.let { it in imeBlockApps() } == true
        if (desired == imeBlockApplied) return
        imeBlockApplied = desired
        worker.execute { applyImeBlock(desired) }
    }

    /**
     * Bypass the keyboard for selected apps by switching the default input method
     * to a do-nothing passthrough IME, so physical key presses reach the app raw
     * instead of being intercepted/translated by the normal keyboard (e.g. the
     * BlackBerry IME). Restores the previously active IME on the way out.
     */
    private fun applyImeBlock(bypass: Boolean) {
        try {
            val current = RootShell.run("settings get secure default_input_method")
                .outString.trim()
            if (bypass) {
                if (current != PASSTHRU_IME) {
                    if (current.isNotEmpty() && current != "null") {
                        prefs?.edit()?.putString(KEY_IME_SAVED, current)?.apply()
                    }
                    RootShell.run("ime enable $PASSTHRU_IME ; ime set $PASSTHRU_IME")
                }
            } else if (current == PASSTHRU_IME) {
                val saved = prefs?.getString(KEY_IME_SAVED, null)
                    ?.takeIf { it.isNotEmpty() && it != "null" } ?: DEFAULT_IME_FALLBACK
                RootShell.run("ime set $saved")
            }
            Log.d("Key2Toolbox", "applyImeBlock: bypass=$bypass, was=$current")
        } catch (e: Exception) {
            Log.e("Key2Toolbox", "applyImeBlock failed for bypass=$bypass", e)
        }
    }

    private fun desiredNavDisabled(): Boolean = when {
        alwaysOff() -> true                          // permanently disabled
        !navLockEnabled() || gestureMode() -> false   // buttons stay live (gesture mode gates in onKeyEvent)
        else -> imeActive                             // disable-while-typing mode
    }

    /** Compute and apply the desired capacitive-button state from current settings. */
    private fun reconcileNav() {
        val desired = desiredNavDisabled()
        // Only log on an actual transition, not every call - this runs on essentially every
        // accessibility event system-wide (including this app's own UI changes), so an
        // unconditional Log.d here was string-building and writing to logcat continuously,
        // directly contending with the UI thread during things like our own tab switches.
        if (desired != navDisabled) {
            Log.d("Key2Toolbox", "reconcileNav: desired=$desired, currentCache=$navDisabled, alwaysOff=${alwaysOff()}, imeActive=$imeActive")
            applyNavDisabled(desired)
        }
        if (desired) scheduleSelfHeal()
    }

    private fun forceReconcile() {
        worker.execute {
            val desired = desiredNavDisabled()
            Log.d("Key2Toolbox", "forceReconcile: desired=$desired, alwaysOff=${alwaysOff()}, imeActive=$imeActive")
            // Always apply directly to the hardware to override any driver/kernel-level resets
            navDisabled = desired
            runRoot(if (desired) "0" else "1")
        }
        if (desiredNavDisabled()) mainHandler.post { scheduleSelfHeal() }
    }

    /**
     * While the buttons are supposed to be disabled, periodically re-write the
     * sysfs node instead of waiting for another accessibility event (window
     * change) to call reconcileNav() again. The driver can reset the node on
     * its own with no corresponding event, silently leaving the buttons live
     * until the next window change or screen cycle without this.
     */
    private val selfHealRunnable = object : Runnable {
        override fun run() {
            if (!desiredNavDisabled()) {
                selfHealRunning = false
                return
            }
            worker.execute { runRoot("0") }
            val interval = if (imeActive) SELF_HEAL_INTERVAL_MS else SELF_HEAL_IDLE_INTERVAL_MS
            mainHandler.postDelayed(this, interval)
        }
    }

    private fun scheduleSelfHeal() {
        if (selfHealRunning) return
        selfHealRunning = true
        val interval = if (imeActive) SELF_HEAL_INTERVAL_MS else SELF_HEAL_IDLE_INTERVAL_MS
        mainHandler.postDelayed(selfHealRunnable, interval)
    }

    // ----------------------------------------------------------- IME Suggestions

    /**
     * Clicks the Nth clickable TextView in the IME window (its suggestion strip) - confirmed on
     * BlackBerry Keyboard, where the candidate strip is exactly a row of clickable TextViews
     * alongside an unrelated ImageButton (the quick-modes toggle), which the TextView classname
     * check filters out.
     */
    private fun clickImeSuggestion(index: Int): Boolean {
        val imeRoot = windows?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.root ?: return false
        try {
            val suggestions = mutableListOf<AccessibilityNodeInfo>()
            findClickableTextViews(imeRoot, suggestions)
            try {
                if (suggestions.size <= index) return false
                return suggestions[index].performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } finally {
                suggestions.forEach { it.recycle() }
            }
        } finally {
            imeRoot.recycle()
        }
    }

    private fun findClickableTextViews(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        if (node.isClickable && node.className?.contains("TextView") == true) {
            list.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findClickableTextViews(child, list)
            child.recycle()
        }
    }

    private fun applyNavDisabled(disabled: Boolean) {
        Log.d("Key2Toolbox", "applyNavDisabled: disabled=$disabled")
        navDisabled = disabled
        worker.execute { runRoot(if (disabled) "0" else "1") }
    }

    private fun writeNodeBlocking(enabled: Boolean) {
        Log.d("Key2Toolbox", "writeNodeBlocking: enabled=$enabled")
        navDisabled = !enabled
        runRoot(if (enabled) "1" else "0")
    }

    private fun runRoot(value: String) {
        val script = if (value == "0") {
            // Write 1 then 0 to bypass driver-level caching if the hardware was reset
            "for d in /sys/class/input/event*; do " +
                "if [ \"\$(cat \"\$d/device/name\" 2>/dev/null)\" = synaptics_dsx_2 ]; then " +
                "echo 1 > \"\$d/device/0dbutton\" 2>/dev/null; " +
                "echo 0 > \"\$d/device/0dbutton\" 2>/dev/null; " +
                "fi; " +
                "done"
        } else {
            // Write 0 then 1 to ensure it enables
            "for d in /sys/class/input/event*; do " +
                "if [ \"\$(cat \"\$d/device/name\" 2>/dev/null)\" = synaptics_dsx_2 ]; then " +
                "echo 0 > \"\$d/device/0dbutton\" 2>/dev/null; " +
                "echo 1 > \"\$d/device/0dbutton\" 2>/dev/null; " +
                "fi; " +
                "done"
        }
        try {
            val res = RootShell.run(script)
            Log.d("Key2Toolbox", "runRoot: value=$value, success=${res.success}, out=${res.outString}")
        } catch (e: Exception) {
            Log.e("Key2Toolbox", "runRoot failed for value=$value", e)
        }
    }

    // --------------------------------------------------------------- PIN Input

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        val kc = event.keyCode

        // IME suggestion shortcuts: Ctrl+W/E/R picks suggestion 1/2/3 from the keyboard's
        // candidate strip. Only consumes the key if a suggestion was actually found and
        // clicked, so Ctrl+W/E/R still behaves normally (e.g. closing a browser tab) when
        // no suggestions are showing.
        if (imeSuggestionsEnabled() && event.isCtrlPressed && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val suggestionIndex = when (kc) {
                KeyEvent.KEYCODE_W -> 0
                KeyEvent.KEYCODE_E -> 1
                KeyEvent.KEYCODE_R -> 2
                else -> -1
            }
            if (suggestionIndex >= 0 && clickImeSuggestion(suggestionIndex)) {
                return true
            }
        }

        // Nav gesture-gate (Back only): while typing, swallow a quick tap on Back
        // and fire it only on a double-tap. Home/Recents can't be gated - Android's
        // window policy acts on them regardless of accessibility consumption.
        if (navLockEnabled() && gestureMode() && imeActive &&
            kc == KeyEvent.KEYCODE_BACK && !isDeviceLocked()
        ) {
            return handleNavGesture(event, kc)
        }

        // In-call shortcuts for Google Phone / Dialer: currency/Ctrl key (Speaker),
        // M key (Mute), digits (Dialpad).
        if (inCallShortcutsEnabled() && isGoogleDialerForeground()) {
            val root = rootInActiveWindow
            if (root != null) {
                try {
                    val checkables = mutableListOf<AccessibilityNodeInfo>()
                    findCheckables(root, checkables)
                    try {
                        // Require the full expected in-call toggle set (keypad, mute, speaker) -
                        // the standalone pre-call dial-a-number screen isn't guaranteed to have
                        // zero checkables, and matching on just "any" would misfire there.
                        if (checkables.size >= 3) {
                            // Unmapped raw keycode for the Currency key is KEYCODE_4 (see
                            // stmpe.kl scancode 5); if Key Remap has it (or the Convenience
                            // key) mapped to Ctrl instead, this still fires either way.
                            val isSpeakerKey = kc == KeyEvent.KEYCODE_CTRL_LEFT || kc == KeyEvent.KEYCODE_4
                            val isMuteKey = kc == KeyEvent.KEYCODE_M

                            if (isSpeakerKey) {
                                if (event.action == KeyEvent.ACTION_UP) {
                                    if (checkables.size > 2) {
                                        checkables[2].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    }
                                }
                                return true
                            }

                            if (isMuteKey) {
                                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                                    if (checkables.size > 1) {
                                        checkables[1].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    }
                                }
                                return true
                            }

                            val digit = keyCodeToDigit(kc)
                            if (digit != null) {
                                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                                    val injectKc = KeyEvent.KEYCODE_0 + digit.toInt()
                                    val keypadNode = checkables[0]
                                    if (keypadNode.isChecked) {
                                        injectDialerKey(injectKc)
                                    } else {
                                        keypadNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                        worker.execute {
                                            for (attempt in 1..10) {
                                                Thread.sleep(50)
                                                val opened = rootInActiveWindow?.let { r ->
                                                    try {
                                                        val cs = mutableListOf<AccessibilityNodeInfo>()
                                                        findCheckables(r, cs)
                                                        try {
                                                            cs.isNotEmpty() && cs[0].isChecked
                                                        } finally {
                                                            cs.forEach { it.recycle() }
                                                        }
                                                    } finally {
                                                        r.recycle()
                                                    }
                                                } ?: false
                                                if (opened) break
                                            }
                                            RootShell.run("input keyevent $injectKc")
                                        }
                                    }
                                }
                                return true
                            }
                        }
                    } finally {
                        checkables.forEach { it.recycle() }
                    }
                } finally {
                    root.recycle()
                }
            }
        }

        // Auto-Focus: focus and type into the first text field on the first printable
        // key press when nothing is focused yet. Gated on a cheap, native-backed "does
        // anything already have input focus?" check rather than a per-app-session "have
        // we tried already" flag - the latter got stuck once focus was lost mid-session
        // (e.g. tapping a back arrow or the screen elsewhere in the same app), since
        // nothing re-armed it without an app change. Checking live focus state instead
        // means it naturally re-attempts whenever focus is actually gone, while still
        // skipping the expensive tree search whenever a field is already focused (the
        // common case while continuing to type).
        if (isAutoFocusEnabledForForeground()) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val unicodeChar = event.unicodeChar
                if (unicodeChar > 0 && event.repeatCount == 0 && !event.isAltPressed && !event.isCtrlPressed) {
                    val root = rootInActiveWindow
                    if (root != null) {
                        try {
                            val alreadyFocused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                            val alreadyFocusedIsTextField = alreadyFocused?.let { AutoFocusController.isEditableTextField(it) } ?: false
                            alreadyFocused?.recycle()
                            if (!alreadyFocusedIsTextField) {
                                val inputNode = AutoFocusController.findFirstEditableNode(root)
                                if (inputNode != null) {
                                    try {
                                        // Some search boxes (Maps, Gmail) actually activate via
                                        // ACTION_CLICK (opening a full search overlay/activity),
                                        // ignoring ACTION_FOCUS entirely - so don't gate on its
                                        // return value, just fire both and wait for real input
                                        // focus to land before injecting the triggering key.
                                        inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                                        inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                        consumedAutofocusKeycode = kc
                                        // onAccessibilityEvent counts this down the instant the target field
                                        // actually receives input focus, so the common case wakes in a few ms
                                        // instead of waiting out a fixed poll interval. The 1s budget below is
                                        // only a safety net for apps where that never cleanly fires.
                                        val latch = CountDownLatch(1)
                                        focusLatch = latch
                                        worker.execute {
                                            val landedInTime = try {
                                                latch.await(1000, TimeUnit.MILLISECONDS)
                                            } catch (_: InterruptedException) {
                                                false
                                            }
                                            focusLatch = null
                                            // Must specifically be the editable field, not just any focus
                                            // holder - Gmail's search transition (a full overlay/activity,
                                            // unlike Maps' inline omnibox) briefly hands input focus to
                                            // intermediate widgets (e.g. the overlay's toolbar/back button)
                                            // before the real search box gets it, and injecting too early
                                            // against one of those drops the keystroke entirely.
                                            val hasInputFocus = landedInTime && (rootInActiveWindow?.let { r ->
                                                try {
                                                    val focused = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                                                    val isEditable = focused?.let { AutoFocusController.isEditableTextField(it) } ?: false
                                                    focused?.recycle()
                                                    isEditable
                                                } finally {
                                                    r.recycle()
                                                }
                                            } ?: false)
                                            if (hasInputFocus) Thread.sleep(150)
                                            // Re-injecting via "input keyevent" turned out unreliable
                                            // here: it reports shell-level success, but the dialer's
                                            // phone-number field's text never actually changes - the
                                            // synthetic event is silently dropped. Setting the text
                                            // directly through the accessibility API instead - the same
                                            // mechanism assistive typing tools are meant to use -
                                            // sidesteps IME/input-connection timing entirely.
                                            val target = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                                            if (target != null) {
                                                try {
                                                    val current = if (target.isShowingHintText) "" else (target.text?.toString() ?: "")
                                                    // In the dialer, the physical letter keys are meant to
                                                    // type their phone-keypad digit (F -> 6), not the raw
                                                    // letter the key produces - but only on the actual
                                                    // Dialpad number-entry field. isGoogleDialerForeground()
                                                    // alone can't tell the Dialpad tab apart from Contacts
                                                    // search / Favorites within the same app, which would
                                                    // otherwise turn contact-name searches into digits too.
                                                    val insertedChar = if (isGoogleDialerForeground() && isDialpadDigitsField(target)) {
                                                        keyCodeToDigit(kc)?.firstOrNull() ?: unicodeChar.toChar()
                                                    } else {
                                                        unicodeChar.toChar()
                                                    }
                                                    val args = Bundle().apply {
                                                        putCharSequence(
                                                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                                            current + insertedChar
                                                        )
                                                    }
                                                    target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                                                } finally {
                                                    target.recycle()
                                                }
                                            }
                                        }
                                        return true // Consume original press event
                                    } finally {
                                        inputNode.recycle()
                                    }
                                }
                            }
                        } finally {
                            root.recycle()
                        }
                    }
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                if (kc == consumedAutofocusKeycode) {
                    consumedAutofocusKeycode = -1
                    return true // Consume corresponding key release event
                }
            }
        }

        // Calculator Keys: routes digit/operator keys to a foreground calculator app.
        // Checks the foreground package itself, so it's safe to call for every key
        // and no-ops everywhere else.
        if (calculatorEnabled() && calculatorFix.onKeyEvent(this, event)) return true

        // PIN Input: map physical keys to the lockscreen PIN pad.
        if (!pinInputEnabled()) return false
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (!isDeviceLocked()) return false

        if (kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER) {
            return clickPinEnter()
        }
        if (kc == KeyEvent.KEYCODE_DEL || kc == KeyEvent.KEYCODE_FORWARD_DEL) {
            return clickPinDelete()
        }
        val digit = keyCodeToDigit(kc)
        if (digit != null) return clickPinButton(digit)
        return false
    }

    /** Consume the nav key; perform its action only on a double-tap. */
    private fun handleNavGesture(event: KeyEvent, kc: Int): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            val duration = event.eventTime - event.downTime
            val now = event.eventTime
            if (duration < LONG_PRESS_MS) { // ignore holds; count quick taps
                val last = lastNavTap[kc]
                if (last != null && (now - last) <= DOUBLE_TAP_MS) {
                    performNav(kc)
                    lastNavTap.remove(kc)
                } else {
                    lastNavTap[kc] = now // first tap; wait for the second
                }
            }
        }
        return true // always swallow the raw key so a single tap does nothing
    }

    private fun performNav(kc: Int) {
        val action = when (kc) {
            KeyEvent.KEYCODE_BACK -> GLOBAL_ACTION_BACK
            KeyEvent.KEYCODE_HOME -> GLOBAL_ACTION_HOME
            KeyEvent.KEYCODE_APP_SWITCH -> GLOBAL_ACTION_RECENTS
            else -> return
        }
        performGlobalAction(action)
    }

    private fun isDeviceLocked(): Boolean {
        val km = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isKeyguardLocked ?: false
    }

    private fun keyCodeToDigit(kc: Int): String? {
        if (kc >= KeyEvent.KEYCODE_0 && kc <= KeyEvent.KEYCODE_9) {
            return (kc - KeyEvent.KEYCODE_0).toString()
        }
        if (kc >= KeyEvent.KEYCODE_NUMPAD_0 && kc <= KeyEvent.KEYCODE_NUMPAD_9) {
            return (kc - KeyEvent.KEYCODE_NUMPAD_0).toString()
        }
        // BlackBerry physical keyboard: phone-dialpad layout mapped onto QWERTY.
        // W(1) E(2) R(3) / S(4) D(5) F(6) / Z(7) X(8) C(9) / Q(0)
        return when (kc) {
            KeyEvent.KEYCODE_Q -> "0"
            KeyEvent.KEYCODE_W -> "1"
            KeyEvent.KEYCODE_E -> "2"
            KeyEvent.KEYCODE_R -> "3"
            KeyEvent.KEYCODE_S -> "4"
            KeyEvent.KEYCODE_D -> "5"
            KeyEvent.KEYCODE_F -> "6"
            KeyEvent.KEYCODE_Z -> "7"
            KeyEvent.KEYCODE_X -> "8"
            KeyEvent.KEYCODE_C -> "9"
            else -> null
        }
    }

    private fun clickPinButton(digit: String): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val ids = arrayOf(
                "com.android.systemui:id/key$digit",
                "com.android.systemui:id/pin_key_$digit",
                "com.android.systemui:id/digit_$digit"
            )
            for (id in ids) if (clickById(root, id)) return true
            return findAndClick(root, digit)
        } finally {
            root.recycle()
        }
    }

    private fun clickPinDelete(): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val ids = arrayOf(
                "com.android.systemui:id/delete_button",
                "com.android.systemui:id/key_backspace",
                "com.android.systemui:id/pin_key_delete"
            )
            for (id in ids) if (clickById(root, id)) return true
            return findAndClickByDesc(root, arrayOf("delete", "backspace"))
        } finally {
            root.recycle()
        }
    }

    private fun clickPinEnter(): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val ids = arrayOf(
                "com.android.systemui:id/key_enter",
                "com.android.systemui:id/pin_key_enter",
                "com.android.systemui:id/check_button"
            )
            for (id in ids) if (clickById(root, id)) return true
            return findAndClickByDesc(root, arrayOf("enter", "confirm", "ok"))
        } finally {
            root.recycle()
        }
    }

    private fun clickById(root: AccessibilityNodeInfo, viewId: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        if (nodes.isNullOrEmpty()) return false
        for (node in nodes) {
            try {
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
            } finally {
                node.recycle()
            }
        }
        return false
    }

    private fun findAndClick(node: AccessibilityNodeInfo, digit: String): Boolean {
        if (node.isClickable) {
            val txt = node.text
            if (txt != null && txt.toString().trim() == digit) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findAndClick(child, digit)
            child.recycle()
            if (found) return true
        }
        return false
    }

    private fun findAndClickByDesc(node: AccessibilityNodeInfo, keywords: Array<String>): Boolean {
        if (node.isClickable) {
            val desc = node.contentDescription
            if (desc != null) {
                val s = desc.toString().lowercase(Locale.ROOT)
                for (kw in keywords) {
                    if (s.contains(kw)) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    }
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findAndClickByDesc(child, keywords)
            child.recycle()
            if (found) return true
        }
        return false
    }

    // ------------------------------------------------------------- Lifecycle

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        mainHandler.removeCallbacks(selfHealRunnable)
        selfHealRunning = false
        writeNodeBlocking(true) // never leave nav buttons dead
        restoreImeBlock()       // never leave the soft keyboard globally suppressed
        return super.onUnbind(intent)
    }

    /** Re-enable the soft keyboard if we'd suppressed it, run synchronously on teardown. */
    private fun restoreImeBlock() {
        if (!imeBlockApplied) return
        imeBlockApplied = false
        applyImeBlock(false)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(selfHealRunnable)
        selfHealRunning = false
        writeNodeBlocking(true)
        restoreImeBlock()
        prefs?.unregisterOnSharedPreferenceChangeListener(prefListener)
        screenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {}
        }
        batteryReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {}
        }
        worker.shutdown()
        super.onDestroy()
    }
}
