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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kgr.key2toolbox.core.AssetInstaller
import com.kgr.key2toolbox.service.Key2AccessibilityService
import com.kgr.key2toolbox.service.isKey2AccessibilityServiceEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ALWAYS_OFF_TARGET = "/data/adb/service.d/nav_always_off.sh"

@Composable
fun NavLockScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember {
        context.getSharedPreferences(Key2AccessibilityService.PREFS, Context.MODE_PRIVATE)
    }

    var serviceEnabled by remember { mutableStateOf(false) }
    var navLock by remember { mutableStateOf(prefs.getBoolean(Key2AccessibilityService.KEY_NAV_LOCK, true)) }
    var gestureMode by remember { mutableStateOf(prefs.getBoolean(Key2AccessibilityService.KEY_NAV_GESTURE, false)) }
    var alwaysOff by remember { mutableStateOf(prefs.getBoolean(Key2AccessibilityService.KEY_NAV_ALWAYS_OFF, false)) }
    var alwaysOffPersisted by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        serviceEnabled = isKey2AccessibilityServiceEnabled(context)
        withContext(Dispatchers.IO) {
            alwaysOffPersisted = AssetInstaller.fileExists(ALWAYS_OFF_TARGET)
        }
    }

    ScreenScaffold(title = stringResource(Screen.NavLock.titleRes), onBack = onBack) {
        AccessibilityServiceBanner(serviceEnabled)

        Text(
            stringResource(R.string.nav_lock_intro),
            style = MaterialTheme.typography.bodySmall
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.nav_lock_toggle))
            Switch(
                checked = navLock,
                onCheckedChange = { checked ->
                    navLock = checked
                    prefs.edit().putBoolean(Key2AccessibilityService.KEY_NAV_LOCK, checked).apply()
                }
            )
        }

        Text(
            stringResource(R.string.nav_lock_gesture_desc),
            style = MaterialTheme.typography.bodySmall
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.nav_lock_gesture_toggle))
            Switch(
                checked = gestureMode,
                onCheckedChange = { checked ->
                    gestureMode = checked
                    prefs.edit().putBoolean(Key2AccessibilityService.KEY_NAV_GESTURE, checked).apply()
                }
            )
        }

        Text(
            stringResource(R.string.nav_lock_always_off_desc),
            style = MaterialTheme.typography.bodySmall
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.nav_lock_always_off_toggle))
            Switch(
                checked = alwaysOff,
                onCheckedChange = { checked ->
                    alwaysOff = checked
                    prefs.edit().putBoolean(Key2AccessibilityService.KEY_NAV_ALWAYS_OFF, checked).apply()
                    // The accessibility service installs/removes the boot
                    // script asynchronously in response to this pref change;
                    // give it a moment, then refresh the displayed status.
                    scope.launch(Dispatchers.IO) {
                        kotlinx.coroutines.delay(800)
                        val persisted = AssetInstaller.fileExists(ALWAYS_OFF_TARGET)
                        withContext(Dispatchers.Main) { alwaysOffPersisted = persisted }
                    }
                }
            )
        }
        Text(
            stringResource(
                R.string.generic_persisted,
                when (alwaysOffPersisted) {
                    null -> stringResource(R.string.generic_checking)
                    true -> stringResource(R.string.generic_yes)
                    false -> stringResource(R.string.generic_no)
                }
            ),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
