package com.kgr.key2toolbox.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kgr.key2toolbox.modules.AppInfo
import com.kgr.key2toolbox.modules.FilterMode
import com.kgr.key2toolbox.modules.PlayStoreTaggerViewModel
import com.kgr.key2toolbox.modules.TagMode
import com.kgr.key2toolbox.modules.TaggerUiState

@Composable
fun PlayStoreTaggerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: PlayStoreTaggerViewModel = viewModel()
    val state by vm.uiState.collectAsState()

    LaunchedEffect(Unit) { vm.load(context) }

    var showConfirm by remember { mutableStateOf(false) }

    ScreenScaffold(title = Screen.PlayStoreTagger.title, onBack = onBack) {
        when (val s = state) {
            is TaggerUiState.Loading -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Loading apps…")
                }
            }

            is TaggerUiState.NoRoot -> {
                Text(
                    "Root access not available. Grant root to Key2 Toolbox and try again.",
                    color = Color(0xFFE57373)
                )
                Button(onClick = { vm.load(context) }) { Text("Retry") }
            }

            is TaggerUiState.Error -> {
                Text("Error: ${s.message}", color = Color(0xFFE57373))
                Button(onClick = { vm.load(context) }) { Text("Retry") }
            }

            is TaggerUiState.Tagging -> {
                TaggingPanel(progress = s.progress, total = s.total, currentApp = s.currentApp, log = s.log)
            }

            is TaggerUiState.Done -> {
                val success = s.results.values.count { it == null }
                val fail = s.results.size - success
                val verb = if (s.tagMode == TagMode.TAG) "tagged" else "untagged"
                Text(
                    "Done — $success $verb${if (fail > 0) ", $fail failed" else ""}",
                    color = if (fail == 0) Color(0xFF81C784) else Color(0xFFE57373)
                )
                LogPanel(log = s.log)
                TextButton(onClick = { vm.dismissResults() }) { Text("Back to list") }
            }

            is TaggerUiState.Ready -> {
                ReadyContent(
                    state = s,
                    selectedCount = vm.selectedCount(),
                    onTagModeChange = { vm.setTagMode(it) },
                    onFilterChange = { vm.setFilter(it) },
                    onQueryChange = { vm.setQuery(it) },
                    onToggleSystem = { vm.setShowSystem(!s.showSystem) },
                    onSelectAll = { vm.selectAll() },
                    onClearSelection = { vm.clearSelection() },
                    onToggle = { vm.toggleSelection(it) },
                    onTagClick = { showConfirm = true },
                    onRefresh = { vm.load(context) }
                )
            }
        }
    }

    // Confirmation dialog
    if (showConfirm) {
        val count = vm.selectedCount()
        val s = state
        val mode = if (s is TaggerUiState.Ready) s.tagMode else TagMode.TAG
        val verb = if (mode == TagMode.TAG) "Tag" else "Untag"
        val desc = if (mode == TagMode.TAG)
            "Set installer to com.android.vending for $count selected app${if (count != 1) "s" else ""}?"
        else
            "Clear installer tag for $count selected app${if (count != 1) "s" else ""}? They will show as sideloaded."
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("$verb $count app${if (count != 1) "s" else ""}") },
            text = { Text(desc) },
            confirmButton = {
                Button(onClick = { showConfirm = false; vm.tagSelected() }) { Text(verb) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ReadyContent(
    state: TaggerUiState.Ready,
    selectedCount: Int,
    onTagModeChange: (TagMode) -> Unit,
    onFilterChange: (FilterMode) -> Unit,
    onQueryChange: (String) -> Unit,
    onToggleSystem: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onToggle: (String) -> Unit,
    onTagClick: () -> Unit,
    onRefresh: () -> Unit
) {
    // Tag / Untag mode
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = state.tagMode == TagMode.TAG,
            onClick = { onTagModeChange(TagMode.TAG) },
            label = { Text("Tag as Play Store") }
        )
        FilterChip(
            selected = state.tagMode == TagMode.UNTAG,
            onClick = { onTagModeChange(TagMode.UNTAG) },
            label = { Text("Remove tag") }
        )
    }

    // Search field
    OutlinedTextField(
        value = state.query,
        onValueChange = onQueryChange,
        label = { Text("Search") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )

    // Filter chips + actions
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = state.filter == FilterMode.NON_PLAY,
            onClick = { onFilterChange(FilterMode.NON_PLAY) },
            label = { Text("Non-Play") }
        )
        FilterChip(
            selected = state.filter == FilterMode.ALL,
            onClick = { onFilterChange(FilterMode.ALL) },
            label = { Text("All") }
        )
        FilterChip(
            selected = state.showSystem,
            onClick = onToggleSystem,
            label = { Text("System") }
        )
    }

    // Selection controls
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onSelectAll) { Text("Select all") }
        TextButton(onClick = onClearSelection) { Text("Clear") }
        TextButton(onClick = onRefresh) { Text("Refresh") }
    }

    // Count / selection status
    Text(
        "${state.apps.size} apps" +
            if (selectedCount > 0) " · $selectedCount selected" else "",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // Tag button — only shown when something is selected
    if (selectedCount > 0) {
        val verb = if (state.tagMode == TagMode.TAG) "Tag" else "Untag"
        Button(onClick = onTagClick, modifier = Modifier.fillMaxWidth()) {
            Text("$verb $selectedCount app${if (selectedCount != 1) "s" else ""}" +
                if (state.tagMode == TagMode.TAG) " as Play Store" else " (clear installer)")
        }
    }

    // Empty state
    if (state.apps.isEmpty()) {
        val msg = if (state.query.isNotEmpty()) "No apps match \"${state.query}\""
        else when (state.filter) {
            FilterMode.NON_PLAY -> "All apps are already tagged as Play Store installed."
            FilterMode.ALL -> "No apps found."
        }
        Text(msg, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    val listHeight = minOf(state.apps.size * 72, 600).dp
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = Modifier.height(listHeight),
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(state.apps, key = { it.packageName }) { app ->
            AppRow(app = app, onToggle = { onToggle(app.packageName) })
        }
    }
}

@Composable
private fun AppRow(app: AppInfo, onToggle: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Checkbox(checked = app.isSelected, onCheckedChange = { onToggle() })

            val bitmap: ImageBitmap = remember(app.packageName) {
                app.icon.toBitmap(width = 96, height = 96).asImageBitmap()
            }
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1
                )
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                SuggestionChip(
                    onClick = {},
                    label = { Text(app.installerLabel, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
    }
}

@Composable
private fun TaggingPanel(progress: Int, total: Int, currentApp: String, log: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text("Tagging $progress/$total: $currentApp")
    }
    LogPanel(log = log)
}

@Composable
private fun LogPanel(log: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
                .padding(10.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = log,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
