package com.kgr.key2toolbox

import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.kgr.key2toolbox.core.RootShell
import com.kgr.key2toolbox.ui.HomeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Full Material You (Monet) theming: on Android 12+ the palette is derived from
 * the system wallpaper, following the system light/dark setting. Older versions
 * fall back to the stock Material 3 light/dark baseline schemes.
 */
@Composable
private fun appColorScheme(dark: Boolean): ColorScheme {
    val context = LocalContext.current
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // IMPORTANT: RootShell must be the first thing to touch libsu's Shell
        // class, since RootShell's init block calls Shell.setDefaultBuilder().
        // If anything calls Shell.getShell()/Shell.cmd() first, setDefaultBuilder()
        // throws (libsu requires it to run before any shell is created), which
        // fails RootShell's <clinit> and poisons every later reference to it
        // with NoClassDefFoundError - which is exactly the crash we just saw.
        //
        // Run on a background thread since this blocks on the root grant
        // prompt (FolkPatch/APatch manager) on first launch.
        lifecycleScope.launch(Dispatchers.IO) {
            RootShell.isRootAvailable()
        }

        setContent {
            val darkTheme = isSystemInDarkTheme()
            // Ported from q25toolbox: on that ROM (BenOS/MTK), `Theme.DeviceDefault.DayNight`
            // didn't reliably flip the status bar icon color or background with day/night -
            // white icons were invisible in light mode, and the background stayed on one
            // theme's color regardless of which scheme Compose was actually rendering. Set
            // both explicitly instead of trusting the parent theme's DayNight resolution.
            // Unverified whether LineageOS on the Key2 has the same issue, but harmless
            // either way - this is a no-op if the parent theme was already correct.
            StatusBarIconAppearance(darkIcons = !darkTheme)
            MaterialTheme(colorScheme = appColorScheme(darkTheme)) {
                StatusBarColor(MaterialTheme.colorScheme.background)
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HomeScreen()
                }
            }
        }
    }
}

@Composable
private fun StatusBarIconAppearance(darkIcons: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = (view.context as Activity).window
    SideEffect {
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = darkIcons
    }
}

@Composable
private fun StatusBarColor(color: Color) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = (view.context as Activity).window
    val argb = color.toArgb()
    SideEffect {
        @Suppress("DEPRECATION")
        window.statusBarColor = argb
    }
}
