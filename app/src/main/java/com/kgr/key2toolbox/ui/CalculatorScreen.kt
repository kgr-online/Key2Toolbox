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
import androidx.compose.ui.unit.dp
import com.kgr.key2toolbox.service.Key2AccessibilityService
import com.kgr.key2toolbox.service.isKey2AccessibilityServiceEnabled

@Composable
fun CalculatorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(Key2AccessibilityService.PREFS, Context.MODE_PRIVATE)
    }

    var serviceEnabled by remember { mutableStateOf(false) }
    var enabled by remember {
        mutableStateOf(prefs.getBoolean(Key2AccessibilityService.KEY_CALCULATOR, false))
    }

    LaunchedEffect(Unit) {
        serviceEnabled = isKey2AccessibilityServiceEnabled(context)
    }

    ScreenScaffold(title = Screen.Calculator.title, onBack = onBack) {
        AccessibilityServiceBanner(serviceEnabled)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Enabled")
            Switch(
                checked = enabled,
                onCheckedChange = { checked ->
                    enabled = checked
                    prefs.edit().putBoolean(Key2AccessibilityService.KEY_CALCULATOR, checked).apply()
                }
            )
        }

        DescriptionDivider()
        Text(
            "In the AOSP/Google Calculator, route physical keys to the calculator buttons: " +
                "digits (Q W E R / S D F / Z X C for 0-9), plus operators - O = +, I = -, A = ×, " +
                "G = ÷, M = ., U = %, B = !, T/Y = ( ), Sym/Alt toggles scientific mode, " +
                "Backspace deletes. No root needed - uses the accessibility service only.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
