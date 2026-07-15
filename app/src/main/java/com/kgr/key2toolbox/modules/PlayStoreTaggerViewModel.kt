package com.kgr.key2toolbox.modules

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kgr.key2toolbox.core.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class FilterMode { ALL, NON_PLAY }
enum class TagMode { TAG, UNTAG }

sealed class TaggerUiState {
    object Loading : TaggerUiState()
    object NoRoot : TaggerUiState()
    data class Ready(
        val apps: List<AppInfo>,
        val filter: FilterMode,
        val query: String,
        val showSystem: Boolean,
        val tagMode: TagMode
    ) : TaggerUiState()
    data class Tagging(
        val progress: Int,
        val total: Int,
        val currentApp: String,
        val log: String,
        val tagMode: TagMode
    ) : TaggerUiState()
    data class Done(
        val results: Map<String, String?>,
        val apps: List<AppInfo>,
        val filter: FilterMode,
        val query: String,
        val showSystem: Boolean,
        val log: String,
        val tagMode: TagMode
    ) : TaggerUiState()
    data class Error(val message: String) : TaggerUiState()
}

class PlayStoreTaggerViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<TaggerUiState>(TaggerUiState.Loading)
    val uiState: StateFlow<TaggerUiState> = _uiState.asStateFlow()

    private var allApps: List<AppInfo> = emptyList()
    private var currentFilter = FilterMode.NON_PLAY
    private var currentQuery = ""
    private var showSystem = false
    private var currentTagMode = TagMode.TAG
    private val logBuilder = StringBuilder()

    fun load(context: Context) {
        viewModelScope.launch {
            _uiState.value = TaggerUiState.Loading
            withContext(Dispatchers.IO) {
                if (!RootShell.isRootAvailable()) {
                    _uiState.value = TaggerUiState.NoRoot
                    return@withContext
                }
                try {
                    allApps = loadApps(context)
                    _uiState.value = TaggerUiState.Ready(filteredApps(), currentFilter, currentQuery, showSystem, currentTagMode)
                } catch (e: Exception) {
                    _uiState.value = TaggerUiState.Error(e.message ?: "Failed to load apps")
                }
            }
        }
    }

    fun setFilter(mode: FilterMode) { currentFilter = mode; updateReady() }
    fun setQuery(q: String) { currentQuery = q.trim(); updateReady() }
    fun setShowSystem(value: Boolean) { showSystem = value; updateReady() }
    fun getShowSystem() = showSystem
    fun setTagMode(mode: TagMode) {
        currentTagMode = mode
        // When switching to untag, default to ALL so Play-tagged apps are visible.
        // When switching to tag, default back to NON_PLAY.
        currentFilter = if (mode == TagMode.UNTAG) FilterMode.ALL else FilterMode.NON_PLAY
        clearSelection()
    }

    fun toggleSelection(packageName: String) {
        allApps = allApps.map {
            if (it.packageName == packageName) it.copy(isSelected = !it.isSelected) else it
        }
        updateReady()
    }

    fun selectAll() {
        val visible = filteredApps().map { it.packageName }.toSet()
        allApps = allApps.map {
            if (it.packageName in visible) it.copy(isSelected = true) else it
        }
        updateReady()
    }

    fun clearSelection() {
        allApps = allApps.map { it.copy(isSelected = false) }
        updateReady()
    }

    fun selectedCount(): Int = allApps.count { it.isSelected }

    fun tagSelected() {
        val selected = allApps.filter { it.isSelected }
        if (selected.isEmpty()) return
        logBuilder.clear()
        val mode = currentTagMode

        viewModelScope.launch {
            val results = mutableMapOf<String, String?>()
            withContext(Dispatchers.IO) {
                selected.forEachIndexed { index, app ->
                    appendLog("─── ${app.label} (${index + 1}/${selected.size})")
                    appendLog("    pkg: ${app.packageName}")

                    withContext(Dispatchers.Main) {
                        _uiState.value = TaggerUiState.Tagging(
                            progress = index + 1,
                            total = selected.size,
                            currentApp = app.label,
                            log = logBuilder.toString(),
                            tagMode = mode
                        )
                    }

                    val error = when (mode) {
                        TagMode.TAG -> PlayStoreTaggerManager.setPlayInstaller(app.packageName) { line ->
                            appendLog("    $line")
                            viewModelScope.launch(Dispatchers.Main) {
                                _uiState.value = TaggerUiState.Tagging(
                                    progress = index + 1,
                                    total = selected.size,
                                    currentApp = app.label,
                                    log = logBuilder.toString(),
                                    tagMode = mode
                                )
                            }
                        }
                        TagMode.UNTAG -> PlayStoreTaggerManager.clearInstaller(app.packageName) { line ->
                            appendLog("    $line")
                            viewModelScope.launch(Dispatchers.Main) {
                                _uiState.value = TaggerUiState.Tagging(
                                    progress = index + 1,
                                    total = selected.size,
                                    currentApp = app.label,
                                    log = logBuilder.toString(),
                                    tagMode = mode
                                )
                            }
                        }
                    }

                    results[app.packageName] = error
                    if (error == null) appendLog("    ✓ Success")
                    else appendLog("    ✗ $error")
                    appendLog("")
                }

                allApps = allApps.map { app ->
                    if (app.packageName in results) {
                        val newInstaller = PlayStoreTaggerManager.getInstaller(app.packageName)
                        app.copy(installerPackage = newInstaller, isSelected = false)
                    } else app
                }
            }

            _uiState.value = TaggerUiState.Done(
                results = results,
                apps = filteredApps(),
                filter = currentFilter,
                query = currentQuery,
                showSystem = showSystem,
                log = logBuilder.toString(),
                tagMode = mode
            )
        }
    }

    fun dismissResults() { updateReady() }

    private fun appendLog(line: String) { logBuilder.appendLine(line) }

    private fun updateReady() {
        val current = _uiState.value
        if (current is TaggerUiState.Ready || current is TaggerUiState.Done) {
            _uiState.value = TaggerUiState.Ready(filteredApps(), currentFilter, currentQuery, showSystem, currentTagMode)
        }
    }

    private fun filteredApps(): List<AppInfo> {
        var list = allApps.filter { if (!showSystem) !it.isSystem else true }
        list = when (currentFilter) {
            FilterMode.ALL -> list
            FilterMode.NON_PLAY -> list.filter { !it.isPlayInstalled }
        }
        if (currentQuery.isNotEmpty()) {
            val q = currentQuery.lowercase()
            list = list.filter {
                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
        return list.sortedBy { it.label.lowercase() }
    }

    private fun loadApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
        else null

        val packages = if (flags != null) pm.getInstalledApplications(flags)
        else @Suppress("DEPRECATION") pm.getInstalledApplications(PackageManager.GET_META_DATA)

        return packages
            .filter { it.packageName != context.packageName }
            .mapNotNull { appInfo ->
                try {
                    val label = pm.getApplicationLabel(appInfo).toString()
                    val icon = pm.getApplicationIcon(appInfo)
                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val installer = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                            pm.getInstallSourceInfo(appInfo.packageName).installingPackageName
                        else @Suppress("DEPRECATION") pm.getInstallerPackageName(appInfo.packageName)
                    } catch (_: Exception) { null }
                    AppInfo(
                        packageName = appInfo.packageName,
                        label = label,
                        icon = icon,
                        installerPackage = installer,
                        isSystem = isSystem
                    )
                } catch (_: Exception) { null }
            }
    }
}
