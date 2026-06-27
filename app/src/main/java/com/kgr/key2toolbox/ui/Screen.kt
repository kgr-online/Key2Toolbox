package com.kgr.key2toolbox.ui

sealed class Screen(val title: String, val subtitle: String = "") {
    data object CtrlKey : Screen("Convenience Key → Ctrl", "Remap convenience key to Ctrl")
    data object Zram : Screen("ZRAM", "Compression algorithm and size")
    data object KbdLight : Screen("Adaptive Keyboard Backlight", "Auto-dim with screen brightness")
    data object WirelessAdb : Screen("Persistent Wireless ADB", "Static port, survives reboot")
    data object Dt2w : Screen("Double-Tap to Wake", "Wake screen with a double tap")
    data object NavLock : Screen("Keyboard Nav Lock", "Stop accidental nav presses")
    data object PinKeyboard : Screen("Lockscreen PIN on Keyboard", "Type your PIN on hardware keys")
    data object ImeBlock : Screen("Per-App Keyboard Block", "Send keys straight to chosen apps")
    data object Wifi5g : Screen("5GHz Hotspot Workaround", "Force US WiFi region for 5GHz AP")
    data object Watch : Screen("Wearable Power Saver", "Stop background Bluetooth reconnect attempts")
    data object BtIdle : Screen("Auto-disable Bluetooth", "Turn off Bluetooth when idle")
    data object Performance : Screen("Performance & Battery Tuning", "Relax CPU scaling and input boost thresholds")
    data object Telemetry : Screen("Global Telemetry Block", "Disable Firebase Crashlytics system-wide")
}

/** Bottom-bar sections. */
enum class AppTab(val label: String) {
    Info("Info"),
    Keyboard("Keyboard"),
    System("System"),
    Network("Network"),
}

/** Screens listed under the Keyboard tab. */
val keyboardScreens = listOf(
    Screen.CtrlKey,
    Screen.KbdLight,
    Screen.NavLock,
    Screen.PinKeyboard,
    Screen.ImeBlock,
)

/** Screens listed under the System tab. */
val systemScreens = listOf(
    Screen.Zram,
    Screen.Performance,
    Screen.Dt2w,
)

/** Screens listed under the Network tab. */
val networkScreens = listOf(
    Screen.Telemetry,
    Screen.WirelessAdb,
    Screen.Wifi5g,
    Screen.Watch,
    Screen.BtIdle,
)
