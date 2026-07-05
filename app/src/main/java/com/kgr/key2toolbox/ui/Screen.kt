package com.kgr.key2toolbox.ui

sealed class Screen(val title: String, val subtitle: String = "") {
    data object Home : Screen("Key2 Toolbox")
    // Keyboard tab
    data object CtrlKey : Screen("Convenience Key → Ctrl", "Remap convenience key to Ctrl")
    data object KbdLight : Screen("Adaptive Keyboard Backlight", "Auto-dim with screen brightness")
    data object NavLock : Screen("Keyboard Nav Lock", "Stop accidental nav presses")
    data object PinKeyboard : Screen("Lockscreen PIN on Keyboard", "Type your PIN on hardware keys")
    data object ImeBlock : Screen("Per-App Keyboard Block", "Route keys straight to chosen apps")
    // System tab
    data object Zram : Screen("ZRAM", "Compression algorithm and size")
    data object WirelessAdb : Screen("Persistent Wireless ADB", "Static port, survives reboot")
    data object Dt2w : Screen("Double-Tap to Wake", "Wake screen with a double tap")
    data object Wifi5g : Screen("5GHz Hotspot Workaround", "Force US WiFi region for 5GHz SoftAP")
    data object PlayStoreTagger : Screen("Play Store Tagger", "Retag apps as Play Store installs")
    data object K2PF : Screen("BBProdFix Settings", "Manage k2prodfix module tweaks")
    data object LedNotify : Screen("LED Notify Colors", "Per-app notification LED colors")
}
