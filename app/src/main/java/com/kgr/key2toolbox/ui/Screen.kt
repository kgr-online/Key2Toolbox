package com.kgr.key2toolbox.ui

enum class AccessType(val label: String) {
    ROOT("root"),
    ACCESSIBILITY("accessibility"),
    NOTIFICATION("notification")
}

sealed class Screen(
    val title: String,
    val subtitle: String = "",
    val access: List<AccessType> = emptyList()
) {
    data object Home : Screen("Key2 Toolbox")

    // Keyboard tab
    data object CtrlKey : Screen(
        "Convenience Key → Ctrl", "Remap convenience key to Ctrl",
        listOf(AccessType.ROOT)
    )
    data object KbdLight : Screen(
        "Adaptive Keyboard Backlight", "Auto-dim with screen brightness",
        listOf(AccessType.ROOT)
    )
    data object NavLock : Screen(
        "Keyboard Nav Lock", "Stop accidental nav presses",
        listOf(AccessType.ACCESSIBILITY)
    )
    data object PinKeyboard : Screen(
        "Lockscreen PIN on Keyboard", "Type your PIN on hardware keys",
        listOf(AccessType.ACCESSIBILITY)
    )
    data object ImeBlock : Screen(
        "Per-App Keyboard Block", "Route keys straight to chosen apps",
        listOf(AccessType.ACCESSIBILITY)
    )

    // System tab
    data object Zram : Screen(
        "ZRAM", "Compression algorithm and size",
        listOf(AccessType.ROOT)
    )
    data object Performance : Screen(
        "Performance & Battery Tuning", "Relax CPU scaling and input boost thresholds",
        listOf(AccessType.ROOT)
    )
    data object Dt2w : Screen(
        "Double-Tap to Wake", "Wake screen with a double tap",
        listOf(AccessType.ROOT)
    )
    data object PlayStoreTagger : Screen(
        "Play Store Tagger", "Retag apps as Play Store installs",
        listOf(AccessType.ROOT)
    )
    data object K2PF : Screen(
        "BBProdFix Settings", "Manage k2prodfix module tweaks",
        listOf(AccessType.ROOT)
    )
    data object LedNotify : Screen(
        "LED Notify Colors", "Per-app notification LED colors",
        listOf(AccessType.ROOT, AccessType.NOTIFICATION)
    )

    // Network tab
    data object Telemetry : Screen(
        "Global Telemetry Block", "Disable Firebase Crashlytics system-wide",
        listOf(AccessType.ROOT)
    )
    data object WirelessAdb : Screen(
        "Persistent Wireless ADB", "Static port, survives reboot",
        listOf(AccessType.ROOT)
    )
    data object Wifi5g : Screen(
        "5GHz Hotspot Workaround", "Force US WiFi region for 5GHz AP",
        listOf(AccessType.ROOT)
    )
    data object Watch : Screen(
        "Wearable Power Saver", "Stop background Bluetooth reconnect attempts",
        listOf(AccessType.ROOT)
    )
    data object BtIdle : Screen(
        "Auto-disable Bluetooth", "Turn off Bluetooth when idle",
        listOf(AccessType.ROOT)
    )
}

/** Bottom-bar sections. */
enum class AppTab(val label: String) {
    Info("Info"),
    Keyboard("Keyboard"),
    System("System"),
    Network("Network"),
    Settings("Settings"),
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
    Screen.PlayStoreTagger,
    Screen.K2PF,
    Screen.LedNotify,
)

/** Screens listed under the Network tab. */
val networkScreens = listOf(
    Screen.Telemetry,
    Screen.WirelessAdb,
    Screen.Wifi5g,
    Screen.Watch,
    Screen.BtIdle,
)
