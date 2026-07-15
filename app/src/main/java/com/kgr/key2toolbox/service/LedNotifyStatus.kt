package com.kgr.key2toolbox.service

import android.content.Context
import androidx.core.app.NotificationManagerCompat

/** True if the user has granted this app's notification listener access. */
fun isLedNotifyListenerEnabled(context: Context): Boolean {
    val enabled = NotificationManagerCompat.getEnabledListenerPackages(context)
    return context.packageName in enabled
}
