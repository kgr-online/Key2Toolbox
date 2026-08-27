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
import com.kgr.key2toolbox.modules.Dt2wController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun Dt2wScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(Dt2wController.State.UNKNOWN) }
    var persisted by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            state = Dt2wController.currentState()
            persisted = Dt2wController.isPersisted()
        }
    }

    ScreenScaffold(title = Screen.Dt2w.title, onBack = onBack) {
        Text(
            stringResource(R.string.dt2w_intro),
            style = MaterialTheme.typography.bodySmall
        )
        Text(stringResource(R.string.generic_live_state, state.name))
        Text(
            stringResource(
                R.string.generic_persisted,
                stringResource(if (persisted) R.string.generic_yes else R.string.generic_no)
            )
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.generic_enabled))
            Switch(
                checked = state == Dt2wController.State.ON,
                enabled = !busy,
                onCheckedChange = { enable ->
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        if (enable) {
                            Dt2wController.enablePersist(context)
                            Dt2wController.applyLiveOn()
                        } else {
                            Dt2wController.disablePersist()
                            Dt2wController.applyLiveOff()
                        }
                        state = Dt2wController.currentState()
                        persisted = Dt2wController.isPersisted()
                        busy = false
                        val persistedTag = context.getString(
                            if (persisted == enable) R.string.persisted_ok else R.string.persisted_mismatch
                        )
                        statusMessage = context.getString(
                            if (enable) R.string.status_dt2w_enabled else R.string.status_dt2w_disabled,
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
