package com.kgr.key2toolbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kgr.key2toolbox.modules.PerformanceController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PerformanceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(false) }
    var selectedUpRateLimit by remember { mutableIntStateOf(PerformanceController.TUNED_UP_RATE_LIMIT) }
    var selectedBoostFreq by remember { mutableIntStateOf(PerformanceController.TUNED_INPUT_BOOST_FREQ) }
    var selectedBoostMs by remember { mutableIntStateOf(PerformanceController.TUNED_INPUT_BOOST_MS) }

    var liveUpRateLimit by remember { mutableStateOf<Int?>(null) }
    var liveBoostFreq by remember { mutableStateOf<Int?>(null) }
    var liveBoostMs by remember { mutableStateOf<Int?>(null) }

    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun refreshLiveValues() {
        scope.launch(Dispatchers.IO) {
            liveUpRateLimit = PerformanceController.currentLiveUpRateLimit()
            liveBoostFreq = PerformanceController.currentLiveInputBoostFreq()
            liveBoostMs = PerformanceController.currentLiveInputBoostMs()
        }
    }

    fun applySettings(newEnabled: Boolean, upRateLimit: Int, boostFreq: Int, boostMs: Int, applyLive: Boolean) {
        busy = true
        scope.launch(Dispatchers.IO) {
            PerformanceController.setSettings(context, newEnabled, upRateLimit, boostFreq, boostMs, applyLive)
            enabled = PerformanceController.isPersisted()
            refreshLiveValues()
            busy = false
            statusMessage = if (applyLive) {
                if (newEnabled) "Settings applied live and persisted for reboot." else "Default settings restored live."
            } else {
                "Settings saved. Will apply on next reboot."
            }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            enabled = PerformanceController.isPersisted()
            selectedUpRateLimit = PerformanceController.persistedUpRateLimit() ?: PerformanceController.TUNED_UP_RATE_LIMIT
            selectedBoostFreq = PerformanceController.persistedInputBoostFreq() ?: PerformanceController.TUNED_INPUT_BOOST_FREQ
            selectedBoostMs = PerformanceController.persistedInputBoostMs() ?: PerformanceController.TUNED_INPUT_BOOST_MS
            liveUpRateLimit = PerformanceController.currentLiveUpRateLimit()
            liveBoostFreq = PerformanceController.currentLiveInputBoostFreq()
            liveBoostMs = PerformanceController.currentLiveInputBoostMs()
        }
    }

    ScreenScaffold(title = Screen.Performance.title, onBack = onBack) {
        Text(
            "Fine-tune kernel scheduler scaling rates and CPU input boost to save battery. " +
            "Tuned settings relax the CPU LITTLE cluster scaling and lower the input boost intensity " +
            "during screen interaction.",
            style = MaterialTheme.typography.bodySmall
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Current Live Status", style = MaterialTheme.typography.titleMedium)
                Text("LITTLE Cluster up_rate_limit: " + (liveUpRateLimit?.let { "$it µs" } ?: "unknown"))
                Text("Input Boost Freq: " + (liveBoostFreq?.let { if (it == 0) "Off" else "${it / 1000} MHz" } ?: "unknown"))
                Text("Input Boost Duration: " + (liveBoostMs?.let { if (it == 0) "Off" else "$it ms" } ?: "unknown"))
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Enable at Boot")
            Switch(
                checked = enabled,
                enabled = !busy,
                onCheckedChange = {
                    enabled = it
                    applySettings(it, selectedUpRateLimit, selectedBoostFreq, selectedBoostMs, applyLive = false)
                }
            )
        }

        if (enabled) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("LITTLE Cluster Schedutil up_rate_limit", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            PerformanceController.DEFAULT_UP_RATE_LIMIT to "Default (500 µs)",
                            PerformanceController.TUNED_UP_RATE_LIMIT to "Tuned (2000 µs)"
                        ).forEach { (opt, label) ->
                            FilterChip(
                                selected = selectedUpRateLimit == opt,
                                enabled = !busy,
                                onClick = {
                                    selectedUpRateLimit = opt
                                    applySettings(true, opt, selectedBoostFreq, selectedBoostMs, applyLive = false)
                                },
                                label = { Text(label) }
                            )
                        }
                    }

                    Text("CAF Input Boost Frequency (LITTLE)", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            PerformanceController.DEFAULT_INPUT_BOOST_FREQ to "Default (1.4 GHz)",
                            PerformanceController.TUNED_INPUT_BOOST_FREQ to "Tuned (1.1 GHz)",
                            0 to "Off"
                        ).forEach { (opt, label) ->
                            FilterChip(
                                selected = selectedBoostFreq == opt,
                                enabled = !busy,
                                onClick = {
                                    selectedBoostFreq = opt
                                    applySettings(true, selectedUpRateLimit, opt, selectedBoostMs, applyLive = false)
                                },
                                label = { Text(label) }
                            )
                        }
                    }

                    Text("CAF Input Boost Duration", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            PerformanceController.DEFAULT_INPUT_BOOST_MS to "Default (40 ms)",
                            PerformanceController.TUNED_INPUT_BOOST_MS to "Tuned (20 ms)",
                            0 to "Off"
                        ).forEach { (opt, label) ->
                            FilterChip(
                                selected = selectedBoostMs == opt,
                                enabled = !busy,
                                onClick = {
                                    selectedBoostMs = opt
                                    applySettings(true, selectedUpRateLimit, selectedBoostFreq, opt, applyLive = false)
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                enabled = !busy,
                onClick = {
                    applySettings(enabled, selectedUpRateLimit, selectedBoostFreq, selectedBoostMs, applyLive = true)
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Apply live now")
            }
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}
