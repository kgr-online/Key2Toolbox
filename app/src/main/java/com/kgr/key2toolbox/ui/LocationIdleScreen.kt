package com.kgr.key2toolbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kgr.key2toolbox.R
import com.kgr.key2toolbox.modules.LocationIdleController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LocationIdleScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var minutes by remember { mutableIntStateOf(LocationIdleController.DEFAULT_TIMEOUT) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun apply(newEnabled: Boolean, newMinutes: Int) {
        busy = true
        scope.launch(Dispatchers.IO) {
            LocationIdleController.setEnabled(context, newEnabled, newMinutes)
            enabled = LocationIdleController.isPersisted()
            running = LocationIdleController.isRunning()
            busy = false
            statusMessage = if (newEnabled)
                context.getString(R.string.location_idle_status_enabled, newMinutes)
            else context.getString(R.string.location_idle_status_disabled)
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            enabled = LocationIdleController.isPersisted()
            running = LocationIdleController.isRunning()
            LocationIdleController.persistedTimeout()?.let { minutes = it }
            // Self-heal a dead OR stale (content-mismatched) daemon - see
            // ExtraDimController/ExtraDimScreen for why a bare "is it running"
            // check isn't enough.
            if (enabled && !LocationIdleController.isHealthy(context, minutes)) {
                LocationIdleController.setEnabled(context, true, minutes)
                running = LocationIdleController.isRunning()
            }
        }
    }

    ScreenScaffold(title = Screen.LocationIdle.title, onBack = onBack) {
        val onOff = stringResource(if (enabled) R.string.generic_state_on else R.string.generic_state_off)
        Text(
            if (enabled && !running)
                stringResource(R.string.generic_state_pending_boot, onOff)
            else stringResource(R.string.generic_state, onOff)
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.generic_enabled))
            Switch(
                checked = enabled,
                enabled = !busy,
                onCheckedChange = { apply(it, minutes) }
            )
        }

        Text(stringResource(R.string.bt_idle_turn_off_after), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LocationIdleController.TIMEOUT_OPTIONS.forEach { opt ->
                FilterChip(
                    selected = minutes == opt,
                    enabled = !busy,
                    onClick = {
                        minutes = opt
                        if (enabled) apply(true, opt)
                    },
                    label = {
                        Text(
                            if (opt >= 60) stringResource(R.string.generic_hours, opt / 60)
                            else stringResource(R.string.generic_minutes, opt)
                        )
                    }
                )
            }
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        DescriptionDivider()
        Text(
            stringResource(R.string.desc_location_idle),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
