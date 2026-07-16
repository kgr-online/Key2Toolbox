package com.kgr.key2toolbox.ui

import com.kgr.key2toolbox.R
import androidx.compose.ui.res.stringResource
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
import com.kgr.key2toolbox.modules.WifiRegdomainController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun Wifi5gScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    var forced by remember { mutableStateOf(false) }
    var country by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    suspend fun refresh() {
        val p = WifiRegdomainController.isPersisted()
        val c = WifiRegdomainController.currentCountry()
        withContext(Dispatchers.Main) { forced = p; country = c; loaded = true }
    }

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { refresh() } }

    ScreenScaffold(title = stringResource(Screen.Wifi5g.titleRes), onBack = onBack) {
        Text(
            stringResource(R.string.desc_wifi5g_1),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            stringResource(R.string.desc_wifi5g_2),
            style = MaterialTheme.typography.bodySmall
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.wifi5g_force_toggle))
            Switch(
                checked = forced,
                enabled = loaded && !busy,
                onCheckedChange = { checked ->
                    forced = checked
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        if (checked) WifiRegdomainController.enable(context)
                        else WifiRegdomainController.disable()
                        kotlinx.coroutines.delay(1500) // let the driver re-apply
                        refresh()
                        withContext(Dispatchers.Main) { busy = false }
                    }
                }
            )
        }

        Text(
            stringResource(R.string.wifi5g_applied_region, country.ifEmpty { "…" }),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
