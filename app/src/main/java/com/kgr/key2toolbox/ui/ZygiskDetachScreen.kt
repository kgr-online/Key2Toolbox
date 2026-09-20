package com.kgr.key2toolbox.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.kgr.key2toolbox.modules.ZygiskDetachController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ZygiskDetachState(
    val enabled: Boolean = false,
    val version: String? = null,
    val needsReboot: Boolean = false,
    /** null = can't tell (non-Magisk root); only an explicit false warns. */
    val zygisk: Boolean? = null,
    val denylisted: Boolean = false
)

private data class ZygiskAppRow(val pkg: String, val label: String, val installed: Boolean)

/** [detached] is null when the CLI couldn't read the list (missing or corrupted). */
private class ZygiskLoadedApps(val detached: List<String>?, val rows: List<ZygiskAppRow>)

@Composable
fun ZygiskDetachScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(ZygiskDetachState()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Detach list editor. `detached` is what's applied on disk; `selection` is the pending edit.
    var detached by remember { mutableStateOf<List<String>?>(emptyList<String>()) }
    var apps by remember { mutableStateOf<List<ZygiskAppRow>>(emptyList<ZygiskAppRow>()) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet<String>()) }
    var loadingApps by remember { mutableStateOf(false) }
    var includeSystem by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            state = withContext(Dispatchers.IO) {
                ZygiskDetachState(
                    enabled = ZygiskDetachController.isEnabled(),
                    version = ZygiskDetachController.installedVersion(),
                    needsReboot = ZygiskDetachController.requiresReboot(),
                    zygisk = ZygiskDetachController.zygiskEnabled(),
                    denylisted = ZygiskDetachController.playStoreDenylisted()
                )
            }
        }
    }

    /**
     * Re-reads the applied list and the installed apps. Labels come from the
     * in-process PackageManager as a best effort and fall back to the package
     * name; the package list itself always comes from the root shell.
     * Detached packages are always shown, even when the User/All filter would
     * hide them or they're no longer installed, so they can be re-attached.
     */
    suspend fun loadApps(resetSelection: Boolean) {
        loadingApps = true
        val withSystem = includeSystem
        val loaded = withContext(Dispatchers.IO) {
            val current = ZygiskDetachController.detachedPackages()
            val all = ZygiskDetachController.installedPackages(includeSystem = true).toSet()
            val listed = if (withSystem) all
            else ZygiskDetachController.installedPackages(includeSystem = false).toSet()
            val pm = context.packageManager
            ZygiskLoadedApps(
                current,
                (listed + (current ?: emptyList<String>())).map { pkg ->
                    val label = runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    }.getOrNull() ?: pkg
                    ZygiskAppRow(pkg, label, installed = pkg in all)
                }
            )
        }
        detached = loaded.detached
        apps = loaded.rows
        if (resetSelection) selection = loaded.detached?.toSet() ?: emptySet<String>()
        loadingApps = false
    }

    fun toggle(pkg: String) {
        selection = if (pkg in selection) selection - pkg else selection + pkg
    }

    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(state.enabled) { if (state.enabled) loadApps(resetSelection = true) }
    LaunchedEffect(includeSystem) { if (state.enabled) loadApps(resetSelection = false) }

    val detachedSet = remember(detached) { detached?.toSet() ?: emptySet<String>() }
    val visibleApps = remember(apps, detachedSet, query) {
        val q = query.trim().lowercase()
        apps.filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
            .sortedWith(compareByDescending<ZygiskAppRow> { it.pkg in detachedSet }.thenBy { it.label.lowercase() })
    }
    // An unreadable list always allows Apply, so a corrupted file can be replaced.
    val dirty = detached == null || selection != detachedSet

    ScreenScaffold(
        title = stringResource(R.string.title_zygisk_detach),
        onBack = onBack
    ) {
        if (state.needsReboot) {
            ZygiskWarningCard(stringResource(R.string.zygisk_detach_reboot_required))
        }
        if (state.zygisk == false) {
            ZygiskWarningCard(stringResource(R.string.zygisk_detach_no_zygisk))
        }
        if (state.enabled && state.denylisted) {
            ZygiskWarningCard(stringResource(R.string.zygisk_detach_denylisted)) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            message = null
                            error = null
                            val result = withContext(Dispatchers.IO) {
                                ZygiskDetachController.removePlayStoreFromDenylist()
                            }
                            if (result.success) {
                                message = context.getString(R.string.status_zygisk_detach_denylist_removed)
                            } else {
                                error = result.outString
                            }
                            busy = false
                            refresh()
                        }
                    }
                ) { Text(stringResource(R.string.zygisk_detach_remove_from_denylist)) }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.zygisk_detach_enable), style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.version?.let { stringResource(R.string.zygisk_detach_module_version, it) }
                            ?: stringResource(R.string.zygisk_detach_module_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.enabled,
                    enabled = !busy,
                    onCheckedChange = { checked ->
                        scope.launch {
                            busy = true
                            message = null
                            error = null
                            if (checked) {
                                message = context.getString(R.string.status_zygisk_detach_installing_module)
                                val result = withContext(Dispatchers.IO) { ZygiskDetachController.enable(context) }
                                if (result.success) {
                                    message = context.getString(R.string.status_zygisk_detach_enabled)
                                } else {
                                    message = null
                                    error = context.getString(R.string.status_zygisk_detach_module_failed, result.outString)
                                }
                            } else {
                                val result = withContext(Dispatchers.IO) { ZygiskDetachController.disable() }
                                if (result.success) {
                                    message = context.getString(R.string.status_zygisk_detach_disabled)
                                } else {
                                    error = result.outString
                                }
                            }
                            busy = false
                            refresh()
                        }
                    }
                )
            }
        }

        if (busy || message != null || error != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                }
                message?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (state.enabled) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.zygisk_detach_section_apps),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                stringResource(R.string.zygisk_detach_selected_count, selection.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            enabled = !busy && selection.isNotEmpty(),
                            onClick = { selection = emptySet<String>() }
                        ) { Text(stringResource(R.string.generic_clear)) }
                    }

                    if (detached == null) {
                        Text(
                            stringResource(R.string.zygisk_detach_list_unreadable),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.generic_search_apps)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ZygiskFilterButton(
                            selected = !includeSystem,
                            label = stringResource(R.string.zygisk_detach_filter_user),
                            onClick = { includeSystem = false }
                        )
                        ZygiskFilterButton(
                            selected = includeSystem,
                            label = stringResource(R.string.zygisk_detach_filter_all),
                            onClick = { includeSystem = true }
                        )
                    }

                    when {
                        loadingApps && apps.isEmpty() -> {
                            Text(
                                stringResource(R.string.zygisk_detach_loading_apps),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        visibleApps.isEmpty() -> {
                            Text(
                                stringResource(R.string.zygisk_detach_no_match, query.trim()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        else -> {
                            // Bounded height: this sits inside ScreenScaffold's vertical scroll.
                            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                                items(visibleApps, key = { it.pkg }) { row ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !busy) { toggle(row.pkg) }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(checked = row.pkg in selection, onCheckedChange = null)
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(row.label, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                if (row.installed) row.pkg
                                                else row.pkg + " - " + stringResource(R.string.zygisk_detach_not_installed),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Button(
                        enabled = dirty && !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                message = null
                                error = null
                                val toApply = selection
                                val result = withContext(Dispatchers.IO) {
                                    ZygiskDetachController.applyDetached(toApply)
                                }
                                if (result.success) {
                                    message = context.getString(R.string.status_zygisk_detach_applied, toApply.size)
                                } else {
                                    error = context.getString(R.string.status_zygisk_detach_apply_failed, result.outString)
                                }
                                // Re-read from the CLI so the UI shows what was really written; keep the
                                // pending selection if the write failed.
                                loadApps(resetSelection = result.success)
                                busy = false
                                refresh()
                            }
                        }
                    ) { Text(stringResource(R.string.generic_apply)) }
                }
            }
        } else {
            Text(
                stringResource(R.string.zygisk_detach_disabled_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        DescriptionDivider()
        Text(
            stringResource(R.string.desc_zygisk_detach),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ZygiskFilterButton(selected: Boolean, label: String, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

@Composable
private fun ZygiskWarningCard(text: String, action: (@Composable () -> Unit)? = null) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text, style = MaterialTheme.typography.bodySmall)
                action?.invoke()
            }
        }
    }
}
