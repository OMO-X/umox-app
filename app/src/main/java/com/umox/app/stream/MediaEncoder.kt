package com.umox.app.stream

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MediaEncoder(
    private val width: Int = 960,
    private val height: Int = 360,
    private val dpi: Int = 160,
    private val bitrate: Int = 2_000_000, // 2 Mbps
    private val fps: Int = 30,
    private val onPacketEncoded: (ByteArray, Boolean) -> Unit,
    private val onLog: (String) -> Unit
) {
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encodeJob: Job? = null
    private var renderJob: Job? = null
    private var isEncoding = false

    private fun initCodec() {
        onLog("Mengonfigurasi H.264 Hardware Encoder ($width x $height @ $fps fps, ${bitrate / 1_000_000} Mbps)...")
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 detik I-frame
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger("latency", 0)
            setInteger("priority", 0)
            setInteger("prepend-sps-pps-to-idr-frames", 1) // Injeksi otomatis SPS/PPS di setiap I-frame
        }

        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            start()
        }
    }

    private fun startOutputLoop() {
        isEncoding = true
        encodeJob = CoroutineScope(Dispatchers.IO).launch {
            val bufferInfo = MediaCodec.BufferInfo()
            var spsPpsBytes: ByteArray? = null

            while (isActive && isEncoding) {
                val codec = mediaCodec ?: break
                val outputBufferIndex = try {
                    codec.dequeueOutputBuffer(bufferInfo, 10_000)
                } catch (e: IllegalStateException) {
                    // Codec telah dihentikan/direlease saat dequeue berlangsung
                    break
                } catch (e: Exception) {
                    break
                }

                when {
                    outputBufferIndex >= 0 -> {
                        try {
                            val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                            if (outputBuffer != null && bufferInfo.size > 0) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                                val outData = ByteArray(bufferInfo.size)
                                outputBuffer.get(outData)

                                val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

                                if (isConfig) {
                                    spsPpsBytes = outData
                                } else {
                                    val hasSps = outData.size >= 5 &&
                                            outData[0] == 0.toByte() && outData[1] == 0.toByte() &&
                                            outData[2] == 0.toByte() && outData[3] == 1.toByte() &&
                                            (outData[4].toInt() and 0x1F) == 7

                                    if (isKeyFrame && !hasSps && spsPpsBytes != null) {
                                        val combined = ByteArray(spsPpsBytes.size + outData.size)
                                        System.arraycopy(spsPpsBytes, 0, combined, 0, spsPpsBytes.size)
                                        System.arraycopy(outData, 0, combined, spsPpsBytes.size, outData.size)
                                        onPacketEncoded(combined, true)
                                    } else {
                                        onPacketEncoded(outData, isKeyFrame)
                                    }
                                }
                            }
                            codec.releaseOutputBuffer(outputBufferIndex, false)
                        } catch (e: Exception) {
                            // Buffer release safe guard
                        }
                    }
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        try {
                            val newFormat = codec.outputFormat
                            onLog("Format output encoder berubah: $newFormat")

                            val csd0 = newFormat.getByteBuffer("csd-0")
                            val csd1 = newFormat.getByteBuffer("csd-1")
                            if (csd0 != null && csd1 != null) {
                                val sps = ByteArray(csd0.remaining()).also { csd0.get(it) }
                                val pps = ByteArray(csd1.remaining()).also { csd1.get(it) }
                                val combined = ByteArray(sps.size + pps.size)
                                System.arraycopy(sps, 0, combined, 0, sps.size)
                                System.arraycopy(pps, 0, combined, sps.size, pps.size)
                                spsPpsBytes = combined
                                onLog("Header SPS/PPS (${combined.size} bytes) siap diinjeksi ke KeyFrame!")
                            }
                        } catch (e: Exception) {
                            // ignore
                        }
                    }
                }
            }
        }
    }

    fun requestKeyFrame() {
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            mediaCodec?.setParameters(params)
            onLog("► Memicu generasi I-Frame (Sync Frame) baru pada encoder...")
        } catch (e: Exception) {
            // ignore
        }
    }

    fun start(mediaProjection: MediaProjection) {
        if (isEncoding) return

        try {
            initCodec()

            // Diwajibkan di Android 14 (API 34+) sebelum createVirtualDisplay
            mediaProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stop()
                }
            }, Handler(Looper.getMainLooper()))

            // VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR mewajibkan Android merender layar utama HP ke virtual display!
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "UMOX-Speedometer-Cast",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                null
            )

            startOutputLoop()
            onLog("Encoder aktif. Memproyeksikan layar HP langsung ke $width x $height...")
            // Minta keyframe pertama
            requestKeyFrame()
        } catch (e: Exception) {
            onLog("Gagal memulai MediaEncoder: ${e.message}")
            stop()
        }
    }

    var hudRenderer: com.umox.app.ui.HudDashboardRenderer? = null
        private set

    /**
     * Mode Pure Cast / Android Auto HUD Dashboard (960x360 @ 30 FPS).
     * Merender grafis HUD navigasi & speedometer independen ke input surface encoder tanpa rekam layar HP.
     */
    fun startPatternTest() {
        if (isEncoding) return

        try {
            initCodec()
            startOutputLoop()

            val surface = inputSurface ?: return
            val renderer = com.umox.app.ui.HudDashboardRenderer(width, height)
            hudRenderer = renderer

            renderJob = CoroutineScope(Dispatchers.Default).launch {
                onLog("★ PURE ANDROID AUTO HUD AKTIF: Merender Cockpit 960x360 ke Speedometer...")
                var frameCount = 0L

                while (isActive && isEncoding) {
                    val canvas: Canvas? = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            surface.lockHardwareCanvas()
                        } else {
                            surface.lockCanvas(null)
                        }
                    } catch (e: Exception) {
                        null
                    }

                    if (canvas != null) {
                        try {
                            renderer.render(canvas)
                        } finally {
                            try {
                                surface.unlockCanvasAndPost(canvas)
                            } catch (e: Exception) {
                                // Surface may have been released
                            }
                        }
                    }

                    frameCount++
                    delay(1000L / fps)
                }
            }
            onLog("Encoder HUD Cockpit berhasil dimulai ($width x $height @ $fps fps).")
        } catch (e: Exception) {
            onLog("Gagal memulai HUD Cockpit: ${e.message}")
            stop()
        }
    }

    fun stop() {
        if (!isEncoding && mediaCodec == null) return
        isEncoding = false
        renderJob?.cancel()
        renderJob = null

        encodeJob?.cancel()
        encodeJob = null

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {}
        virtualDisplay = null

        try {
            mediaCodec?.stop()
        } catch (_: Exception) {}
        try {
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null

        try {
            inputSurface?.release()
        } catch (_: Exception) {}
        inputSurface = null
        onLog("Encoder video dihentikan.")
    }
}
