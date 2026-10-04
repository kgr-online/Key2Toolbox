package com.kgr.key2toolbox.modules

import com.kgr.key2toolbox.modules.GestureSettings.Action
import com.kgr.key2toolbox.modules.GestureSettings.Zone
import com.kgr.key2toolbox.service.EdgeSwipe.Dir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureSettingsTest {

    @Test fun defaultsForSideZones() {
        for (z in listOf(Zone.LEFT, Zone.RIGHT)) {
            assertEquals(Action.BACK, GestureSettings.defaultBinding(z, Dir.STRAIGHT, hold = false))
            assertEquals(Action.PREVIOUS_APP, GestureSettings.defaultBinding(z, Dir.STRAIGHT, hold = true))
            assertEquals(Action.NOTIFICATIONS, GestureSettings.defaultBinding(z, Dir.DIAG_B, hold = false))
            assertEquals(Action.QUICK_SETTINGS, GestureSettings.defaultBinding(z, Dir.DIAG_A, hold = false))
            assertEquals(Action.NONE, GestureSettings.defaultBinding(z, Dir.DIAG_A, hold = true))
        }
    }

    @Test fun ownKeyWinsOverTheLegacySideKey() {
        val saved = mapOf("gest_left_mode" to "CUSTOM", "gest_lat_mode" to "OFF")
        assertEquals("CUSTOM", GestureSettings.pick(Zone.LEFT, "mode", saved::get, "OFF"))
    }

    @Test fun legacySideKeyIsUsedByBothSidesUntilTheyAreSaved() {
        val saved = mapOf("gest_lat_thickness_dp" to 11)
        assertEquals(11, GestureSettings.pick(Zone.LEFT, "thickness_dp", saved::get, 14))
        assertEquals(11, GestureSettings.pick(Zone.RIGHT, "thickness_dp", saved::get, 14))
    }

    @Test fun paletteReferencesParseOnlyKnownFamiliesAndTones() {
        assertEquals("accent1" to 500, GestureSettings.parseMyRef("accent1:500"))
        assertEquals("neutral2" to 0, GestureSettings.parseMyRef(GestureSettings.myRef("neutral2", 0)))
        listOf(null, "", "accent1", "accent1:", "accent9:500", "accent1:555", "accent1:500:1", "x").forEach {
            assertEquals(null, GestureSettings.parseMyRef(it))
        }
    }

    @Test fun missingEverywhereGivesTheDefault() {
        assertEquals(28, GestureSettings.pick(Zone.RIGHT, "distance_dp", { _: String -> null as Int? }, 28))
    }

    @Test fun backupKeysCoverNewAndLegacySideKeys() {
        val keys = GestureSettings.allKeys().toSet()
        assertTrue("gest_left_mode" in keys && "gest_right_act_straight_hold" in keys)
        assertTrue("gest_arrow_color_ref" in keys && "gest_arrow_active_ref" in keys) // palette references are backed up
        assertTrue("gest_lat_mode" in keys && "gest_lat_act_diag_a_swipe" in keys) // old backups still restore
    }
}
