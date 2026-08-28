package com.kgr.key2toolbox.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Slider
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kgr.key2toolbox.R
import com.kgr.key2toolbox.modules.ToolbeltController
import com.kgr.key2toolbox.modules.ToolbeltController.Slot
import com.kgr.key2toolbox.modules.ToolbeltController.ToolbeltAction
import com.kgr.key2toolbox.modules.ToolbeltController.ToolbeltIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** "HANGUP_OR_HOME" -> "Hangup or home". Power-user module; not localised per action. */
private fun prettify(name: String): String =
    name.split('_').joinToString(" ") { it.lowercase() }
        .replaceFirstChar { it.uppercase() }

@Composable
fun ToolbeltScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember {
        context.getSharedPreferences(ToolbeltController.PREFS, android.content.Context.MODE_PRIVATE)
    }

    var enabled by remember { mutableStateOf(ToolbeltController.isEnabled(context)) }
    var autoHide by remember {
        mutableStateOf(prefs.getBoolean(ToolbeltController.KEY_AUTOHIDE_FULLSCREEN, true))
    }
    var height by remember { mutableStateOf(ToolbeltController.heightDp(prefs)) }
    var iconScale by remember {
        mutableStateOf((ToolbeltController.iconScale(prefs) * 100).toInt())
    }
    var haptic by remember { mutableStateOf(ToolbeltController.hapticLevel(prefs)) }
    var collapsible by remember { mutableStateOf(ToolbeltController.isCollapsible(prefs)) }
    var colorMode by remember { mutableStateOf(ToolbeltController.colorMode(prefs)) }
    var xposedActive by remember { mutableStateOf(ToolbeltController.isXposedActive()) }
    var navMode by remember { mutableStateOf(-1) }
    val slots: SnapshotStateList<Slot> =
        remember { ToolbeltController.getSlots(context).toMutableStateList() }
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val m = ToolbeltController.syncNavMode(context)
            val a = loadLaunchableApps(context)
            withContext(Dispatchers.Main) {
                navMode = m
                xposedActive = ToolbeltController.isXposedActive()
                apps = a
            }
        }
    }

    fun persistSlots() {
        val snapshot = slots.toList()
        scope.launch(Dispatchers.IO) { ToolbeltController.setSlots(context, snapshot) }
    }

    ScreenScaffold(title = Screen.Toolbelt.title, onBack = onBack) {
        Text(stringResource(R.string.toolbelt_intro), style = MaterialTheme.typography.bodySmall)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.generic_enabled))
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    enabled = on
                    scope.launch(Dispatchers.IO) { ToolbeltController.setEnabled(context, on) }
                }
            )
        }

        // LSPosed status
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(
                        if (xposedActive) R.string.toolbelt_xposed_ok else R.string.toolbelt_xposed_missing
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (xposedActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                if (!xposedActive) {
                    Text(
                        stringResource(R.string.toolbelt_xposed_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val modeLabel = when (navMode) {
                    2 -> stringResource(R.string.toolbelt_navmode_gesture)
                    1 -> stringResource(R.string.toolbelt_navmode_2button)
                    0 -> stringResource(R.string.toolbelt_navmode_3button)
                    else -> "?"
                }
                Text(
                    stringResource(R.string.toolbelt_navmode_current, modeLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.toolbelt_autohide_fullscreen), modifier = Modifier.weight(1f))
            Switch(
                checked = autoHide,
                onCheckedChange = { on ->
                    autoHide = on
                    prefs.edit().putBoolean(ToolbeltController.KEY_AUTOHIDE_FULLSCREEN, on).apply()
                }
            )
        }

        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.toolbelt_collapsible), modifier = Modifier.weight(1f))
                Switch(
                    checked = collapsible,
                    onCheckedChange = { on ->
                        collapsible = on
                        prefs.edit().putBoolean(ToolbeltController.KEY_COLLAPSIBLE, on).apply()
                    }
                )
            }
            Text(
                stringResource(R.string.toolbelt_collapsible_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        DescriptionDivider()
        Text(
            stringResource(R.string.toolbelt_appearance_section),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )

        IntSliderRow(
            label = stringResource(R.string.toolbelt_height),
            value = height, valueText = "${height}dp", range = 36f..88f, steps = 25,
            onChange = { height = it }, onCommit = {
                prefs.edit().putInt(ToolbeltController.KEY_HEIGHT_DP, height).apply()
            }
        )
        IntSliderRow(
            label = stringResource(R.string.toolbelt_icon_size),
            value = iconScale, valueText = "$iconScale%", range = 40f..100f, steps = 11,
            onChange = { iconScale = it }, onCommit = {
                prefs.edit().putInt(ToolbeltController.KEY_ICON_SCALE, iconScale).apply()
            }
        )
        IntSliderRow(
            label = stringResource(R.string.toolbelt_haptics),
            value = haptic,
            valueText = stringArrayResource(R.array.toolbelt_haptic_levels).getOrElse(haptic) { "$haptic" },
            range = 0f..3f, steps = 2,
            onChange = { haptic = it }, onCommit = {
                prefs.edit().putInt(ToolbeltController.KEY_HAPTIC, haptic).apply()
            }
        )

        val colorLabels = stringArrayResource(R.array.toolbelt_color_modes)
        PickerRow(
            label = stringResource(R.string.toolbelt_color),
            current = colorLabels.getOrElse(colorMode) { "$colorMode" },
            options = listOf(0, 1, 2).map { it to colorLabels.getOrElse(it) { "$it" } },
            onPick = {
                colorMode = it
                prefs.edit().putInt(ToolbeltController.KEY_COLOR_MODE, it).apply()
            }
        )

        DescriptionDivider()
        Text(
            stringResource(R.string.toolbelt_slots_section),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )

        slots.forEachIndexed { index, slot ->
            SlotCard(
                index = index,
                slot = slot,
                apps = apps,
                onChange = { updated ->
                    slots[index] = updated
                    persistSlots()
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    val defs = ToolbeltController.DEFAULT_SLOTS
                    slots.clear(); slots.addAll(defs)
                    scope.launch(Dispatchers.IO) { ToolbeltController.resetSlots(context) }
                },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.toolbelt_reset)) }
            OutlinedButton(
                onClick = { scope.launch(Dispatchers.IO) { ToolbeltController.restartLauncher() } },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.toolbelt_restart_systemui)) }
        }

        DescriptionDivider()
        Text(
            stringResource(R.string.toolbelt_debug_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SlotCard(index: Int, slot: Slot, apps: List<Pair<String, String>>, onChange: (Slot) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(slot.icon.res),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.toolbelt_slot_n, index + 1),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            PickerRow(
                label = stringResource(R.string.toolbelt_field_icon),
                current = prettify(slot.icon.name),
                options = ToolbeltIcon.entries.map { it to prettify(it.name) },
                onPick = { onChange(slot.copy(icon = it)) }
            )
            GestureRow(
                stringResource(R.string.toolbelt_field_tap), slot.tap, slot.tapArg, apps,
            ) { act, a -> onChange(slot.copy(tap = act, tapArg = a)) }
            GestureRow(
                stringResource(R.string.toolbelt_field_double), slot.doubleTap, slot.doubleArg, apps,
            ) { act, a -> onChange(slot.copy(doubleTap = act, doubleArg = a)) }
            GestureRow(
                stringResource(R.string.toolbelt_field_long), slot.longTap, slot.longArg, apps,
            ) { act, a -> onChange(slot.copy(longTap = act, longArg = a)) }
        }
    }
}

@Composable
private fun GestureRow(
    label: String,
    action: ToolbeltAction,
    arg: String?,
    apps: List<Pair<String, String>>,
    onChange: (ToolbeltAction, String?) -> Unit,
) {
    PickerRow(
        label = label,
        current = prettify(action.name),
        options = ToolbeltAction.entries.map { it to prettify(it.name) },
        onPick = { picked ->
            onChange(picked, if (picked == ToolbeltAction.LAUNCH_APP) arg else null)
        }
    )
    if (action == ToolbeltAction.LAUNCH_APP) {
        PickerRow(
            label = stringResource(R.string.toolbelt_field_app),
            current = apps.firstOrNull { it.first == arg }?.second
                ?: arg ?: stringResource(R.string.toolbelt_pick_app),
            options = apps.map { it.first to it.second },
            onPick = { onChange(action, it) }
        )
    }
}

/** All apps with a launcher entry: package -> label, sorted, self excluded. */
private fun loadLaunchableApps(context: android.content.Context): List<Pair<String, String>> {
    val pm = context.packageManager
    val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
        .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0).asSequence()
        .map { it.activityInfo.packageName }
        .filter { it != context.packageName }
        .distinct()
        .map { pkg ->
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
                pkg
            }
            pkg to label
        }
        .sortedBy { it.second.lowercase() }
        .toList()
}

@Composable
private fun IntSliderRow(
    label: String,
    value: Int,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Int) -> Unit,
    onCommit: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            onValueChangeFinished = onCommit,
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun <T> PickerRow(
    label: String,
    current: String,
    options: List<Pair<T, String>>,
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(76.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            current,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.generic_back)) }
            },
            title = { Text(label) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(options) { (value, text) ->
                        Text(
                            text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (text == current) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { open = false; onPick(value) }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            }
        )
    }
}
