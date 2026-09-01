package com.example.vrviewer.vr

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.vrviewer.viewmodel.ViewerViewModel
import kotlinx.coroutines.launch

/**
 * A [GLSurfaceView] that renders a stereo split-screen VR preview.
 */
@SuppressLint("ViewConstructor")
class VrGlSurfaceView(
    context: Context,
    private val viewModel: ViewerViewModel
) : GLSurfaceView(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderer: DistortionRenderer

    init {
        setEGLContextClientVersion(3)
        renderer = DistortionRenderer(profile = CardboardProfile.DEFAULT_V2) { surfaceTexture ->
            mainHandler.post {
                viewModel.bindSurfaceTexture(surfaceTexture)
            }
        }
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            viewModel.uiState.collect { state ->
                val isThermal = state.sourceName == "Thermal"
                val isSplit = state.splitMode
                renderer.useThermalTexture = isThermal
                renderer.splitMode = isSplit
                renderer.palette = state.paletteIndex
                renderer.thermalFrameProvider = if (isThermal || isSplit) {
                    { viewModel.getThermalFrame() }
                } else {
                    null
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        viewModel.unbindSurfaceTexture()
        super.onDetachedFromWindow()
    }
}
