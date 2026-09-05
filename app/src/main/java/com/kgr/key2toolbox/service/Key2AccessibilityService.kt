package com.kgr.key2toolbox.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.BatteryManager
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import com.kgr.key2toolbox.core.AssetInstaller
import com.kgr.key2toolbox.core.RootShell
import com.kgr.key2toolbox.inputfix.CalculatorInputFix
import com.kgr.key2toolbox.inputfix.ComposerEnterKeyHandler
import android.media.AudioManager
import com.kgr.key2toolbox.modules.AutoFocusController
import com.kgr.key2toolbox.modules.BatteryUsageController
import com.kgr.key2toolbox.modules.RecentsController
import com.kgr.key2toolbox.modules.SlimRecentsController
import com.kgr.key2toolbox.modules.ToolbeltController
import com.kgr.key2toolbox.modules.ToolbeltController.ToolbeltAction
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

        /**
         * The live service instance, so in-process code that needs a running
         * AccessibilityService's own Context can reach it - specifically
         * [com.kgr.key2toolbox.service.TickerOverlayController], whose
         * TYPE_ACCESSIBILITY_OVERLAY window can only be added via a WindowManager
         * obtained from such a Context, not a plain app Context.
         */
        @Volatile
        var instance: Key2AccessibilityService? = null
            private set

        /** Coalesce window-event bursts into one immersive-state `dumpsys` probe. */
        private const val FULLSCREEN_PROBE_DEBOUNCE_MS = 200L

        const val PREFS = "key2tweaks"
        const val KEY_NAV_LOCK = "nav_lock_enabled"
        const val KEY_NAV_GESTURE = "nav_gesture_mode" // false=disable buttons, true=double-tap gate (Back)
        const val KEY_NAV_ALWAYS_OFF = "nav_always_off" // disable nav buttons permanently
        const val KEY_PIN_INPUT = "pin_input_enabled"
        const val KEY_IME_BLOCK = "ime_block_enabled"     // bypass IME in selected apps
        const val KEY_IME_BLOCK_APPS = "ime_block_apps"   // StringSet of package names
        const val KEY_IME_SAVED = "ime_block_saved_ime"   // IME to restore when leaving a blocked app
        const val KEY_IME_SUGGESTIONS = "ime_suggestions_enabled" // Ctrl+W/E/R picks IME suggestion 1/2/3
        const val KEY_CHAT_COMPOSER = "chat_composer_enabled" // Enter -> send in chat apps
        const val KEY_CALCULATOR = "calculator_enabled"   // route digit/operator keys to a foreground calculator
        const val KEY_IN_CALL_SHORTCUTS = "in_call_shortcuts_enabled" // M=mute, Speed/$=speaker, letters=dialpad in-call

        // Our do-nothing IME: while it's active, physical key presses go straight
        // to the app instead of being intercepted/translated by the normal keyboard.
        const val PASSTHRU_IME = "com.kgr.key2toolbox/.service.Key2PassthroughIme"
        // Key2 stock keyboard - the default to fall back to if we have nothing saved.
        private const val DEFAULT_IME_FALLBACK =
            "com.blackberry.keyboard/com.blackberry.inputmethod.core.BlackBerryIME"

        // Every localized label Google Dialer uses for the three in-call action-bar buttons
        // (incall_label_speaker / _mute / _dialpad). Exact trimmed, lowercased match.
        private val SPEAKER_LABELS = setOf("altaveu", "altavoz", "altifalante", "alto-falante", "altofalante", "altoparlanti", "bocina", "bozgorailua", "difuzor", "dinamik", "garsiakalbis", "głośnik", "hangszóró", "haut-parleur", "hoparlör", "hátalari", "högtalare", "højttaler", "høyttaler", "isipikha", "kaiutin", "karnay", "kõlar", "lautsprecher", "loa", "luidspreker", "pmbsr suara", "reproduktor", "skaļrunis", "speaker", "spika", "vivavoce", "zvočnik", "zvučnik", "ηχείο", "високогов.", "динамик", "динамік", "дынамік", "звучник", "катуу сүйлөткүч", "чанга яригч", "բարձրախոս", "רמקול", "اسپیکر", "بلندگو", "مكبر الصوت", "स्पिकर", "स्पीकर", "स्‍पीकर", "স্পিকার", "স্পীকাৰ", "ਸਪੀਕਰ", "સ્પીકર", "ସ୍ପିକର୍‌", "ஸ்பீக்கர்", "స్పీకర్", "ಸ್ಪೀಕರ್‌", "സ്പീക്കർ", "ස්පීකරය", "ลำโพง", "ລຳໂພງ", "စပီကာ", "სპიკერი", "የድምጽ ማጉያ", "ឧបករណ៍​បំពង​សំឡេង", "スピーカー", "免提", "喇叭", "擴音", "스피커")
        private val MUTE_LABELS = setOf("bisukan", "couper le son", "couper micro", "demp", "dempen", "desakt. audioa", "desativ. som", "hiqi zërin", "hljóð af", "i-mute", "isklj. zvuk", "isključi zvuk", "izklopi zvok", "izslēgt", "kutt lyden", "ljud av", "mute", "mykistä", "nutildyti", "némítás", "ovozsiz", "redam", "sesi kapat", "silencia", "silenciar", "silenzia", "silențios", "sluk mikrofon", "stumm", "susdurun", "thulisa", "tắt tiếng", "vaigista", "vypnúť zvuk", "wycisz", "zima maikrofoni", "ztlumit", "σίγαση", "без звука", "выкл. гук", "дууг хаах", "дыбысын өшіру", "заглушаване", "исклучи звук", "искључи звук", "мікрофон", "үнүн өчүрүү", "անջատել", "השתקה", "خاموش کریں", "صامت کردن", "كتم", "म्युट गर्नुहोस्", "म्यूट करा", "म्यूट करें", "মিউট করুন", "মিউট কৰক", "ਮਿਊਟ ਕਰੋ", "મ્યૂટ કરો", "ମ୍ୟୁଟ୍ କର", "ஒலியடக்கு", "మ్యూట్", "ಮ್ಯೂಟ್‌", "മ്യൂട്ടുചെയ്യുക", "නිහඬ කරන්න", "ปิดเสียง", "ປີດສຽງ", "အသံပိတ်ရန်", "დადუმება", "ድምፀ-ከል አድርግ", "បិទ​សំឡេង", "ミュート", "静音", "靜音", "음소거")
        private val DIALPAD_LABELS = setOf("billentyűzet", "blloku i tasteve", "bàn phím", "cipartast.", "clavier", "ikhiphedi", "keypad", "klaviatura", "klaviatuur", "klaviatūra", "klawiatura", "klávesnice", "knappsats", "nommerblad", "näppäimistö", "pad kekunci", "talnaborð", "tastatur", "tastatura", "tastatură", "tastenfeld", "tastierino", "teclado", "teclat", "teklatua", "telefonska tastatura", "tipkovnica", "toetsenblok", "tuş takımı", "vitufe vya simu", "číselník", "πληκτρολόγιο", "клавиа­тура", "клавиатура", "клавіатура", "клавіятура", "ном. тергич", "пернетақта", "тастатура", "товчлуур", "թվաշար", "לוח חיוג", "صفحه کلید", "لوحة المفاتيح", "کی پیڈ", "किप्याड", "कीपॅड", "कीपैड", "কীপেড", "কীপ্যাড", "ਕੀਪੈਡ", "કીપેડ", "କୀ’ପେଡ", "கீபேட்", "కీప్యాడ్", "ಕೀಪ್ಯಾಡ್‌", "കീപാഡ്", "යතුරු පුවරුව", "ปุ่มกด", "ແປ້ນກົດ", "ခလုတ်ခုံ", "კლავიატურა", "ቁልፍ ሰሌዳ", "ផ្ទាំងចុចលេខ", "キーパッド", "拨号键盘", "撥號鍵盤", "키패드")

        private const val LONG_PRESS_MS = 350L
        private const val DOUBLE_TAP_MS = 300L

        /**
         * BB physical-keyboard suggestion strip renders as its own short
         * TYPE_INPUT_METHOD window - same window type as a real soft keyboard, just
         * much shorter. Anything under this height doesn't count as "the IME is up"
         * for Nav Lock / Toolbelt auto-hide, so the strip alone won't hide the belt
         * or gate nav buttons. Tune after checking actual on-device heights via
         * `adb logcat | grep isImeVisible`.
         */
        private const val MIN_IME_HEIGHT_DP = 100

        // Auto-Focus timing.
        private const val AUTO_FOCUS_FOCUS_TIMEOUT_MS = 1000L
        private const val AUTO_FOCUS_SETTLE_MS = 150L
        private const val NO_EDITABLE_CACHE_MS = 1500L

        private const val ALWAYS_OFF_SCRIPT = "nav_always_off.sh"
        private const val ALWAYS_OFF_TARGET = "/data/adb/service.d/$ALWAYS_OFF_SCRIPT"
    }

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val composerHandler = ComposerEnterKeyHandler(
        ComposerEnterKeyHandler.defaultSupportedPackages(),
        ComposerEnterKeyHandler.defaultSendButtonMatchers()
    )
    private val calculatorFix = CalculatorInputFix()
    private val autoFocusWorker: ExecutorService = Executors.newSingleThreadExecutor()

    // --- Auto-Focus state ---
    private val consumedAutofocusKeys = mutableSetOf<Int>()
    @Volatile private var focusLatch: CountDownLatch? = null
    @Volatile private var autoFocusInjecting = false
    private val pendingAutoFocusKeys = mutableListOf<Pair<Int, Char>>()
    private val autoFocusLock = Any()
    private var noEditableWindowId = -1
    private var noEditableAtMs = 0L

    private var batteryReceiver: BroadcastReceiver? = null
    @Volatile private var batteryThresholdArmed = false

    @Volatile private var navDisabled = false // last state pushed to kernel
    @Volatile private var imeActive = false   // keyboard currently showing
    @Volatile private var imeBlockApplied = false // last show_ime value we pushed (true = suppressed)
    @Volatile private var foregroundPkg: String? = null // last seen foreground app package
    @Volatile private var fullscreenCached = false       // last known immersive state of the foreground app
    @Volatile private var fullscreenProbeInFlight = false
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
        if (key.startsWith("toolbelt_")) {
            // Anything that changes the reserved bottom inset needs a launcher
            // restart - the taskbar only re-reads that on recreation on this build.
            if (key == ToolbeltController.KEY_ENABLED ||
                key == ToolbeltController.KEY_HEIGHT_DP ||
                key == ToolbeltController.KEY_COLLAPSIBLE ||
                key == ToolbeltController.KEY_COLLAPSED ||
                key == ToolbeltController.KEY_COLOR_MODE
            ) {
                val on = prefs?.getBoolean(ToolbeltController.KEY_ENABLED, false) ?: false
                worker.execute {
                    if (key == ToolbeltController.KEY_ENABLED) {
                        ToolbeltController.pushGlobalActive(on)
                        ToolbeltController.syncNavMode(this)
                    }
                    // Only bounce the launcher if the reserved inset actually moved.
                    if (ToolbeltController.pushInset(this)) ToolbeltController.restartLauncher()
                }
            }
            refreshToolbelt(rebuild = true)
        }
    }

    /** (Re)attach or detach the toolbelt overlay to match current settings. */
    private fun refreshToolbelt(rebuild: Boolean = false) {
        // Keep the belt off the lockscreen - its buttons would be dead there and
        // it would sit on top of the PIN pad.
        if (isDeviceLocked()) {
            ToolbeltOverlayController.hide()
            return
        }
        ToolbeltOverlayController.refresh(this, ::handleToolbeltAction, rebuild)
    }

    /** True while a cellular or VoIP call occupies the audio path. No permission needed. */
    private fun isInCall(): Boolean {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION
    }

    /**
     * Every "open Recents" trigger (Toolbelt slot, physical app-switch key)
     * routes through here. [RecentsController.getLayoutMode] and
     * [SlimRecentsController.listTasks] both run a root shell command, so this
     * always dispatches off [worker] - never call performGlobalAction's
     * stock-Overview branch or build the overlay on the calling thread.
     */
    private fun openRecents() {
        // Mode read is non-root (world-readable Global key) so it never adds
        // shell-spawn latency to this path.
        val mode = RecentsController.getLayoutMode(this)
        worker.execute {
            try {
                if (mode.isOverlay) {
                    val cards = mode == RecentsController.LayoutMode.MASONRY
                    val tasks = SlimRecentsController.listTasks(this)
                    // The foreground app has no fresh task snapshot (those are
                    // taken on background), so its tile would be black/stale.
                    // Grab a live screenshot for it - before the overlay's own
                    // window goes up, so the scrim isn't in the shot.
                    val liveTop = if (cards && tasks.isNotEmpty()) captureForRecents() else null
                    val topId = tasks.firstOrNull()?.taskId
                    mainHandler.post {
                        SlimRecentsOverlayController.show(this, tasks, cards)
                        if (liveTop != null && topId != null) {
                            SlimRecentsOverlayController.fillSnapshots(mapOf(topId to liveTop))
                        }
                        // Slim List's window just attached above the Toolbelt's
                        // in z-order (both are TYPE_ACCESSIBILITY_OVERLAY from
                        // this app; whichever attaches most recently wins).
                        // That leaves the belt visible but untouchable. There's
                        // no direct "bring to front" API for a window added via
                        // WindowManager - removing and re-adding is the only way
                        // to change stacking, so re-add it now to reclaim the
                        // top spot for its own bounds.
                        ToolbeltOverlayController.hide()
                        ToolbeltOverlayController.refresh(this, ::handleToolbeltAction)
                    }
                    // Masonry: window is already up with placeholders; load the
                    // file snapshots for the rest and stream them in. The top
                    // tile keeps its live screenshot when we got one.
                    if (cards && tasks.isNotEmpty()) {
                        val ids = if (liveTop != null) tasks.drop(1).map { it.taskId }
                        else tasks.map { it.taskId }
                        val snaps = SlimRecentsController.loadSnapshots(ids)
                        mainHandler.post { SlimRecentsOverlayController.fillSnapshots(snaps) }
                    }
                } else {
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                }
            } catch (t: Throwable) {
                Log.e("Key2Toolbox", "openRecents failed", t)
            }
        }
    }

    /**
     * A live screenshot of the current screen for the Masonry "hero" tile - the
     * foreground app has no fresh stored snapshot. Trims the status bar and the
     * toolbelt strip. Blocking (root screencap) - call off the main thread and
     * before the overlay attaches. `null` on failure -> caller falls back to the
     * stored snapshot.
     */
    private fun captureForRecents(): Bitmap? {
        val sbId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val top = if (sbId > 0) resources.getDimensionPixelSize(sbId) else 0
        val belt = prefs?.let {
            (ToolbeltController.reservedDp(it) * resources.displayMetrics.density).toInt()
        } ?: 0
        return SlimRecentsController.captureScreen(top, belt)
    }

    private fun handleToolbeltAction(action: ToolbeltAction, arg: String?) {
        // Any toolbelt press should close Slim List first - none of the
        // actions below know or care that it might be open, and leaving it up
        // (e.g. after Home backgrounds whatever was behind it) strands it on
        // top of the next screen with no way to see what's underneath.
        // RECENTS is the one exception: openRecents() -> show() already
        // refreshes an already-open Slim List in place via rebuildRows()
        // rather than a full close+reopen, so pre-closing it here would just
        // add an unnecessary flicker for that specific action.
        if (action != ToolbeltAction.RECENTS && SlimRecentsOverlayController.isShowing()) {
            SlimRecentsOverlayController.hide()
        }
        when (action) {
            ToolbeltAction.NONE, ToolbeltAction.TOGGLE_BELT -> {} // handled in the overlay
            ToolbeltAction.LAUNCH_APP -> launchApp(arg)
            ToolbeltAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            ToolbeltAction.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            ToolbeltAction.RECENTS -> openRecents()
            ToolbeltAction.NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            ToolbeltAction.QUICK_SETTINGS -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            ToolbeltAction.POWER_DIALOG -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            ToolbeltAction.LOCK_SCREEN -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            ToolbeltAction.SPLIT_SCREEN -> performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            ToolbeltAction.SCREENSHOT ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                    performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                else worker.execute { RootShell.run("input keyevent 120") }
            ToolbeltAction.VOICE_ASSIST -> launchVoiceAssist()
            ToolbeltAction.OPEN_MENU -> worker.execute { RootShell.run("input keyevent 82") }
            ToolbeltAction.SEARCH -> worker.execute { RootShell.run("input keyevent 84") }
            ToolbeltAction.DIALER_KEYPAD -> {
                try {
                    startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                }
            }
            ToolbeltAction.DIALER_HOME -> {
                try {
                    val telecom = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                    val dialerPkg = telecom?.defaultDialerPackage
                    val launchIntent = dialerPkg?.let { packageManager.getLaunchIntentForPackage(it) }
                    if (launchIntent != null) {
                        startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } else {
                        // Fallback if we can't resolve a default dialer for some reason -
                        // ACTION_DIAL always opens the keypad tab, but it's better than nothing.
                        startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                } catch (_: Exception) {
                }
            }
            ToolbeltAction.LAST_APP -> {
                // Open Overview, then trigger it again: AOSP returns to the
                // previously focused task, i.e. "switch to last app".
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                mainHandler.postDelayed({ performGlobalAction(GLOBAL_ACTION_RECENTS) }, 350)
            }
            ToolbeltAction.HANGUP -> worker.execute { RootShell.run("input keyevent 6") }
            ToolbeltAction.HANGUP_OR_HOME ->
                if (isInCall()) worker.execute { RootShell.run("input keyevent 6") }
                else performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun launchApp(pkg: String?) {
        val target = pkg?.takeIf { it.isNotBlank() } ?: return
        val intent = packageManager.getLaunchIntentForPackage(target)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return
        try {
            startActivity(intent)
        } catch (_: Exception) {
            // app gone / not launchable
        }
    }

    private fun launchVoiceAssist() {
        val intent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                // no assistant installed
            }
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
        instance = this
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

            // Toolbelt: mirror the enabled state + live nav mode for the SystemUI
            // hook, then bring the belt up if it's on.
            val toolbeltOn = prefs?.getBoolean(ToolbeltController.KEY_ENABLED, false) ?: false
            ToolbeltController.pushGlobalActive(toolbeltOn)
            ToolbeltController.syncNavMode(this)
            ToolbeltController.pushInset(this)
            mainHandler.post { refreshToolbelt(rebuild = true) }
        }

        val rx = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d("Key2Toolbox", "Screen state changed: ${intent?.action}")
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    // A TYPE_ACCESSIBILITY_OVERLAY window can render above the
                    // keyguard and doesn't tear itself down just because the
                    // screen locked - close it immediately so it can never be
                    // sitting in front of the lock screen on wake.
                    SlimRecentsOverlayController.hide()
                    return
                }
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
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        try {
            ContextCompat.registerReceiver(this, rx, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            screenReceiver = rx
            Log.d("Key2Toolbox", "Successfully registered screenReceiver")
        } catch (e: Exception) {
            Log.e("Key2Toolbox", "Failed to register screenReceiver", e)
        }

        // Battery Usage: auto-reset stats once the level crosses the threshold while charging,
        // a stand-in for BATTERY_STATUS_FULL which this device's charging driver never reports.
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
    private fun imeSuggestionsEnabled() = prefs?.getBoolean(KEY_IME_SUGGESTIONS, false) ?: false

    /**
     * Clicks the Nth word in the IME window's candidate strip. On the BlackBerry
     * Keyboard (5.x) each word is a non-clickable TextView wrapped in a clickable
     * FrameLayout, laid out in a horizontal RecyclerView. Match "clickable node
     * whose subtree holds exactly one TextView with non-blank text", ordered
     * left-to-right by screen position - that picks only the word slots and skips
     * the strip's quick-modes ImageButton and the back / switch-IME ImageViews
     * (none of which carry text). If a keyboard doesn't use this shape, nothing
     * matches and the key falls through unconsumed.
     */
    private fun clickImeSuggestion(index: Int): Boolean {
        val imeRoot = windows?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.root
            ?: return false
        try {
            val slots = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
            collectSuggestionSlots(imeRoot, slots)
            val ordered = slots.sortedBy { it.first }.map { it.second }
            try {
                if (ordered.size <= index) return false
                return ordered[index].performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } finally {
                ordered.forEach { it.recycle() }
            }
        } finally {
            imeRoot.recycle()
        }
    }

    private fun collectSuggestionSlots(node: AccessibilityNodeInfo, out: MutableList<Pair<Int, AccessibilityNodeInfo>>) {
        if (node.isClickable && !singleTextViewText(node).isNullOrBlank()) {
            val b = Rect().also { node.getBoundsInScreen(it) }
            out.add(b.left to AccessibilityNodeInfo.obtain(node))
            return // a matched slot is a leaf for our purposes; don't descend into it
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectSuggestionSlots(child, out)
            child.recycle()
        }
    }

    /** Text of the single TextView in [node]'s subtree, or null if there are zero or several. */
    private fun singleTextViewText(node: AccessibilityNodeInfo): String? {
        var text: String? = null
        var count = 0
        fun rec(n: AccessibilityNodeInfo) {
            if (n.className?.contains("TextView") == true) {
                count++
                text = n.text?.toString()
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                rec(c)
                c.recycle()
            }
        }
        rec(node)
        return if (count == 1) text else null
    }
    private fun chatComposerEnabled() = prefs?.getBoolean(KEY_CHAT_COMPOSER, false) ?: false
    private fun calculatorEnabled() = prefs?.getBoolean(KEY_CALCULATOR, false) ?: false
    private fun inCallShortcutsEnabled() = prefs?.getBoolean(KEY_IN_CALL_SHORTCUTS, false) ?: false

    private fun isGoogleDialerForeground(): Boolean {
        val pkg = foregroundPkg ?: return false
        return pkg == "com.google.android.dialer" || pkg == "com.google.android.apps.dialer"
    }

    private fun getDialerKeycode(kc: Int): Int? = when (kc) {
        KeyEvent.KEYCODE_W -> KeyEvent.KEYCODE_1
        KeyEvent.KEYCODE_E -> KeyEvent.KEYCODE_2
        KeyEvent.KEYCODE_R -> KeyEvent.KEYCODE_3
        KeyEvent.KEYCODE_S -> KeyEvent.KEYCODE_4
        KeyEvent.KEYCODE_D -> KeyEvent.KEYCODE_5
        KeyEvent.KEYCODE_F -> KeyEvent.KEYCODE_6
        KeyEvent.KEYCODE_Z -> KeyEvent.KEYCODE_7
        KeyEvent.KEYCODE_X -> KeyEvent.KEYCODE_8
        KeyEvent.KEYCODE_C -> KeyEvent.KEYCODE_9
        KeyEvent.KEYCODE_0 -> KeyEvent.KEYCODE_0
        else -> null
    }

    private fun dialerDigitChar(kc: Int): Char? = when (getDialerKeycode(kc)) {
        KeyEvent.KEYCODE_0 -> '0'; KeyEvent.KEYCODE_1 -> '1'; KeyEvent.KEYCODE_2 -> '2'
        KeyEvent.KEYCODE_3 -> '3'; KeyEvent.KEYCODE_4 -> '4'; KeyEvent.KEYCODE_5 -> '5'
        KeyEvent.KEYCODE_6 -> '6'; KeyEvent.KEYCODE_7 -> '7'; KeyEvent.KEYCODE_8 -> '8'
        KeyEvent.KEYCODE_9 -> '9'; else -> null
    }

    private fun findCheckables(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        if (node.isCheckable) list.add(AccessibilityNodeInfo.obtain(node))
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findCheckables(child, list)
            child.recycle()
        }
    }

    private fun findCheckableByLabel(
        checkables: List<AccessibilityNodeInfo>,
        labels: Set<String>
    ): AccessibilityNodeInfo? = checkables.firstOrNull { nodeSubtreeContainsLabel(it, labels) }

    private fun nodeSubtreeContainsLabel(node: AccessibilityNodeInfo, labels: Set<String>): Boolean {
        if (node.contentDescription?.toString()?.trim()?.lowercase() in labels ||
            node.text?.toString()?.trim()?.lowercase() in labels
        ) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (nodeSubtreeContainsLabel(child, labels)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }

    private fun findDialerDigitsField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in arrayOf("com.google.android.dialer:id/digits", "com.google.android.apps.dialer:id/digits")) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes != null && nodes.isNotEmpty()) {
                for (i in 1 until nodes.size) nodes[i].recycle()
                return nodes[0]
            }
        }
        return null
    }

    private fun insertDialerDigit(target: AccessibilityNodeInfo, kc: Int) {
        try {
            val digit = dialerDigitChar(kc) ?: return
            val current = if (target.isShowingHintText) "" else (target.text?.toString() ?: "")
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current + digit)
            }
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally {
            target.recycle()
        }
    }

    private fun autoOpenDialpad() {
        val root = rootInActiveWindow ?: return
        try {
            val checkables = mutableListOf<AccessibilityNodeInfo>()
            findCheckables(root, checkables)
            try {
                if (checkables.size >= 3) {
                    val keypadNode = findCheckableByLabel(checkables, DIALPAD_LABELS)
                    if (keypadNode != null && !keypadNode.isChecked) {
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

    // --- Auto-Focus ---

    private fun isAutoFocusEnabledForForeground(): Boolean {
        val p = prefs ?: return false
        if (!AutoFocusController.isEnabled(p)) return false
        val pkg = foregroundPkg ?: return false
        return pkg in AutoFocusController.getSelectedApps(p)
    }

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

    private fun runAutoFocusInjection(latch: CountDownLatch) {
        try {
            val landedInTime = try {
                latch.await(AUTO_FOCUS_FOCUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                false
            }
            focusLatch = null
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

    private fun setCaretToEnd(target: AccessibilityNodeInfo, end: Int) {
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, end)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        }
        target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    private fun imeBlockEnabled() = prefs?.getBoolean(KEY_IME_BLOCK, false) ?: false
    private fun imeBlockApps(): Set<String> =
        prefs?.getStringSet(KEY_IME_BLOCK_APPS, emptySet()) ?: emptySet()

    // ---------------------------------------------------------------- Nav Lock

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        imeActive = isImeVisible()
        reconcileNav()

        val pkg = foregroundAppPackage()
        val pkgChanged = pkg != null && pkg != foregroundPkg
        if (pkgChanged) {
            foregroundPkg = pkg
            reconcileImeBlock()
        }

        // Toolbelt: keep the belt attached; slide it away while the soft keyboard
        // is up or the foreground app is fullscreen/immersive. The immersive
        // check is async + cached (see [scheduleFullscreenProbe]); every event
        // just re-applies the last known value. `anyImeWindow` also catches the
        // short physical-keyboard toolbar strip - the belt hides for that too in
        // translucent mode, where it would otherwise show through.
        ToolbeltOverlayController.setImeVisible(imeActive, anyImeWindow())
        ToolbeltOverlayController.setForegroundFullscreen(fullscreenCached)
        refreshToolbelt()
        scheduleFullscreenProbe(event, pkgChanged)

        // In-Call Shortcuts: open the dialpad tab the moment the in-call screen appears.
        if (inCallShortcutsEnabled() && isGoogleDialerForeground() &&
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            autoOpenDialpad()
        }

        // Auto-Focus: wake a pending focus wait the moment the target field takes input focus.
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

    private val fullscreenProbeRunnable = Runnable { runFullscreenProbe() }

    /**
     * Ask for a fresh immersive-state probe, but only on window-shaped events
     * (app switch, windows changed, window state changed) and coalesced through
     * a short delay so a burst of events triggers one `dumpsys` at most.
     */
    private fun scheduleFullscreenProbe(event: AccessibilityEvent?, pkgChanged: Boolean) {
        val t = event?.eventType
        val windowish = pkgChanged ||
            t == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (!windowish) return
        mainHandler.removeCallbacks(fullscreenProbeRunnable)
        mainHandler.postDelayed(fullscreenProbeRunnable, FULLSCREEN_PROBE_DEBOUNCE_MS)
    }

    private fun runFullscreenProbe() {
        if (fullscreenProbeInFlight) return
        fullscreenProbeInFlight = true
        val fallback = isForegroundFullscreenByStrip()
        worker.execute {
            val value = try { probeImmersiveViaDump() } catch (_: Exception) { null } ?: fallback
            fullscreenProbeInFlight = false
            if (value != fullscreenCached) {
                fullscreenCached = value
                mainHandler.post {
                    ToolbeltOverlayController.setForegroundFullscreen(value)
                    refreshToolbelt()
                }
            }
        }
    }

    /**
     * Whether the focused app window is *requesting* an immersive
     * (status-bar-hidden) layout, read from `dumpsys window`. `null` = the
     * focused window couldn't be resolved, caller keeps the last value.
     *
     * This keys off the app's requested inset visibility, not whether a bar is
     * on screen right now, so a transient status-bar reveal - the privacy chip
     * flash on a location/mic/camera hit, or a deliberate swipe-to-peek - does
     * not read as "left fullscreen" and does not bounce the belt back in. The
     * belt only reappears when the foreground app itself drops the immersive
     * request (e.g. a video player going from fullscreen to inline).
     */
    private fun probeImmersiveViaDump(): Boolean? {
        val out = RootShell.run("dumpsys window windows").outString
        if (out.isBlank()) return null
        val hash = Regex("""mCurrentFocus=Window\{(\w+)""").find(out)?.groupValues?.get(1) ?: return null
        val start = out.indexOf("Window{$hash")
        if (start < 0) return null
        val tail = out.substring(start)
        val end = tail.indexOf("\n  Window #").let { if (it < 0) minOf(tail.length, 6000) else it }
        val block = tail.substring(0, end)
        return Regex("""Requested non-default-visibility types:[^\n]*\bstatusBars\b""").containsMatchIn(block) ||
            Regex("""vsysui=[^\n]*(FULLSCREEN|IMMERSIVE)""").containsMatchIn(block) ||
            Regex("""\bfl=[^\n]*\bFULLSCREEN\b""").containsMatchIn(block)
    }

    /**
     * Fallback immersive check: the foreground app is fullscreen when the system
     * status-bar strip is absent from the accessibility window list. Cheap and
     * root-free, but fooled by a transient bar reveal - hence it only backs up
     * [probeImmersiveViaDump] when the `dumpsys` parse fails.
     */
    private fun isForegroundFullscreenByStrip(): Boolean {
        val list = try { windows ?: return false } catch (_: Exception) { return false }
        if (list.none { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }) return false
        val strip = (32 * resources.displayMetrics.density).toInt()
        val hasStatusBar = list.any { w ->
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) return@any false
            val b = Rect().also { w.getBoundsInScreen(it) }
            b.top <= 0 && b.height() in 1..strip
        }
        return !hasStatusBar
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

    /** Any TYPE_INPUT_METHOD window at all, regardless of height. */
    private fun anyImeWindow(): Boolean = try {
        windows?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: false
    } catch (_: Exception) {
        false
    }

    private fun isImeVisible(): Boolean {
        val windowList: List<AccessibilityWindowInfo> = try {
            windows ?: return false
        } catch (_: Exception) {
            return false
        }
        val imeWindows = windowList.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        if (imeWindows.isEmpty()) return false

        val minPx = (MIN_IME_HEIGHT_DP * resources.displayMetrics.density).toInt()
        val tallest = imeWindows.maxOf { w ->
            Rect().also { w.getBoundsInScreen(it) }.height()
        }
        Log.d("Key2Toolbox", "isImeVisible: tallest IME window height=${tallest}px, threshold=${minPx}px")
        return tallest >= minPx
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

        // Slim List escape hatch: while the overlay is showing, Back/Home/
        // app-switch always close it, unconditionally, before any other
        // feature's gating below gets a look at the key. This has to be first
        // and unconditional - the overlay is FLAG_NOT_FOCUSABLE so it can't
        // otherwise receive keys, and every other consumer of these keycodes
        // is gated behind its own settings (Nav Lock's gesture mode, etc.)
        // that may not be active, which would otherwise leave the overlay
        // with no way to dismiss at all.
        if (SlimRecentsOverlayController.isShowing()) {
            when (kc) {
                KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_DOWN) SlimRecentsOverlayController.hide()
                    return true
                }
                KeyEvent.KEYCODE_HOME -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        SlimRecentsOverlayController.hide()
                        performGlobalAction(GLOBAL_ACTION_HOME)
                    }
                    return true
                }
                KeyEvent.KEYCODE_APP_SWITCH -> {
                    if (event.action == KeyEvent.ACTION_DOWN) openRecents() // re-show with a fresh task list
                    return true
                }
            }
        }

        // IME suggestion shortcuts: Ctrl+W/E/R picks suggestion 1/2/3 from the keyboard's
        // candidate strip. Only consumes the key if a suggestion was actually found and
        // clicked, so Ctrl+W/E/R still behaves normally (e.g. closing a browser tab) when
        // no suggestions are showing.
        if (imeSuggestionsEnabled() && event.isCtrlPressed &&
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        ) {
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

        // In-Call Shortcuts: on the Google Dialer in-call screen, Speed/$ -> Speaker,
        // M -> Mute, letter keys -> dialpad digits. Runs before Auto-Focus below.
        if (inCallShortcutsEnabled() && isGoogleDialerForeground()) {
            val root = rootInActiveWindow
            if (root != null) {
                try {
                    val checkables = mutableListOf<AccessibilityNodeInfo>()
                    findCheckables(root, checkables)
                    try {
                        if (checkables.size >= 3) {
                            val isSpeakerKey = kc == KeyEvent.KEYCODE_CTRL_LEFT || kc == KeyEvent.KEYCODE_4
                            val isMuteKey = kc == KeyEvent.KEYCODE_M
                            if (isSpeakerKey) {
                                if (event.action == KeyEvent.ACTION_UP) {
                                    findCheckableByLabel(checkables, SPEAKER_LABELS)
                                        ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                }
                                return true
                            }
                            if (isMuteKey) {
                                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                                    findCheckableByLabel(checkables, MUTE_LABELS)
                                        ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                }
                                return true
                            }
                            if (getDialerKeycode(kc) != null) {
                                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                                    val keypadNode = findCheckableByLabel(checkables, DIALPAD_LABELS)
                                    when {
                                        keypadNode == null -> {}
                                        keypadNode.isChecked ->
                                            findDialerDigitsField(root)?.let { insertDialerDigit(it, kc) }
                                        else -> {
                                            keypadNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                            worker.execute {
                                                var digits: AccessibilityNodeInfo? = null
                                                for (attempt in 1..10) {
                                                    Thread.sleep(50)
                                                    digits = rootInActiveWindow?.let { r ->
                                                        try {
                                                            findDialerDigitsField(r)
                                                        } finally {
                                                            r.recycle()
                                                        }
                                                    }
                                                    if (digits != null) break
                                                }
                                                digits?.let { insertDialerDigit(it, kc) }
                                            }
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

        // Auto-Focus: on the first printable keypress in a selected app while nothing is
        // focused, find + focus the first text field and replay the key(s) into it.
        if (isAutoFocusEnabledForForeground()) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val unicodeChar = event.unicodeChar
                if (unicodeChar > 0 && event.repeatCount == 0 && !event.isAltPressed && !event.isCtrlPressed) {
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
                    return true
                }
            }
        }

        // Chat Enter-to-Send: in a supported chat app, a plain Enter clicks the send
        // button instead of inserting a newline (Alt/Shift+Enter still newlines).
        // Pre-gated on the tracked foreground package so it costs nothing elsewhere.
        if (chatComposerEnabled() && composerHandler.supportsPackage(foregroundPkg) &&
            composerHandler.onKeyEvent(this, event)
        ) {
            return true
        }

        // Calculator Keys: route digit/operator keys to a foreground calculator app.
        if (calculatorEnabled() && CalculatorInputFix.isCalculatorPackage(foregroundPkg) &&
            calculatorFix.onKeyEvent(this, event)
        ) return true

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
        teardownToolbelt()      // never leave the real nav bar hidden with no belt to replace it
        return super.onUnbind(intent)
    }

    /**
     * Drop the belt and tell the SystemUI hook to restore the real navigation
     * bar: with no accessibility service there is no overlay to stand in for it.
     * onServiceConnected pushes the flag back if the module is still enabled.
     */
    private fun teardownToolbelt() {
        ToolbeltOverlayController.hide()
        try {
            RootShell.run("settings put global ${ToolbeltController.GLOBAL_ACTIVE} 0")
        } catch (_: Exception) {}
    }

    /** Re-enable the soft keyboard if we'd suppressed it, run synchronously on teardown. */
    private fun restoreImeBlock() {
        if (!imeBlockApplied) return
        imeBlockApplied = false
        applyImeBlock(false)
    }

    override fun onDestroy() {
        isRunning = false
        instance = null
        writeNodeBlocking(true)
        restoreImeBlock()
        teardownToolbelt()
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
        autoFocusWorker.shutdown()
        super.onDestroy()
    }
}
