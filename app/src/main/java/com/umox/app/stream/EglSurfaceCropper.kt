package com.umox.app.stream

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Konfigurasi geometri proyeksi widescreen Omoway (960x360), Margin Aman HUD,
 * dan Rekayasa GPU Anti-Redup (Daylight / Overlay Boost).
 */
object OmowayViewportConfig {
    @Volatile var outputWidth: Int = 960
    @Volatile var outputHeight: Int = 360

    // 1. Pengaturan Margin Aman HUD Speedometer Omoway (Default AKTIF: 840x270 di dalam 960x360)
    // Menghindari tumpang tindih dengan jam (kiri atas), baterai/status (bawah), dan angka km/h (kanan).
    @Volatile var useClusterSafeMargins: Boolean = true
    @Volatile var marginLeft: Int = 0
    @Volatile var marginRight: Int = 120
    @Volatile var marginTop: Int = 45
    @Volatile var marginBottom: Int = 45

    val contentLeft: Int
        get() = if (useClusterSafeMargins) marginLeft.coerceIn(0, 320) else 0

    val contentBottom: Int
        get() = if (useClusterSafeMargins) marginBottom.coerceIn(0, 140) else 0

    val contentWidth: Int
        get() = if (useClusterSafeMargins) {
            (outputWidth - marginLeft.coerceIn(0, 320) - marginRight.coerceIn(0, 360)).coerceAtLeast(320)
        } else {
            outputWidth
        }

    val contentHeight: Int
        get() = if (useClusterSafeMargins) {
            (outputHeight - marginTop.coerceIn(0, 140) - marginBottom.coerceIn(0, 140)).coerceAtLeast(120)
        } else {
            outputHeight
        }

    // 2. Rekayasa GPU Anti-Overlay Gelap Omoway (Real-time GLSL Fragment Shader Boost)
    // Melawan lapisan gelap transparan bawaan headunit Omoway agar tampilan siang hari tetap terang & tajam.
    @Volatile var colorBoostEnabled: Boolean = true
    @Volatile var boostBrightness: Float = 0.14f   // Range: -0.10f .. +0.45f
    @Volatile var boostContrast: Float = 1.32f     // Range:  0.90f ..  1.90f
    @Volatile var boostGamma: Float = 0.68f        // Range:  0.45f ..  1.10f (< 1.0 mengangkat shadow/midtone!)
    @Volatile var boostSaturation: Float = 1.35f   // Range:  0.80f ..  1.90f
    @Volatile var edgeSharpening: Float = 0.45f    // Range:  0.0f .. 1.0f (Adaptive GPU Edge Sharpening)

    // Dimensi sumber Android Auto (1280x720 dengan active UI 1120x360)
    @Volatile var aaSourceWidth: Int = 1280
    @Volatile var aaSourceHeight: Int = 720
    @Volatile var aaActiveWidth: Int = 1120
    @Volatile var aaWidthMargin: Int = 160
    @Volatile var aaActiveHeight: Int = 360
    @Volatile var aaHeightMargin: Int = 360

    // Holder SurfaceTexture untuk Live Preview di MainActivity (seperti `v.java` di WayXAuto)
    private val previewLock = Any()
    @Volatile var previewSurfaceTexture: SurfaceTexture? = null
    @Volatile var boundPreviewTexture: SurfaceTexture? = null
    @Volatile var previewWidth: Int = 0
    @Volatile var previewHeight: Int = 0
    @Volatile var previewDestroyPending: Boolean = false

    fun attachPreview(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        synchronized(previewLock) {
            previewSurfaceTexture = surfaceTexture
            previewWidth = width
            previewHeight = height
            previewDestroyPending = false
        }
    }

    /**
     * Dipanggil saat layar HP dimatikan atau MainActivity masuk ke background (`onPause`/`onStop`),
     * persis `x.java` lines 31-35 di WayXAuto agar `eglSwapBuffers` pada `TextureView` tidak pernah
     * memblokir thread encoder 60 FPS ketika layar fisik HP mati (`SurfaceFlinger` berhenti).
     */
    fun pausePreview(surfaceTexture: SurfaceTexture?) {
        synchronized(previewLock) {
            if (surfaceTexture == null || previewSurfaceTexture === surfaceTexture) {
                previewSurfaceTexture = null
            }
        }
    }

    fun detachPreview(surfaceTexture: SurfaceTexture): Boolean {
        synchronized(previewLock) {
            if (previewSurfaceTexture === surfaceTexture) {
                previewSurfaceTexture = null
            }
            if (boundPreviewTexture !== surfaceTexture) {
                return true
            }
            previewDestroyPending = true
            return false
        }
    }

    fun withPreviewLock(block: () -> Unit) {
        synchronized(previewLock, block)
    }
}

/**
 * Pipeline OpenGL ES 2.0 Hardware Cropper & 60 FPS Frame Repeater (`f.java` + `u.java` dari WayXAuto).
 *
 * - Menerima frame dekode H.264 Android Auto (1280x720) melalui `GL_TEXTURE_EXTERNAL_OES`.
 * - Melakukan crop presisi tinggi ke area aktif `1120x360` di GPU (Zero-CPU-Copy).
 * - Merender ke `Surface` input milik Hardware H.264 Encoder (`960x360` @ 60 FPS).
 * - Sekaligus merender ke `TextureView` Live Preview di layar HP (@ 30 FPS non-blocking).
 */
class EglSurfaceCropper(encoderInputSurface: Surface) {

    val oesTextureId: Int
    private val glProgram: Int
    private val eglDisplay: EGLDisplay
    private val eglConfig: EGLConfig
    private val eglContext: EGLContext
    private val encoderEglSurface: EGLSurface
    private val vertexBuffer: FloatBuffer

    // State untuk Live Preview Surface (`u.java`)
    private var previewEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var currentPreviewTexture: SurfaceTexture? = null
    private var currentPreviewSurface: Surface? = null
    private var lastPreviewRenderNs: Long = 0L

    init {
        vertexBuffer = ByteBuffer.allocateDirect(64)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw IllegalStateException("EGL initialize failed")
        }

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            0x3142, 1, // EGL_RECORDABLE_ANDROID
            EGL14.EGL_NONE
        )
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
            throw IllegalStateException("EGL recordable config failed")
        }

        eglConfig = configs[0]!!
        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            eglConfig,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0
        )
        encoderEglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            eglConfig,
            encoderInputSurface,
            intArrayOf(EGL14.EGL_NONE),
            0
        )

        if (!EGL14.eglMakeCurrent(eglDisplay, encoderEglSurface, encoderEglSurface, eglContext)) {
            throw IllegalStateException("EGL make current failed")
        }

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        oesTextureId = texIds[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        glProgram = GLES20.glCreateProgram()
        val vs = compileShader(
            GLES20.GL_VERTEX_SHADER,
            "attribute vec2 p;attribute vec2 uv;uniform mat4 transform;uniform vec2 crop;varying vec2 t;" +
                "void main(){gl_Position=vec4(p,0.,1.);t=(transform*vec4((uv-.5)*crop+.5,0.,1.)).xy;}"
        )
        val fs = compileShader(
            GLES20.GL_FRAGMENT_SHADER,
            "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;" +
                "uniform samplerExternalOES image;" +
                "uniform float uBrightness;" +
                "uniform float uContrast;" +
                "uniform float uGamma;" +
                "uniform float uSaturation;" +
                "uniform float uSharpen;" +
                "varying vec2 t;" +
                "void main(){" +
                "vec4 c=texture2D(image,t);" +
                "if (uSharpen > 0.01) {" +
                "vec2 step = vec2(0.0010416, 0.002777);" +
                "vec4 up = texture2D(image, t + vec2(0.0, -step.y));" +
                "vec4 down = texture2D(image, t + vec2(0.0, step.y));" +
                "vec4 left = texture2D(image, t + vec2(-step.x, 0.0));" +
                "vec4 right = texture2D(image, t + vec2(step.x, 0.0));" +
                "vec4 lap = 4.0 * c - (up + down + left + right);" +
                "c = clamp(c + uSharpen * lap, 0.0, 1.0);" +
                "}" +
                "vec3 rgb=clamp(c.rgb,0.0,1.0);" +
                "rgb=pow(rgb,vec3(uGamma));" +
                "rgb=clamp((rgb-0.5)*uContrast+0.5+uBrightness,0.0,1.0);" +
                "float luma=dot(rgb,vec3(0.2126,0.7152,0.0722));" +
                "rgb=mix(vec3(luma),rgb,uSaturation);" +
                "gl_FragColor=vec4(clamp(rgb,0.0,1.0),c.a);" +
                "}"
        )
        GLES20.glAttachShader(glProgram, vs)
        GLES20.glAttachShader(glProgram, fs)
        GLES20.glLinkProgram(glProgram)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(glProgram, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            throw IllegalStateException("GL program link failed")
        }

        vertexBuffer.put(
            floatArrayOf(
                -1.0f, -1.0f, 0.0f, 0.0f,
                 1.0f, -1.0f, 1.0f, 0.0f,
                -1.0f,  1.0f, 0.0f, 1.0f,
                 1.0f,  1.0f, 1.0f, 1.0f
            )
        ).position(0)
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            throw IllegalStateException("GL shader compile failed")
        }
        return shader
    }

    fun renderToEncoder(transformMatrix: FloatArray, cropX: Float, cropY: Float, presentationTimeNs: Long) {
        drawFrame(
            transformMatrix,
            cropX,
            cropY,
            OmowayViewportConfig.outputWidth,
            OmowayViewportConfig.outputHeight,
            useSafeMargins = OmowayViewportConfig.useClusterSafeMargins
        )
        EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderEglSurface, presentationTimeNs)
        if (!EGL14.eglSwapBuffers(eglDisplay, encoderEglSurface)) {
            throw IllegalStateException("EGL swapBuffers failed")
        }
    }

    /**
     * Merender frame yang sama ke `TextureView` di layar HP (maks 30 FPS, non-blocking) seperti `u.java`.
     */
    fun renderToPreviewIfAttached(transformMatrix: FloatArray, cropX: Float, cropY: Float, nowNs: Long) {
        var targetSt: SurfaceTexture? = null
        var w = 0
        var h = 0
        OmowayViewportConfig.withPreviewLock {
            targetSt = OmowayViewportConfig.previewSurfaceTexture
            w = OmowayViewportConfig.previewWidth
            h = OmowayViewportConfig.previewHeight
        }

        if (targetSt !== currentPreviewTexture) {
            releasePreviewSurface()
        }

        val st = targetSt ?: return
        if (w <= 0 || h <= 0 || (nowNs - lastPreviewRenderNs) < 33_000_000L) return
        lastPreviewRenderNs = nowNs

        if (currentPreviewTexture == null) {
            var valid = false
            OmowayViewportConfig.withPreviewLock {
                if (OmowayViewportConfig.previewSurfaceTexture === st) {
                    OmowayViewportConfig.boundPreviewTexture = st
                    currentPreviewTexture = st
                    val s = Surface(st)
                    currentPreviewSurface = s
                    previewEglSurface = EGL14.eglCreateWindowSurface(
                        eglDisplay,
                        eglConfig,
                        s,
                        intArrayOf(EGL14.EGL_NONE),
                        0
                    )
                    valid = previewEglSurface != EGL14.EGL_NO_SURFACE
                }
            }
            if (!valid) {
                releasePreviewSurface()
                return
            }
        }

        try {
            if (!EGL14.eglMakeCurrent(eglDisplay, previewEglSurface, previewEglSurface, eglContext)) {
                releasePreviewSurface()
            } else {
                EGL14.eglSwapInterval(eglDisplay, 0)
                // Tampilkan margin & boost warna yang sama persis di preview layar HP agar pengguna bisa menyetel secara visual
                drawFrame(
                    transformMatrix,
                    cropX,
                    cropY,
                    w,
                    h,
                    useSafeMargins = OmowayViewportConfig.useClusterSafeMargins
                )
                if (!EGL14.eglSwapBuffers(eglDisplay, previewEglSurface)) {
                    releasePreviewSurface()
                }
            }
        } finally {
            EGL14.eglMakeCurrent(eglDisplay, encoderEglSurface, encoderEglSurface, eglContext)
        }
    }

    private fun drawFrame(
        transformMatrix: FloatArray,
        cropX: Float,
        cropY: Float,
        surfaceWidth: Int,
        surfaceHeight: Int,
        useSafeMargins: Boolean
    ) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val outW = OmowayViewportConfig.outputWidth
        val outH = OmowayViewportConfig.outputHeight
        if (useSafeMargins) {
            val vpX = (OmowayViewportConfig.contentLeft * surfaceWidth) / outW
            val vpY = (OmowayViewportConfig.contentBottom * surfaceHeight) / outH
            val vpW = (OmowayViewportConfig.contentWidth * surfaceWidth) / outW
            val vpH = (OmowayViewportConfig.contentHeight * surfaceHeight) / outH
            GLES20.glViewport(vpX, vpY, vpW, vpH)
        } else {
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        }

        GLES20.glUseProgram(glProgram)
        val posLoc = GLES20.glGetAttribLocation(glProgram, "p")
        val uvLoc = GLES20.glGetAttribLocation(glProgram, "uv")

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        vertexBuffer.position(2)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glEnableVertexAttribArray(uvLoc)

        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(glProgram, "transform"), 1, false, transformMatrix, 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(glProgram, "crop"), cropX, cropY)

        val boostActive = OmowayViewportConfig.colorBoostEnabled
        val brightness = if (boostActive) OmowayViewportConfig.boostBrightness else 0.0f
        val contrast = if (boostActive) OmowayViewportConfig.boostContrast else 1.0f
        val gamma = if (boostActive) OmowayViewportConfig.boostGamma else 1.0f
        val saturation = if (boostActive) OmowayViewportConfig.boostSaturation else 1.0f

        GLES20.glUniform1f(GLES20.glGetUniformLocation(glProgram, "uBrightness"), brightness)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(glProgram, "uContrast"), contrast)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(glProgram, "uGamma"), gamma)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(glProgram, "uSaturation"), saturation)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(glProgram, "uSharpen"), OmowayViewportConfig.edgeSharpening)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(glProgram, "image"), 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun releasePreviewSurface() {
        if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
            try {
                EGL14.eglDestroySurface(eglDisplay, previewEglSurface)
            } catch (_: Exception) {}
            previewEglSurface = EGL14.EGL_NO_SURFACE
        }
        try {
            currentPreviewSurface?.release()
        } catch (_: Exception) {}
        currentPreviewSurface = null

        val oldSt = currentPreviewTexture ?: return
        var shouldReleaseSt = false
        OmowayViewportConfig.withPreviewLock {
            if (OmowayViewportConfig.previewDestroyPending && OmowayViewportConfig.boundPreviewTexture === oldSt) {
                shouldReleaseSt = true
            }
            OmowayViewportConfig.boundPreviewTexture = null
            OmowayViewportConfig.previewDestroyPending = false
        }
        if (shouldReleaseSt) {
            try {
                oldSt.release()
            } catch (_: Exception) {}
        }
        currentPreviewTexture = null
    }

    fun release() {
        releasePreviewSurface()
        try {
            GLES20.glDeleteProgram(glProgram)
            GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
        } catch (_: Exception) {}
        try {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, encoderEglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        } catch (_: Exception) {}
    }
}

