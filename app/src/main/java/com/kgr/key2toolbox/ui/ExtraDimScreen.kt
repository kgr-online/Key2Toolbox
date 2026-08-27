package com.kgr.key2toolbox.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.kgr.key2toolbox.R
import com.kgr.key2toolbox.modules.ExtraDimController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun formatMinutes(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return "%02d:%02d".format(h, m)
}

@Composable
fun ExtraDimScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var activated by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(50f) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    var scheduleEnabled by remember { mutableStateOf(false) }
    var scheduleRunning by remember { mutableStateOf(false) }
    var startMinutes by remember { mutableIntStateOf(ExtraDimController.DEFAULT_START_MINUTES) }
    var endMinutes by remember { mutableIntStateOf(ExtraDimController.DEFAULT_END_MINUTES) }
    var scheduleBusy by remember { mutableStateOf(false) }
    var editingStart by remember { mutableStateOf(false) }
    var editingEnd by remember { mutableStateOf(false) }

    fun applySchedule(newEnabled: Boolean, newStart: Int, newEnd: Int) {
        scheduleBusy = true
        scope.launch(Dispatchers.IO) {
            ExtraDimController.setScheduleEnabled(context, newEnabled, newStart, newEnd)
            scheduleEnabled = ExtraDimController.isScheduleEnabled()
            scheduleRunning = ExtraDimController.isScheduleRunning()
            scheduleBusy = false
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            activated = ExtraDimController.isActivated()
            level = ExtraDimController.getDimmingLevel().toFloat()
            scheduleEnabled = ExtraDimController.isScheduleEnabled()
            scheduleRunning = ExtraDimController.isScheduleRunning()
            if (scheduleEnabled) {
                startMinutes = ExtraDimController.persistedStartMinutes()
                endMinutes = ExtraDimController.persistedEndMinutes()
                // The daemon can die mid-session (e.g. the root shell that launched it
                // got recycled), or be alive but running a stale script left over from
                // an older app version / a changed window (which a bare "is it running"
                // check can't see - it loops forever either way). Self-heal on either
                // case instead of just reporting "not running" passively.
                if (!ExtraDimController.isScheduleHealthy(context, startMinutes, endMinutes)) {
                    ExtraDimController.setScheduleEnabled(context, true, startMinutes, endMinutes)
                    scheduleRunning = ExtraDimController.isScheduleRunning()
                }
            }
        }
    }

    ScreenScaffold(title = Screen.ExtraDim.title, onBack = onBack) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.extra_dim_activated))
            Switch(
                checked = activated,
                enabled = !busy,
                onCheckedChange = { enable ->
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        ExtraDimController.setActivated(enable)
                        activated = ExtraDimController.isActivated()
                        busy = false
                        statusMessage = context.getString(
                            if (enable) R.string.status_extra_dim_activated
                            else R.string.status_extra_dim_deactivated
                        )
                    }
                }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                stringResource(R.string.extra_dim_intensity, level.toInt()),
                style = MaterialTheme.typography.titleSmall
            )
            Slider(
                value = level,
                enabled = !busy,
                valueRange = 0f..100f,
                onValueChange = { level = it },
                onValueChangeFinished = {
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        ExtraDimController.setDimmingLevel(level.toInt())
                        level = ExtraDimController.getDimmingLevel().toFloat()
                        busy = false
                        statusMessage =
                            context.getString(R.string.status_extra_dim_intensity, level.toInt())
                    }
                }
            )
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        Text(
            stringResource(R.string.extra_dim_auto_night),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            stringResource(R.string.desc_extra_dim_schedule),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            if (scheduleEnabled && !scheduleRunning)
                stringResource(
                    R.string.extra_dim_schedule_pending_boot,
                    stringResource(R.string.generic_state_on)
                )
            else
                stringResource(
                    R.string.extra_dim_schedule_state,
                    stringResource(if (scheduleEnabled) R.string.generic_state_on else R.string.generic_state_off)
                )
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.generic_enabled))
            Switch(
                checked = scheduleEnabled,
                enabled = !scheduleBusy,
                onCheckedChange = { applySchedule(it, startMinutes, endMinutes) }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !scheduleBusy) { editingStart = true }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.extra_dim_starts_at), style = MaterialTheme.typography.titleSmall)
            Text(formatMinutes(startMinutes), style = MaterialTheme.typography.titleMedium)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !scheduleBusy) { editingEnd = true }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.extra_dim_ends_at), style = MaterialTheme.typography.titleSmall)
            Text(formatMinutes(endMinutes), style = MaterialTheme.typography.titleMedium)
        }

        DescriptionDivider()
        Text(
            stringResource(R.string.desc_extra_dim),
            style = MaterialTheme.typography.bodySmall
        )
    }

    if (editingStart) {
        TimePickerDialog(
            initialMinutes = startMinutes,
            onDismiss = { editingStart = false },
            onConfirm = { newMinutes ->
                startMinutes = newMinutes
                editingStart = false
                if (scheduleEnabled) applySchedule(true, newMinutes, endMinutes)
            }
        )
    }

    if (editingEnd) {
        TimePickerDialog(
            initialMinutes = endMinutes,
            onDismiss = { editingEnd = false },
            onConfirm = { newMinutes ->
                endMinutes = newMinutes
                editingEnd = false
                if (scheduleEnabled) applySchedule(true, startMinutes, newMinutes)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialMinutes / 60,
        initialMinute = initialMinutes % 60,
        is24Hour = true
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        // The dial needs more width than Compose's "platform default" dialog width
        // budgets for, which was clipping the right side of the clock face -
        // this is the standard fix for wide dialog content like TimePicker/DatePicker.
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.width(IntrinsicSize.Min),
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) {
                Text(stringResource(R.string.generic_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.generic_cancel)) }
        }
    )
}
