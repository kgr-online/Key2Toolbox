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
import com.kgr.key2toolbox.modules.CtrlKeyController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CtrlKeyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var keymapState by remember { mutableStateOf(CtrlKeyController.State.UNKNOWN) }
    var ctrlPersisted by remember { mutableStateOf(false) }
    var symState by remember { mutableStateOf(CtrlKeyController.SymState.UNKNOWN) }
    var symPersisted by remember { mutableStateOf(false) }
    var symApplicable by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            keymapState = CtrlKeyController.currentKeymapState()
            ctrlPersisted = CtrlKeyController.isPersisted()
            symState = CtrlKeyController.currentSymState()
            symPersisted = CtrlKeyController.isSymPersisted()
            symApplicable = CtrlKeyController.isSymFixApplicable()
        }
    }

    ScreenScaffold(title = Screen.CtrlKey.title, onBack = onBack) {
        Text("Live Ctrl keymap: ${keymapState.name}")
        Text("Ctrl persisted: ${if (ctrlPersisted) "Yes" else "No"}")

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Enable Ctrl remap")
            Switch(
                checked = ctrlPersisted,
                enabled = !busy,
                onCheckedChange = { enable ->
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        val result = if (enable) {
                            CtrlKeyController.applyOn(context)
                        } else {
                            CtrlKeyController.applyOff(context)
                        }
                        val needsReboot = CtrlKeyController.requiresReboot()
                        keymapState = CtrlKeyController.currentKeymapState()
                        ctrlPersisted = CtrlKeyController.isPersisted()
                        busy = false

                        statusMessage = when {
                            !result.success ->
                                "Failed to ${if (enable) "enable" else "disable"} Ctrl remap: ${result.outString}"
                            needsReboot ->
                                "Ctrl remap ${if (enable) "staged" else "un-staged"}. " +
                                    "Reboot your device for this to take effect."
                            else ->
                                "Ctrl remap ${if (enable) "enabled" else "disabled"}. " +
                                    "Persisted: ${if (ctrlPersisted) "yes" else "NO - check service.d write permissions"}."
                        }
                    }
                }
            )
        }

        if (symApplicable) {
            Text(
                "Fixes the physical SYM key, which some ROM builds map incorrectly " +
                    "(the symbol picker won't open until this is fixed).",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Live SYM keymap: ${symState.name}")
            Text("SYM fix persisted: ${if (symPersisted) "Yes" else "No"}")

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enable SYM fix")
                Switch(
                    checked = symPersisted,
                    enabled = !busy,
                    onCheckedChange = { enable ->
                        busy = true
                        scope.launch(Dispatchers.IO) {
                            val result = if (enable) {
                                CtrlKeyController.applySymOn(context)
                            } else {
                                CtrlKeyController.applySymOff(context)
                            }
                            val needsReboot = CtrlKeyController.requiresReboot()
                            symState = CtrlKeyController.currentSymState()
                            symPersisted = CtrlKeyController.isSymPersisted()
                            busy = false

                            statusMessage = when {
                                !result.success ->
                                    "Failed to ${if (enable) "enable" else "disable"} SYM fix: ${result.outString}"
                                needsReboot ->
                                    "SYM fix ${if (enable) "staged" else "un-staged"}. " +
                                        "Reboot your device for this to take effect."
                                else ->
                                    "SYM fix ${if (enable) "enabled" else "disabled"}."
                            }
                        }
                    }
                )
            }
        } else {
            Text(
                "SYM already works natively on this device - no fix needed.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}
