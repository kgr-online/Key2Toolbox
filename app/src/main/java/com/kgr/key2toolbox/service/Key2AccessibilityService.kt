package com.kgr.key2toolbox.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import com.kgr.key2toolbox.core.AssetInstaller
import com.kgr.key2toolbox.core.RootShell
import com.kgr.key2toolbox.modules.AutoFocusController
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
        /**
         * True while this service instance is actually connected and bound.
         * Ground truth for "is accessibility enabled" - unlike reading
         * ENABLED_ACCESSIBILITY_SERVICES back from Settings.Secure, this
         * can't be silently withheld by the system/ROM; it's set directly
         * by the lifecycle callbacks below.
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        const val PREFS = "key2tweaks"
        const val KEY_NAV_LOCK = "nav_lock_enabled"
        const val KEY_NAV_GESTURE = "nav_gesture_mode" // false=disable buttons, true=double-tap gate (Back)
        const val KEY_NAV_ALWAYS_OFF = "nav_always_off" // disable nav buttons permanently
        const val KEY_PIN_INPUT = "pin_input_enabled"
        const val KEY_IME_BLOCK = "ime_block_enabled"     // bypass IME in selected apps
        const val KEY_IME_BLOCK_APPS = "ime_block_apps"   // StringSet of package names
        const val KEY_IME_SAVED = "ime_block_saved_ime"   // IME to restore when leaving a blocked app

        // Our do-nothing IME: while it's active, physical key presses go straight
        // to the app instead of being intercepted/translated by the normal keyboard.
        const val PASSTHRU_IME = "com.kgr.key2toolbox/.service.Key2PassthroughIme"
        // Key2 stock keyboard - the default to fall back to if we have nothing saved.
        private const val DEFAULT_IME_FALLBACK =
            "com.blackberry.keyboard/com.blackberry.inputmethod.core.BlackBerryIME"

        private const val LONG_PRESS_MS = 350L
        private const val DOUBLE_TAP_MS = 300L

        // Auto-Focus: how long to wait for the field we just asked for to take input
        // focus before giving up on the injection, and how long to let its input
        // connection settle once it has.
        private const val AUTO_FOCUS_FOCUS_TIMEOUT_MS = 1000L
        private const val AUTO_FOCUS_SETTLE_MS = 150L
        // How long a "this window has no editable field" result is trusted before the
        // tree is walked again (content can appear without a window event).
        private const val NO_EDITABLE_CACHE_MS = 1500L

        private const val ALWAYS_OFF_SCRIPT = "nav_always_off.sh"
        private const val ALWAYS_OFF_TARGET = "/data/adb/service.d/$ALWAYS_OFF_SCRIPT"
    }

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    // Separate from [worker]: auto-focus injection sleeps up to a second waiting for
    // focus to land, and must not block the shared root-command queue.
    private val autoFocusWorker: ExecutorService = Executors.newSingleThreadExecutor()

    // --- Auto-Focus state -----------------------------------------------------
    // Keycodes whose ACTION_DOWN auto-focus consumed, so their matching ACTION_UP is
    // consumed too. A set because a whole burst of keys can be consumed while one
    // injection is in flight (see [autoFocusInjecting]).
    private val consumedAutofocusKeys = mutableSetOf<Int>()
    // Set right before a focus-and-type attempt starts waiting, so onAccessibilityEvent
    // can wake it the instant the target field actually receives input focus.
    @Volatile private var focusLatch: CountDownLatch? = null
    // True from the moment auto-focus consumes a key until its text has been written into
    // the field. Everything typed in that window is consumed and queued in
    // [pendingAutoFocusKeys] rather than left to the IME, whose keystrokes would otherwise
    // be overwritten by our ACTION_SET_TEXT taking a snapshot from before them.
    @Volatile private var autoFocusInjecting = false
    // (keycode, character typed) pairs in press order. Keycode is kept because the dialer's
    // number field wants the key's phone-keypad digit (F -> 6) instead of the raw letter,
    // and which applies is only known once the landed-in field is seen.
    private val pendingAutoFocusKeys = mutableListOf<Pair<Int, Char>>()
    private val autoFocusLock = Any()
    // Window id + time of the last tree walk that found no editable field, so a burst of
    // typing on a screen with nothing to focus doesn't re-walk the tree per keystroke.
    private var noEditableWindowId = -1
    private var noEditableAtMs = 0L

    @Volatile private var navDisabled = false // last state pushed to kernel
    @Volatile private var imeActive = false   // keyboard currently showing
    @Volatile private var imeBlockApplied = false // last show_ime value we pushed (true = suppressed)
    @Volatile private var foregroundPkg: String? = null // last seen foreground app package
    private val lastNavTap = HashMap<Int, Long>() // keycode -> last short-tap time
    private var prefs: SharedPreferences? = null
    private var screenReceiver: BroadcastReceiver? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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
        isRunning = true
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

    // ---------------------------------------------------------------- Nav Lock

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        imeActive = isImeVisible()
        reconcileNav()

        val pkg = foregroundAppPackage()
        if (pkg != null && pkg != foregroundPkg) {
            foregroundPkg = pkg
            reconcileImeBlock()
        }

        // Wake a pending auto-focus wait (see onKeyEvent) the moment the field it asked
        // for actually takes input focus, instead of waiting out the poll timeout.
        focusLatch?.let { latch ->
            val t = event?.eventType
            if (t == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                t == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED ||
                t == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            ) {
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

    // ---------------------------------------------------------------- Auto-Focus

    private fun isAutoFocusEnabledForForeground(): Boolean {
        val p = prefs ?: return false
        if (!AutoFocusController.isEnabled(p)) return false
        val pkg = foregroundPkg ?: return false
        return pkg in AutoFocusController.getSelectedApps(p)
    }

    /**
     * Whether [node] is a phone-number entry field (a dialpad's number line, or any
     * `android:inputType="phone"` field), where the physical letter keys are meant to
     * type their phone-keypad digit (F -> 6) rather than the raw letter. Keyed on
     * inputType rather than a dialer resource-id: it holds across Dialer versions/OEMs
     * and naturally excludes the same app's contact-*search* field (which is
     * TYPE_CLASS_TEXT), which is exactly the case the old id check existed to separate.
     */
    private fun isPhoneNumberField(node: AccessibilityNodeInfo): Boolean =
        (node.inputType and android.text.InputType.TYPE_MASK_CLASS) ==
            android.text.InputType.TYPE_CLASS_PHONE

    private fun recentlyFoundNoEditableField(root: AccessibilityNodeInfo): Boolean =
        root.windowId == noEditableWindowId &&
            (SystemClock.uptimeMillis() - noEditableAtMs) < NO_EDITABLE_CACHE_MS

    private fun rememberNoEditableField(root: AccessibilityNodeInfo) {
        noEditableWindowId = root.windowId
        noEditableAtMs = SystemClock.uptimeMillis()
    }

    /**
     * Waits for the field auto-focus just asked for to take input focus, then writes every
     * key consumed since (see [autoFocusInjecting]) into it in one go, draining any that
     * arrived while the write itself was in flight. Runs on [autoFocusWorker]; only the
     * list handoff is synchronized, never the accessibility calls.
     */
    private fun runAutoFocusInjection(latch: CountDownLatch) {
        try {
            val landedInTime = try {
                latch.await(AUTO_FOCUS_FOCUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                false
            }
            focusLatch = null
            // The field can report focus a beat before its input connection is live -
            // injecting into that gap drops the text. Only worth waiting when focus landed.
            if (landedInTime) Thread.sleep(AUTO_FOCUS_SETTLE_MS)

            while (true) {
                val batch = synchronized(autoFocusLock) {
                    val copy = pendingAutoFocusKeys.toList()
                    pendingAutoFocusKeys.clear()
                    if (copy.isEmpty()) autoFocusInjecting = false
                    copy
                }
                if (batch.isEmpty()) return
                if (!insertAutoFocusText(batch)) {
                    Log.d("Key2Toolbox", "autoFocus: no editable field took focus, dropped ${batch.size} key(s)")
                    return
                }
            }
        } catch (e: Exception) {
            Log.e("Key2Toolbox", "autoFocus injection failed", e)
        } finally {
            synchronized(autoFocusLock) {
                pendingAutoFocusKeys.clear()
                autoFocusInjecting = false
            }
        }
    }

    /**
     * Appends [batch] to whatever editable field currently holds input focus, writing
     * nothing (and returning false) if that isn't an editable text field. Uses
     * ACTION_SET_TEXT rather than re-injecting key events, which was found to be silently
     * dropped by some fields (notably the dialer's number field).
     */
    private fun insertAutoFocusText(batch: List<Pair<Int, Char>>): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } finally {
            root.recycle()
        }
        val target = focused ?: return false
        try {
            if (!AutoFocusController.isEditableTextField(target)) return false
            // In a phone-number field, physical letter keys type their phone-keypad digit
            // (F -> 6), matching what the dialer does natively for every key after this one.
            val asDialpad = isPhoneNumberField(target)
            val addition = buildString {
                for ((keycode, typed) in batch) {
                    append(if (asDialpad) (dialerDigitChar(keycode) ?: typed) else typed)
                }
            }
            val current = if (target.isShowingHintText) "" else (target.text?.toString() ?: "")
            val updated = current + addition
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
            }
            if (!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
            setCaretToEnd(target, updated.length)
            return true
        } finally {
            target.recycle()
        }
    }

    /**
     * Moves the caret after the text just written AND tells the IME about it - without this
     * the IME still believes the cursor is at position 0 on an empty field, so its next
     * keystroke inserts at the front and auto-capitalizes ("first letter after switching
     * apps comes out capitalized / doubled").
     */
    private fun setCaretToEnd(target: AccessibilityNodeInfo, end: Int) {
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, end)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        }
        target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    private fun dialerDigitChar(kc: Int): Char? = when (kc) {
        KeyEvent.KEYCODE_W -> '1'
        KeyEvent.KEYCODE_E -> '2'
        KeyEvent.KEYCODE_R -> '3'
        KeyEvent.KEYCODE_S -> '4'
        KeyEvent.KEYCODE_D -> '5'
        KeyEvent.KEYCODE_F -> '6'
        KeyEvent.KEYCODE_Z -> '7'
        KeyEvent.KEYCODE_X -> '8'
        KeyEvent.KEYCODE_C -> '9'
        KeyEvent.KEYCODE_0 -> '0'
        else -> null
    }

    // --------------------------------------------------------------- IME Block

    /**
     * The package of the focused/active TYPE_APPLICATION window - i.e. the app
     * behind any keyboard. Reading the application window (not the event source)
     * keeps this stable while the IME window comes and goes.
     */
    private fun foregroundAppPackage(): String? {
        val windowList: List<AccessibilityWindowInfo> = try {
            windows ?: return null
        } catch (_: Exception) {
            return null
        }
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

    /** Compute and apply the desired capacitive-button state from current settings. */
    private fun reconcileNav() {
        val desired = when {
            alwaysOff() -> true                          // permanently disabled
            !navLockEnabled() || gestureMode() -> false   // buttons stay live (gesture mode gates in onKeyEvent)
            else -> imeActive                             // disable-while-typing mode
        }
        Log.d("Key2Toolbox", "reconcileNav: desired=$desired, currentCache=$navDisabled, alwaysOff=${alwaysOff()}, imeActive=$imeActive")
        if (desired != navDisabled) applyNavDisabled(desired)
    }

    private fun forceReconcile() {
        worker.execute {
            val desired = when {
                alwaysOff() -> true                          // permanently disabled
                !navLockEnabled() || gestureMode() -> false   // buttons stay live (gesture mode gates in onKeyEvent)
                else -> imeActive                             // disable-while-typing mode
            }
            Log.d("Key2Toolbox", "forceReconcile: desired=$desired, alwaysOff=${alwaysOff()}, imeActive=$imeActive")
            // Always apply directly to the hardware to override any driver/kernel-level resets
            navDisabled = desired
            runRoot(if (desired) "0" else "1")
        }
    }

    private fun isImeVisible(): Boolean {
        val windowList: List<AccessibilityWindowInfo> = try {
            windows ?: return false
        } catch (_: Exception) {
            return false
        }
        return windowList.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
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

        // Nav gesture-gate (Back only): while typing, swallow a quick tap on Back
        // and fire it only on a double-tap. Home/Recents can't be gated - Android's
        // window policy acts on them regardless of accessibility consumption.
        if (navLockEnabled() && gestureMode() && imeActive &&
            kc == KeyEvent.KEYCODE_BACK && !isDeviceLocked()
        ) {
            return handleNavGesture(event, kc)
        }

        // Auto-Focus: on the first printable keypress in a selected app while nothing is
        // focused, find + focus the first text field and replay the key(s) into it. Gated
        // on live "is anything focused?" state, not a per-session flag, so it re-attempts
        // whenever focus is actually lost mid-session.
        if (isAutoFocusEnabledForForeground()) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val unicodeChar = event.unicodeChar
                if (unicodeChar > 0 && event.repeatCount == 0 && !event.isAltPressed && !event.isCtrlPressed) {
                    // An injection is already in flight: hand this key to it so keys 2..n land
                    // in the same ACTION_SET_TEXT, in order, rather than racing the IME.
                    val queued = synchronized(autoFocusLock) {
                        if (autoFocusInjecting) {
                            pendingAutoFocusKeys.add(kc to unicodeChar.toChar())
                            true
                        } else {
                            false
                        }
                    }
                    if (queued) {
                        consumedAutofocusKeys.add(kc)
                        return true
                    }

                    val root = rootInActiveWindow
                    if (root != null) {
                        try {
                            val alreadyFocused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                            val alreadyFocusedIsTextField =
                                alreadyFocused?.let { AutoFocusController.isEditableTextField(it) } ?: false
                            alreadyFocused?.recycle()
                            if (!alreadyFocusedIsTextField && !recentlyFoundNoEditableField(root)) {
                                val inputNode = AutoFocusController.findFirstEditableNode(root)
                                if (inputNode == null) {
                                    rememberNoEditableField(root)
                                } else {
                                    try {
                                        // Some search boxes (Maps, Gmail) activate via ACTION_CLICK
                                        // (a full search overlay) and ignore ACTION_FOCUS - fire both
                                        // and wait for real input focus before injecting.
                                        inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                                        inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                        consumedAutofocusKeys.add(kc)
                                        synchronized(autoFocusLock) {
                                            pendingAutoFocusKeys.clear()
                                            pendingAutoFocusKeys.add(kc to unicodeChar.toChar())
                                            autoFocusInjecting = true
                                        }
                                        val latch = CountDownLatch(1)
                                        focusLatch = latch
                                        autoFocusWorker.execute { runAutoFocusInjection(latch) }
                                        return true
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
                if (consumedAutofocusKeys.remove(kc)) {
                    return true // consume the matching release of a key we swallowed
                }
            }
        }

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
        isRunning = false
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
        isRunning = false
        writeNodeBlocking(true)
        restoreImeBlock()
        prefs?.unregisterOnSharedPreferenceChangeListener(prefListener)
        screenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {}
        }
        worker.shutdown()
        autoFocusWorker.shutdown()
        super.onDestroy()
    }
}
