package com.kgr.key2toolbox.modules

import kotlin.math.pow

/**
 * Pure decision logic of the Toolbelt (no Android types, so it runs in JVM tests): which colour mode, how visible and
 * how blurred the belt is for the app in front, and which tint the icons get. The overlay only draws what
 * [resolve] returns, so there is one place where "global vs per-app vs fullscreen" is decided.
 */
object ToolbeltPolicy {

    /** Colour source of the bar. Ids are the persisted values; 2 is also the global "follow app" mode. */
    enum class ColorMode(val id: Int) {
        BLACK(0), MATERIAL(1), FOLLOW_APP(2);

        companion object {
            fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: BLACK
        }
    }

    /** Per-app colour override; [DEFAULT] = use the global mode. */
    enum class PerAppColor(val id: Int) {
        DEFAULT(-1), BLACK(0), MATERIAL(1), FOLLOW_APP(2);

        companion object {
            fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: DEFAULT
        }
    }

    /** What the belt does when this app goes fullscreen; [DEFAULT] = follow the global auto-hide switch. */
    enum class PerAppVisibility(val id: Int) {
        DEFAULT(0),
        HIDE_IN_FULLSCREEN(1),
        KEEP_IN_FULLSCREEN(4);

        companion object {
            // Ids 2 and 3 were "hide status bar" options of a dropped experiment; 3 also hid the toolbar.
            fun fromId(id: Int) = when (id) {
                1, 3 -> HIDE_IN_FULLSCREEN
                4 -> KEEP_IN_FULLSCREEN
                else -> DEFAULT
            }
        }
    }

    data class AppConfig(
        val color: PerAppColor = PerAppColor.DEFAULT,
        val visibility: PerAppVisibility = PerAppVisibility.DEFAULT,
    ) {
        val isDefault get() = color == PerAppColor.DEFAULT && visibility == PerAppVisibility.DEFAULT
    }

    data class Global(
        val colorMode: ColorMode,
        val opacityPercent: Int,   // 15..100
        val autoHideFullscreen: Boolean,
    )

    /** What is going on in front of the belt right now. */
    data class Front(val fullscreen: Boolean, val fullIme: Boolean, val anyIme: Boolean = fullIme)

    data class Resolved(
        val visible: Boolean,
        val colorMode: ColorMode,
        val opacityPercent: Int,
    )

    fun resolve(global: Global, app: AppConfig, front: Front): Resolved {
        val hideInFullscreen = when (app.visibility) {
            PerAppVisibility.HIDE_IN_FULLSCREEN -> true
            PerAppVisibility.KEEP_IN_FULLSCREEN -> false
            PerAppVisibility.DEFAULT -> global.autoHideFullscreen
        }
        val visible = !(front.fullscreen && hideInFullscreen) && !front.fullIme
        // With any input-method window up (e.g. the physical-keyboard strip) the belt is always opaque black, so the
        // keyboard cannot show through and it never clashes with the IME.
        if (front.anyIme) return Resolved(visible, ColorMode.BLACK, 100)
        val mode = if (app.color == PerAppColor.DEFAULT) global.colorMode else ColorMode.fromId(app.color.id)
        return Resolved(visible, mode, global.opacityPercent.coerceIn(15, 100))
    }

    /** Folds the old "keep the belt in fullscreen" list into per-app settings, without touching an explicit choice. */
    fun mergeKeepApps(configs: Map<String, AppConfig>, keep: Set<String>): Map<String, AppConfig> {
        val out = configs.toMutableMap()
        for (pkg in keep) {
            val cur = out[pkg] ?: AppConfig()
            if (cur.visibility == PerAppVisibility.DEFAULT) out[pkg] = cur.copy(visibility = PerAppVisibility.KEEP_IN_FULLSCREEN)
        }
        return out
    }

    // ---------------------------------------------------------------- colour maths

    private fun lin(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    /** WCAG relative luminance of an ARGB colour (alpha ignored), 0..1. */
    fun luminance(argb: Int): Double =
        0.2126 * lin((argb shr 16) and 0xFF) + 0.7152 * lin((argb shr 8) and 0xFF) + 0.0722 * lin(argb and 0xFF)

    private fun contrast(a: Double, b: Double): Double = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)

    /** [fg] (with its own alpha) painted over the opaque [bg]. Result is opaque. */
    fun composite(fg: Int, bg: Int): Int {
        val a = (fg ushr 24) / 255f
        fun ch(shift: Int) = (((fg shr shift) and 0xFF) * a + ((bg shr shift) and 0xFF) * (1 - a) + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    const val ICON_LIGHT = 0xFFFFFFFF.toInt()
    const val ICON_DARK = 0xFF1B1B1B.toInt()

    /**
     * Icon tint for a bar of colour [bar] (alpha included) drawn over [backdrop]: whichever of white or near-black
     * contrasts more with the colour the user actually sees. The icon is always derived from the final bar colour, never
     * from a separate light/dark flag, so the two cannot disagree.
     */
    fun iconColorFor(bar: Int, backdrop: Int): Int {
        val seen = luminance(composite(bar, backdrop))
        return if (contrast(seen, luminance(ICON_LIGHT)) >= contrast(seen, luminance(ICON_DARK))) ICON_LIGHT else ICON_DARK
    }

    /** Follow-app bar colours: a fixed light or dark neutral, chosen by [LightDark] from the app's own pixels. */
    const val FOLLOW_DARK = 0xFF0A0A0A.toInt()
    const val FOLLOW_LIGHT = 0xFFEEEEEE.toInt()

    /**
     * Schmitt trigger on the perceived lightness (0..1) of the strip above the belt: flips to light above [HIGH], back
     * to dark below [LOW], and keeps its state in between, so a mid-grey page does not make the bar flicker.
     */
    class LightDark(var light: Boolean = false) {
        fun update(lightness: Double): Boolean {
            if (!light && lightness > HIGH) light = true
            else if (light && lightness < LOW) light = false
            return light
        }
        companion object { const val HIGH = 0.6; const val LOW = 0.4 }
    }

    /** Perceived lightness 0..1 of an ARGB colour (WCAG luminance mapped back through the sRGB curve). */
    fun lightness(argb: Int): Double {
        val y = luminance(argb)
        return if (y <= 0.0031308) y * 12.92 else 1.055 * y.pow(1 / 2.4) - 0.055
    }
}
