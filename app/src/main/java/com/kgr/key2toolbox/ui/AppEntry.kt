package com.kgr.key2toolbox.ui

import com.kgr.key2toolbox.R
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.util.Locale

data class AppEntry(val label: String, val pkg: String)

/** All apps with a launcher entry, labelled and sorted, self excluded. */
fun loadAllLaunchableApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved = pm.queryIntentActivities(intent, 0)
    return resolved.asSequence()
        .map { it.activityInfo.packageName }
        .filter { it != context.packageName }
        .distinct()
        .map { pkg ->
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                pkg
            }
            AppEntry(label, pkg)
        }
        .sortedBy { it.label.lowercase(Locale.ROOT) }
        .toList()
}
