package com.example.vrviewer.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * [CameraSource] implementation backed by camera2.
 *
 * For a logical multi-camera, [physicalCameraId] identifies the specific physical
 * lens to use and [focalLength] is passed as a physical-camera key so the HAL
 * selects that lens. For a normal (non-logical) camera, [physicalCameraId] should
 * match [logicalCameraId].
 *
 * @param logicalCameraId Camera2 ID of the camera to open.
 * @param physicalCameraId Physical lens ID to select (may equal [logicalCameraId]).
 * @param focalLength Focal length to request for the physical lens.
 * @param name Human-readable label for this lens.
 */
class PhoneCameraSource(
    private val context: Context,
    private val logicalCameraId: String,
    private val physicalCameraId: String,
    val focalLength: Float,
    private val minFocalLength: Float,
    val name: String
) : CameraSource {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val handlerThread = HandlerThread("PhoneCamera-$physicalCameraId").apply { start() }
    private val handler = Handler(handlerThread.looper)

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var targetSurface: Surface? = null
    private var targetTexture: SurfaceTexture? = null

    override fun start(target: SurfaceTexture) {
        stop()
        targetTexture = target
        targetSurface = Surface(target)

        try {
            cameraManager.openCamera(logicalCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createSession(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    stop()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error for $name")
                    stop()
                }
            }, handler)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing CAMERA permission", e)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Cannot access camera for $name", e)
        }
    }

    override fun stop() {
        try {
            captureSession?.stopRepeating()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop repeating request for $name", e)
        }
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        targetSurface?.release()
        targetSurface = null
        targetTexture = null
    }

    fun release() {
        stop()
        handlerThread.quitSafely()
    }

    private fun createSession(camera: CameraDevice) {
        val surface = targetSurface ?: return
        try {
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            .apply {
                                addTarget(surface)
                                applyLowLatencySettings(cameraManager, logicalCameraId, name)
                                try {
                                    setPhysicalCameraKey(
                                        CaptureRequest.LENS_FOCAL_LENGTH,
                                        focalLength,
                                        physicalCameraId
                                    )
                                } catch (e: IllegalArgumentException) {
                                    Log.w(TAG, "Physical camera key not supported for $name")
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && minFocalLength > 0f) {
                                        val zoomRatio = focalLength / minFocalLength
                                        Log.w(TAG, "Trying zoom ratio $zoomRatio for $name")
                                        try {
                                            set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
                                        } catch (zoomException: IllegalArgumentException) {
                                            Log.w(TAG, "Zoom ratio not supported for $name, using logical focal length")
                                            set(CaptureRequest.LENS_FOCAL_LENGTH, focalLength)
                                        }
                                    } else {
                                        set(CaptureRequest.LENS_FOCAL_LENGTH, focalLength)
                                    }
                                }
                            }
                            .build()
                        session.setRepeatingRequest(request, null, handler)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Failed to configure capture session for $name")
                        stop()
                    }
                },
                handler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capture session for $name", e)
        }
    }

    companion object {
        private const val TAG = "PhoneCameraSource"

        /**
         * Return a simple label for a lens based on its physical focal length.
         */
        fun nameForFocalLength(focalLength: Float): String {
            return when {
                focalLength < 2.5f -> "Ultra-wide"
                focalLength < 7.0f -> "Wide"
                else -> "Telephoto"
            }
        }
    }
}
