package com.kgr.key2toolbox.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kgr.key2toolbox.R
import com.kgr.key2toolbox.modules.RecentsController
import com.kgr.key2toolbox.modules.RecentsController.LayoutMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RecentsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()

    var xposedActive by remember { mutableStateOf(RecentsController.isXposedActive()) }
    var mode by remember { mutableStateOf(LayoutMode.STOCK) }
    var scrim by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val m = RecentsController.getLayoutMode()
            val s = RecentsController.getScrimAlpha()
            withContext(Dispatchers.Main) {
                mode = m
                scrim = s
                xposedActive = RecentsController.isXposedActive()
            }
        }
    }

    fun setModeAsync(newMode: LayoutMode) {
        mode = newMode
        scope.launch(Dispatchers.IO) { RecentsController.setLayoutMode(newMode) }
    }

    ScreenScaffold(title = Screen.Recents.title, onBack = onBack) {
        Text(
            stringResource(R.string.recents_intro),
            style = MaterialTheme.typography.bodySmall
        )

        DescriptionDivider()
        Text(
            stringResource(R.string.recents_section_lsposed),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    stringResource(
                        if (xposedActive) R.string.recents_xposed_ok
                        else R.string.recents_xposed_missing
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (xposedActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                if (!xposedActive) {
                    Text(
                        stringResource(R.string.recents_xposed_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    stringResource(R.string.recents_grid_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    stringResource(R.string.recents_grid_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                val options = listOf(
                    LayoutMode.STOCK to R.string.recents_mode_stock,
                    LayoutMode.GRID to R.string.recents_mode_grid,
                    LayoutMode.MASONRY to R.string.recents_mode_masonry,
                    LayoutMode.SLIM_LIST to R.string.recents_mode_slim
                )
                options.forEach { (value, labelRes) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { setModeAsync(value) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = mode == value, onClick = { setModeAsync(value) })
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(labelRes), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (mode == LayoutMode.SLIM_LIST) {
                    Text(
                        stringResource(R.string.recents_slim_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.recents_transparency_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "${(scrim * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    stringResource(R.string.recents_transparency_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = scrim,
                    onValueChange = { scrim = it },
                    onValueChangeFinished = {
                        scope.launch(Dispatchers.IO) {
                            RecentsController.setScrimAlpha(scrim)
                            RecentsController.restartLauncher()
                        }
                    },
                    valueRange = 0f..1f
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { scope.launch(Dispatchers.IO) { RecentsController.restartLauncher() } },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.recents_restart_launcher)) }
            OutlinedButton(
                onClick = { scope.launch(Dispatchers.IO) { RecentsController.restartSystemUi() } },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.recents_restart_systemui)) }
        }

        DescriptionDivider()

        Text(
            stringResource(R.string.recents_debug_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
