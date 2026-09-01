package com.example.vrviewer.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Log

/**
 * Camera2 capture-request settings tuned for low motion-to-photon latency while keeping the
 * image usable.
 *
 * Multi-frame-queued post-processing (stabilisation) is disabled, while per-frame
 * enhancements run in their FAST variants so they do not add frame delay. The frame rate is
 * locked to the fastest fixed-FPS range advertised by the device. Unsupported keys are
 * skipped with a warning so the request still builds on older HALs.
 */
internal fun CaptureRequest.Builder.applyLowLatencySettings(
    cameraManager: CameraManager,
    cameraId: String,
    sourceName: String
) {
    setSafely(this, CaptureRequest.CONTROL_CAPTURE_INTENT,
        CameraMetadata.CONTROL_CAPTURE_INTENT_PREVIEW, "preview intent", sourceName)
    setSafely(this, CaptureRequest.EDGE_MODE,
        CameraMetadata.EDGE_MODE_FAST, "edge mode fast", sourceName)
    setSafely(this, CaptureRequest.NOISE_REDUCTION_MODE,
        CameraMetadata.NOISE_REDUCTION_MODE_FAST, "noise reduction fast", sourceName)
    setSafely(this, CaptureRequest.SHADING_MODE,
        CameraMetadata.SHADING_MODE_FAST, "fast shading", sourceName)
    setSafely(this, CaptureRequest.HOT_PIXEL_MODE,
        CameraMetadata.HOT_PIXEL_MODE_FAST, "hot pixel fast", sourceName)
    setSafely(this, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
        CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_FAST, "aberration correction fast", sourceName)
    setSafely(this, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
        CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF, "OIS off", sourceName)
    setSafely(this, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF, "video stabilisation off", sourceName)
    setSafely(this, CaptureRequest.STATISTICS_FACE_DETECT_MODE,
        CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF, "face detection off", sourceName)
    setSafely(this, CaptureRequest.CONTROL_AF_MODE,
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO, "continuous video AF", sourceName)

    val characteristics = try {
        cameraManager.getCameraCharacteristics(cameraId)
    } catch (e: Exception) {
        Log.w(TAG, "Cannot read characteristics for $sourceName", e)
        return
    }

    val fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
    if (fpsRanges != null && fpsRanges.isNotEmpty()) {
        val bestRange = fpsRanges.filter { it.lower == it.upper }
            .maxByOrNull { it.upper }
            ?: fpsRanges.maxByOrNull { it.upper }
            ?: fpsRanges[0]
        setSafely(this, CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestRange,
            "target FPS range $bestRange", sourceName)
    }
}

private fun <T> setSafely(
    request: CaptureRequest.Builder,
    key: CaptureRequest.Key<T>,
    value: T,
    label: String,
    sourceName: String
) {
    try {
        request.set(key, value)
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "$label not supported for $sourceName")
    }
}

private const val TAG = "Camera2Latency"
