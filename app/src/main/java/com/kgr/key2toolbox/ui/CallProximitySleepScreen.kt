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
import com.kgr.key2toolbox.modules.CallProximitySleepController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CallProximitySleepScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var debounceMs by remember { mutableIntStateOf(CallProximitySleepController.DEFAULT_DEBOUNCE_MS) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun apply(newEnabled: Boolean, newDebounceMs: Int) {
        busy = true
        scope.launch(Dispatchers.IO) {
            CallProximitySleepController.setEnabled(context, newEnabled, newDebounceMs)
            enabled = CallProximitySleepController.isPersisted()
            running = CallProximitySleepController.isRunning()
            busy = false
            statusMessage = if (newEnabled)
                context.getString(R.string.status_call_proximity_sleep_enabled, newDebounceMs)
            else context.getString(R.string.status_call_proximity_sleep_disabled)
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            enabled = CallProximitySleepController.isPersisted()
            running = CallProximitySleepController.isRunning()
            CallProximitySleepController.persistedDebounceMs()?.let { debounceMs = it }
            // Self-heal a dead OR stale (content-mismatched) daemon - see
            // ExtraDimController/ExtraDimScreen for why a bare "is it
            // running" check isn't enough.
            if (enabled && !CallProximitySleepController.isHealthy(context, debounceMs)) {
                CallProximitySleepController.setEnabled(context, true, debounceMs)
                running = CallProximitySleepController.isRunning()
            }
        }
    }

    ScreenScaffold(title = Screen.CallProximitySleep.title, onBack = onBack) {
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
                onCheckedChange = { apply(it, debounceMs) }
            )
        }

        Text(stringResource(R.string.call_proximity_sleep_debounce_label), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CallProximitySleepController.DEBOUNCE_OPTIONS.forEach { opt ->
                FilterChip(
                    selected = debounceMs == opt,
                    enabled = !busy,
                    onClick = {
                        debounceMs = opt
                        if (enabled) apply(true, opt)
                    },
                    label = { Text(stringResource(R.string.generic_ms, opt)) }
                )
            }
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        DescriptionDivider()
        Text(
            stringResource(R.string.desc_call_proximity_sleep),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
