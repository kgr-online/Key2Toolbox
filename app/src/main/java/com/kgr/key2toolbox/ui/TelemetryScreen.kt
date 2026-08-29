package com.kgr.key2toolbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kgr.key2toolbox.R
import com.kgr.key2toolbox.modules.TelemetryController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TelemetryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(false) }
    var totalApps by remember { mutableIntStateOf(0) }
    var blockedApps by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<List<TelemetryController.AppReport>>(emptyList()) }
    var blockedSet by remember { mutableStateOf<Set<String>>(emptySet()) }
    var hasDetected by remember { mutableStateOf(false) }
    var detecting by remember { mutableStateOf(false) }

    fun refreshCounts() {
        scope.launch(Dispatchers.IO) {
            totalApps = TelemetryController.totalAffectedApps()
            blockedApps = TelemetryController.totalBlockedApps()
            report = TelemetryController.blockReport()
            blockedSet = TelemetryController.blockedPackages(context)
            hasDetected = true
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            enabled = TelemetryController.isPersisted()
            totalApps = TelemetryController.totalAffectedApps()
            blockedApps = TelemetryController.totalBlockedApps()
            report = TelemetryController.blockReport()
            blockedSet = TelemetryController.blockedPackages(context)
            hasDetected = true
            // Same class of bug as Extra Dim's schedule daemon: the watchdog can die
            // mid-session (e.g. the root shell that launched it got recycled), or be
            // alive but running a stale script from an older app build (which a bare
            // "is it running" check can't see) - self-heal on either case instead of
            // leaving telemetry silently unblocked/outdated until the next reboot.
            if (enabled && !TelemetryController.isHealthy(context)) {
                TelemetryController.setEnabled(context, true)
            }
        }
    }

    ScreenScaffold(title = Screen.Telemetry.title, onBack = onBack) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.telemetry_protection_status), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = when {
                        blockedApps > 0 && blockedApps == totalApps -> stringResource(R.string.telemetry_status_fully_protected)
                        blockedApps > 0 -> stringResource(R.string.telemetry_status_partially_protected)
                        else -> stringResource(R.string.telemetry_status_unprotected)
                    },
                    color = if (blockedApps > 0 && blockedApps == totalApps)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )
                Text(stringResource(R.string.telemetry_detected_apps, totalApps))
                Text(stringResource(R.string.telemetry_blocked_apps, blockedApps))
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.enable_at_boot))
            Switch(
                checked = enabled,
                enabled = !busy,
                onCheckedChange = {
                    enabled = it
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        TelemetryController.setEnabled(context, it)
                        busy = false
                        statusMessage = if (it)
                            context.getString(R.string.status_telemetry_enabled)
                        else
                            context.getString(R.string.status_telemetry_disabled)
                    }
                }
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        TelemetryController.blockAllDetected(context)
                        refreshCounts()
                        busy = false
                        statusMessage = context.getString(R.string.status_telemetry_toggled)
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.telemetry_block_now))
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                enabled = !detecting,
                onClick = {
                    detecting = true
                    scope.launch(Dispatchers.IO) {
                        report = TelemetryController.blockReport()
                        blockedSet = TelemetryController.blockedPackages(context)
                        hasDetected = true
                        detecting = false
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.telemetry_detect_apps))
            }
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        if (hasDetected) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.telemetry_app_list_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.telemetry_app_list_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (report.isEmpty()) {
                        Text(
                            stringResource(R.string.telemetry_app_list_empty),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            report.forEach { app ->
                                val blocked = blockedSet.contains(app.pkg)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Checkbox(
                                        checked = blocked,
                                        onCheckedChange = { checked ->
                                            // optimistic UI update; TelemetryController mirrors
                                            // to root + applies live on enable, in the background.
                                            blockedSet = if (checked) blockedSet + app.pkg else blockedSet - app.pkg
                                            scope.launch(Dispatchers.IO) {
                                                TelemetryController.setPackageBlocked(context, app.pkg, checked)
                                                blockedApps = TelemetryController.totalBlockedApps()
                                            }
                                        }
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            app.pkg,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            stringResource(
                                                if (blocked) R.string.telemetry_app_status_blocked
                                                else R.string.telemetry_app_status_unblocked
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (blocked)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        DescriptionDivider()
        Text(
            stringResource(R.string.desc_telemetry),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
