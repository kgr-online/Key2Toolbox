package com.kgr.key2toolbox.service

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper

/**
 * Flashlight toggle for the edge gestures. Needs no camera permission (CameraManager.setTorchMode). The torch state
 * is learned from a callback (it also changes when another app uses the torch), so the toggle always flips what is
 * really on.
 */
object TorchToggle {
    private var cm: CameraManager? = null
    private var cameraId: String? = null
    @Volatile private var on = false

    private fun ensure(ctx: Context): CameraManager? {
        cm?.let { return it }
        val m = ctx.applicationContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        cameraId = m.cameraIdList.firstOrNull { id ->
            m.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        m.registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(id: String, enabled: Boolean) { if (id == cameraId) on = enabled }
        }, Handler(Looper.getMainLooper()))
        cm = m
        return m
    }

    /** Flips the torch; false if the device has none or the camera is busy. */
    fun toggle(ctx: Context): Boolean = try {
        val m = ensure(ctx)
        val id = cameraId
        if (m == null || id == null) false else { m.setTorchMode(id, !on); true }
    } catch (_: Exception) { false }
}
