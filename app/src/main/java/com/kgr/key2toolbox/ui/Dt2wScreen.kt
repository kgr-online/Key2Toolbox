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
import androidx.compose.ui.graphics.Color
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

    // Raw driver-reported state - diagnostic only. On this touch driver,
    // the gesture status fields (wg_enabled / wakeup_gesture.double_tap)
    // only update to reflect the armed state after the screen actually
    // suspends - not immediately after the echo write while the screen
    // is still on. Do NOT drive the switch's checked state off this, or
    // it will always snap back right after being toggled.
    var state by remember { mutableStateOf(Dt2wController.State.UNKNOWN) }

    // What the user actually asked for - this drives the switch position.
    var intendedOn by remember { mutableStateOf(false) }

    // null = unknown/couldn't read, true = driver reports gesture capable,
    // false = driver's f11/f12 capability flags are both down, meaning
    // writes to the gesture node are a no-op regardless of value - a
    // known kernel/firmware-level issue on this device, also reproducible
    // via the stock Settings app's own gesture toggle. Not fixable from
    // here; surface it plainly instead of pretending the toggle works.
    var gestureCapable by remember { mutableStateOf<Boolean?>(null) }

    var persisted by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            state = Dt2wController.currentState()
            intendedOn = state == Dt2wController.State.ON
            persisted = Dt2wController.isPersisted()
            gestureCapable = Dt2wController.isGestureCapable()
        }
    }

    ScreenScaffold(title = Screen.Dt2w.title, onBack = onBack) {
        Text(
            stringResource(R.string.dt2w_intro),
            style = MaterialTheme.typography.bodySmall
        )

        if (gestureCapable == false) {
            Text(
                "The touch driver currently reports no gesture-wake capability " +
                    "(a known Synaptics-chip kernel quirk on this device - see the " +
                    "K2TB DT2W notes). Enabling below will report success but won't " +
                    "actually arm double-tap. A reboot has sometimes cleared it " +
                    "temporarily. Not something this app can fix.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFCC5500)
            )
        }

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
                checked = intendedOn,
                enabled = !busy,
                onCheckedChange = { enable ->
                    busy = true
                    intendedOn = enable // optimistic - reverted below only on actual write failure
                    scope.launch(Dispatchers.IO) {
                        val result = if (enable) {
                            Dt2wController.enablePersist(context)
                            Dt2wController.applyLiveOn()
                        } else {
                            Dt2wController.disablePersist()
                            Dt2wController.applyLiveOff()
                        }

                        // Diagnostic readback only - won't reflect armed
                        // state until the next suspend, so it is NOT used
                        // to drive the switch.
                        state = Dt2wController.currentState()
                        persisted = Dt2wController.isPersisted()
                        gestureCapable = Dt2wController.isGestureCapable()
                        busy = false

                        if (!result.success) {
                            intendedOn = !enable
                        }

                        val persistedTag = context.getString(
                            if (persisted == enable) R.string.persisted_ok else R.string.persisted_mismatch
                        )
                        statusMessage = if (gestureCapable == false) {
                            "Write sent, but the driver isn't reporting gesture capability right now - see warning above."
                        } else {
                            context.getString(
                                if (enable) R.string.status_dt2w_enabled else R.string.status_dt2w_disabled,
                                persistedTag
                            )
                        }
                    }
                }
            )
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}
