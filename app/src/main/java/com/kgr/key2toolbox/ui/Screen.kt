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
        "Physical Keyboard Fixes", "Remap Ctrl key, fix SYM key",
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
    data object AdBlock : Screen(
        "AdBlock", "Systemless hosts-based ad & tracker blocking",
        listOf(AccessType.ROOT)
    )
    data object DenylistManager : Screen(
        "Denylist Manager", "Manage Magisk DenyList and Zygisk-Hide together",
        listOf(AccessType.ROOT)
    )
    data object Zram : Screen(
        "ZRAM", "Compression algorithm and size",
        listOf(AccessType.ROOT)
    )
    data object WirelessAdb : Screen(
        "Persistent Wireless ADB", "Static port, survives reboot",
        listOf(AccessType.ROOT)
    )
    data object Dt2w : Screen(
        "Double-Tap to Wake", "Wake screen with a double tap",
        listOf(AccessType.ROOT)
    )
    data object Wifi5g : Screen(
        "5GHz Hotspot Workaround", "Force US WiFi region for 5GHz SoftAP",
        listOf(AccessType.ROOT)
    )
    data object PlayStoreTagger : Screen(
        "Play Store Tagger", "Retag apps as Play Store installs",
        listOf(AccessType.ROOT)
    )
    data object K2PF : Screen(
        "K2ProdFix Settings", "Manage k2prodfix module tweaks",
        listOf(AccessType.ROOT)
    )
    data object LedNotify : Screen(
        "LED Notify Colors", "Per-app notification LED colors",
        listOf(AccessType.ROOT, AccessType.NOTIFICATION)
    )
    data object BtIdle : Screen(
        "Auto-disable Bluetooth", "Turn off Bluetooth when idle",
        listOf(AccessType.ROOT)
    )
}
