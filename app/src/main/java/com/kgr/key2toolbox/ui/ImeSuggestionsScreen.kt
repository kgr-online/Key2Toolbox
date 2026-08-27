package com.kgr.key2toolbox.ui

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kgr.key2toolbox.R
import com.kgr.key2toolbox.modules.CtrlKeyController
import com.kgr.key2toolbox.service.Key2AccessibilityService
import com.kgr.key2toolbox.service.isKey2AccessibilityServiceEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ImeSuggestionsScreen(onBack: () -> Unit, onNavigateToCtrlKey: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(Key2AccessibilityService.PREFS, Context.MODE_PRIVATE)
    }

    var serviceEnabled by remember { mutableStateOf(false) }
    var ctrlRemapped by remember { mutableStateOf(true) } // assume ok until the root check says otherwise
    var enabled by remember {
        mutableStateOf(prefs.getBoolean(Key2AccessibilityService.KEY_IME_SUGGESTIONS, false))
    }

    LaunchedEffect(Unit) {
        serviceEnabled = isKey2AccessibilityServiceEnabled(context)
        ctrlRemapped = withContext(Dispatchers.IO) {
            CtrlKeyController.currentKeymapState() == CtrlKeyController.State.CTRL
        }
    }

    ScreenScaffold(title = Screen.ImeSuggestions.title, onBack = onBack) {
        AccessibilityServiceBanner(serviceEnabled)

        if (!ctrlRemapped) {
            RequirementBanner(
                message = stringResource(R.string.ime_suggestions_requires_ctrl_key),
                actionLabel = stringResource(R.string.ime_suggestions_open_ctrl_key),
                onAction = onNavigateToCtrlKey
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.generic_enabled))
            Switch(
                checked = enabled,
                onCheckedChange = { checked ->
                    enabled = checked
                    prefs.edit().putBoolean(Key2AccessibilityService.KEY_IME_SUGGESTIONS, checked).apply()
                }
            )
        }

        DescriptionDivider()
        Text(
            stringResource(R.string.desc_ime_suggestions),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
