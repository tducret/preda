package com.example.vrviewer.camera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * [CameraSource] implementation for the Infiray USB-C thermal camera.
 *
 * The Pixel does not expose UVC devices through camera2, so this source uses
 * the AUSBC UVC library directly: a [USBMonitor] requests USB permission and
 * delivers a [USBMonitor.UsbControlBlock], then a [UVCCamera] streams MJPEG/YUYV
 * frames into the shared [SurfaceTexture] used by the GL renderer.
 */
class ThermalCameraSource(
    private val context: Context
) : CameraSource {

    private val usbMonitor: USBMonitor
    private val permissionHandler = Handler(Looper.getMainLooper())
    private var pendingPermissionRunnable: Runnable? = null
    private var pendingPermissionDevice: UsbDevice? = null

    init {
        val listener = object : USBMonitor.OnDeviceConnectListener {
            override fun onAttach(device: UsbDevice?) {
                if (device != null && isTargetDevice(device)) {
                    Log.i(TAG, "UVC camera attached: ${device.deviceName} (${device.vendorId}/${device.productId})")
                    // Delay the permission request so a flapping USB connection
                    // doesn't cancel the dialog before the user can act on it.
                    requestPermission(device)
                }
            }

            override fun onDetach(device: UsbDevice?) {
                if (device != null && isTargetDevice(device)) {
                    Log.i(TAG, "UVC camera detached: ${device.deviceName}")
                    cancelPendingPermissionRequest()
                    closeCamera()
                }
            }

            override fun onConnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?, createNew: Boolean) {
                if (ctrlBlock != null && device != null && isTargetDevice(device)) {
                    Log.i(TAG, "UVC camera connected: ${device.deviceName}")
                    openCamera(ctrlBlock)
                }
            }

            override fun onDisconnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                Log.i(TAG, "UVC camera disconnected")
                closeCamera()
            }

            override fun onCancel(device: UsbDevice?) {
                Log.w(TAG, "UVC camera permission cancelled: ${device?.deviceName}")
            }
        }
        usbMonitor = USBMonitor(ReceiverFlagContextWrapper(context.applicationContext), listener)
    }

    /**
     * Request USB permission for [device], but wait a short delay first.
     * If the device detaches before the delay expires, the request is cancelled.
     */
    private fun requestPermission(device: UsbDevice) {
        cancelPendingPermissionRequest()
        pendingPermissionDevice = device
        val runnable = Runnable {
            pendingPermissionRunnable = null
            pendingPermissionDevice = null
            Log.i(TAG, "Requesting permission for device: ${device.deviceName}")
            usbMonitor.requestPermission(device)
        }
        pendingPermissionRunnable = runnable
        permissionHandler.postDelayed(runnable, PERMISSION_REQUEST_DELAY_MS)
    }

    private fun cancelPendingPermissionRequest() {
        pendingPermissionRunnable?.let { permissionHandler.removeCallbacks(it) }
        pendingPermissionRunnable = null
        pendingPermissionDevice = null
    }

    private var uvcCamera: UVCCamera? = null
    private var targetTexture: SurfaceTexture? = null
    private var isRegistered = false

    private val shutterExecutor = Executors.newSingleThreadScheduledExecutor()
    private var shutterFuture: ScheduledFuture<*>? = null

    // AUSBC requires a non-null preview surface to start the streaming threads,
    // but copying to the surface crashes on this device. We use a tiny, fixed-size
    // ImageReader as a dummy sink that accepts the producer buffers so streaming
    // can start, and consume the real frames through the frame callback.
    private var dummyReader: ImageReader? = null
    private var dummyReaderThread: HandlerThread? = null
    private var dummyReaderHandler: Handler? = null

    // Double-buffered RGBX frame delivered by the UVC library's frame callback.
    private val frameLock = Object()
    private var frontBuffer: ByteBuffer? = null
    private var backBuffer: ByteBuffer? = null

    private val frameCallback = IFrameCallback { buffer ->
        if (buffer == null) return@IFrameCallback
        val size = buffer.remaining()
        synchronized(frameLock) {
            if (backBuffer == null || backBuffer!!.capacity() < size) {
                backBuffer = ByteBuffer.allocateDirect(size)
                    .order(ByteOrder.nativeOrder())
            }
            backBuffer!!.clear()
            backBuffer!!.put(buffer)
            backBuffer!!.flip()
            val tmp = frontBuffer
            frontBuffer = backBuffer
            backBuffer = tmp
        }
    }

    /**
     * Return the most recent RGBX thermal frame, or null if none has arrived yet.
     * The returned buffer is a direct ByteBuffer in native byte order.
     */
    fun getLatestFrame(): ByteBuffer? = synchronized(frameLock) { frontBuffer }

    override fun start(target: SurfaceTexture) {
        stop()
        targetTexture = target

        if (!isRegistered) {
            usbMonitor.register()
            isRegistered = true
        }

        // Request permission for an already-connected device. If permission is
        // already granted, USBMonitor will call onConnect asynchronously.
        usbMonitor.deviceList.firstOrNull { isTargetDevice(it) }?.let { device ->
            Log.i(TAG, "Already-attached UVC device found: ${device.deviceName}")
            requestPermission(device)
        }
    }

    override fun stop() {
        cancelPendingPermissionRequest()
        closeCamera()
        targetTexture = null
    }

    /**
     * Release the USB monitor and any held native resources.
     */
    fun release() {
        cancelPendingPermissionRequest()
        closeCamera()
        if (isRegistered) {
            usbMonitor.unregister()
            isRegistered = false
        }
        usbMonitor.destroy()
    }

    private fun closeCamera() {
        stopShutterTimer()
        try {
            uvcCamera?.stopPreview()
            uvcCamera?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error while closing UVC camera", e)
        }
        uvcCamera = null
        releaseDummyPreviewSurface()
    }

    private fun startShutterTimer() {
        stopShutterTimer()
        shutterFuture = shutterExecutor.scheduleWithFixedDelay({
            val camera = uvcCamera ?: return@scheduleWithFixedDelay
            try {
                camera.sendCommand(SHUTTER_FFC_COMMAND)
                Log.d(TAG, "Auto shutter FFC executed")
            } catch (e: Exception) {
                Log.w(TAG, "Auto shutter failed", e)
            }
        }, INITIAL_SHUTTER_DELAY_MS, SHUTTER_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun stopShutterTimer() {
        shutterFuture?.cancel(false)
        shutterFuture = null
    }

    private fun createDummyPreviewSurface(width: Int, height: Int): Surface? {
        releaseDummyPreviewSurface()
        val thread = HandlerThread("ThermalDummyReader").apply { start() }
        dummyReaderThread = thread
        dummyReaderHandler = Handler(thread.looper)

        val reader = ImageReader.newInstance(width, height, DUMMY_PREVIEW_FORMAT, DUMMY_MAX_IMAGES)
        reader.setOnImageAvailableListener({ reader ->
            var image: Image? = null
            try {
                image = reader.acquireNextImage()
            } catch (e: Exception) {
                Log.v(TAG, "Dummy image acquire failed: ${e.message}")
            } finally {
                try {
                    image?.close()
                } catch (_: Exception) {
                }
            }
        }, dummyReaderHandler)
        dummyReader = reader
        return reader.surface
    }

    private fun releaseDummyPreviewSurface() {
        try {
            dummyReader?.close()
        } catch (_: Exception) {
        }
        dummyReader = null
        dummyReaderThread?.quitSafely()
        dummyReaderThread = null
        dummyReaderHandler = null
    }

    private fun openCamera(ctrlBlock: USBMonitor.UsbControlBlock) {
        if (uvcCamera != null) {
            Log.w(TAG, "UVC camera already open; skipping duplicate open")
            return
        }

        try {
            val camera = UVCCamera()
            uvcCamera = camera
            camera.open(ctrlBlock.clone())

            configurePreviewSize(camera)

            val previewSize = camera.previewSize
            Log.i(TAG, "Thermal preview size: ${previewSize?.width}x${previewSize?.height}")

            // SurfaceTexture rendering crashes in libUVCCamera's copyToSurface on this
            // device, so we consume RGBX frames via the frame callback and upload them
            // to a regular GL texture in the renderer instead.
            createDummyPreviewSurface(previewSize?.width ?: 256, previewSize?.height ?: 196)?.let { surface ->
                camera.setPreviewDisplay(surface)
            }
            camera.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_RGBX)
            camera.startPreview()
            startShutterTimer()
            Log.i(TAG, "Thermal camera preview started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open thermal camera", e)
            closeCamera()
        }
    }

    /**
     * Pick a preview size and format by querying the camera's supported sizes.
     * Tries MJPEG first, then YUYV, and uses the first resolution that the
     * native UVC layer accepts.
     */
    private fun configurePreviewSize(camera: UVCCamera) {
        val formats = listOf(
            UVCCamera.FRAME_FORMAT_MJPEG,
            UVCCamera.FRAME_FORMAT_YUYV
        )

        for (format in formats) {
            val sizes = try {
                camera.getSupportedSizeList(format)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query supported sizes for format=$format", e)
                emptyList()
            }
            Log.i(TAG, "Format $format supported sizes: ${sizes.map { "${it.width}x${it.height}" }})")
            for (size in sizes) {
                try {
                    camera.setPreviewSize(
                        size.width,
                        size.height,
                        UVCCamera.DEFAULT_PREVIEW_MIN_FPS,
                        UVCCamera.DEFAULT_PREVIEW_MAX_FPS,
                        format,
                        UVCCamera.DEFAULT_BANDWIDTH
                    )
                    Log.i(TAG, "Selected thermal preview size: ${size.width}x${size.height}, format=$format")
                    return
                } catch (e: IllegalArgumentException) {
                    Log.v(TAG, "Unsupported size: ${size.width}x${size.height}, format=$format")
                }
            }
        }

        Log.w(TAG, "No supported preview size found")
    }

    private fun isTargetDevice(device: UsbDevice): Boolean {
        return device.vendorId == INFIRAY_VENDOR_ID && device.productId == INFIRAY_PRODUCT_ID
    }

    companion object {
        private const val TAG = "ThermalCameraSource"
        const val INFIRAY_VENDOR_ID = 5396
        const val INFIRAY_PRODUCT_ID = 1

        // Wait before requesting USB permission so a flapping connection does not
        // cancel the system dialog before the user can respond.
        private const val PERMISSION_REQUEST_DELAY_MS = 1500L

        // Format used for the dummy preview sink. JPEG buffers cannot be accessed
        // directly via ANativeWindow_lock, which causes the native copyToSurface
        // call to fail safely and avoids the libUVCCamera row-overread crash while
        // still allowing the streaming threads to start and feed the frame callback.
        private const val DUMMY_PREVIEW_FORMAT = ImageFormat.JPEG
        private const val DUMMY_MAX_IMAGES = 2

        private const val SHUTTER_INTERVAL_MS = 60_000L
        private const val INITIAL_SHUTTER_DELAY_MS = 10_000L
        private const val SHUTTER_FFC_COMMAND = 32768
    }
}

/**
 * Context wrapper that forces [Context.RECEIVER_NOT_EXPORTED] for dynamic broadcast
 * receiver registration. The AUSBC 3.2.x [USBMonitor] uses the no-flag overload,
 * which throws a [SecurityException] on Android 14+.
 */
private class ReceiverFlagContextWrapper(base: Context) : ContextWrapper(base) {

    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            super.registerReceiver(receiver, filter)
        }
    }
}
