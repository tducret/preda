package com.example.vrviewer.camera

import android.graphics.SurfaceTexture

/**
 * Abstraction over a video source that can render preview frames into a [SurfaceTexture].
 */
interface CameraSource {

    /**
     * Start streaming frames into [target].
     */
    fun start(target: SurfaceTexture)

    /**
     * Stop streaming and release any held resources.
     */
    fun stop()
}
