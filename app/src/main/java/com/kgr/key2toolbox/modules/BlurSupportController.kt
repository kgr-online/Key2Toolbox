package com.kgr.key2toolbox.modules

import com.kgr.key2toolbox.core.AssetInstaller
import com.kgr.key2toolbox.core.RootShell

/**
 * Optional systemless module that makes Android advertise background blur on
 * ROMs that don't (the Key2's LineageOS leaves
 * `ro.surface_flinger.supports_background_blur` unset, so
 * `WindowManager.isCrossWindowBlurEnabled` is false and the Slim List / Masonry
 * blur slider is dead).
 *
 * Same effect as the community "Enable Blurs" module
 * (Magisk-Modules-Alt-Repo/enable-blurs): the two read-only props are set at
 * boot through the module's `system.prop`. Written from scratch here (that
 * module is AGPL-3.0) and without its Pixel 5 `service.sh`, which restarts
 * SurfaceFlinger - unnecessary on this device, where `persist.sys.sf.disable_blurs`
 * is not set.
 *
 * Takes effect after a reboot (Android reads the prop once at boot). Cost: blur
 * is applied system-wide to anything that asks for it, and the Adreno 512 is not
 * a fast GPU, so large radii can drop frames.
 */
object BlurSupportController {

    private const val MODULE_ID = "k2tb_enableblurs"
    private const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"
    private const val UPSTREAM_DIR = "/data/adb/modules/enable-blurs"
    private const val PROP = "ro.surface_flinger.supports_background_blur"

    /** Our module, or the upstream one, is present in the module list. */
    fun isInstalled(): Boolean =
        AssetInstaller.fileExists("$MODULE_DIR/system.prop") ||
            AssetInstaller.fileExists("$UPSTREAM_DIR/system.prop")

    /** The prop is live (module installed and a boot has happened since). */
    fun isActive(): Boolean =
        RootShell.run("getprop $PROP").outString.trim() == "1"

    /** Writes the module; needs a reboot. Returns whether the files landed. */
    fun install(): Boolean {
        RootShell.run(
            "mkdir -p '$MODULE_DIR' && " +
                "printf 'id=$MODULE_ID\\nname=K2TB Enable Blurs\\nversion=1\\nversionCode=1\\n" +
                "author=kgr-online\\ndescription=Advertise background blur (managed via Key2Toolbox)\\n' " +
                "> '$MODULE_DIR/module.prop' && " +
                "printf 'ro.sf.blurs_are_expensive=1\\n$PROP=1\\n' > '$MODULE_DIR/system.prop' && " +
                "chmod 644 '$MODULE_DIR/module.prop' '$MODULE_DIR/system.prop'"
        )
        return AssetInstaller.fileExists("$MODULE_DIR/system.prop")
    }

    /** Removes our module (not the upstream one); needs a reboot. */
    fun uninstall(): Boolean {
        RootShell.run("rm -rf '$MODULE_DIR'")
        return !AssetInstaller.fileExists("$MODULE_DIR/system.prop")
    }

    /** True when the upstream module (not ours) is what provides it, so we won't remove it. */
    fun isUpstreamOnly(): Boolean =
        !AssetInstaller.fileExists("$MODULE_DIR/system.prop") &&
            AssetInstaller.fileExists("$UPSTREAM_DIR/system.prop")
}
