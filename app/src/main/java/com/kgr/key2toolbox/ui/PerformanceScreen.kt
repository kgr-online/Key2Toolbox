package com.kgr.key2toolbox.ui

import com.kgr.key2toolbox.R
import androidx.compose.ui.res.stringResource
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
                context.getString(
                    if (newEnabled) R.string.status_performance_applied_live else R.string.status_performance_restored_live
                )
            } else {
                context.getString(R.string.status_performance_saved)
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

    ScreenScaffold(title = stringResource(Screen.Performance.titleRes), onBack = onBack) {
        Text(
            stringResource(R.string.desc_performance),
            style = MaterialTheme.typography.bodySmall
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.performance_current_live_status), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.performance_up_rate_limit, liveUpRateLimit?.let { stringResource(R.string.generic_us, it) } ?: stringResource(R.string.generic_unknown)))
                Text(stringResource(R.string.performance_input_boost_freq, liveBoostFreq?.let { if (it == 0) stringResource(R.string.generic_state_off) else stringResource(R.string.generic_mhz, it / 1000) } ?: stringResource(R.string.generic_unknown)))
                Text(stringResource(R.string.performance_input_boost_duration, liveBoostMs?.let { if (it == 0) stringResource(R.string.generic_state_off) else stringResource(R.string.generic_ms, it) } ?: stringResource(R.string.generic_unknown)))
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.enable_at_boot))
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
                    Text(stringResource(R.string.performance_up_rate_limit_title), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            PerformanceController.DEFAULT_UP_RATE_LIMIT to stringResource(R.string.performance_up_rate_default),
                            PerformanceController.TUNED_UP_RATE_LIMIT to stringResource(R.string.performance_up_rate_tuned)
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

                    Text(stringResource(R.string.performance_boost_freq_title), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            PerformanceController.DEFAULT_INPUT_BOOST_FREQ to stringResource(R.string.performance_boost_freq_default),
                            PerformanceController.TUNED_INPUT_BOOST_FREQ to stringResource(R.string.performance_boost_freq_tuned),
                            0 to stringResource(R.string.generic_state_off)
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

                    Text(stringResource(R.string.performance_boost_duration_title), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            PerformanceController.DEFAULT_INPUT_BOOST_MS to stringResource(R.string.performance_boost_duration_default),
                            PerformanceController.TUNED_INPUT_BOOST_MS to stringResource(R.string.performance_boost_duration_tuned),
                            0 to stringResource(R.string.generic_state_off)
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
                Text(stringResource(R.string.performance_apply_live_now))
            }
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}
