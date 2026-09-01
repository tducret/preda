package com.example.vrviewer.vr

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView.Renderer
import android.os.Process
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GL renderer that draws the camera feed as a barrel-distorted quad for each eye.
 *
 * The camera feed is bound to an external OES texture. This renderer creates a
 * [SurfaceTexture] around that texture so a [CameraSource] can stream into it.
 */
class DistortionRenderer(
    private val profile: CardboardProfile = CardboardProfile.DEFAULT_V2,
    private val onSurfaceTextureCreated: (SurfaceTexture) -> Unit = {}
) : Renderer {

    private val vertexShader = """
        #version 300 es
        layout(location = 0) in vec2 aPosition;
        layout(location = 1) in vec2 aTexCoord;
        uniform mat4 uTransformMatrix;
        uniform vec2 uVertexScale;
        out vec2 vTexCoord;
        void main() {
            gl_Position = vec4(aPosition * uVertexScale, 0.0, 1.0);
            // Camera frames are delivered rotated on this device; rotate texture
            // coordinates back to upright before applying the SurfaceTexture
            // transform matrix.
            vec2 rotated = vec2(aTexCoord.y, 1.0 - aTexCoord.x);
            vTexCoord = (uTransformMatrix * vec4(rotated, 0.0, 1.0)).xy;
        }
    """.trimIndent()

    private val fragmentShader = """
        #version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision highp float;

        in vec2 vTexCoord;
        uniform samplerExternalOES uCameraTexture;
        uniform vec2 uCenter;
        uniform float uK1;
        uniform float uK2;
        uniform float uScale;

        out vec4 fragColor;

        void main() {
            vec2 centered = vTexCoord - uCenter;
            float r2 = dot(centered, centered);
            float r4 = r2 * r2;
            float factor = 1.0 + uK1 * r2 + uK2 * r4;
            vec2 distorted = centered * factor * uScale;
            vec2 finalUv = distorted + uCenter;

            if (finalUv.x < 0.0 || finalUv.x > 1.0 || finalUv.y < 0.0 || finalUv.y > 1.0) {
                fragColor = vec4(0.0, 0.0, 0.0, 1.0);
            } else {
                fragColor = texture(uCameraTexture, finalUv);
            }
        }
    """.trimIndent()

    private val thermalVertexShader = """
        #version 300 es
        layout(location = 0) in vec2 aPosition;
        layout(location = 1) in vec2 aTexCoord;
        uniform vec2 uVertexScale;
        out vec2 vTexCoord;
        void main() {
            gl_Position = vec4(aPosition * uVertexScale, 0.0, 1.0);
            // Thermal frames are delivered upright; the image is correct after
            // removing the 180-degree rotation, but it appears mirrored
            // horizontally. Flip the x texture coordinate to correct the mirror.
            vTexCoord = vec2(1.0 - aTexCoord.x, aTexCoord.y);
        }
    """.trimIndent()

    private val thermalFragmentShader = """
        #version 300 es
        precision highp float;

        in vec2 vTexCoord;
        uniform sampler2D uCameraTexture;
        uniform vec2 uCenter;
        uniform float uK1;
        uniform float uK2;
        uniform float uScale;
        uniform int uPalette;

        out vec4 fragColor;

        vec3 paletteWhiteHot(float t) {
            return vec3(t);
        }

        vec3 paletteBlackHot(float t) {
            return vec3(1.0 - t);
        }

        vec3 paletteIronbow(float t) {
            if (t < 0.2) {
                return mix(vec3(0.0, 0.0, 0.1), vec3(0.0, 0.5, 0.75), t / 0.2);
            } else if (t < 0.4) {
                return mix(vec3(0.0, 0.5, 0.75), vec3(0.0, 1.0, 0.2), (t - 0.2) / 0.2);
            } else if (t < 0.6) {
                return mix(vec3(0.0, 1.0, 0.2), vec3(1.0, 1.0, 0.0), (t - 0.4) / 0.2);
            } else if (t < 0.8) {
                return mix(vec3(1.0, 1.0, 0.0), vec3(1.0, 0.2, 0.0), (t - 0.6) / 0.2);
            } else {
                return mix(vec3(1.0, 0.2, 0.0), vec3(1.0, 1.0, 1.0), (t - 0.8) / 0.2);
            }
        }

        vec3 paletteLava(float t) {
            if (t < 0.25) {
                return mix(vec3(0.0, 0.0, 0.0), vec3(0.6, 0.0, 0.0), t / 0.25);
            } else if (t < 0.5) {
                return mix(vec3(0.6, 0.0, 0.0), vec3(0.9, 0.4, 0.0), (t - 0.25) / 0.25);
            } else if (t < 0.75) {
                return mix(vec3(0.9, 0.4, 0.0), vec3(1.0, 0.85, 0.0), (t - 0.5) / 0.25);
            } else {
                return mix(vec3(1.0, 0.85, 0.0), vec3(1.0, 1.0, 0.9), (t - 0.75) / 0.25);
            }
        }

        vec3 paletteRainbow(float t) {
            if (t < 0.166) {
                return mix(vec3(0.3, 0.0, 0.5), vec3(0.0, 0.0, 1.0), t / 0.166);
            } else if (t < 0.333) {
                return mix(vec3(0.0, 0.0, 1.0), vec3(0.0, 1.0, 0.5), (t - 0.166) / 0.166);
            } else if (t < 0.5) {
                return mix(vec3(0.0, 1.0, 0.5), vec3(0.0, 1.0, 0.0), (t - 0.333) / 0.166);
            } else if (t < 0.666) {
                return mix(vec3(0.0, 1.0, 0.0), vec3(1.0, 1.0, 0.0), (t - 0.5) / 0.166);
            } else if (t < 0.833) {
                return mix(vec3(1.0, 1.0, 0.0), vec3(1.0, 0.5, 0.0), (t - 0.666) / 0.166);
            } else {
                return mix(vec3(1.0, 0.5, 0.0), vec3(1.0, 0.0, 0.0), (t - 0.833) / 0.166);
            }
        }

        vec3 paletteArctic(float t) {
            if (t < 0.25) {
                return mix(vec3(0.1, 0.0, 0.2), vec3(0.0, 0.2, 0.5), t / 0.25);
            } else if (t < 0.5) {
                return mix(vec3(0.0, 0.2, 0.5), vec3(0.0, 0.6, 0.8), (t - 0.25) / 0.25);
            } else if (t < 0.75) {
                return mix(vec3(0.0, 0.6, 0.8), vec3(0.2, 0.9, 0.5), (t - 0.5) / 0.25);
            } else {
                return mix(vec3(0.2, 0.9, 0.5), vec3(1.0, 1.0, 0.3), (t - 0.75) / 0.25);
            }
        }

        void main() {
            vec2 centered = vTexCoord - uCenter;
            float r2 = dot(centered, centered);
            float r4 = r2 * r2;
            float factor = 1.0 + uK1 * r2 + uK2 * r4;
            vec2 distorted = centered * factor * uScale;
            vec2 finalUv = distorted + uCenter;

            if (finalUv.x < 0.0 || finalUv.x > 1.0 || finalUv.y < 0.0 || finalUv.y > 1.0) {
                fragColor = vec4(0.0, 0.0, 0.0, 1.0);
            } else {
                vec4 texColor = texture(uCameraTexture, finalUv);
                float t = dot(texColor.rgb, vec3(0.299, 0.587, 0.114));

                vec3 color;
                if (uPalette == 0) {
                    color = paletteWhiteHot(t);
                } else if (uPalette == 1) {
                    color = paletteBlackHot(t);
                } else if (uPalette == 2) {
                    color = paletteIronbow(t);
                } else if (uPalette == 3) {
                    color = paletteLava(t);
                } else if (uPalette == 4) {
                    color = paletteRainbow(t);
                } else {
                    color = paletteArctic(t);
                }
                fragColor = vec4(color, 1.0);
            }
        }
    """.trimIndent()

    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var transformMatrixHandle = 0
    private var textureHandle = 0
    private var centerHandle = 0
    private var k1Handle = 0
    private var k2Handle = 0
    private var scaleHandle = 0
    private var vertexScaleHandle = 0

    private val textureMatrix = FloatArray(16)

    private var vao = 0
    private var vbo = 0
    private var cameraTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null

    private var thermalProgram = 0
    private var thermalPositionHandle = 0
    private var thermalTexCoordHandle = 0
    private var thermalTextureHandle = 0
    private var thermalCenterHandle = 0
    private var thermalK1Handle = 0
    private var thermalK2Handle = 0
    private var thermalScaleHandle = 0
    private var thermalVertexScaleHandle = 0
    private var thermalPaletteHandle = 0
    private var thermalTextureId = 0

    private var width = 0
    private var height = 0
    private var isReady = false

    /**
     * When true the renderer uploads frames from [thermalFrameProvider] to a regular
     * 2D texture and uses the thermal shader instead of the SurfaceTexture/OES path.
     */
    @Volatile
    var useThermalTexture: Boolean = false

    @Volatile
    var palette: Int = 0

    /**
     * When true the renderer draws the phone camera (OES) on the left half-screen
     * and the thermal camera on the right half-screen.
     */
    @Volatile
    var splitMode: Boolean = false

    /**
     * Supplier of the latest RGBX thermal frame. Called on the GL thread.
     */
    var thermalFrameProvider: (() -> ByteBuffer?)? = null

    /**
     * Return the [SurfaceTexture] that feeds this renderer's camera texture.
     * Valid after the surface has been created.
     */
    fun getSurfaceTexture(): SurfaceTexture? = surfaceTexture

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Run the GL render thread at display priority to reduce scheduling jitter.
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)

        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

        program = createProgram(vertexShader, fragmentShader)
        if (program == 0) {
            Log.e(TAG, "Failed to create shader program")
            return
        }

        positionHandle = GLES30.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES30.glGetAttribLocation(program, "aTexCoord")
        transformMatrixHandle = GLES30.glGetUniformLocation(program, "uTransformMatrix")
        textureHandle = GLES30.glGetUniformLocation(program, "uCameraTexture")
        centerHandle = GLES30.glGetUniformLocation(program, "uCenter")
        k1Handle = GLES30.glGetUniformLocation(program, "uK1")
        k2Handle = GLES30.glGetUniformLocation(program, "uK2")
        scaleHandle = GLES30.glGetUniformLocation(program, "uScale")
        vertexScaleHandle = GLES30.glGetUniformLocation(program, "uVertexScale")

        thermalProgram = createProgram(thermalVertexShader, thermalFragmentShader)
        if (thermalProgram != 0) {
            thermalPositionHandle = GLES30.glGetAttribLocation(thermalProgram, "aPosition")
            thermalTexCoordHandle = GLES30.glGetAttribLocation(thermalProgram, "aTexCoord")
            thermalTextureHandle = GLES30.glGetUniformLocation(thermalProgram, "uCameraTexture")
            thermalCenterHandle = GLES30.glGetUniformLocation(thermalProgram, "uCenter")
            thermalK1Handle = GLES30.glGetUniformLocation(thermalProgram, "uK1")
            thermalK2Handle = GLES30.glGetUniformLocation(thermalProgram, "uK2")
            thermalScaleHandle = GLES30.glGetUniformLocation(thermalProgram, "uScale")
            thermalVertexScaleHandle = GLES30.glGetUniformLocation(thermalProgram, "uVertexScale")
            thermalPaletteHandle = GLES30.glGetUniformLocation(thermalProgram, "uPalette")
        } else {
            Log.e(TAG, "Failed to create thermal shader program")
        }

        createQuad()
        createCameraTexture()
        createThermalTexture()

        surfaceTexture?.let { onSurfaceTextureCreated(it) }
        isReady = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width
        this.height = height
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        if (!isReady) return

        if (splitMode) {
            drawSplitFrame()
        } else if (useThermalTexture) {
            drawThermalFrame()
        } else {
            drawSurfaceTextureFrame()
        }
    }

    private fun drawSurfaceTextureFrame() {
        surfaceTexture?.updateTexImage()
        surfaceTexture?.getTransformMatrix(textureMatrix)

        if (program == 0) return

        GLES30.glUseProgram(program)
        GLES30.glBindVertexArray(vao)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES30.glUniform1i(textureHandle, 0)
        GLES30.glUniformMatrix4fv(transformMatrixHandle, 1, false, textureMatrix, 0)

        val scale = computeAspectRatioScale(CAMERA_BUFFER_WIDTH, CAMERA_BUFFER_HEIGHT)
        GLES30.glUniform2f(vertexScaleHandle, scale[0], scale[1])

        drawBothEyes(::drawEye)

        GLES30.glBindVertexArray(0)
        GLES30.glUseProgram(0)
    }

    private fun drawThermalFrame() {
        if (thermalProgram == 0) return

        val frame = thermalFrameProvider?.invoke()
        if (frame != null) {
            uploadThermalTexture(frame)
        }

        GLES30.glUseProgram(thermalProgram)
        GLES30.glBindVertexArray(vao)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, thermalTextureId)
        GLES30.glUniform1i(thermalTextureHandle, 0)

        val scale = computeAspectRatioScale(THERMAL_WIDTH, THERMAL_HEIGHT)
        GLES30.glUniform2f(thermalVertexScaleHandle, scale[0], scale[1])

        drawBothEyes(::drawThermalEye)

        GLES30.glBindVertexArray(0)
        GLES30.glUseProgram(0)
    }

    private fun drawSplitFrame() {
        surfaceTexture?.updateTexImage()
        surfaceTexture?.getTransformMatrix(textureMatrix)

        val frame = thermalFrameProvider?.invoke()
        if (frame != null) {
            uploadThermalTexture(frame)
        }

        // Left eye: phone camera (OES shader)
        if (program != 0) {
            GLES30.glViewport(0, 0, width / 2, height)
            GLES30.glUseProgram(program)
            GLES30.glBindVertexArray(vao)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
            GLES30.glUniform1i(textureHandle, 0)
            GLES30.glUniformMatrix4fv(transformMatrixHandle, 1, false, textureMatrix, 0)

            val scale = computeAspectRatioScale(CAMERA_BUFFER_WIDTH, CAMERA_BUFFER_HEIGHT)
            GLES30.glUniform2f(vertexScaleHandle, scale[0], scale[1])

            drawEye(0.5f - ipdUvOffset, 0.5f)

            GLES30.glBindVertexArray(0)
            GLES30.glUseProgram(0)
        }

        // Right eye: thermal (2D shader with palette)
        if (thermalProgram != 0) {
            GLES30.glViewport(width / 2, 0, width / 2, height)
            GLES30.glUseProgram(thermalProgram)
            GLES30.glBindVertexArray(vao)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, thermalTextureId)
            GLES30.glUniform1i(thermalTextureHandle, 0)

            val scale = computeAspectRatioScale(THERMAL_WIDTH, THERMAL_HEIGHT)
            GLES30.glUniform2f(thermalVertexScaleHandle, scale[0], scale[1])

            drawThermalEye(0.5f + ipdUvOffset, 0.5f)

            GLES30.glBindVertexArray(0)
            GLES30.glUseProgram(0)
        }
    }

    private fun drawBothEyes(eyeDrawer: (Float, Float) -> Unit) {
        // Left eye
        GLES30.glViewport(0, 0, width / 2, height)
        eyeDrawer(0.5f - ipdUvOffset, 0.5f)

        // Right eye
        GLES30.glViewport(width / 2, 0, width / 2, height)
        eyeDrawer(0.5f + ipdUvOffset, 0.5f)
    }

    /**
     * Compute a vertex scale that preserves the camera frame's aspect ratio inside
     * each half-screen eye viewport. This letterboxes (or pillarboxes) the video
     * instead of stretching it to fill the viewport, preventing vertical
     * distortion and keeping the full frame visible.
     */
    private fun computeAspectRatioScale(videoWidth: Int, videoHeight: Int): FloatArray {
        if (width == 0 || height == 0) return floatArrayOf(1.0f, 1.0f)

        val viewportAspect = (width / 2f) / height.toFloat()
        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()

        val scaleX = minOf(1.0f, videoAspect / viewportAspect)
        val scaleY = minOf(1.0f, viewportAspect / videoAspect)
        return floatArrayOf(scaleX, scaleY)
    }

    private fun drawEye(centerX: Float, centerY: Float) {
        GLES30.glUniform2f(centerHandle, centerX, centerY)
        GLES30.glUniform1f(k1Handle, profile.distortionCoefficients[0])
        GLES30.glUniform1f(k2Handle, profile.distortionCoefficients[1])
        GLES30.glUniform1f(scaleHandle, profile.distortionScale())
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawThermalEye(centerX: Float, centerY: Float) {
        GLES30.glUniform2f(thermalCenterHandle, centerX, centerY)
        GLES30.glUniform1f(thermalK1Handle, profile.distortionCoefficients[0])
        GLES30.glUniform1f(thermalK2Handle, profile.distortionCoefficients[1])
        GLES30.glUniform1f(thermalScaleHandle, profile.distortionScale())
        GLES30.glUniform1i(thermalPaletteHandle, palette)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    /**
     * Horizontal UV offset applied to each eye's distortion center.
     *
     * Cardboard v2 has a 64 mm fixed IPD. Phones vary in width, so this is a
     * small empirical correction to keep the lens optical axis aligned with the
     * center of each half-screen viewport. Tune this if the stereo image still
     * feels off-center in the headset.
     */
    private val ipdUvOffset: Float
        get() = (profile.interLensDistance / DEFAULT_IPD_METERS) * 0.02f

    private fun createQuad() {
        val vertices = floatArrayOf(
            // position    // texCoord
            -1.0f, -1.0f, 0.0f, 0.0f,
            1.0f, -1.0f, 1.0f, 0.0f,
            -1.0f, 1.0f, 0.0f, 1.0f,
            1.0f, 1.0f, 1.0f, 1.0f
        )

        val vboArray = IntArray(1)
        val vaoArray = IntArray(1)
        GLES30.glGenBuffers(1, vboArray, 0)
        GLES30.glGenVertexArrays(1, vaoArray, 0)
        vbo = vboArray[0]
        vao = vaoArray[0]

        GLES30.glBindVertexArray(vao)

        val buffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vertices)
                position(0)
            }

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            vertices.size * 4,
            buffer,
            GLES30.GL_STATIC_DRAW
        )

        GLES30.glEnableVertexAttribArray(positionHandle)
        GLES30.glVertexAttribPointer(
            positionHandle,
            2,
            GLES30.GL_FLOAT,
            false,
            4 * 4,
            0
        )

        GLES30.glEnableVertexAttribArray(texCoordHandle)
        GLES30.glVertexAttribPointer(
            texCoordHandle,
            2,
            GLES30.GL_FLOAT,
            false,
            4 * 4,
            2 * 4
        )

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindVertexArray(0)
    }

    private fun createCameraTexture() {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]

        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MIN_FILTER,
            GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MAG_FILTER,
            GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE
        )

        surfaceTexture = SurfaceTexture(cameraTextureId)
        // 1080p gives a noticeably sharper preview than 720p. The per-frame latency
        // optimisations in Camera2Latency.kt keep the pipeline responsive at this size.
        surfaceTexture?.setDefaultBufferSize(CAMERA_BUFFER_WIDTH, CAMERA_BUFFER_HEIGHT)
    }

    private fun createThermalTexture() {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        thermalTextureId = textures[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, thermalTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        // Allocate placeholder storage; real frame data is uploaded each draw call.
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA,
            THERMAL_WIDTH,
            THERMAL_HEIGHT,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            null
        )
    }

    private var uploadedThermalWidth = THERMAL_WIDTH
    private var uploadedThermalHeight = THERMAL_HEIGHT

    private fun uploadThermalTexture(frame: ByteBuffer) {
        val expectedBytes = THERMAL_WIDTH * THERMAL_HEIGHT * 4
        if (frame.remaining() < expectedBytes) {
            Log.w(TAG, "Thermal frame too small: ${frame.remaining()} < $expectedBytes")
            return
        }

        if (uploadedThermalWidth != THERMAL_WIDTH || uploadedThermalHeight != THERMAL_HEIGHT) {
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                GLES30.GL_RGBA,
                THERMAL_WIDTH,
                THERMAL_HEIGHT,
                0,
                GLES30.GL_RGBA,
                GLES30.GL_UNSIGNED_BYTE,
                frame
            )
            uploadedThermalWidth = THERMAL_WIDTH
            uploadedThermalHeight = THERMAL_HEIGHT
        } else {
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                0,
                0,
                THERMAL_WIDTH,
                THERMAL_HEIGHT,
                GLES30.GL_RGBA,
                GLES30.GL_UNSIGNED_BYTE,
                frame
            )
        }
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        if (vertexShader == 0 || fragmentShader == 0) return 0

        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Program link error: ${GLES30.glGetProgramInfoLog(program)}")
            GLES30.glDeleteProgram(program)
            return 0
        }

        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)
        return program
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compile error: ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        private const val TAG = "DistortionRenderer"

        // Cardboard v2 IPD in metres, used to normalise the empirical
        // lens-centre offset.
        private const val DEFAULT_IPD_METERS = 0.064f

        // Camera stream resolution. 1080p matches the phone preview stream native size for
        // the sharpest image; the capture-request tuning keeps frame latency low.
        private const val CAMERA_BUFFER_WIDTH = 1920
        private const val CAMERA_BUFFER_HEIGHT = 1080

        // Infiray T2L-A4L thermal preview resolution. RGBX is 4 bytes per pixel.
        private const val THERMAL_WIDTH = 256
        private const val THERMAL_HEIGHT = 196
    }
}
