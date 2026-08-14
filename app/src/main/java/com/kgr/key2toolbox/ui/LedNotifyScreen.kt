package com.kgr.key2toolbox.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kgr.key2toolbox.core.RootShell
import com.kgr.key2toolbox.modules.LedNotifyManager
import com.kgr.key2toolbox.service.LedNotifyListenerService
import com.kgr.key2toolbox.service.isLedNotifyListenerEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private data class LedAppEntry(val label: String, val pkg: String)

/** Preset on/off blink durations offered for "Flash length". */
private val FLASH_LENGTH_OPTIONS_MS = listOf(250, 500, 1000, 2000)

/**
 * Presets offered for the minimum-importance LED filter. "Any importance"
 * uses NONE(0) as the threshold, which passes every real importance value
 * a notification can have - i.e. no filtering, not a special sentinel.
 */
private val IMPORTANCE_THRESHOLD_OPTIONS: List<Pair<String, Int>> = listOf(
    "Any importance" to NotificationManager.IMPORTANCE_NONE,
    "Low and above" to NotificationManager.IMPORTANCE_LOW,
    "Default and above" to NotificationManager.IMPORTANCE_DEFAULT,
    "High only" to NotificationManager.IMPORTANCE_HIGH
)

/** Swatches offered per app. "None" (null) clears the assignment. */
private val PALETTE: List<Pair<String, Color?>> = listOf(
    "None" to null,
    "Red" to Color(0xFFFF0000),
    "Green" to Color(0xFF00FF00),
    "Blue" to Color(0xFF0000FF),
    "Cyan" to Color(0xFF00FFFF),
    "Magenta" to Color(0xFFFF00FF),
    "Yellow" to Color(0xFFFFFF00),
    "White" to Color(0xFFFFFFFF),
    "Orange" to Color(0xFFFF8800),
    "Purple" to Color(0xFF8800FF)
)

/**
 * Per-app LED notification colors, driven directly through [LedNotifyManager]
 * (root sysfs writes) instead of LineageOS's own per-app light-color picker,
 * whose color quantization doesn't match this device's LED hardware.
 */
@Composable
fun LedNotifyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember {
        context.getSharedPreferences(LedNotifyListenerService.PREFS, Context.MODE_PRIVATE)
    }

    var listenerEnabled by remember { mutableStateOf(false) }
    var rootAvailable by remember { mutableStateOf<Boolean?>(null) }
    var ledAvailable by remember { mutableStateOf<Boolean?>(null) }
    var featureEnabled by remember {
        mutableStateOf(prefs.getBoolean(LedNotifyListenerService.KEY_ENABLED, false))
    }
    var flashWhileScreenOn by remember {
        mutableStateOf(prefs.getBoolean(LedNotifyListenerService.KEY_FLASH_WHILE_SCREEN_ON, false))
    }
    var flashLengthMs by remember {
        mutableStateOf(
            prefs.getInt(
                LedNotifyListenerService.KEY_FLASH_LENGTH_MS,
                LedNotifyListenerService.DEFAULT_FLASH_LENGTH_MS
            )
        )
    }
    var cycleMode by remember {
        mutableStateOf(prefs.getBoolean(LedNotifyListenerService.KEY_CYCLE_MODE, false))
    }
    var respectDnd by remember {
        mutableStateOf(
            prefs.getBoolean(
                LedNotifyListenerService.KEY_RESPECT_DND,
                LedNotifyListenerService.DEFAULT_RESPECT_DND
            )
        )
    }
    var minImportance by remember {
        mutableStateOf(
            prefs.getInt(
                LedNotifyListenerService.KEY_MIN_IMPORTANCE,
                LedNotifyListenerService.DEFAULT_MIN_IMPORTANCE
            )
        )
    }
    var apps by remember { mutableStateOf<List<LedAppEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    // Bump this to force color-row recomposition after a prefs write.
    var assignmentVersion by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        listenerEnabled = isLedNotifyListenerEnabled(context)
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
        withContext(Dispatchers.IO) {
            rootAvailable = RootShell.isRootAvailable()
            ledAvailable = if (rootAvailable == true) LedNotifyManager.isAvailable() else null
        }
    }

    fun colorFor(pkg: String): Color? {
        val raw = prefs.getInt(LedNotifyListenerService.colorKey(pkg), Int.MIN_VALUE)
        return if (raw == Int.MIN_VALUE) null else Color(raw or (0xFF shl 24))
    }

    fun assign(pkg: String, color: Color?) {
        prefs.edit().apply {
            if (color == null) remove(LedNotifyListenerService.colorKey(pkg))
            else putInt(LedNotifyListenerService.colorKey(pkg), color.toArgb())
        }.apply()
        assignmentVersion++
    }

    val filtered = remember(apps, query) {
        val q = query.trim().lowercase(Locale.ROOT)
        val list = apps ?: emptyList()
        if (q.isEmpty()) list
        else list.filter { it.label.lowercase(Locale.ROOT).contains(q) || it.pkg.contains(q) }
    }
    val assignedCount = remember(apps, assignmentVersion) {
        (apps ?: emptyList()).count { colorFor(it.pkg) != null }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
        }
        Text(Screen.LedNotify.title, style = MaterialTheme.typography.headlineSmall)

        Text(
            "Assign a raw LED color per app, written straight to the LED " +
                "hardware - this bypasses LineageOS's own notification light " +
                "color picker, whose colors don't come out accurate on this " +
                "device. Needs root and notification access.",
            style = MaterialTheme.typography.bodySmall
        )

        NotificationAccessBanner(listenerEnabled)

        when (rootAvailable) {
            null -> {}
            false -> WarningLine("Root not available - LED writes will fail.")
            true -> when (ledAvailable) {
                false -> WarningLine("No supported LED device found on this ROM/kernel.")
                else -> {}
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Enable LED Notify")
            Switch(
                checked = featureEnabled,
                onCheckedChange = { checked ->
                    featureEnabled = checked
                    prefs.edit().putBoolean(LedNotifyListenerService.KEY_ENABLED, checked).apply()
                    if (!checked) {
                        scope.launch { withContext(Dispatchers.IO) { LedNotifyManager.off() } }
                    }
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Flash while screen is on")
                Text(
                    "Off by default - the LED already suppresses itself once " +
                        "the screen is on, since you're looking at it anyway.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = flashWhileScreenOn,
                onCheckedChange = { checked ->
                    flashWhileScreenOn = checked
                    prefs.edit()
                        .putBoolean(LedNotifyListenerService.KEY_FLASH_WHILE_SCREEN_ON, checked)
                        .apply()
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Respect Do Not Disturb")
                Text(
                    "On by default - suppresses the LED whenever the system " +
                        "is in DND, however it was triggered (manual toggle, " +
                        "schedule, or a LOS Mode). Turn off if you want the " +
                        "LED to keep flashing during DND.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = respectDnd,
                onCheckedChange = { checked ->
                    respectDnd = checked
                    prefs.edit()
                        .putBoolean(LedNotifyListenerService.KEY_RESPECT_DND, checked)
                        .apply()
                }
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Minimum importance to light the LED")
            Text(
                "Some apps post a notification on a low-importance channel and " +
                    "retract it themselves a couple seconds later - often a sign " +
                    "of a muted conversation. Raise this to skip those instead " +
                    "of flashing briefly for something that's about to " +
                    "disappear anyway.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                IMPORTANCE_THRESHOLD_OPTIONS.forEach { (label, value) ->
                    SelectableChip(
                        label = label,
                        selected = minImportance == value,
                        onClick = {
                            minImportance = value
                            prefs.edit()
                                .putInt(LedNotifyListenerService.KEY_MIN_IMPORTANCE, value)
                                .apply()
                        }
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Flash length")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FLASH_LENGTH_OPTIONS_MS.forEach { ms ->
                    FlashLengthChip(
                        ms = ms,
                        selected = flashLengthMs == ms,
                        onClick = {
                            flashLengthMs = ms
                            prefs.edit().putInt(LedNotifyListenerService.KEY_FLASH_LENGTH_MS, ms).apply()
                        }
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("When multiple notifications are active")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectableChip(
                    label = "Show only the most recent",
                    selected = !cycleMode,
                    onClick = {
                        cycleMode = false
                        prefs.edit().putBoolean(LedNotifyListenerService.KEY_CYCLE_MODE, false).apply()
                    }
                )
                SelectableChip(
                    label = "Cycle through colors",
                    selected = cycleMode,
                    onClick = {
                        cycleMode = true
                        prefs.edit().putBoolean(LedNotifyListenerService.KEY_CYCLE_MODE, true).apply()
                    }
                )
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search apps") }
        )

        when (val list = apps) {
            null -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) { CircularProgressIndicator() }

            else -> {
                Text(
                    "$assignedCount of ${list.size} apps have a color assigned",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    filtered.forEach { app ->
                        AppColorRow(
                            app = app,
                            current = colorFor(app.pkg),
                            onPick = { color ->
                                assign(app.pkg, color)
                                if (color != null) {
                                    scope.launch {
                                        // Preview with 3 actual on/off pulses at the
                                        // chosen flash length - software blink, same
                                        // mechanism the listener service uses, since
                                        // this hardware has no kernel blink trigger.
                                        withContext(Dispatchers.IO) {
                                            repeat(3) {
                                                LedNotifyManager.setColor(color.toArgb())
                                                delay(flashLengthMs.toLong())
                                                LedNotifyManager.off()
                                                delay(flashLengthMs.toLong())
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WarningLine(message: String) {
    Text(
        message,
        color = Color(0xFFE57373),
        style = MaterialTheme.typography.bodySmall
    )
}

/** Notification-access equivalent of [AccessibilityServiceBanner]. */
@Composable
private fun NotificationAccessBanner(enabled: Boolean) {
    if (enabled) {
        Text("Notification access: enabled", color = Color(0xFF81C784))
        return
    }
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "This feature needs notification access granted.",
                color = Color(0xFFE57373),
                style = MaterialTheme.typography.bodyMedium
            )
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }) {
                Text("Open Notification Access Settings")
            }
        }
    }
}

@Composable
private fun AppColorRow(app: LedAppEntry, current: Color?, onPick: (Color?) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            app.label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            app.pkg,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PALETTE.forEach { (name, color) ->
                val isSelected = (color == null && current == null) || (color != null && color == current)
                Swatch(name = name, color = color, isSelected = isSelected, onClick = { onPick(color) })
            }
        }
    }
}

@Composable
private fun Swatch(name: String, color: Color?, isSelected: Boolean, onClick: () -> Unit) {
    val ringColor = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF3A3A3C)
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(color ?: Color(0xFF1C1C1E))
            .border(width = if (isSelected) 2.dp else 1.dp, color = ringColor, shape = CircleShape)
            .clickable(onClick = onClick)
    )
}

@Composable
private fun FlashLengthChip(ms: Int, selected: Boolean, onClick: () -> Unit) {
    val label = if (ms >= 1000) "${ms / 1000}s" else "${ms}ms"
    SelectableChip(label = label, selected = selected, onClick = onClick)
}

@Composable
private fun SelectableChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val ringColor = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF3A3A3C)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color(0xFF1C1C1E))
            .border(width = if (selected) 2.dp else 1.dp, color = ringColor, shape = RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1, softWrap = false)
    }
}

/** All apps with a launcher entry, labelled and sorted, self excluded. */
private fun loadLaunchableApps(context: Context): List<LedAppEntry> {
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
            LedAppEntry(label, pkg)
        }
        .sortedBy { it.label.lowercase(Locale.ROOT) }
        .toList()
}
