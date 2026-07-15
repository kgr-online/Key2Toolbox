package com.kgr.key2toolbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kgr.key2toolbox.modules.K2PFController
import com.kgr.key2toolbox.modules.ZramController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun K2PFScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()

    var installed by remember { mutableStateOf<Boolean?>(null) }  // null = loading
    var productProps by remember { mutableStateOf(false) }
    var a2dp by remember { mutableStateOf(false) }
    var volumeSteps by remember { mutableStateOf(false) }
    var tripleBuffer by remember { mutableStateOf(false) }
    var bgAppLimit by remember { mutableStateOf(false) }
    var swappiness by remember { mutableStateOf<Int?>(null) }
    var k2tbOwnsSwappiness by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            installed = K2PFController.isInstalled()
            if (installed == true) {
                productProps = K2PFController.isProductPropsEnabled()
                a2dp = K2PFController.isA2dpOffloadEnabled()
                volumeSteps = K2PFController.isVolumeStepsEnabled()
                tripleBuffer = K2PFController.isTripleBufferingEnabled()
                bgAppLimit = K2PFController.isBgAppLimitEnabled()
                swappiness = K2PFController.serviceSwappiness()
                k2tbOwnsSwappiness = ZramController.isPersisted()
            }
        }
    }

    ScreenScaffold(title = Screen.K2PF.title, onBack = onBack) {
        when (installed) {
            null -> Text("Checking for bb-prodfix module…")

            false -> {
                Text(
                    "The bb-prodfix (BBProdFix) module is not installed.",
                    color = Color(0xFFE57373)
                )
                Text(
                    "Install the BBProdFix Magisk/APatch module to access these settings. " +
                        "Once installed, return here to manage its tweaks.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            true -> {
                Text(
                    "Manage BBProdFix module settings here. Changes to system props " +
                        "require a reboot to take effect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                K2PFToggle(
                    title = "BlackBerry Device Identity",
                    description = "Forces ro.product.* props to BlackBerry values " +
                        "(brand=blackberry, model=KEY2, manufacturer=BlackBerry). " +
                        "Required by some BB-specific apps to verify they're running " +
                        "on a Key2. Your ROM may already set these correctly.",
                    checked = productProps,
                    busy = busy,
                    rebootRequired = true
                ) { checked ->
                    productProps = checked
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        K2PFController.setProductProps(checked)
                        busy = false
                    }
                }

                K2PFToggle(
                    title = "Bluetooth A2DP Offload",
                    description = "Moves Bluetooth audio processing to the DSP. " +
                        "Improves audio quality and battery life on BT headphones. " +
                        "Persist props apply live; audio stack restart may be needed.",
                    checked = a2dp,
                    busy = busy,
                    rebootRequired = false
                ) { checked ->
                    a2dp = checked
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        K2PFController.setA2dpOffload(checked)
                        busy = false
                    }
                }

                K2PFToggle(
                    title = "Extended Volume Steps",
                    description = "14 call volume steps and 30 media volume steps " +
                        "instead of the Android defaults (7 and 15).",
                    checked = volumeSteps,
                    busy = busy,
                    rebootRequired = true
                ) { checked ->
                    volumeSteps = checked
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        K2PFController.setVolumeSteps(checked)
                        busy = false
                    }
                }

                K2PFToggle(
                    title = "SurfaceFlinger Triple Buffering",
                    description = "Allows SurfaceFlinger to hold 3 frame buffers " +
                        "instead of 2. Can reduce jank under GPU load.",
                    checked = tripleBuffer,
                    busy = busy,
                    rebootRequired = true
                ) { checked ->
                    tripleBuffer = checked
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        K2PFController.setTripleBuffering(checked)
                        busy = false
                    }
                }

                K2PFToggle(
                    title = "Background App Limit (60)",
                    description = "Tells the Qualcomm process manager to allow up to " +
                        "60 background apps before killing them. Stock Android is " +
                        "much more aggressive.",
                    checked = bgAppLimit,
                    busy = busy,
                    rebootRequired = true
                ) { checked ->
                    bgAppLimit = checked
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        K2PFController.setBgAppLimit(checked)
                        busy = false
                    }
                }

                // Swappiness card — special handling since Key2Toolbox may own it
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Swappiness", style = MaterialTheme.typography.titleMedium)

                        if (k2tbOwnsSwappiness) {
                            Text(
                                "Key2 Toolbox is managing swappiness via the ZRAM module. " +
                                    "BBProdFix swappiness is disabled — configure it in the ZRAM screen.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                "Current value in BBProdFix service.sh: ${swappiness ?: "not set"}. " +
                                    "Changes apply on next boot.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            listOf(
                                60 to "60 — Conservative (recommended)",
                                80 to "80 — Balanced",
                                100 to "100 — Aggressive"
                            ).forEach { (value, label) ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    androidx.compose.material3.RadioButton(
                                        selected = swappiness == value,
                                        enabled = !busy,
                                        onClick = {
                                            swappiness = value
                                            busy = true
                                            scope.launch(Dispatchers.IO) {
                                                K2PFController.setSwappiness(value)
                                                busy = false
                                            }
                                        }
                                    )
                                    Text(label)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun K2PFToggle(
    title: String,
    description: String,
    checked: Boolean,
    busy: Boolean,
    rebootRequired: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (rebootRequired) {
                    Text("Reboot required", style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFFB74D))
                }
            }
            Switch(checked = checked, enabled = !busy, onCheckedChange = onToggle)
        }
    }
}
