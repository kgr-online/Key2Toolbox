package com.kgr.key2toolbox.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.kgr.key2toolbox.R
import com.kgr.key2toolbox.modules.TickerSettings

/** Per-app blacklist for the ticker: apps picked here never get a ticker banner. */
@Composable
fun TickerBlockedAppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    AppPickerScreen(
        title = stringResource(R.string.ticker_blocked_apps_label),
        initial = TickerSettings.blockedApps(context),
        onChange = { TickerSettings.setBlockedApps(context, it) },
        onBack = onBack,
        countLabel = { n, total -> stringResource(R.string.ticker_blocked_apps_of_total, n, total) },
    )
}
