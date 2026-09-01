package com.example.vrviewer.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.example.vrviewer.camera.CameraSource
import com.example.vrviewer.camera.PhoneCameraSource
import com.example.vrviewer.camera.ThermalCameraSource
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI state exposed by [ViewerViewModel].
 */
data class UiState(
    val sourceName: String = "",
    val sourceIndex: Int = 0,
    val sourceCount: Int = 0,
    val isReady: Boolean = false,
    val paletteIndex: Int = 0,
    val paletteName: String = "",
    val splitMode: Boolean = false
)

/**
 * ViewModel that owns the available camera sources and exposes a ready flag.
 */
class ViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val sources = mutableListOf<CameraSource>()
    private val sourceNames = mutableListOf<String>()
    private var initialized = false
    private var hasSplitOption = false
    private val displayCount: Int get() = if (hasSplitOption) sources.size + 1 else sources.size

    private var activeSource: CameraSource? = null
    private var boundTexture: SurfaceTexture? = null

    private var splitMode = false
    private var splitPhoneSource: CameraSource? = null
    private var splitThermalSource: CameraSource? = null

    /**
     * The currently selected camera source, or null before any source is selected.
     */
    fun currentSource(): CameraSource? = activeSource

    /**
     * Enumerate phone and thermal cameras. Must be called after CAMERA permission is granted.
     */
    fun initializeSources() {
        if (initialized) return

        sources.clear()
        sourceNames.clear()

        val cameraManager = getApplication<Application>()
            .getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Find the logical back camera and create one source per available focal length.
        val phoneSources = try {
            enumeratePhoneSources(cameraManager)
        } catch (e: SecurityException) {
            Log.w(TAG, "CAMERA permission not granted; will retry enumeration later")
            return
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enumerate phone cameras", e)
            emptyList()
        }

        phoneSources.forEach { source ->
            sources.add(source)
            sourceNames.add(source.name)
        }

        // Add Infiray USB-C thermal camera if it is currently connected.
        val usbManager = getApplication<Application>()
            .getSystemService(Context.USB_SERVICE) as UsbManager
        val isThermalConnected = try {
            usbManager.deviceList.values.any { device ->
                device.vendorId == ThermalCameraSource.INFIRAY_VENDOR_ID &&
                    device.productId == ThermalCameraSource.INFIRAY_PRODUCT_ID
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enumerate USB devices", e)
            false
        }

        if (isThermalConnected) {
            sources.add(ThermalCameraSource(getApplication()))
            sourceNames.add("Thermal")
        }

        hasSplitOption = isThermalConnected && phoneSources.isNotEmpty()
        if (hasSplitOption) {
            sourceNames.add("Split")
        }

        initialized = true
        Log.i(TAG, "Initialized ${sources.size} sources: ${sourceNames} (splitOption=$hasSplitOption)")
        _uiState.value = UiState(
            sourceName = sourceNames.getOrElse(0) { "" },
            sourceIndex = 0,
            sourceCount = displayCount,
            isReady = boundTexture != null,
            splitMode = false
        )
    }

    /**
     * Create a [PhoneCameraSource] for every usable phone lens.
     *
     * First tries to expose the individual physical cameras of a logical
     * multi-camera. If that is not possible on this device, falls back to
     * cycling through the logical camera's reported focal lengths.
     */
    private fun enumeratePhoneSources(cameraManager: CameraManager): List<PhoneCameraSource> {
        val result = mutableListOf<PhoneCameraSource>()
        val usedPhysicalIds = mutableSetOf<String>()

        cameraManager.cameraIdList.forEach { id ->
            try {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                val isLogical = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
                val physicalIds = characteristics.physicalCameraIds

                Log.i(
                    TAG,
                    "Camera $id facing=$facing logical=$isLogical focal=${focalLengths?.contentToString()} physicalIds=${physicalIds.toList()}"
                )

                if (facing != CameraCharacteristics.LENS_FACING_BACK) return@forEach

                val logicalMinFocal = focalLengths?.minOrNull() ?: 0f

                if (isLogical && physicalIds.isNotEmpty()) {
                    // Create one source per physical camera, opened through the logical camera.
                    physicalIds.forEach { physicalId ->
                        if (usedPhysicalIds.add(physicalId)) {
                            try {
                                val physicalCharacteristics = cameraManager.getCameraCharacteristics(physicalId)
                                val physicalFocal = physicalCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                                Log.i(TAG, "  physical $physicalId focal=${physicalFocal?.contentToString()}")
                                if (physicalFocal != null && physicalFocal.isNotEmpty()) {
                                    val focalLength = physicalFocal.min()
                                    // Skip duplicate focal lengths for the same logical camera.
                                    if (result.none { it.focalLength == focalLength }) {
                                        val name = PhoneCameraSource.nameForFocalLength(focalLength)
                                        Log.i(TAG, "  -> adding source $name ($focalLength mm)")
                                        result.add(
                                            PhoneCameraSource(
                                                getApplication(),
                                                logicalCameraId = id,
                                                physicalCameraId = physicalId,
                                                focalLength = focalLength,
                                                minFocalLength = logicalMinFocal,
                                                name = name
                                            )
                                        )
                                    } else {
                                        Log.i(TAG, "  -> skipping duplicate focal length $focalLength")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Cannot inspect physical camera $physicalId", e)
                            }
                        }
                    }
                }

                // If no physical camera sources were usable, fall back to the logical camera's focal lengths.
                if (result.isEmpty() && focalLengths != null) {
                    focalLengths.sorted().forEach { focalLength ->
                        result.add(
                            PhoneCameraSource(
                                getApplication(),
                                logicalCameraId = id,
                                physicalCameraId = id,
                                focalLength = focalLength,
                                minFocalLength = logicalMinFocal,
                                name = PhoneCameraSource.nameForFocalLength(focalLength)
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to inspect camera $id", e)
            }
        }
        return result
    }

    /**
     * Select a camera source by index and start it if a [SurfaceTexture] has already been bound.
     * When [index] points past the last real source, Split mode is activated.
     */
    fun selectSource(index: Int) {
        initializeSources()
        if (displayCount == 0) return

        val clamped = index.mod(displayCount)

        if (clamped == _uiState.value.sourceIndex && activeSource != null && !splitMode) {
            return
        }
        if (splitMode && clamped == _uiState.value.sourceIndex) {
            return
        }

        exitSplitMode()
        activeSource?.stop()

        if (hasSplitOption && clamped == sources.size) {
            enterSplitMode()
        } else {
            activeSource = sources[clamped]
            _uiState.value = _uiState.value.copy(
                sourceIndex = clamped,
                sourceName = sourceNames[clamped],
                sourceCount = displayCount,
                isReady = false,
                splitMode = false
            )
            boundTexture?.let { activeSource?.start(it) }
            _uiState.value = _uiState.value.copy(isReady = boundTexture != null)
        }
    }

    /**
     * Switch to the next camera source in the rotation.
     */
    fun cycleSource() {
        initializeSources()
        if (displayCount == 0) return
        selectSource(_uiState.value.sourceIndex + 1)
    }

    fun cyclePalette() {
        val next = (_uiState.value.paletteIndex + 1) % PALETTE_NAMES.size
        _uiState.value = _uiState.value.copy(
            paletteIndex = next,
            paletteName = PALETTE_NAMES[next]
        )
    }

    /**
     * Bind the GL camera [SurfaceTexture] to the active source.
     */
    fun bindSurfaceTexture(surfaceTexture: SurfaceTexture) {
        if (splitMode) {
            if (boundTexture != surfaceTexture) {
                splitPhoneSource?.stop()
                boundTexture = surfaceTexture
            }
            splitPhoneSource?.start(surfaceTexture)
            splitThermalSource?.start(surfaceTexture)
            _uiState.value = _uiState.value.copy(isReady = true)
            return
        }
        if (boundTexture == surfaceTexture) {
            activeSource?.start(surfaceTexture)
            _uiState.value = _uiState.value.copy(isReady = activeSource != null)
            return
        }
        activeSource?.stop()
        boundTexture = surfaceTexture
        activeSource?.start(surfaceTexture)
        _uiState.value = _uiState.value.copy(isReady = activeSource != null)
    }

    /**
     * Stop the active source and clear the bound texture.
     */
    fun unbindSurfaceTexture() {
        if (splitMode) {
            splitPhoneSource?.stop()
            splitThermalSource?.stop()
            boundTexture = null
            _uiState.value = _uiState.value.copy(isReady = false)
            return
        }
        activeSource?.stop()
        boundTexture = null
        _uiState.value = _uiState.value.copy(isReady = false)
    }

    private fun enterSplitMode() {
        exitSplitMode()
        splitMode = true

        val phoneSource = sources.firstOrNull { it is PhoneCameraSource && it.name == "Ultra-wide" }
            ?: sources.firstOrNull { it is PhoneCameraSource }
        val thermalSource = sources.firstOrNull { it is ThermalCameraSource }

        splitPhoneSource = phoneSource
        splitThermalSource = thermalSource

        boundTexture?.let { texture ->
            splitPhoneSource?.start(texture)
            splitThermalSource?.start(texture)
        }

        _uiState.value = _uiState.value.copy(
            sourceIndex = sources.size,
            sourceName = "Split",
            sourceCount = displayCount,
            isReady = boundTexture != null,
            splitMode = true
        )
        Log.i(TAG, "Entered split mode: phone=${phoneSource}, thermal=${thermalSource}")
    }

    private fun exitSplitMode() {
        if (!splitMode) return
        splitMode = false
        splitPhoneSource?.stop()
        splitThermalSource?.stop()
        splitPhoneSource = null
        splitThermalSource = null
        Log.i(TAG, "Exited split mode")
    }

    fun getThermalFrame(): ByteBuffer? {
        return (sources.firstOrNull { it is ThermalCameraSource } as? ThermalCameraSource)?.getLatestFrame()
    }

    override fun onCleared() {
        super.onCleared()
        exitSplitMode()
        activeSource?.stop()
        sources.filterIsInstance<PhoneCameraSource>().forEach { it.release() }
        sources.filterIsInstance<ThermalCameraSource>().forEach { it.release() }
    }

    companion object {
        private const val TAG = "ViewerViewModel"

        val PALETTE_NAMES = listOf("White Hot", "Black Hot", "Ironbow", "Lava", "Rainbow", "Arctic")
    }
}
