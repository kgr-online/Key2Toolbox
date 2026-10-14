package com.kgr.key2toolbox.modules

import com.kgr.key2toolbox.modules.ToolbeltPolicy.AppConfig
import com.kgr.key2toolbox.modules.ToolbeltPolicy.ColorMode
import com.kgr.key2toolbox.modules.ToolbeltPolicy.Front
import com.kgr.key2toolbox.modules.ToolbeltPolicy.Global
import com.kgr.key2toolbox.modules.ToolbeltPolicy.PerAppColor
import com.kgr.key2toolbox.modules.ToolbeltPolicy.PerAppVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbeltPolicyTest {
    private val g = Global(ColorMode.BLACK, opacityPercent = 100, autoHideFullscreen = true)
    private val idle = Front(fullscreen = false, fullIme = false)

    @Test fun perAppColorBeatsGlobalAndDefaultFallsBack() {
        assertEquals(ColorMode.MATERIAL, ToolbeltPolicy.resolve(g, AppConfig(PerAppColor.MATERIAL), idle).colorMode)
        assertEquals(ColorMode.BLACK, ToolbeltPolicy.resolve(g, AppConfig(), idle).colorMode)
        assertEquals(ColorMode.FOLLOW_APP, ToolbeltPolicy.resolve(g, AppConfig(PerAppColor.FOLLOW_APP), idle).colorMode)
    }

    @Test fun fullscreenFollowsGlobalUnlessTheAppOverridesIt() {
        val fs = Front(fullscreen = true, fullIme = false)
        assertFalse(ToolbeltPolicy.resolve(g, AppConfig(), fs).visible)
        assertTrue(ToolbeltPolicy.resolve(g, AppConfig(visibility = PerAppVisibility.KEEP_IN_FULLSCREEN), fs).visible)
        assertTrue(ToolbeltPolicy.resolve(g.copy(autoHideFullscreen = false), AppConfig(), fs).visible)
        assertFalse(ToolbeltPolicy.resolve(g.copy(autoHideFullscreen = false),
            AppConfig(visibility = PerAppVisibility.HIDE_IN_FULLSCREEN), fs).visible)
    }

    @Test fun notFullscreenAlwaysShowsEvenWhenTheAppSaysHide() {
        assertTrue(ToolbeltPolicy.resolve(g, AppConfig(visibility = PerAppVisibility.HIDE_IN_FULLSCREEN), idle).visible)
    }

    @Test fun keyboardAlwaysHidesEvenWhenKept() {
        val ime = Front(fullscreen = false, fullIme = true)
        assertFalse(ToolbeltPolicy.resolve(g, AppConfig(visibility = PerAppVisibility.KEEP_IN_FULLSCREEN), ime).visible)
    }

    @Test fun oldVisibilityIdsMapToTheNewOnes() {
        assertEquals(PerAppVisibility.HIDE_IN_FULLSCREEN, PerAppVisibility.fromId(1))
        assertEquals(PerAppVisibility.HIDE_IN_FULLSCREEN, PerAppVisibility.fromId(3))
        assertEquals(PerAppVisibility.DEFAULT, PerAppVisibility.fromId(2))
        assertEquals(PerAppVisibility.KEEP_IN_FULLSCREEN, PerAppVisibility.fromId(4))
    }

    @Test fun anyImeForcesOpaqueBlackWhateverTheSettings() {
        val translucent = g.copy(colorMode = ColorMode.MATERIAL, opacityPercent = 40)
        val strip = Front(fullscreen = false, fullIme = false, anyIme = true)
        val r = ToolbeltPolicy.resolve(translucent, AppConfig(PerAppColor.FOLLOW_APP), strip)
        assertEquals(ColorMode.BLACK, r.colorMode)
        assertEquals(100, r.opacityPercent)
        assertTrue(r.visible)   // the short strip does not hide the belt; a full keyboard does
        assertEquals(40, ToolbeltPolicy.resolve(translucent, AppConfig(), idle).opacityPercent)
    }

    @Test fun keepAppsMigrateWithoutOverridingExplicitChoices() {
        val cfg = mapOf("x" to AppConfig(visibility = PerAppVisibility.HIDE_IN_FULLSCREEN), "y" to AppConfig(PerAppColor.BLACK))
        val out = ToolbeltPolicy.mergeKeepApps(cfg, setOf("x", "y", "z"))
        assertEquals(PerAppVisibility.HIDE_IN_FULLSCREEN, out["x"]!!.visibility)
        assertEquals(PerAppVisibility.KEEP_IN_FULLSCREEN, out["y"]!!.visibility)
        assertEquals(PerAppColor.BLACK, out["y"]!!.color)
        assertEquals(PerAppVisibility.KEEP_IN_FULLSCREEN, out["z"]!!.visibility)
    }

    // The reported bug: icons must follow the bar the user sees, across the whole opacity range.
    @Test fun iconsFlipExactlyWithTheVisibleBarColour() {
        val darkBackdrop = 0xFF000000.toInt()
        val lightBackdrop = 0xFFFFFFFF.toInt()
        // A black bar over anything dark is light-on-dark; a light bar is dark-on-light.
        assertEquals(ToolbeltPolicy.ICON_LIGHT, ToolbeltPolicy.iconColorFor(ToolbeltPolicy.FOLLOW_DARK, darkBackdrop))
        assertEquals(ToolbeltPolicy.ICON_DARK, ToolbeltPolicy.iconColorFor(ToolbeltPolicy.FOLLOW_LIGHT, lightBackdrop))
        // A black bar at 15% over a white page looks nearly white, so the icons must be dark; at 100% they must be light.
        val black15 = (0x26 shl 24) or 0x0A0A0A
        assertEquals(ToolbeltPolicy.ICON_DARK, ToolbeltPolicy.iconColorFor(black15, lightBackdrop))
        assertEquals(ToolbeltPolicy.ICON_LIGHT, ToolbeltPolicy.iconColorFor(0xFF0A0A0A.toInt(), lightBackdrop))
        // Monotonic: once the visible colour is dark enough for light icons, darker never goes back to dark icons.
        var seenLight = false
        for (a in 15..255 step 5) {
            val light = ToolbeltPolicy.iconColorFor((a shl 24) or 0x0A0A0A, lightBackdrop) == ToolbeltPolicy.ICON_LIGHT
            if (seenLight) assertTrue("alpha $a", light)
            seenLight = seenLight || light
        }
    }

    @Test fun hysteresisHoldsInTheMiddleBand() {
        val s = ToolbeltPolicy.LightDark(false)
        assertFalse(s.update(0.5))   // inside the band: stays dark
        assertTrue(s.update(0.7))    // above 0.6: light
        assertTrue(s.update(0.5))    // inside the band: stays light
        assertFalse(s.update(0.3))   // below 0.4: dark
    }

    @Test fun lightnessOfWhiteBlackAndGrey() {
        assertEquals(1.0, ToolbeltPolicy.lightness(0xFFFFFFFF.toInt()), 0.01)
        assertEquals(0.0, ToolbeltPolicy.lightness(0xFF000000.toInt()), 0.01)
        assertEquals(0.5, ToolbeltPolicy.lightness(0xFF808080.toInt()), 0.02)
    }
}
