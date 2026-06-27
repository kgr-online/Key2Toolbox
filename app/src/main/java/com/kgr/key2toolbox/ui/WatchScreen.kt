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
import androidx.compose.ui.unit.dp
import com.kgr.key2toolbox.modules.WatchController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WatchScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(WatchController.State.UNKNOWN) }
    var names by remember { mutableStateOf<List<String>>(emptyList()) }
    var persistedDormant by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            state = WatchController.currentState()
            names = WatchController.nodeNames()
            persistedDormant = WatchController.isPersistedDormant()
        }
    }

    ScreenScaffold(title = Screen.Watch.title, onBack = onBack) {
        Text(
            "Stops a paired Galaxy/Wear watch from draining the battery with constant " +
                "Bluetooth reconnect attempts when it's out of range - without unpairing. " +
                "Set dormant when you're not using the watch; set active to reconnect. " +
                "Bluetooth stays free for speakers either way.",
            style = MaterialTheme.typography.bodySmall
        )

        when (state) {
            WatchController.State.NONE ->
                Text("No paired watch found in Play Services.")

            else -> {
                if (names.isNotEmpty()) Text("Watch: ${names.joinToString(", ")}")
                Text("State: ${state.name}")
                Text("Stays dormant after reboot: ${if (persistedDormant) "Yes" else "No"}")

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Active")
                    Switch(
                        checked = state == WatchController.State.ACTIVE,
                        enabled = !busy && state != WatchController.State.UNKNOWN,
                        onCheckedChange = { active ->
                            busy = true
                            scope.launch(Dispatchers.IO) {
                                WatchController.setDormant(context, dormant = !active)
                                state = WatchController.currentState()
                                persistedDormant = WatchController.isPersistedDormant()
                                busy = false
                                statusMessage =
                                    "Watch set ${if (active) "active" else "dormant"}. " +
                                        "Play Services was restarted to reload the change."
                            }
                        }
                    )
                }

                Text(
                    "Toggling restarts Google Play Services, which may briefly interrupt " +
                        "notifications.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}
