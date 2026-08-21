package com.kgr.key2toolbox.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage // swap for your existing image loader if K2TB uses a different one
import com.kgr.key2toolbox.service.Key2AccessibilityService // adjust package if this lives elsewhere
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun SettingsScreen(
    currentVersionName: String, // pass in BuildConfig.VERSION_NAME from the caller
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var contributors by remember { mutableStateOf<List<GitHubContributor>?>(null) }
    var contributorsError by remember { mutableStateOf<String?>(null) }

    var accessibilityEnabled by remember { mutableStateOf(false) }
    var notificationEnabled by remember { mutableStateOf(false) }
    var rootEnabled by remember { mutableStateOf<Boolean?>(null) } // null = still checking

    var backupMessage by remember { mutableStateOf<String?>(null) }
    var backupError by remember { mutableStateOf<String?>(null) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) } // awaiting confirmation
    var selectedModules by remember { mutableStateOf(SettingsBackup.BackupModule.entries.toSet()) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val json = SettingsBackup.exportToJson(context, currentVersionName, selectedModules)
                    SettingsBackup.writeToUri(context, uri, json)
                }
                backupError = null
                backupMessage = "Settings exported successfully."
            } catch (e: Exception) {
                backupMessage = null
                backupError = "Export failed: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        pendingImportUri = uri // confirm before overwriting current settings
    }

    fun refreshStatuses() {
        accessibilityEnabled = isAccessibilityServiceEnabled(context)
        notificationEnabled = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
        scope.launch {
            rootEnabled = withContext(Dispatchers.IO) { Shell.getShell().isRoot }
        }
    }

    // Initial check, plus re-check whenever the user returns to the app
    // (e.g. after toggling Accessibility/Notification access in system settings)
    DisposableEffect(lifecycleOwner) {
        refreshStatuses()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshStatuses()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Load contributors once when the screen is first shown
    LaunchedEffect(Unit) {
        when (val result = withContext(Dispatchers.IO) { GitHubClient.fetchContributors() }) {
            is GitHubResult.Success -> contributors = result.data
            is GitHubResult.Error -> contributorsError = result.message
        }
    }

    pendingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text("Import settings?") },
            text = {
                Text(
                    "This will overwrite your current settings for the selected module(s) " +
                    "(keys present in the backup file) with the values from the selected " +
                    "file. Unselected modules are left untouched. This can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingImportUri = null
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                val json = SettingsBackup.readFromUri(context, uri)
                                SettingsBackup.importFromJson(context, json, selectedModules)
                            }
                            when (result) {
                                is SettingsBackup.ImportResult.Success -> {
                                    backupError = null
                                    backupMessage = "Restored ${result.restoredKeys} setting(s)." +
                                        (if (result.zramRestored) " ZRAM config restored — reboot to apply." else "")
                                }
                                is SettingsBackup.ImportResult.Failure -> {
                                    backupMessage = null
                                    backupError = result.message
                                }
                            }
                        } catch (e: Exception) {
                            backupMessage = null
                            backupError = "Import failed: ${e.javaClass.simpleName}: ${e.message}"
                        }
                    }
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportUri = null }) { Text("Cancel") }
            }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        SectionHeader("Updates")
        UpdateCard(
            currentVersion = currentVersionName,
            state = updateState,
            onCheckNow = {
                scope.launch {
                    updateState = UpdateState.Checking
                    updateState = UpdateChecker.checkForUpdate(currentVersionName)
                }
            },
            onInstall = { release ->
                scope.launch {
                    try {
                        var lastProgress = -2
                        val apk = UpdateChecker.downloadApk(context, release) { progress ->
                            if (progress != lastProgress) {
                                lastProgress = progress
                                updateState = UpdateState.Downloading(progress.coerceAtLeast(0))
                            }
                        }
                        updateState = UpdateState.Installing
                        val success = UpdateChecker.installApkAsRoot(apk)
                        updateState = if (success) {
                            UpdateState.Installed(release.tagName)
                        } else {
                            UpdateState.Failed("pm install failed — check logcat tag K2TB-Updater")
                        }
                    } catch (e: Exception) {
                        updateState = UpdateState.Failed(e.message ?: "Unknown error during update")
                    }
                }
            }
        )

        Spacer(Modifier.height(24.dp))

        SectionHeader("Quick Access")
        StatusSettingsRow(
            title = "Accessibility Service",
            subtitle = "Enable K2TB's accessibility features",
            icon = Icons.Default.Accessibility,
            enabled = accessibilityEnabled
        ) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        StatusSettingsRow(
            title = "Notification Access",
            subtitle = "Enable notification listener",
            icon = Icons.Default.Notifications,
            enabled = notificationEnabled
        ) {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        RootStatusRow(rootEnabled = rootEnabled)

        Spacer(Modifier.height(24.dp))

        SectionHeader("Backup & Restore")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Export your module settings (keyboard, LED, etc.) to a JSON file, " +
                    "or restore from a previous backup. Pick which modules to include below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                SettingsBackup.BackupModule.entries.forEach { module ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = module in selectedModules,
                            onCheckedChange = { checked ->
                                selectedModules = if (checked) {
                                    selectedModules + module
                                } else {
                                    selectedModules - module
                                }
                            }
                        )
                        Text(module.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = selectedModules.isNotEmpty(),
                        onClick = {
                            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
                            exportLauncher.launch("key2toolbox_backup_$timestamp.json")
                        }
                    ) {
                        Text("Export")
                    }
                    OutlinedButton(
                        enabled = selectedModules.isNotEmpty(),
                        onClick = {
                            importLauncher.launch(arrayOf("application/json"))
                        }
                    ) {
                        Text("Import")
                    }
                }
                if (selectedModules.isEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Select at least one module above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                backupMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                backupError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        SectionHeader("Contributors")
        ContributorsSection(
            contributors = contributors,
            error = contributorsError,
            onContributorClick = { url ->
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        )

        Spacer(Modifier.height(24.dp))
        SectionHeader("About")
        Text(
            "Key2Toolbox v$currentVersionName",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "github.com/kgr-online/Key2Toolbox",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 4.dp)
                .clickable {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/kgr-online/Key2Toolbox"))
                    )
                }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun StatusSettingsRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (enabled) "Enabled" else "Not enabled",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = if (enabled) Color(0xFF4CAF50) else Color(0xFFE57373)
            )
        }
    }
}

/** Root has no dedicated system settings page to link to (varies by APatch/FolkPatch/Magisk),
 *  so this is informational only, not clickable. */
@Composable
private fun RootStatusRow(rootEnabled: Boolean?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.padding(end = 16.dp))
        Column {
            Text("Root Access", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Required for system-level modules",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                when (rootEnabled) {
                    true -> "Enabled"
                    false -> "Not enabled"
                    null -> "Checking…"
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = when (rootEnabled) {
                    true -> Color(0xFF4CAF50)
                    false -> Color(0xFFE57373)
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/** Checks whether Key2AccessibilityService is currently enabled via the
 *  system's ENABLED_ACCESSIBILITY_SERVICES setting. */
/**
 * Whether Key2AccessibilityService is currently connected and running.
 * Reads the service's own self-reported state rather than Settings.Secure,
 * which was confirmed (via temporary logging) to return an empty string
 * in-process on this device even while `adb shell settings get secure
 * enabled_accessibility_services` showed the entry correctly from shell.
 */
private fun isAccessibilityServiceEnabled(context: android.content.Context): Boolean {
    return Key2AccessibilityService.isRunning
}

@Composable
private fun UpdateCard(
    currentVersion: String,
    state: UpdateState,
    onCheckNow: () -> Unit,
    onInstall: (GitHubRelease) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Current version", style = MaterialTheme.typography.bodySmall)
                    Text(currentVersion, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                }
                if (state !is UpdateState.Downloading && state !is UpdateState.Installing) {
                    Button(onClick = onCheckNow) {
                        Text("Check now")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            when (state) {
                is UpdateState.Idle -> Unit

                is UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Checking for updates…", style = MaterialTheme.typography.bodySmall)
                }

                is UpdateState.UpToDate -> Text(
                    "You're up to date.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                is UpdateState.UpdateAvailable -> Column {
                    Text(
                        "Update available: ${state.release.tagName}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (state.release.body.isNotBlank()) {
                        Text(
                            state.release.body.take(200),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { onInstall(state.release) }) {
                        Text("Download & install")
                    }
                }

                is UpdateState.Downloading -> Column {
                    Text("Downloading update…", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    if (state.progress >= 0) {
                        LinearProgressIndicator(
                            progress = { state.progress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                is UpdateState.Installing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Installing…", style = MaterialTheme.typography.bodySmall)
                }

                is UpdateState.Installed -> Text(
                    "Installed ${state.version}. Restart the app to finish.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )

                is UpdateState.Failed -> Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ContributorsSection(
    contributors: List<GitHubContributor>?,
    error: String?,
    onContributorClick: (String) -> Unit
) {
    when {
        error != null -> Text(
            "Couldn't load contributors: $error",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        contributors == null -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Loading contributors…", style = MaterialTheme.typography.bodySmall)
        }

        contributors.isEmpty() -> Text(
            "No contributor data available.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(contributors) { contributor ->
                ContributorChip(contributor) { onContributorClick(contributor.htmlUrl) }
            }
        }
    }
}

@Composable
private fun ContributorChip(contributor: GitHubContributor, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(72.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AsyncImage(
            model = contributor.avatarUrl,
            contentDescription = contributor.login,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            contributor.login,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
        Text(
            "${contributor.contributions} commits",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}
