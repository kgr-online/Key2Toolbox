package com.kgr.key2toolbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kgr.key2toolbox.modules.WatchController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WatchScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var supported by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf(listOf<WatchController.WearableDevice>()) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun refreshDevices() {
        scope.launch(Dispatchers.IO) {
            supported = WatchController.isSupported()
            if (supported) {
                devices = WatchController.getDevices()
            }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            supported = WatchController.isSupported()
            if (supported) {
                devices = WatchController.getDevices()
            }
        }
    }

    ScreenScaffold(title = Screen.Watch.title, onBack = onBack) {
        Text(
            "Smartwatches or fitness trackers paired via Google Play Services generate constant " +
            "reconnect attempts and alarms while out of range, draining the battery. " +
            "Set a device to Dormant to stop these wakeups without unpairing or factory-resetting.",
            style = MaterialTheme.typography.bodySmall
        )

        if (!supported) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "No Google Wearable connection database detected. " +
                    "This feature is only active if you have Wear OS or GMS wearables paired.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (devices.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "No paired wearables found in Google Play Services. " +
                    "If you have a smartwatch, ensure it is configured and paired.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            devices.forEach { device ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(device.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "MAC: ${device.macAddress}", 
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (device.enabled) "Status: Active" else "Status: Dormant (Power Saving)",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (device.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                            )
                        }
                        Switch(
                            checked = !device.enabled, // Switch is ON for "Dormant (Power Saving)"
                            enabled = !busy,
                            onCheckedChange = { isDormant ->
                                busy = true
                                scope.launch(Dispatchers.IO) {
                                    WatchController.setDeviceDormant(context, device.macAddress, isDormant)
                                    refreshDevices()
                                    busy = false
                                    statusMessage = if (isDormant) {
                                        "${device.name} set to dormant. Reconnection loops stopped."
                                    } else {
                                        "${device.name} activated. Reconnecting..."
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}
