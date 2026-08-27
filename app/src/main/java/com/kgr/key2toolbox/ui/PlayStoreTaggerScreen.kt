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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kgr.key2toolbox.R
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
                    Text(stringResource(R.string.tagger_loading_apps))
                }
            }

            is TaggerUiState.NoRoot -> {
                Text(
                    stringResource(R.string.tagger_no_root),
                    color = Color(0xFFE57373)
                )
                Button(onClick = { vm.load(context) }) { Text(stringResource(R.string.generic_retry)) }
            }

            is TaggerUiState.Error -> {
                Text(stringResource(R.string.tagger_error, s.message), color = Color(0xFFE57373))
                Button(onClick = { vm.load(context) }) { Text(stringResource(R.string.generic_retry)) }
            }

            is TaggerUiState.Tagging -> {
                TaggingPanel(progress = s.progress, total = s.total, currentApp = s.currentApp, log = s.log)
            }

            is TaggerUiState.Done -> {
                val success = s.results.values.count { it == null }
                val fail = s.results.size - success
                val verb = stringResource(
                    if (s.tagMode == TagMode.TAG) R.string.tagger_verb_tagged
                    else R.string.tagger_verb_untagged
                )
                Text(
                    if (fail > 0) stringResource(R.string.tagger_done_with_failures, success, verb, fail)
                    else stringResource(R.string.tagger_done, success, verb),
                    color = if (fail == 0) Color(0xFF81C784) else Color(0xFFE57373)
                )
                LogPanel(log = s.log)
                TextButton(onClick = { vm.dismissResults() }) { Text(stringResource(R.string.tagger_back_to_list)) }
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
        val verb = stringResource(
            if (mode == TagMode.TAG) R.string.tagger_verb_tag else R.string.tagger_verb_untag
        )
        val desc = if (mode == TagMode.TAG)
            stringResource(R.string.tagger_confirm_tag_desc, count)
        else
            stringResource(R.string.tagger_confirm_untag_desc, count)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.tagger_confirm_title, verb, count)) },
            text = { Text(desc) },
            confirmButton = {
                Button(onClick = { showConfirm = false; vm.tagSelected() }) { Text(verb) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text(stringResource(R.string.generic_cancel)) }
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
            label = { Text(stringResource(R.string.tagger_tag_as_play_store)) }
        )
        FilterChip(
            selected = state.tagMode == TagMode.UNTAG,
            onClick = { onTagModeChange(TagMode.UNTAG) },
            label = { Text(stringResource(R.string.tagger_remove_tag)) }
        )
    }

    // Search field
    OutlinedTextField(
        value = state.query,
        onValueChange = onQueryChange,
        label = { Text(stringResource(R.string.generic_search)) },
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
            label = { Text(stringResource(R.string.tagger_filter_non_play)) }
        )
        FilterChip(
            selected = state.filter == FilterMode.ALL,
            onClick = { onFilterChange(FilterMode.ALL) },
            label = { Text(stringResource(R.string.tagger_filter_all)) }
        )
        FilterChip(
            selected = state.showSystem,
            onClick = onToggleSystem,
            label = { Text(stringResource(R.string.tagger_filter_system)) }
        )
    }

    // Selection controls
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onSelectAll) { Text(stringResource(R.string.tagger_select_all)) }
        TextButton(onClick = onClearSelection) { Text(stringResource(R.string.generic_clear)) }
        TextButton(onClick = onRefresh) { Text(stringResource(R.string.generic_refresh)) }
    }

    // Count / selection status
    Text(
        if (selectedCount > 0)
            stringResource(R.string.tagger_count_with_selection, state.apps.size, selectedCount)
        else
            stringResource(R.string.tagger_count, state.apps.size),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // Tag button — only shown when something is selected
    if (selectedCount > 0) {
        Button(onClick = onTagClick, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (state.tagMode == TagMode.TAG)
                    stringResource(R.string.tagger_action_tag, selectedCount)
                else
                    stringResource(R.string.tagger_action_untag, selectedCount)
            )
        }
    }

    // Empty state
    if (state.apps.isEmpty()) {
        val msg = if (state.query.isNotEmpty()) stringResource(R.string.tagger_no_match, state.query)
        else when (state.filter) {
            FilterMode.NON_PLAY -> stringResource(R.string.tagger_all_already_tagged)
            FilterMode.ALL -> stringResource(R.string.tagger_no_apps_found)
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
        Text(stringResource(R.string.tagger_tagging_progress, progress, total, currentApp))
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
