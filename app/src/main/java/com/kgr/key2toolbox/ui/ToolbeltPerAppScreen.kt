package com.kgr.key2toolbox.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.kgr.key2toolbox.R
import com.kgr.key2toolbox.modules.ToolbeltController
import com.kgr.key2toolbox.modules.ToolbeltPolicy.AppConfig as ToolbeltAppConfig
import com.kgr.key2toolbox.modules.ToolbeltPolicy.PerAppColor
import com.kgr.key2toolbox.modules.ToolbeltPolicy.PerAppVisibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private data class ToolbeltAppEntry(
    val label: String,
    val pkg: String,
    val icon: ImageBitmap?,
)

@Composable
fun ToolbeltPerAppScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(ToolbeltController.PREFS, Context.MODE_PRIVATE)
    }

    var apps by remember { mutableStateOf<List<ToolbeltAppEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    var showOnlyCustomized by remember { mutableStateOf(false) }
    val configs = remember { mutableStateMapOf<String, ToolbeltAppConfig>() }
    var editingApp by remember { mutableStateOf<ToolbeltAppEntry?>(null) }

    LaunchedEffect(Unit) {
        val currentConfigs = withContext(Dispatchers.IO) {
            ToolbeltController.getPerAppConfigs(prefs)
        }
        configs.putAll(currentConfigs)

        val loaded = withContext(Dispatchers.IO) {
            loadAppEntries(context)
        }
        apps = loaded
    }

    val allApps = apps ?: emptyList()
    val customCount = configs.count { !it.value.isDefault }

    val filtered = remember(allApps, query, showOnlyCustomized, configs.toMap()) {
        val q = query.trim().lowercase(Locale.ROOT)
        allApps.filter { app ->
            val matchesQuery = q.isEmpty() ||
                app.label.lowercase(Locale.ROOT).contains(q) ||
                app.pkg.lowercase(Locale.ROOT).contains(q)
            val isCustom = configs[app.pkg]?.isDefault == false
            val matchesFilter = !showOnlyCustomized || isCustom
            matchesQuery && matchesFilter
        }
    }

    ScreenScaffold(title = stringResource(R.string.toolbelt_per_app_title), onBack = onBack) {
        Text(
            stringResource(R.string.toolbelt_per_app_settings_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.generic_search_apps)) }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = !showOnlyCustomized,
                onClick = { showOnlyCustomized = false },
                label = { Text(stringResource(R.string.toolbelt_per_app_filter_all, allApps.size)) }
            )
            FilterChip(
                selected = showOnlyCustomized,
                onClick = { showOnlyCustomized = true },
                label = { Text(stringResource(R.string.toolbelt_per_app_filter_custom, customCount)) }
            )
        }

        if (apps == null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filtered, key = { it.pkg }) { app ->
                    val appConfig = configs[app.pkg] ?: ToolbeltAppConfig()
                    AppConfigRow(
                        app = app,
                        config = appConfig,
                        onClick = { editingApp = app }
                    )
                }
            }
        }
    }

    editingApp?.let { target ->
        val currentConfig = configs[target.pkg] ?: ToolbeltAppConfig()
        ToolbeltAppConfigDialog(
            app = target,
            initialConfig = currentConfig,
            onDismiss = { editingApp = null },
            onSave = { updated ->
                configs[target.pkg] = updated
                ToolbeltController.setAppConfig(context, target.pkg, updated)
                editingApp = null
            },
            onReset = {
                configs.remove(target.pkg)
                ToolbeltController.setAppConfig(context, target.pkg, ToolbeltAppConfig())
                editingApp = null
            }
        )
    }
}

@Composable
private fun AppConfigRow(
    app: ToolbeltAppEntry,
    config: ToolbeltAppConfig,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (!config.isDefault)
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (app.icon != null) {
                Image(
                    bitmap = app.icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = app.label.take(1).uppercase(Locale.ROOT),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.pkg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(2.dp))

                val colorLabel = when (config.color) {
                    PerAppColor.DEFAULT -> stringResource(R.string.toolbelt_per_app_color_default)
                    PerAppColor.BLACK -> stringResource(R.string.toolbelt_per_app_color_black)
                    PerAppColor.MATERIAL -> stringResource(R.string.toolbelt_per_app_color_material)
                    PerAppColor.FOLLOW_APP -> stringResource(R.string.toolbelt_per_app_color_follow)
                }
                val visLabel = when (config.visibility) {
                    PerAppVisibility.DEFAULT -> stringResource(R.string.toolbelt_per_app_vis_default)
                    PerAppVisibility.HIDE_IN_FULLSCREEN -> stringResource(R.string.toolbelt_per_app_vis_hide_fullscreen)
                    PerAppVisibility.KEEP_IN_FULLSCREEN -> stringResource(R.string.toolbelt_per_app_vis_keep_fullscreen)
                }

                if (!config.isDefault) {
                    Text(
                        text = stringResource(R.string.toolbelt_per_app_summary_format, colorLabel, visLabel),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    Text(
                        text = stringResource(R.string.toolbelt_per_app_vis_default),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolbeltAppConfigDialog(
    app: ToolbeltAppEntry,
    initialConfig: ToolbeltAppConfig,
    onDismiss: () -> Unit,
    onSave: (ToolbeltAppConfig) -> Unit,
    onReset: () -> Unit,
) {
    var selectedColor by remember { mutableStateOf(initialConfig.color) }
    var selectedVisibility by remember { mutableStateOf(initialConfig.visibility) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (app.icon != null) {
                    Image(
                        bitmap = app.icon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text = app.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.toolbelt_per_app_color_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )

                val colorOptions = listOf(
                    PerAppColor.DEFAULT to stringResource(R.string.toolbelt_per_app_color_default),
                    PerAppColor.MATERIAL to stringResource(R.string.toolbelt_per_app_color_material),
                    PerAppColor.BLACK to stringResource(R.string.toolbelt_per_app_color_black),
                    PerAppColor.FOLLOW_APP to stringResource(R.string.toolbelt_per_app_color_follow),
                )

                colorOptions.forEach { (option, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedColor = option }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedColor == option,
                            onClick = { selectedColor = option }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(text = label, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                DescriptionDivider()

                Text(
                    text = stringResource(R.string.toolbelt_per_app_visibility_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )

                val visOptions = listOf(
                    PerAppVisibility.DEFAULT to stringResource(R.string.toolbelt_per_app_vis_default),
                    PerAppVisibility.HIDE_IN_FULLSCREEN to stringResource(R.string.toolbelt_per_app_vis_hide_fullscreen),
                    PerAppVisibility.KEEP_IN_FULLSCREEN to stringResource(R.string.toolbelt_per_app_vis_keep_fullscreen),
                )

                visOptions.forEach { (option, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedVisibility = option }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedVisibility == option,
                            onClick = { selectedVisibility = option }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(text = label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(ToolbeltAppConfig(selectedColor, selectedVisibility))
            }) {
                Text(stringResource(R.string.generic_apply))
            }
        },
        dismissButton = {
            Row {
                if (!initialConfig.isDefault) {
                    TextButton(onClick = onReset) {
                        Text(stringResource(R.string.toolbelt_per_app_reset))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.generic_cancel))
                }
            }
        }
    )
}

private fun loadAppEntries(context: Context): List<ToolbeltAppEntry> {
    val pm = context.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    val activities = pm.queryIntentActivities(launcherIntent, 0) +
        pm.queryIntentActivities(homeIntent, 0)

    return activities.asSequence()
        .map { it.activityInfo.packageName }
        .filter { it != context.packageName }
        .distinct()
        .map { pkg ->
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                pkg
            }
            val icon = try {
                val d = pm.getApplicationIcon(pkg)
                d.toBitmap(width = 72, height = 72).asImageBitmap()
            } catch (_: Exception) {
                null
            }
            ToolbeltAppEntry(label = label, pkg = pkg, icon = icon)
        }
        .sortedBy { it.label.lowercase(Locale.ROOT) }
        .toList()
}
