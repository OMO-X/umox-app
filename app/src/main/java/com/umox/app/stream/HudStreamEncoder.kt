package com.umox.app.stream

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import com.umox.app.ui.HudDashboardRenderer
import kotlinx.coroutines.*
import java.nio.ByteBuffer

/**
 * Encoder H.264 Baseline mandiri untuk merender grafis HUD Cockpit Digital (960x360 @ 30 FPS).
 * Menggunakan pengkodean buffer-ke-buffer YUV420 (NV12/I420) yang 100% kompatibel di seluruh chip
 * hardware Android (Snapdragon, Exynos, MediaTek) tanpa ketergantungan surface atau dialog izin sistem.
 */
class HudStreamEncoder(
    private val width: Int = 960,
    private val height: Int = 360,
    private val fps: Int = 30,
    private val bitrate: Int = 2_000_000,
    private val onPacketEncoded: (ByteArray) -> Unit,
    private val onLog: (String) -> Unit
) {
    private var mediaCodec: MediaCodec? = null
    private var encodeJob: Job? = null
    private var renderJob: Job? = null
    @Volatile
    private var isRunning = false
    @Volatile
    private var isSemiPlanar = true

    // Buffer pre-alokasi untuk mencegah GC (Garbage Collection) pada loop 30 FPS
    private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val argbPixels = IntArray(width * height)
    private val yuvBuffer = ByteArray(width * height * 3 / 2)

    private fun findEncoder(): MediaCodecInfo? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            val types = info.supportedTypes
            for (type in types) {
                if (type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)) {
                    return info
                }
            }
        }
        return null
    }

    private fun initCodec(): Boolean {
        try {
            val encoderInfo = findEncoder()
            val caps = encoderInfo?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val supportedFormats = caps?.colorFormats ?: intArrayOf()

            var chosenFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            if (supportedFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)) {
                chosenFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                isSemiPlanar = true
            } else if (supportedFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)) {
                chosenFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                isSemiPlanar = true
            } else if (supportedFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)) {
                chosenFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                isSemiPlanar = false
            }

            onLog("Konfigurasi HUD Codec: ${encoderInfo?.name} (Color: $chosenFormat, Baseline Profile 3.1, 2 Mbps)...")

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, chosenFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 detik I-frame
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                // Wajib Baseline Profile untuk Desay SV Linux Decoder
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                // Opsi Qualcomm Snapdragon CAVLC
                setInteger("vendor.qti-ext-enc-entropy-mode.value", 0)
                setInteger("prepend-sps-pps-to-idr-frames", 1)
                setInteger("latency", 0)
                setInteger("priority", 0)
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            mediaCodec = codec
            return true
        } catch (e: Exception) {
            onLog("Gagal inisialisasi HUD Video Encoder: ${e.message}")
            return false
        }
    }

    fun start(renderer: HudDashboardRenderer) {
        if (isRunning) return
        if (!initCodec()) return

        isRunning = true
        val codec = mediaCodec ?: return

        // 1. Thread Penerima Hasil Encode H.264 (Output Loop)
        encodeJob = CoroutineScope(Dispatchers.IO).launch {
            val bufferInfo = MediaCodec.BufferInfo()
            var spsPpsBytes: ByteArray? = null

            while (isActive && isRunning) {
                val outputBufferIndex = try {
                    codec.dequeueOutputBuffer(bufferInfo, 10_000)
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
                                    val hasSps = hasSpsInPacket(outData)
                                    if (isKeyFrame && !hasSps && spsPpsBytes != null) {
                                        val combined = ByteArray(spsPpsBytes.size + outData.size)
                                        System.arraycopy(spsPpsBytes, 0, combined, 0, spsPpsBytes.size)
                                        System.arraycopy(outData, 0, combined, spsPpsBytes.size, outData.size)
                                        onPacketEncoded(combined)
                                    } else {
                                        onPacketEncoded(outData)
                                    }
                                }
                            }
                            codec.releaseOutputBuffer(outputBufferIndex, false)
                        } catch (_: Exception) {}
                    }
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        try {
                            val newFormat = codec.outputFormat
                            onLog("Format encoder HUD: $newFormat")
                            val csd0 = newFormat.getByteBuffer("csd-0")
                            val csd1 = newFormat.getByteBuffer("csd-1")
                            if (csd0 != null && csd1 != null) {
                                val sps = ByteArray(csd0.remaining()).also { csd0.get(it) }
                                val pps = ByteArray(csd1.remaining()).also { csd1.get(it) }
                                val combined = ByteArray(sps.size + pps.size)
                                System.arraycopy(sps, 0, combined, 0, sps.size)
                                System.arraycopy(pps, 0, combined, sps.size, pps.size)
                                spsPpsBytes = combined
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        // 2. Thread Pengirim Frame Grafis HUD (Render & Input Loop @ 30 FPS)
        renderJob = CoroutineScope(Dispatchers.Default).launch {
            onLog("★ HUD COCKPIT DIGITAL AKTIF (@ $fps FPS, Resolusi 960x360)...")
            val frameIntervalMs = 1000L / fps
            val frameTimeUs = 1_000_000L / fps
            var pts = 0L

            while (isActive && isRunning) {
                val t0 = System.currentTimeMillis()

                // Render tampilan dashboard terbaru ke canvas
                renderer.render(canvas)
                bitmap.getPixels(argbPixels, 0, width, 0, 0, width, height)

                // Konversi ARGB ke format YUV420 hardware
                if (isSemiPlanar) {
                    argbToNv12(argbPixels, yuvBuffer, width, height)
                } else {
                    argbToI420(argbPixels, yuvBuffer, width, height)
                }

                // Masukkan buffer YUV ke encoder
                val inputIndex = try {
                    codec.dequeueInputBuffer(10_000)
                } catch (e: Exception) {
                    -1
                }

                if (inputIndex >= 0) {
                    val inputBuffer: ByteBuffer? = codec.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        inputBuffer.put(yuvBuffer)
                        codec.queueInputBuffer(inputIndex, 0, yuvBuffer.size, pts, 0)
                        pts += frameTimeUs
                    }
                }

                val elapsed = System.currentTimeMillis() - t0
                val sleepTime = (frameIntervalMs - elapsed).coerceAtLeast(0L)
                delay(sleepTime)
            }
        }
    }

    private fun hasSpsInPacket(data: ByteArray): Boolean {
        var i = 0
        while (i < data.size - 4) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) {
                    val ntype = data[i + 3].toInt() and 0x1F
                    if (ntype == 7) return true
                    i += 3
                } else if (data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    val ntype = data[i + 4].toInt() and 0x1F
                    if (ntype == 7) return true
                    i += 4
                } else {
                    i++
                }
            } else {
                i++
            }
        }
        return false
    }

    private fun argbToNv12(argb: IntArray, yuv: ByteArray, w: Int, h: Int) {
        val frameSize = w * h
        var yIndex = 0
        var uvIndex = frameSize

        for (j in 0 until h) {
            for (i in 0 until w) {
                val c = argb[j * w + i]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yIndex++] = y.coerceIn(0, 255).toByte()

                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                    yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
    }

    private fun argbToI420(argb: IntArray, yuv: ByteArray, w: Int, h: Int) {
        val frameSize = w * h
        val qSize = frameSize / 4
        var yIndex = 0
        var uIndex = frameSize
        var vIndex = frameSize + qSize

        for (j in 0 until h) {
            for (i in 0 until w) {
                val c = argb[j * w + i]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yIndex++] = y.coerceIn(0, 255).toByte()

                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    yuv[uIndex++] = u.coerceIn(0, 255).toByte()
                    yuv[vIndex++] = v.coerceIn(0, 255).toByte()
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
        } catch (_: Exception) {}
    }

    fun stop() {
        isRunning = false
        renderJob?.cancel()
        encodeJob?.cancel()
        renderJob = null
        encodeJob = null
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null
    }
}
