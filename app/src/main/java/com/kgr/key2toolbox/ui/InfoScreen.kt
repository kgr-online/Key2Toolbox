package com.kgr.key2toolbox.ui

import com.kgr.key2toolbox.R
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row as LayoutRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kgr.key2toolbox.core.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class Row(val label: String, val value: String, val key: String? = null)

/**
 * Fetched once from [HomeScreen] (which survives tab switches), rather than
 * inside this composable - re-fetching (and flashing back to empty cards)
 * every time the user merely revisits the Info tab was the "wonky, pops up"
 * symptom, since `when(tab)`'s branches are disposed/recreated on every
 * switch and lose their own `remember` state.
 */
internal class InfoState {
    var device: List<Row> by mutableStateOf(emptyList())
    var battery: List<Row> by mutableStateOf(emptyList())
}

internal suspend fun InfoState.refresh(context: Context) {
    battery = readBatteryRows(context)
    withContext(Dispatchers.IO) {
        // buildDeviceRows() shells out to getprop via Runtime.exec, which blocks
        // for real process-fork time - keep it (and the root sysfs read) off the
        // main thread rather than in the calling LaunchedEffect's default dispatcher.
        val deviceRows = buildDeviceRows(context)
        val health = readBatteryHealthRows(context)
        withContext(Dispatchers.Main) {
            device = deviceRows
            if (health.isNotEmpty()) battery = battery + health
        }
    }
}

@Composable
internal fun InfoScreen(state: InfoState, scrollState: ScrollState, onOpenBatteryUsage: () -> Unit) {
    val device = state.device
    val battery = state.battery

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)

        InfoCard(stringResource(R.string.info_device)) { device.forEach { LabelValue(it) } }

        InfoCard(stringResource(R.string.info_battery)) {
            battery.forEach { row ->
                LabelValue(row)
                if (row.key == "level") {
                    BatteryUsageEntryRow(onClick = onOpenBatteryUsage)
                }
            }
        }
    }
}

@Composable
private fun BatteryUsageEntryRow(onClick: () -> Unit) {
    LayoutRow(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Filled.QueryStats, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.info_battery_usage_row), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.info_battery_usage_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private val NEUTRAL = Color(0xFFB0B0B0)

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun LabelValue(row: Row) {
    Column {
        Text(row.label, style = MaterialTheme.typography.labelMedium, color = NEUTRAL)
        Text(row.value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun getprop(key: String): String = try {
    Runtime.getRuntime().exec(arrayOf("getprop", key))
        .inputStream.bufferedReader().readText().trim()
} catch (_: Exception) {
    ""
}

private fun buildDeviceRows(context: Context): List<Row> {
    val rows = mutableListOf(
        Row(context.getString(R.string.info_model), "${Build.MANUFACTURER} ${Build.MODEL}"),
        Row(context.getString(R.string.info_android), context.getString(R.string.info_android_value, Build.VERSION.RELEASE, Build.VERSION.SDK_INT)),
    )
    getprop("ro.lineage.version").takeIf { it.isNotEmpty() }?.let { rows += Row(context.getString(R.string.info_lineageos), it) }
    rows += Row(context.getString(R.string.info_build), Build.DISPLAY)
    Build.VERSION.SECURITY_PATCH.takeIf { it.isNotEmpty() }?.let { rows += Row(context.getString(R.string.info_security_patch), it) }
    System.getProperty("os.version")?.takeIf { it.isNotEmpty() }?.let { rows += Row(context.getString(R.string.info_kernel), it) }
    return rows
}

private fun readBatteryRows(context: Context): List<Row> {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?: return emptyList()
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
    val status = when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
        BatteryManager.BATTERY_STATUS_CHARGING -> context.getString(R.string.battery_status_charging)
        BatteryManager.BATTERY_STATUS_DISCHARGING -> context.getString(R.string.battery_status_discharging)
        BatteryManager.BATTERY_STATUS_FULL -> context.getString(R.string.battery_status_full)
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> context.getString(R.string.battery_status_not_charging)
        else -> context.getString(R.string.generic_unknown)
    }
    val health = when (intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
        BatteryManager.BATTERY_HEALTH_GOOD -> context.getString(R.string.battery_health_good)
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> context.getString(R.string.battery_health_overheat)
        BatteryManager.BATTERY_HEALTH_DEAD -> context.getString(R.string.battery_health_dead)
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> context.getString(R.string.battery_health_over_voltage)
        BatteryManager.BATTERY_HEALTH_COLD -> context.getString(R.string.battery_health_cold)
        else -> context.getString(R.string.generic_unknown)
    }
    val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
    val volt = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
    val tech = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: ""

    val rows = mutableListOf<Row>()
    if (pct >= 0) rows += Row(context.getString(R.string.info_level), context.getString(R.string.info_level_value, pct, status), key = "level")
    rows += Row(context.getString(R.string.info_health), health)
    if (temp > 0) rows += Row(context.getString(R.string.info_temperature), String.format("%.1f °C", temp / 10.0))
    if (volt > 0) rows += Row(context.getString(R.string.info_voltage), String.format("%.3f V", volt / 1000.0))
    if (tech.isNotEmpty()) rows += Row(context.getString(R.string.info_technology), tech)
    return rows
}

/** Capacity-based health and cycle count from sysfs (needs root). */
private fun readBatteryHealthRows(context: Context): List<Row> {
    val out = RootShell.run(
        "cat /sys/class/power_supply/battery/charge_full " +
            "/sys/class/power_supply/battery/charge_full_design " +
            "/sys/class/power_supply/battery/cycle_count 2>/dev/null"
    ).out.map { it.trim() }
    val full = out.getOrNull(0)?.toLongOrNull()
    val design = out.getOrNull(1)?.toLongOrNull()
    val cycles = out.getOrNull(2)?.toIntOrNull()

    val rows = mutableListOf<Row>()
    if (full != null && design != null && design > 0) {
        val pct = full * 100 / design
        rows += Row(context.getString(R.string.info_capacity), context.getString(R.string.info_capacity_value, full / 1000, design / 1000, pct))
    }
    if (cycles != null) rows += Row(context.getString(R.string.info_charge_cycles), cycles.toString())
    return rows
}
