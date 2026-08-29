package com.kgr.key2toolbox.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kgr.key2toolbox.R

enum class AccessType(@StringRes val labelRes: Int) {
    ROOT(R.string.access_root),
    ACCESSIBILITY(R.string.access_accessibility),
    NOTIFICATION(R.string.access_notification);

    val label: String
        @Composable get() = stringResource(labelRes)
}

sealed class Screen(
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int? = null,
    val access: List<AccessType> = emptyList()
) {
    val title: String
        @Composable get() = stringResource(titleRes)

    val subtitle: String
        @Composable get() = subtitleRes?.let { stringResource(it) } ?: ""

    data object Home : Screen(R.string.app_name)

    // Keyboard tab
    data object CtrlKey : Screen(
        R.string.title_ctrl_key, R.string.subtitle_ctrl_key,
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
    data object ChatComposer : Screen(
        R.string.title_chat_composer, R.string.subtitle_chat_composer,
        listOf(AccessType.ACCESSIBILITY)
    )
    data object InCallShortcuts : Screen(
        R.string.title_in_call_shortcuts, R.string.subtitle_in_call_shortcuts,
        listOf(AccessType.ACCESSIBILITY)
    )
    data object AutoFocus : Screen(
        R.string.title_auto_focus, R.string.subtitle_auto_focus,
        listOf(AccessType.ACCESSIBILITY)
    )

    // Display / System / Network tabs (see HomeScreen for the split)
    data object AdBlock : Screen(
        R.string.title_adblock, R.string.subtitle_adblock,
        listOf(AccessType.ROOT)
    )
    data object DenylistManager : Screen(
        R.string.title_denylist, R.string.subtitle_denylist,
        listOf(AccessType.ROOT)
    )
    data object Zram : Screen(
        R.string.title_zram, R.string.subtitle_zram,
        listOf(AccessType.ROOT)
    )
    data object WirelessAdb : Screen(
        R.string.title_wireless_adb, R.string.subtitle_wireless_adb,
        listOf(AccessType.ROOT)
    )
    data object Dt2w : Screen(
        R.string.title_dt2w, R.string.subtitle_dt2w,
        listOf(AccessType.ROOT)
    )
    data object ExtraDim : Screen(
        R.string.title_extra_dim, R.string.subtitle_extra_dim,
        listOf(AccessType.ROOT)
    )
    data object Recents : Screen(
        R.string.title_recents, R.string.subtitle_recents,
        listOf(AccessType.ROOT)
    )
    data object Toolbelt : Screen(
        R.string.title_toolbelt, R.string.subtitle_toolbelt,
        listOf(AccessType.ACCESSIBILITY, AccessType.ROOT)
    )
    data object Wifi5g : Screen(
        R.string.title_wifi5g, R.string.subtitle_wifi5g,
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
    data object BatteryUsage : Screen(
        R.string.title_battery_usage, R.string.subtitle_battery_usage,
        listOf(AccessType.ROOT)
    )
    data object Telemetry : Screen(
        R.string.title_telemetry, R.string.subtitle_telemetry,
        listOf(AccessType.ROOT)
    )
    data object BtIdle : Screen(
        R.string.title_bt_idle, R.string.subtitle_bt_idle,
        listOf(AccessType.ROOT)
    )
    data object LocationIdle : Screen(
        R.string.title_location_idle, R.string.subtitle_location_idle,
        listOf(AccessType.ROOT)
    )
    data object TickerNotifications : Screen(
        R.string.title_ticker_notifications, R.string.subtitle_ticker_notifications,
        listOf(AccessType.ROOT, AccessType.ACCESSIBILITY, AccessType.NOTIFICATION)
    )
}
