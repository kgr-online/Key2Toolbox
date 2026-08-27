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
import com.kgr.key2toolbox.modules.KbdLightController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun KbdLightScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var persisted by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            persisted = KbdLightController.isPersisted()
            running = KbdLightController.isRunningLive()
        }
    }

    ScreenScaffold(title = Screen.KbdLight.title, onBack = onBack) {
        Text(
            stringResource(
                R.string.generic_persisted,
                stringResource(if (persisted) R.string.generic_yes else R.string.generic_no)
            )
        )
        Text(
            stringResource(
                R.string.generic_currently_running,
                stringResource(if (running) R.string.generic_yes else R.string.generic_no)
            )
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.generic_enabled))
            Switch(
                checked = persisted,
                enabled = !busy,
                onCheckedChange = { enable ->
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        if (enable) {
                            KbdLightController.enable(context, startLiveNow = true)
                        } else {
                            KbdLightController.disable(stopLiveNow = true)
                        }
                        persisted = KbdLightController.isPersisted()
                        running = KbdLightController.isRunningLive()
                        busy = false
                        val persistedTag = context.getString(
                            if (persisted == enable) R.string.persisted_ok
                            else R.string.persisted_mismatch
                        )
                        statusMessage = context.getString(
                            if (enable) R.string.status_kbd_light_enabled
                            else R.string.status_kbd_light_disabled,
                            persistedTag
                        )
                    }
                }
            )
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}
