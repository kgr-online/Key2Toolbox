package com.kgr.key2toolbox.ui

import com.kgr.key2toolbox.R
import androidx.compose.ui.res.stringResource
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kgr.key2toolbox.modules.AutoFocusController
import com.kgr.key2toolbox.service.Key2AccessibilityService
import com.kgr.key2toolbox.service.isKey2AccessibilityServiceEnabled

private val DIALER_PACKAGES = setOf("com.google.android.dialer", "com.google.android.apps.dialer")

@Composable
fun InCallShortcutsScreen(onBack: () -> Unit, onNavigateToAutoFocus: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(Key2AccessibilityService.PREFS, Context.MODE_PRIVATE)
    }

    var serviceEnabled by remember { mutableStateOf(false) }
    var enabled by remember {
        mutableStateOf(prefs.getBoolean(Key2AccessibilityService.KEY_IN_CALL_SHORTCUTS, false))
    }
    var dialerHasAutoFocus by remember {
        mutableStateOf(
            AutoFocusController.isEnabled(prefs) &&
                AutoFocusController.getSelectedApps(prefs).any { it in DIALER_PACKAGES }
        )
    }

    LaunchedEffect(Unit) {
        serviceEnabled = isKey2AccessibilityServiceEnabled(context)
        dialerHasAutoFocus = AutoFocusController.isEnabled(prefs) &&
            AutoFocusController.getSelectedApps(prefs).any { it in DIALER_PACKAGES }
    }

    ScreenScaffold(title = stringResource(Screen.InCallShortcuts.titleRes), onBack = onBack) {
        AccessibilityServiceBanner(serviceEnabled)

        if (!dialerHasAutoFocus) {
            RequirementBanner(
                stringResource(R.string.requires_auto_focus_dialer),
                actionLabel = stringResource(R.string.action_open_auto_focus),
                onAction = onNavigateToAutoFocus
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.generic_enabled))
            Switch(
                checked = enabled,
                onCheckedChange = { checked ->
                    enabled = checked
                    prefs.edit().putBoolean(Key2AccessibilityService.KEY_IN_CALL_SHORTCUTS, checked).apply()
                }
            )
        }

        DescriptionDivider()
        Text(
            stringResource(R.string.desc_in_call_shortcuts),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
