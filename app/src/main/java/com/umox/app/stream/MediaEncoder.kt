package com.umox.app.stream

import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/**
 * Data paket frame H.264 terstruktur untuk dikirim ke Desay SV Port 9528 (`p.java` di WayXAuto).
 */
data class EncodedVideoFrame(
    val annexBSlice: ByteArray,
    val isKeyFrame: Boolean,
    val presentationTimeUs: Long,
    val sps: ByteArray,
    val pps: ByteArray
)

/**
 * Hardware H.264 Baseline 60 FPS Encoder + OpenGL ES 2.0 Surface Cropper & Frame Repeater.
 *
 * Mengadopsi arsitektur `g.java`, `f.java`, `z8.java`, `qt.java`, dan `r5.java` dari WayXAuto:
 * - Resolusi Output: `960x360` @ `60 FPS`, `4.000.000 bps (4 Mbps)` CBR Baseline Profile.
 * - Forced Keyframe Interval: Setiap **6 frame** (10 Keyframe per detik!) untuk pemulihan instan 0 delay.
 * - 60Hz Frame Repeater: Mengulang frame terakhir di GPU setiap 16.67ms saat layar diam agar speedometer
 *   tidak pernah membeku dan requestKeyFrame() langsung dijawab dalam <16ms.
 */
class MediaEncoder(
    private val width: Int = 960,
    private val height: Int = 360,
    private val dpi: Int = 160,
    bitrate: Int = 3_500_000,
    fps: Int = 60,
    private val onFrameEncoded: (EncodedVideoFrame) -> Unit,
    private val onFirstSourceFrame: (() -> Unit)? = null,
    private val onLog: (String) -> Unit
) {
    private val isRunning = AtomicBoolean(false)
    private val forceKeyFrameRequested = AtomicBoolean(true)
    private val pendingCaptureFrames = AtomicInteger(0)

    private var encoderThread: Thread? = null
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    var decoderInputSurface: Surface? = null
        private set

    @Volatile
    var capturedSourceFrames: Long = 0L
        private set

    @Volatile
    var encodedOutputFrames: Long = 0L
        private set

    @Volatile
    var currentBitrate: Int = bitrate
        private set

    @Volatile
    var currentFps: Int = fps
        private set

    @Volatile
    private var activeCodec: MediaCodec? = null

    /**
     * Mode 1: Mulai pipeline 60 FPS OpenGL Surface Bridge untuk Android Auto (input 1280x720 -> crop 1120x360 -> encode 960x360).
     */
    fun startSurfaceBridge(): Surface? {
        if (isRunning.get()) return decoderInputSurface
        val readyLatch = CountDownLatch(1)
        var startupError: Throwable? = null

        isRunning.set(true)
        encoderThread = Thread({
            runGlEncoderLoop(
                sourceWidth = OmowayViewportConfig.aaSourceWidth,
                sourceHeight = OmowayViewportConfig.aaSourceHeight,
                cropX = OmowayViewportConfig.aaActiveWidth.toFloat() / OmowayViewportConfig.aaSourceWidth.toFloat(),
                cropY = OmowayViewportConfig.aaActiveHeight.toFloat() / OmowayViewportConfig.aaSourceHeight.toFloat(),
                mediaProjection = null,
                onReady = { readyLatch.countDown() },
                onError = { err ->
                    startupError = err
                    readyLatch.countDown()
                }
            )
        }, "UMOX-GlEncoder60").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        readyLatch.await(3500, TimeUnit.MILLISECONDS)
        if (startupError != null || decoderInputSurface == null) {
            onLog("✖ Gagal memulai 60 FPS GL Surface Bridge: ${startupError?.message}")
            stop()
            return null
        }
        onLog("✔ 60 FPS OpenGL H.264 Bridge aktif (${width}x${height} @ ${currentFps}fps, ${currentBitrate / 1000} kbps CBR, KeyInt=6).")
        return decoderInputSurface
    }

    /**
     * Mode 2: Mulai pipeline 60 FPS OpenGL Screen Mirroring (`VirtualDisplay` -> `SurfaceTexture` -> `EglSurfaceCropper` -> `MediaCodec`).
     */
    fun start(mediaProjection: MediaProjection) {
        if (isRunning.get()) return
        val readyLatch = CountDownLatch(1)
        var startupError: Throwable? = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    onLog("MediaProjection dihentikan oleh sistem.")
                    stop()
                }
            }, null)
        }

        isRunning.set(true)
        encoderThread = Thread({
            runGlEncoderLoop(
                sourceWidth = width,
                sourceHeight = height,
                cropX = 1.0f,
                cropY = 1.0f,
                mediaProjection = mediaProjection,
                onReady = { readyLatch.countDown() },
                onError = { err ->
                    startupError = err
                    readyLatch.countDown()
                }
            )
        }, "UMOX-ScreenEncoder60").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        readyLatch.await(3500, TimeUnit.MILLISECONDS)
        if (startupError != null) {
            onLog("✖ Gagal memulai Screen Encoder: ${startupError?.message}")
            stop()
        } else {
            onLog("✔ 60 FPS Zero-Lag Screen Encoder aktif (${width}x${height} @ ${currentFps}fps, ${currentBitrate / 1000} kbps CBR, KeyInt=6).")
        }
    }

    fun requestKeyFrame() {
        forceKeyFrameRequested.set(true)
    }

    /**
     * Mengatur bitrate dan target frame rate secara real-time pada encoder aktif.
     */
    fun updateBitrate(newBitrate: Int, newFps: Int = currentFps) {
        currentBitrate = newBitrate
        currentFps = newFps
        val codec = activeCodec ?: return
        try {
            val bundle = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate)
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec.setParameters(bundle)
            forceKeyFrameRequested.set(true)
            onLog("⚙ Bitrate encoder disesuaikan ke ${newBitrate / 1000} kbps (${newFps} FPS)")
        } catch (e: Exception) {
            onLog("⚠ Gagal mengatur bitrate real-time: ${e.message}")
        }
    }

    private fun runGlEncoderLoop(
        sourceWidth: Int,
        sourceHeight: Int,
        cropX: Float,
        cropY: Float,
        mediaProjection: MediaProjection?,
        onReady: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        var codec: MediaCodec? = null
        var encoderInputSurface: Surface? = null
        var eglCropper: EglSurfaceCropper? = null
        var captureSurfaceTexture: SurfaceTexture? = null
        var captureSurface: Surface? = null
        var callbackThread: HandlerThread? = null

        try {
            codec = createConfiguredEncoder()
            activeCodec = codec
            encoderInputSurface = codec.createInputSurface()
            codec.start()

            eglCropper = EglSurfaceCropper(encoderInputSurface)

            callbackThread = HandlerThread("UMOX-FrameAvailable").apply { start() }
            val callbackHandler = Handler(callbackThread.looper)

            captureSurfaceTexture = SurfaceTexture(eglCropper.oesTextureId).apply {
                setDefaultBufferSize(sourceWidth, sourceHeight)
                setOnFrameAvailableListener({
                    pendingCaptureFrames.incrementAndGet()
                }, callbackHandler)
            }
            captureSurface = Surface(captureSurfaceTexture)
            decoderInputSurface = captureSurface

            if (mediaProjection != null) {
                virtualDisplay = mediaProjection.createVirtualDisplay(
                    "UMOX-ClusterDisplay",
                    sourceWidth,
                    sourceHeight,
                    dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    captureSurface,
                    null,
                    null
                )
            }

            onReady()

            val bufferInfo = MediaCodec.BufferInfo()
            val transformMatrix = FloatArray(16)
            var hasReceivedFirstFrame = false
            var renderedFrames = 0L
            var lastKeyframeRenderedAt = 0L
            var spsBytes = ByteArray(0)
            var ppsBytes = ByteArray(0)

            var frameIntervalNs = 1_000_000_000L / currentFps.coerceIn(15, 60)
            var nextRenderTargetNs = SystemClock.elapsedRealtimeNanos()

            while (isRunning.get()) {
                val loopNowNs = SystemClock.elapsedRealtimeNanos()

                // 1. Konsumsi frame baru dari SurfaceTexture jika tersedia
                var pending = pendingCaptureFrames.getAndSet(0)
                if (pending > 0) {
                    do {
                        captureSurfaceTexture.updateTexImage()
                        capturedSourceFrames++
                        pending--
                    } while (pending > 0)
                    captureSurfaceTexture.getTransformMatrix(transformMatrix)
                    if (!hasReceivedFirstFrame) {
                        hasReceivedFirstFrame = true
                        onFirstSourceFrame?.invoke()
                    }
                }

                // 2. Pacing FPS (render frame baru atau ulangi frame terakhir)
                frameIntervalNs = 1_000_000_000L / currentFps.coerceIn(15, 60)
                val nowNs = SystemClock.elapsedRealtimeNanos()
                if (hasReceivedFirstFrame && nowNs >= nextRenderTargetNs) {
                    // Advance target waktu (seperti `z8.java` di WayXAuto)
                    nextRenderTargetNs += frameIntervalNs
                    if (nextRenderTargetNs <= nowNs) {
                        nextRenderTargetNs += (((nowNs - nextRenderTargetNs) / frameIntervalNs) + 1L) * frameIntervalNs
                    }

                    // Paksa KeyFrame setiap 6 frame (`qt.e` di WayXAuto) atau saat diminta cluster
                    val needSync = forceKeyFrameRequested.getAndSet(false) ||
                        renderedFrames == 0L ||
                        (renderedFrames - lastKeyframeRenderedAt) >= 6L

                    if (needSync) {
                        try {
                            val syncBundle = Bundle().apply {
                                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                            }
                            codec.setParameters(syncBundle)
                            lastKeyframeRenderedAt = renderedFrames
                        } catch (_: Exception) {}
                    }

                    eglCropper.renderToEncoder(transformMatrix, cropX, cropY, nowNs)
                    eglCropper.renderToPreviewIfAttached(transformMatrix, cropX, cropY, nowNs)
                    renderedFrames++
                }

                // 3. Kuras (drain) output MediaCodec secara non-blocking (seperti `g.java` di WayXAuto)
                while (isRunning.get()) {
                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 0L)
                    if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break
                    } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val outFmt = codec.outputFormat
                        val csd0 = outFmt.getByteBuffer("csd-0")?.let { buf ->
                            ByteArray(buf.remaining()).also { buf.get(it) }
                        } ?: ByteArray(0)
                        val csd1 = outFmt.getByteBuffer("csd-1")?.let { buf ->
                            ByteArray(buf.remaining()).also { buf.get(it) }
                        } ?: ByteArray(0)

                        val extractedSps = extractNaluByType(7, csd0)
                        val extractedPps = extractNaluByType(8, if (csd1.isNotEmpty()) csd1 else csd0)
                        if (extractedSps.isNotEmpty()) spsBytes = extractedSps
                        if (extractedPps.isNotEmpty()) ppsBytes = extractedPps
                    } else if (outIdx >= 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val rawBytes = ByteArray(bufferInfo.size)
                            outBuf.get(rawBytes)

                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                val sps = extractNaluByType(7, rawBytes)
                                val pps = extractNaluByType(8, rawBytes)
                                if (sps.isNotEmpty()) spsBytes = sps
                                if (pps.isNotEmpty()) ppsBytes = pps
                            } else {
                                // Pastikan SPS/PPS terekstrak jika disisipkan inline oleh hardware encoder
                                val inlineSps = extractNaluByType(7, rawBytes)
                                val inlinePps = extractNaluByType(8, rawBytes)
                                if (inlineSps.isNotEmpty()) spsBytes = inlineSps
                                if (inlinePps.isNotEmpty()) ppsBytes = inlinePps

                                val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 ||
                                    containsNaluType(5, rawBytes)
                                val normalizedSlice = normalizeAnnexBNalus(rawBytes)
                                if (normalizedSlice.isNotEmpty()) {
                                    encodedOutputFrames++
                                    onFrameEncoded(
                                        EncodedVideoFrame(
                                            annexBSlice = normalizedSlice,
                                            isKeyFrame = isKeyFrame,
                                            presentationTimeUs = bufferInfo.presentationTimeUs,
                                            sps = spsBytes,
                                            pps = ppsBytes
                                        )
                                    )
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    } else {
                        break
                    }
                }

                // 4. Park singkat (maks 2ms) jika belum waktunya frame berikutnya & tidak ada frame baru
                if (pendingCaptureFrames.get() == 0) {
                    val waitNs = if (hasReceivedFirstFrame) {
                        (nextRenderTargetNs - SystemClock.elapsedRealtimeNanos()).coerceIn(250_000L, 2_000_000L)
                    } else {
                        2_000_000L
                    }
                    LockSupport.parkNanos(waitNs)
                }
            }
        } catch (t: Throwable) {
            if (decoderInputSurface == null) {
                onError(t)
            } else if (isRunning.get()) {
                onLog("⚠ GL Encoder loop terhenti: ${t.message}")
            }
        } finally {
            activeCodec = null
            decoderInputSurface = null
            try {
                virtualDisplay?.release()
            } catch (_: Exception) {}
            virtualDisplay = null

            try {
                captureSurface?.release()
            } catch (_: Exception) {}
            try {
                captureSurfaceTexture?.setOnFrameAvailableListener(null)
                captureSurfaceTexture?.release()
            } catch (_: Exception) {}
            try {
                callbackThread?.quitSafely()
            } catch (_: Exception) {}
            try {
                eglCropper?.release()
            } catch (_: Exception) {}
            try {
                codec?.stop()
            } catch (_: Exception) {}
            try {
                codec?.release()
            } catch (_: Exception) {}
            try {
                encoderInputSurface?.release()
            } catch (_: Exception) {}
        }
    }

    private fun createConfiguredEncoder(): MediaCodec {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, currentBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, currentFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (_: Exception) {
            // Fallback jika device menolak profil eksplisit
            val fallbackFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, currentBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, currentFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            codec.configure(fallbackFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        return codec
    }

    fun stop() {
        isRunning.set(false)
        val t = encoderThread
        encoderThread = null
        if (t != null) {
            LockSupport.unpark(t)
            try {
                t.join(800)
            } catch (_: Exception) {}
        }
    }

    companion object {
        /**
         * Memecah buffer H.264 (baik Annex-B maupun AVC 4-byte length) menjadi daftar NALU tanpa start code (`r5.d`).
         */
        fun splitNalus(data: ByteArray): List<ByteArray> {
            val result = ArrayList<ByteArray>()
            if (data.isEmpty()) return result

            if (startCodeLengthAt(0, data) > 0) {
                var i = 0
                while (i < data.size) {
                    val scLen = startCodeLengthAt(i, data)
                    val naluStart = i + scLen
                    i = naluStart
                    while (i < data.size && startCodeLengthAt(i, data) == 0) {
                        i++
                    }
                    if (i > naluStart) {
                        result.add(data.copyOfRange(naluStart, i))
                    }
                }
            } else {
                var i = 0
                while (i + 4 <= data.size) {
                    val len = ((data[i].toInt() and 0xFF) shl 24) or
                        ((data[i + 1].toInt() and 0xFF) shl 16) or
                        ((data[i + 2].toInt() and 0xFF) shl 8) or
                        (data[i + 3].toInt() and 0xFF)
                    i += 4
                    if (len in 1..(data.size - i)) {
                        result.add(data.copyOfRange(i, i + len))
                        i += len
                    } else {
                        break
                    }
                }
            }
            return result
        }

        /**
         * Mengekstrak NALU tipe tertentu (misal 7=SPS, 8=PPS) dengan awalan `00 00 00 01` (`r5.e`).
         */
        fun extractNaluByType(naluType: Int, data: ByteArray): ByteArray {
            for (nalu in splitNalus(data)) {
                if (nalu.isNotEmpty() && (nalu[0].toInt() and 0x1F) == naluType) {
                    val out = ByteArray(4 + nalu.size)
                    out[3] = 1
                    System.arraycopy(nalu, 0, out, 4, nalu.size)
                    return out
                }
            }
            return ByteArray(0)
        }

        fun containsNaluType(naluType: Int, data: ByteArray): Boolean {
            for (nalu in splitNalus(data)) {
                if (nalu.isNotEmpty() && (nalu[0].toInt() and 0x1F) == naluType) {
                    return true
                }
            }
            return false
        }

        /**
         * Menormalisasi seluruh NALU di dalam buffer agar masing-masing diawali `00 00 00 01` (`r5.a`).
         */
        fun normalizeAnnexBNalus(data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(data.size + 16)
            for (nalu in splitNalus(data)) {
                if (nalu.isEmpty()) continue
                out.write(0)
                out.write(0)
                out.write(0)
                out.write(1)
                out.write(nalu, 0, nalu.size)
            }
            return out.toByteArray()
        }

        private fun startCodeLengthAt(idx: Int, data: ByteArray): Int {
            if (idx + 2 < data.size && data[idx] == 0.toByte() && data[idx + 1] == 0.toByte()) {
                if (data[idx + 2] == 1.toByte()) return 3
                if (idx + 3 < data.size && data[idx + 2] == 0.toByte() && data[idx + 3] == 1.toByte()) return 4
            }
            return 0
        }
    }
}
