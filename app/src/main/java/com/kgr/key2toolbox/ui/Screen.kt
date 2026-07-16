package com.kgr.key2toolbox.ui

import androidx.annotation.StringRes
import com.kgr.key2toolbox.R

enum class AccessType(@StringRes val labelRes: Int) {
    ROOT(R.string.access_root),
    ACCESSIBILITY(R.string.access_accessibility),
    NOTIFICATION(R.string.access_notification)
}

sealed class Screen(
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int = 0,
    val access: List<AccessType> = emptyList()
) {
    data object Home : Screen(R.string.app_name)
    data object BatteryUsage : Screen(R.string.title_battery_usage, access = listOf(AccessType.ROOT))

    // Keyboard tab
    data object KeyRemap : Screen(
        R.string.title_key_remap, R.string.subtitle_key_remap,
        listOf(AccessType.ROOT)
    )
    data object KbdLight : Screen(
        R.string.title_kbd_light, R.string.subtitle_kbd_light,
        listOf(AccessType.ROOT)
    )
    data object NavLock : Screen(
        R.string.title_nav_lock, R.string.subtitle_nav_lock,
        listOf(AccessType.ACCESSIBILITY)
    )
    data object PinKeyboard : Screen(
        R.string.title_pin_keyboard, R.string.subtitle_pin_keyboard,
        listOf(AccessType.ACCESSIBILITY)
    )
    data object ImeBlock : Screen(
        R.string.title_ime_block, R.string.subtitle_ime_block,
        listOf(AccessType.ACCESSIBILITY)
    )
    data object Calculator : Screen(
        R.string.title_calculator, R.string.subtitle_calculator,
        listOf(AccessType.ACCESSIBILITY)
    )
    data object ImeSuggestions : Screen(
        R.string.title_ime_suggestions, R.string.subtitle_ime_suggestions,
        listOf(AccessType.ACCESSIBILITY)
    )

    // System tab
    data object InCallShortcuts : Screen(
        R.string.title_in_call_shortcuts, R.string.subtitle_in_call_shortcuts,
        listOf(AccessType.ACCESSIBILITY)
    )
    data object AutoFocus : Screen(
        R.string.title_auto_focus, R.string.subtitle_auto_focus,
        listOf(AccessType.ACCESSIBILITY)
    )
    data object Zram : Screen(
        R.string.title_zram, R.string.subtitle_zram,
        listOf(AccessType.ROOT)
    )
    data object Performance : Screen(
        R.string.title_performance, R.string.subtitle_performance,
        listOf(AccessType.ROOT)
    )
    data object Dt2w : Screen(
        R.string.title_dt2w, R.string.subtitle_dt2w,
        listOf(AccessType.ROOT)
    )
    data object PlayStoreTagger : Screen(
        R.string.title_play_store_tagger, R.string.subtitle_play_store_tagger,
        listOf(AccessType.ROOT)
    )
    data object K2PF : Screen(
        R.string.title_k2pf, R.string.subtitle_k2pf,
        listOf(AccessType.ROOT)
    )
    data object LedNotify : Screen(
        R.string.title_led_notify, R.string.subtitle_led_notify,
        listOf(AccessType.ROOT, AccessType.NOTIFICATION)
    )
    data object ExtraDim : Screen(
        R.string.title_extra_dim, R.string.subtitle_extra_dim,
        listOf(AccessType.ROOT)
    )

    // Network tab
    data object Telemetry : Screen(
        R.string.title_telemetry, R.string.subtitle_telemetry,
        listOf(AccessType.ROOT)
    )
    data object WirelessAdb : Screen(
        R.string.title_wireless_adb, R.string.subtitle_wireless_adb,
        listOf(AccessType.ROOT)
    )
    data object Wifi5g : Screen(
        R.string.title_wifi5g, R.string.subtitle_wifi5g,
        listOf(AccessType.ROOT)
    )
    data object Watch : Screen(
        R.string.title_watch, R.string.subtitle_watch,
        listOf(AccessType.ROOT)
    )
    data object BtIdle : Screen(
        R.string.title_bt_idle, R.string.subtitle_bt_idle,
        listOf(AccessType.ROOT)
    )
}

/** Bottom-bar sections. */
enum class AppTab(@StringRes val labelRes: Int) {
    Info(R.string.tab_info),
    Keyboard(R.string.tab_keyboard),
    System(R.string.tab_system),
    Network(R.string.tab_network),
    Settings(R.string.tab_settings),
}

/** Screens listed under the Keyboard tab. */
val keyboardScreens = listOf(
    Screen.KeyRemap,
    Screen.KbdLight,
    Screen.NavLock,
    Screen.PinKeyboard,
    Screen.ImeBlock,
    Screen.Calculator,
    Screen.ImeSuggestions,
)

/** Screens listed under the System tab. */
val systemScreens = listOf(
    Screen.Zram,
    Screen.Performance,
    Screen.Dt2w,
    Screen.PlayStoreTagger,
    Screen.K2PF,
    Screen.LedNotify,
    Screen.ExtraDim,
    Screen.InCallShortcuts,
    Screen.AutoFocus,
)

/** Screens listed under the Network tab. */
val networkScreens = listOf(
    Screen.Telemetry,
    Screen.WirelessAdb,
    Screen.Wifi5g,
    Screen.Watch,
    Screen.BtIdle,
)
