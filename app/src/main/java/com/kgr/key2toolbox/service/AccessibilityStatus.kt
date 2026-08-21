package com.kgr.key2toolbox.service

import android.content.Context

/**
 * Whether Key2AccessibilityService is currently connected and running.
 *
 * Previously this read back AccessibilityManager's enabled-service list,
 * which on at least one ROM state silently returned an empty result even
 * though the service was genuinely enabled and bound (confirmed via
 * `dumpsys accessibility` and `settings get secure
 * enabled_accessibility_services` from shell). Rather than depend on that
 * read succeeding, ask the service directly - it's ground truth and can't
 * be silently withheld.
 *
 * `context` is kept in the signature (unused) so existing call sites don't
 * need to change.
 */
fun isKey2AccessibilityServiceEnabled(@Suppress("UNUSED_PARAMETER") context: Context): Boolean {
    return Key2AccessibilityService.isRunning
}
