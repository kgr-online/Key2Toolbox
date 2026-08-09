package com.kgr.key2toolbox.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kgr.key2toolbox.modules.DenylistController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun List<AppRow>.sortedForDisplay(): List<AppRow> =
    sortedWith(
        compareByDescending<AppRow> { it.magiskDenied || it.zygiskHideHidden }
            .thenBy { it.label.lowercase() }
    )

private data class AppRow(
    val packageName: String,
    val label: String,
    val magiskDenied: Boolean,
    val zygiskHideHidden: Boolean
)

@Composable
fun DenylistScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(DenylistController.isEnabled(context)) }
    var loading by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<AppRow>>(emptyList()) }

    var magiskAvailable by remember { mutableStateOf(false) }
    var magiskEnforced by remember { mutableStateOf(false) }
    var zygiskHideInstalled by remember { mutableStateOf(false) }
    var hmaOssInstalled by remember { mutableStateOf(false) }

    suspend fun refresh() {
        loading = true
        withContext(Dispatchers.IO) {
            magiskAvailable = DenylistController.isMagiskAvailable()
            magiskEnforced = magiskAvailable && DenylistController.isMagiskDenylistEnforced()
            zygiskHideInstalled = DenylistController.isZygiskHideInstalled()
            hmaOssInstalled = DenylistController.isHmaOssInstalled()

            val magiskEntries = if (magiskAvailable) DenylistController.magiskDenylistEntries() else emptyList()
            val zygiskConfig = if (zygiskHideInstalled) DenylistController.zygiskHideConfig() else emptyMap()

            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)

            apps = installed
                .filter { showSystemApps || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                .map { appInfo ->
                    val label = try {
                        pm.getApplicationLabel(appInfo).toString()
                    } catch (e: Exception) {
                        appInfo.packageName
                    }
                    val entry = DenylistController.entryFor(appInfo.packageName, magiskEntries, zygiskConfig)
                    AppRow(appInfo.packageName, label, entry.magiskDenied, entry.zygiskHideHidden)
                }
                .sortedForDisplay()
        }
        loading = false
    }

    LaunchedEffect(enabled, showSystemApps) {
        if (enabled) refresh()
    }

    fun toggleMagisk(pkg: String, denied: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) {
                if (denied) {
                    DenylistController.addToMagiskDenylist(context, pkg)
                } else {
                    DenylistController.removeFromMagiskDenylist(pkg)
                }
            }
            apps = apps.map { if (it.packageName == pkg) it.copy(magiskDenied = denied) else it }
                .sortedForDisplay()
        }
    }

    fun toggleZygiskHide(pkg: String, hidden: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) {
                DenylistController.setZygiskHideHidden(context, pkg, hidden)
            }
            apps = apps.map { if (it.packageName == pkg) it.copy(zygiskHideHidden = hidden) else it }
                .sortedForDisplay()
        }
    }

    fun openHmaOss() {
        val (pkg, activity) = DenylistController.hmaOssComponent()
        val intent = Intent().apply { setClassName(pkg, activity) }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // HMA-OSS's manager activity name changed or app isn't launchable -
            // nothing else to do here beyond not crashing.
        }
    }

    ScreenScaffold(title = Screen.DenylistManager.title, onBack = onBack) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Manage from K2TB", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Adds/removes apps from Magisk's DenyList and Zygisk-Hide's " +
                                "config together. Leave off if you manage these " +
                                "independently (FolkPatch, APatch, HMA, etc.) - K2TB " +
                                "won't touch either list unless this is on.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            DenylistController.setEnabled(context, it)
                        }
                    )
                }

                if (enabled) {
                    Spacer(Modifier.height(8.dp))
                    if (!magiskAvailable) {
                        Text(
                            "Magisk not detected - only Zygisk-Hide will be managed here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else if (!magiskEnforced) {
                        Text(
                            "Magisk's DenyList enforcement is currently OFF globally - " +
                                "toggles below will still add/remove entries, but they " +
                                "won't take effect until enforcement is turned on in Magisk.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (!zygiskHideInstalled) {
                        Text(
                            "Zygisk-Hide not detected - only Magisk's DenyList will be managed here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        if (enabled && hmaOssInstalled) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("HMA-OSS", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "App-list hiding, settings hiding, and install-source " +
                                "spoofing are managed separately in HMA-OSS's own app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = ::openHmaOss) { Text("Open") }
                }
            }
        }

        if (enabled) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search apps") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Show system apps", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = showSystemApps, onCheckedChange = { showSystemApps = it })
            }

            if (loading) {
                Text("Loading apps...", style = MaterialTheme.typography.bodyMedium)
            } else {
                val filtered = apps.filter {
                    searchQuery.isBlank() ||
                        it.label.contains(searchQuery, ignoreCase = true) ||
                        it.packageName.contains(searchQuery, ignoreCase = true)
                }
                Text(
                    "${filtered.size} apps",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                filtered.forEach { app ->
                    AppRowCard(
                        app = app,
                        showMagisk = magiskAvailable,
                        showZygiskHide = zygiskHideInstalled,
                        onMagiskToggle = { toggleMagisk(app.packageName, it) },
                        onZygiskHideToggle = { toggleZygiskHide(app.packageName, it) }
                    )
                }
            }
        } else {
            Text(
                "Turn on \"Manage from K2TB\" above to see and edit your app list.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AppRowCard(
    app: AppRow,
    showMagisk: Boolean,
    showZygiskHide: Boolean,
    onMagiskToggle: (Boolean) -> Unit,
    onZygiskHideToggle: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(app.label, style = MaterialTheme.typography.titleSmall)
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (showMagisk) {
                    Row {
                        Text("Magisk", style = MaterialTheme.typography.bodySmall)
                        Switch(checked = app.magiskDenied, onCheckedChange = onMagiskToggle)
                    }
                }
                if (showZygiskHide) {
                    Row {
                        Text("Zygisk-Hide", style = MaterialTheme.typography.bodySmall)
                        Switch(checked = app.zygiskHideHidden, onCheckedChange = onZygiskHideToggle)
                    }
                }
            }
        }
    }
}
