package com.kgr.key2toolbox.ui

import android.content.Context
import android.os.Build
import android.view.WindowManager
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kgr.key2toolbox.R
import com.kgr.key2toolbox.modules.BlurSupportController
import com.kgr.key2toolbox.modules.RecentsController
import com.kgr.key2toolbox.modules.RecentsController.LayoutMode
import com.kgr.key2toolbox.modules.SlimRecentsController
import com.kgr.key2toolbox.modules.ToolbeltController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RecentsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(ToolbeltController.PREFS, android.content.Context.MODE_PRIVATE)
    }

    var xposedActive by remember { mutableStateOf(RecentsController.isXposedActive()) }
    // Is the module injected in the launcher (what the hooked Grid needs)? null until the root check returns.
    var launcherHooked by remember { mutableStateOf<Boolean?>(null) }
    var mode by remember { mutableStateOf(LayoutMode.STOCK) }
    var scrim by remember { mutableFloatStateOf(1f) }
    var scrimColorMode by remember { mutableStateOf(SlimRecentsController.scrimColorMode(prefs)) }
    var scrimOpacity by remember { mutableStateOf(SlimRecentsController.scrimOpacityPercent(prefs)) }
    var scrimBlur by remember { mutableStateOf(SlimRecentsController.scrimBlurPercent(prefs)) }
    var animPct by remember { mutableStateOf(SlimRecentsController.animDurationPercent(prefs)) }
    var gridCorner by remember { mutableStateOf(SlimRecentsController.gridCornerDp(prefs)) }
    var quiltCorner by remember { mutableStateOf(SlimRecentsController.quiltCornerDp(prefs)) }
    // Cross-window blur can be unavailable (battery saver, unsupported GPU path): say so instead of a dead slider.
    val blurSupported = remember {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).isCrossWindowBlurEnabled
    }

    // Optional module that makes this ROM advertise blur support (reboot needed).
    var blurModule by remember { mutableStateOf<Boolean?>(null) } // installed?
    var blurUpstream by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val bi = BlurSupportController.isInstalled()
            val bu = BlurSupportController.isUpstreamOnly()
            withContext(Dispatchers.Main) { blurModule = bi; blurUpstream = bu }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val m = RecentsController.getLayoutMode()
            val s = RecentsController.getScrimAlpha()
            val hooked = RecentsController.isLauncherHooked()
            withContext(Dispatchers.Main) {
                launcherHooked = hooked
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
                if (mode.isOverlay) {
                    Text(
                        stringResource(
                            if (mode == LayoutMode.MASONRY) R.string.recents_masonry_note
                            else R.string.recents_slim_note
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        // Grid falls back to our own overlay when the LSPosed module is not active (see
        // Key2AccessibilityService.openRecents); with the module, the launcher draws it.
        val gridOverlay = mode == LayoutMode.GRID && launcherHooked == false

        // Slim List / Masonry (and the Grid fallback) paint their own full-screen scrim in-process - no
        // launcher hook involved, so this is a separate control from the GRID/
        // STOCK transparency slider below.
        if (mode.isOverlay || gridOverlay) {
            DescriptionDivider()
            Text(
                stringResource(R.string.recents_slim_appearance_section),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            val scrimColorLabels = stringArrayResource(R.array.recents_slim_scrim_color_modes)
            PickerRow(
                label = stringResource(R.string.recents_slim_scrim_color),
                current = scrimColorLabels.getOrElse(scrimColorMode) { "$scrimColorMode" },
                options = listOf(0, 1).map { it to scrimColorLabels.getOrElse(it) { "$it" } },
                onPick = {
                    scrimColorMode = it
                    prefs.edit().putInt(SlimRecentsController.KEY_SCRIM_COLOR_MODE, it).apply()
                }
            )
            IntSliderRow(
                label = stringResource(R.string.recents_slim_scrim_opacity),
                value = scrimOpacity, valueText = "$scrimOpacity%", range = 15f..100f, steps = 16,
                onChange = { scrimOpacity = it }, onCommit = {
                    prefs.edit().putInt(SlimRecentsController.KEY_SCRIM_OPACITY, scrimOpacity).apply()
                }
            )
            IntSliderRow(
                label = stringResource(R.string.recents_slim_scrim_blur),
                value = scrimBlur, valueText = if (blurSupported) "$scrimBlur%" else "-",
                range = 0f..100f, steps = 19,
                onChange = { if (blurSupported) scrimBlur = it }, onCommit = {
                    if (blurSupported) prefs.edit().putInt(SlimRecentsController.KEY_SCRIM_BLUR, scrimBlur).apply()
                }
            )
            if (!blurSupported) {
                Text(
                    stringResource(R.string.recents_slim_blur_unsupported),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Offer the module while blur is unavailable, and keep it removable once installed.
            val installed = blurModule
            if (installed != null && (!blurSupported || installed)) {
                Text(
                    stringResource(R.string.recents_blur_module_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (installed && !blurSupported) {
                    Text(
                        stringResource(R.string.recents_blur_module_pending),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (!blurUpstream) {
                    OutlinedButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                if (installed) BlurSupportController.uninstall() else BlurSupportController.install()
                                val now = BlurSupportController.isInstalled()
                                withContext(Dispatchers.Main) { blurModule = now }
                            }
                        }
                    ) {
                        Text(
                            stringResource(
                                if (installed) R.string.recents_blur_module_remove
                                else R.string.recents_blur_module_install
                            )
                        )
                    }
                }
            }
            // Corner radius only applies to the tile layouts; the vertical list has its own fixed pills.
            if (gridOverlay) {
                IntSliderRow(
                    label = stringResource(R.string.recents_slim_corner_grid),
                    value = gridCorner,
                    valueText = if (gridCorner == 0) stringResource(R.string.recents_slim_corner_square) else "$gridCorner dp",
                    range = 0f..SlimRecentsController.MAX_CORNER_DP.toFloat(), steps = SlimRecentsController.MAX_CORNER_DP - 1,
                    onChange = { gridCorner = it }, onCommit = {
                        prefs.edit().putInt(SlimRecentsController.KEY_GRID_CORNER_DP, gridCorner).apply()
                    }
                )
            }
            if (mode == LayoutMode.MASONRY) {
                IntSliderRow(
                    label = stringResource(R.string.recents_slim_corner_quilt),
                    value = quiltCorner,
                    valueText = if (quiltCorner == 0) stringResource(R.string.recents_slim_corner_square) else "$quiltCorner dp",
                    range = 0f..SlimRecentsController.MAX_CORNER_DP.toFloat(), steps = SlimRecentsController.MAX_CORNER_DP - 1,
                    onChange = { quiltCorner = it }, onCommit = {
                        prefs.edit().putInt(SlimRecentsController.KEY_QUILT_CORNER_DP, quiltCorner).apply()
                    }
                )
            }
            IntSliderRow(
                label = stringResource(R.string.recents_slim_anim_duration),
                value = animPct,
                valueText = if (animPct == 0) stringResource(R.string.recents_slim_anim_off) else "$animPct%",
                range = 0f..200f, steps = 19,
                onChange = { animPct = it }, onCommit = {
                    prefs.edit().putInt(SlimRecentsController.KEY_ANIM_DURATION, animPct).apply()
                }
            )
            Text(
                stringResource(R.string.recents_slim_anim_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // LSPosed is only involved in Grid mode - the overlay modes and Stock don't touch it.
        if (mode == LayoutMode.GRID) {
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
                            if (launcherHooked == true) R.string.recents_xposed_ok
                            else R.string.recents_xposed_missing
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (launcherHooked == true) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                    // Which path Grid is using right now.
                    Text(
                        stringResource(
                            if (launcherHooked == true) R.string.recents_grid_using_hook
                            else R.string.recents_grid_using_overlay
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (launcherHooked == false) {
                        Text(
                            stringResource(R.string.recents_xposed_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // The Overview scrim is a launcher property - only Grid (and Stock) use it.
        if (mode == LayoutMode.GRID || mode == LayoutMode.STOCK) {
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
