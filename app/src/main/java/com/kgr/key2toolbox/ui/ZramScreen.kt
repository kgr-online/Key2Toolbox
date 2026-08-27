package com.kgr.key2toolbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kgr.key2toolbox.R
import com.kgr.key2toolbox.modules.ZramController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ZramScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedSize by remember { mutableStateOf(ZramController.Size.OFF) }
    var availableAlgorithms by remember { mutableStateOf(listOf(ZramController.DEFAULT_ALGORITHM)) }
    var selectedAlgorithm by remember { mutableStateOf(ZramController.DEFAULT_ALGORITHM) }
    var selectedSwappiness by remember { mutableStateOf(60) }
    var liveSizeBytes by remember { mutableStateOf<Long?>(null) }
    var liveAlgorithm by remember { mutableStateOf<String?>(null) }
    var liveSwappiness by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showApplyWarning by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var romDefaults by remember { mutableStateOf<ZramController.RomDefaults?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            romDefaults = ZramController.romDefaults()
            selectedSize = ZramController.persistedSize()
                ?: ZramController.currentLiveSize()
                ?: ZramController.Size.OFF
            availableAlgorithms = ZramController.availableAlgorithms()
            selectedAlgorithm = ZramController.persistedAlgorithm()
                ?: ZramController.currentAlgorithm()
                ?: ZramController.DEFAULT_ALGORITHM
            selectedSwappiness = ZramController.persistedSwappiness()
                ?: ZramController.currentLiveSwappiness()
                ?: 60
            liveSizeBytes = ZramController.currentLiveSizeBytes()
            liveAlgorithm = ZramController.currentAlgorithm()
            liveSwappiness = ZramController.currentLiveSwappiness()
        }
    }

    ScreenScaffold(title = Screen.Zram.title, onBack = onBack) {
        val liveSizeText = liveSizeBytes?.let {
            stringResource(R.string.generic_mb, (it / 1024 / 1024).toInt())
        } ?: stringResource(R.string.generic_unknown_inactive)
        Text(stringResource(R.string.zram_current_live_size, liveSizeText))
        Text(
            stringResource(
                R.string.zram_current_live_algorithm,
                liveAlgorithm ?: stringResource(R.string.generic_unknown)
            )
        )
        Text(
            stringResource(
                R.string.zram_current_live_swappiness,
                liveSwappiness?.toString() ?: stringResource(R.string.generic_unknown)
            )
        )
        val persistedText = if (selectedSize == ZramController.Size.OFF)
            stringResource(R.string.zram_size_off)
        else
            stringResource(
                R.string.zram_persisted_summary,
                selectedSize.label, selectedAlgorithm, selectedSwappiness
            )
        Text(stringResource(R.string.zram_persisted_setting, persistedText))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.zram_compression_algorithm), style = MaterialTheme.typography.titleMedium)

                availableAlgorithms.forEach { algo ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selectedAlgorithm == algo,
                            enabled = !busy,
                            onClick = {
                                selectedAlgorithm = algo
                                if (selectedSize != ZramController.Size.OFF) {
                                    busy = true
                                    scope.launch(Dispatchers.IO) {
                                        ZramController.setSize(context, selectedSize, algo, selectedSwappiness, applyLive = false)
                                        busy = false
                                        statusMessage = context.getString(R.string.status_zram_algorithm, algo)
                                    }
                                }
                            }
                        )
                        Text(
                            if (algo == romDefaults?.algorithm) stringResource(R.string.zram_rom_default, algo) else algo,
                            color = if (algo == romDefaults?.algorithm) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.generic_size), style = MaterialTheme.typography.titleMedium)

                ZramController.Size.entries.forEach { size ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selectedSize == size,
                            enabled = !busy,
                            onClick = {
                                selectedSize = size
                                busy = true
                                scope.launch(Dispatchers.IO) {
                                    ZramController.setSize(context, size, selectedAlgorithm, selectedSwappiness, applyLive = false)
                                    busy = false
                                    statusMessage = context.getString(R.string.status_zram_size, size.label)
                                }
                            }
                        )
                        Text(
                            if (size == romDefaults?.size) stringResource(R.string.zram_rom_default, size.label) else size.label,
                            color = if (size == romDefaults?.size) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.zram_swappiness), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.desc_zram_swappiness),
                    style = MaterialTheme.typography.bodySmall
                )
                romDefaults?.swappiness?.let {
                    Text(
                        stringResource(R.string.zram_rom_default_value, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Slider(
                        value = selectedSwappiness.toFloat(),
                        onValueChange = { selectedSwappiness = it.toInt() },
                        onValueChangeFinished = {
                            if (selectedSize != ZramController.Size.OFF) {
                                busy = true
                                scope.launch(Dispatchers.IO) {
                                    ZramController.setSize(
                                        context,
                                        selectedSize,
                                        selectedAlgorithm,
                                        selectedSwappiness,
                                        applyLive = false
                                    )
                                    liveSwappiness = ZramController.currentLiveSwappiness()
                                    busy = false
                                    statusMessage = context.getString(R.string.status_zram_swappiness, selectedSwappiness)
                                }
                            }
                        },
                        valueRange = 0f..100f,
                        enabled = !busy && selectedSize != ZramController.Size.OFF,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = selectedSwappiness.toString(),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }

        Button(
            enabled = !busy && selectedSize != ZramController.Size.OFF,
            onClick = { showApplyWarning = true }
        ) {
            Text(stringResource(R.string.zram_apply_now))
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        if (showApplyWarning) {
            AlertDialog(
                onDismissRequest = { showApplyWarning = false },
                title = { Text(stringResource(R.string.zram_apply_dialog_title)) },
                text = { Text(stringResource(R.string.zram_apply_dialog_desc)) },
                confirmButton = {
                    TextButton(onClick = {
                        showApplyWarning = false
                        busy = true
                        scope.launch(Dispatchers.IO) {
                            ZramController.setSize(context, selectedSize, selectedAlgorithm, selectedSwappiness, applyLive = true)
                            liveSizeBytes = ZramController.currentLiveSizeBytes()
                            liveAlgorithm = ZramController.currentAlgorithm()
                            liveSwappiness = ZramController.currentLiveSwappiness()
                            busy = false
                            statusMessage = context.getString(
                                R.string.status_zram_applied_live,
                                selectedSize.label, selectedAlgorithm, selectedSwappiness
                            )
                        }
                    }) { Text(stringResource(R.string.generic_apply)) }
                },
                dismissButton = {
                    TextButton(onClick = { showApplyWarning = false }) {
                        Text(stringResource(R.string.generic_cancel))
                    }
                }
            )
        }
    }
}
