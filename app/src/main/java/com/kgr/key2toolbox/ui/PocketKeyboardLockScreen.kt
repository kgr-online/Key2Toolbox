package com.kgr.key2toolbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kgr.key2toolbox.R
import com.kgr.key2toolbox.modules.PocketKeyboardLockController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PocketKeyboardLockScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun apply(newEnabled: Boolean) {
        busy = true
        scope.launch(Dispatchers.IO) {
            PocketKeyboardLockController.setEnabled(context, newEnabled)
            enabled = PocketKeyboardLockController.isPersisted()
            running = PocketKeyboardLockController.isRunning()
            busy = false
            statusMessage = context.getString(
                if (newEnabled) R.string.status_pocket_kbd_lock_enabled
                else R.string.status_pocket_kbd_lock_disabled
            )
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            enabled = PocketKeyboardLockController.isPersisted()
            running = PocketKeyboardLockController.isRunning()
            // Self-heal a dead OR stale (content-mismatched) daemon - see
            // ExtraDimController/ExtraDimScreen for why a bare "is it
            // running" check isn't enough.
            if (enabled && !PocketKeyboardLockController.isHealthy(context)) {
                PocketKeyboardLockController.setEnabled(context, true)
                running = PocketKeyboardLockController.isRunning()
            }
        }
    }

    ScreenScaffold(title = Screen.PocketKeyboardLock.title, onBack = onBack) {
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
                onCheckedChange = { apply(it) }
            )
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        DescriptionDivider()
        Text(
            stringResource(R.string.desc_pocket_kbd_lock),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            stringResource(R.string.pocket_kbd_lock_pin_keyboard_note),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
