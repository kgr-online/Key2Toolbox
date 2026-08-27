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
        Text(stringResource(R.string.ctrl_key_live_ctrl_keymap, keymapState.name))
        Text(
            stringResource(
                R.string.ctrl_key_ctrl_persisted,
                stringResource(if (ctrlPersisted) R.string.generic_yes else R.string.generic_no)
            )
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ctrl_key_enable_ctrl_remap))
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
                            !result.success -> context.getString(
                                if (enable) R.string.ctrl_key_status_ctrl_enable_failed
                                else R.string.ctrl_key_status_ctrl_disable_failed,
                                result.outString
                            )
                            needsReboot -> context.getString(
                                if (enable) R.string.ctrl_key_status_ctrl_staged
                                else R.string.ctrl_key_status_ctrl_unstaged
                            )
                            else -> context.getString(
                                if (enable) R.string.ctrl_key_status_ctrl_enabled
                                else R.string.ctrl_key_status_ctrl_disabled,
                                context.getString(
                                    if (ctrlPersisted) R.string.ctrl_key_persist_ok
                                    else R.string.ctrl_key_persist_fail
                                )
                            )
                        }
                    }
                }
            )
        }

        if (symApplicable) {
            Text(
                stringResource(R.string.ctrl_key_sym_desc),
                style = MaterialTheme.typography.bodySmall
            )
            Text(stringResource(R.string.ctrl_key_live_sym_keymap, symState.name))
            Text(
                stringResource(
                    R.string.ctrl_key_sym_fix_persisted,
                    stringResource(if (symPersisted) R.string.generic_yes else R.string.generic_no)
                )
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ctrl_key_enable_sym_fix))
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
                                !result.success -> context.getString(
                                    if (enable) R.string.ctrl_key_status_sym_enable_failed
                                    else R.string.ctrl_key_status_sym_disable_failed,
                                    result.outString
                                )
                                needsReboot -> context.getString(
                                    if (enable) R.string.ctrl_key_status_sym_staged
                                    else R.string.ctrl_key_status_sym_unstaged
                                )
                                else -> context.getString(
                                    if (enable) R.string.ctrl_key_status_sym_enabled
                                    else R.string.ctrl_key_status_sym_disabled
                                )
                            }
                        }
                    }
                )
            }
        } else {
            Text(
                stringResource(R.string.ctrl_key_sym_native),
                style = MaterialTheme.typography.bodySmall
            )
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}
