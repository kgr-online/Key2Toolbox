package com.kgr.key2toolbox.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Wifi
import com.kgr.key2toolbox.BuildConfig
import com.kgr.key2toolbox.settings.SettingsScreen
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import com.kgr.key2toolbox.R

enum class AppTab(@StringRes val labelRes: Int, val icon: ImageVector) {
    Info(R.string.tab_info, Icons.Filled.Home),
    Keyboard(R.string.tab_keyboard, Icons.Filled.Keyboard),
    Display(R.string.tab_display, Icons.Filled.Smartphone),
    System(R.string.tab_system, Icons.Filled.Build),
    Network(R.string.tab_network, Icons.Filled.Wifi),
    Settings(R.string.tab_settings, Icons.Filled.Settings),
}

val keyboardScreens = listOf(
    Screen.CtrlKey,
    Screen.KbdLight,
    Screen.NavLock,
    Screen.PinKeyboard,
    Screen.ImeBlock,
    Screen.Calculator,
    Screen.ImeSuggestions,
    Screen.ChatComposer,
    Screen.InCallShortcuts,
    Screen.AutoFocus
)

val displayScreens = listOf(
    Screen.Dt2w,
    Screen.ExtraDim,
    Screen.Recents
)

val networkScreens = listOf(
    Screen.Wifi5g,
    Screen.WirelessAdb,
    Screen.BtIdle,
    Screen.LocationIdle,
    Screen.Telemetry
)

val systemScreens = listOf(
    Screen.AdBlock,
    Screen.DenylistManager,
    Screen.K2PF,
    Screen.LedNotify,
    Screen.PlayStoreTagger,
    Screen.TickerNotifications,
    Screen.Zram
)

@Composable
fun HomeScreen() {
    var tab by remember { mutableStateOf(AppTab.Info) }
    var detail by remember { mutableStateOf<Screen?>(null) }

    BackHandler(enabled = detail != null) { detail = null }

    Scaffold(
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry && (entry != AppTab.Info || detail == null),
                        onClick = { tab = entry; detail = null },
                        icon = { Icon(entry.icon, contentDescription = null) },
                        label = { Text(stringResource(entry.labelRes)) }
                    )
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val current = detail
            if (current != null) {
                DetailHost(current, onNavigate = { detail = it }) { detail = null }
            } else when (tab) {
                AppTab.Info -> InfoScreen(onOpenBatteryUsage = { detail = Screen.BatteryUsage })
                AppTab.Keyboard -> CategoryMenu(R.string.tab_keyboard, keyboardScreens) { detail = it }
                AppTab.Display -> CategoryMenu(R.string.tab_display, displayScreens) { detail = it }
                AppTab.System -> CategoryMenu(R.string.tab_system, systemScreens) { detail = it }
                AppTab.Network -> CategoryMenu(R.string.tab_network, networkScreens) { detail = it }
                AppTab.Settings -> SettingsScreen(currentVersionName = BuildConfig.VERSION_NAME)
            }
        }
    }
}

@Composable
private fun DetailHost(screen: Screen, onNavigate: (Screen) -> Unit, onBack: () -> Unit) {
    when (screen) {
        Screen.CtrlKey -> CtrlKeyScreen(onBack)
        Screen.KbdLight -> KbdLightScreen(onBack)
        Screen.NavLock -> NavLockScreen(onBack)
        Screen.PinKeyboard -> PinKeyboardScreen(onBack)
        Screen.ImeBlock -> ImeBlockScreen(onBack)
        Screen.Calculator -> CalculatorScreen(onBack)
        Screen.ImeSuggestions -> ImeSuggestionsScreen(onBack, onNavigateToCtrlKey = { onNavigate(Screen.CtrlKey) })
        Screen.ChatComposer -> ChatComposerScreen(onBack)
        Screen.InCallShortcuts -> InCallShortcutsScreen(onBack)
        Screen.AutoFocus -> AutoFocusScreen(onBack)
        Screen.Dt2w -> Dt2wScreen(onBack)
        Screen.ExtraDim -> ExtraDimScreen(onBack)
        Screen.Recents -> RecentsScreen(onBack)
        Screen.Wifi5g -> Wifi5gScreen(onBack)
        Screen.WirelessAdb -> WirelessAdbScreen(onBack)
        Screen.BtIdle -> BtIdleScreen(onBack)
        Screen.LocationIdle -> LocationIdleScreen(onBack)
        Screen.Telemetry -> TelemetryScreen(onBack)
        Screen.AdBlock -> AdBlockScreen(onBack)
        Screen.DenylistManager -> DenylistScreen(onBack)
        Screen.K2PF -> K2PFScreen(onBack)
        Screen.LedNotify -> LedNotifyScreen(onBack)
        Screen.PlayStoreTagger -> PlayStoreTaggerScreen(onBack)
        Screen.TickerNotifications -> TickerNotificationsScreen(onBack)
        Screen.Zram -> ZramScreen(onBack)
        Screen.BatteryUsage -> BatteryUsageScreen(onBack)
        Screen.Home -> Unit
    }
}

@Composable
private fun CategoryMenu(@StringRes titleRes: Int, screens: List<Screen>, onNavigate: (Screen) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(titleRes), style = MaterialTheme.typography.headlineMedium)
        screens.forEach { screen ->
            MenuEntry(screen) { onNavigate(screen) }
        }
    }
}

@Composable
private fun MenuEntry(screen: Screen, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(screen.title, style = MaterialTheme.typography.titleMedium)
            if (screen.subtitle.isNotEmpty() || screen.access.isNotEmpty()) {
                Row(verticalAlignment = Alignment.Top) {
                    if (screen.subtitle.isNotEmpty()) {
                        Text(
                            screen.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    if (screen.access.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            screen.access.forEach { at ->
                                Text(
                                    "[" + stringResource(at.labelRes) + "]",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
