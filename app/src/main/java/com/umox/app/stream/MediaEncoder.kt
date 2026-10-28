package com.umox.app.stream

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.view.Surface

/**
 * Hardware H.264 Baseline Encoder (960x360) dengan GPU Surface Input (`COLOR_FormatSurface`).
 *
 * Mendukung 2 mode operasi Zero-CPU-Copy:
 * 1. **Hardware Surface Bridge (`startSurfaceBridge`)**: Menerima render langsung dari
 *    Hardware Decoder Android Auto ke `inputSurface` GPU, melakukan scaling hardware ke `960x360`,
 *    menjaga frame rate konstan 30 FPS (`KEY_REPEAT_PREVIOUS_FRAME_AFTER`), dan men-sanitasi NALU.
 * 2. **Screen Mirroring (`start(mediaProjection)`)**: Merekam layar penuh via `VirtualDisplay`.
 */
class MediaEncoder(
    private val width: Int = 960,
    private val height: Int = 360,
    private val dpi: Int = 160,
    private val bitrate: Int = 2_500_000,
    private val fps: Int = 30,
    private val onPacketEncoded: (ByteArray, Boolean) -> Unit,
    private val onLog: (String) -> Unit
) {
    private var mediaCodec: MediaCodec? = null
    private var virtualDisplay: VirtualDisplay? = null

    var inputSurface: Surface? = null
        private set

    @Volatile
    private var isRunning = false
    private var encoderThread: Thread? = null

    private val naluSanitizer = H264NaluSanitizer { cleanPacket, isKeyFrame ->
        onPacketEncoded(cleanPacket, isKeyFrame)
    }

    /**
     * Mode 1: Mulai encoder dan kembalikan `Surface` input GPU (untuk Android Auto Hardware Bridge).
     */
    fun startSurfaceBridge(): Surface? {
        if (isRunning) return inputSurface
        return try {
            configureAndStartCodec()
            onLog("✔ Hardware H.264 Surface Bridge aktif (${width}x${height} @ ${fps}fps, ${bitrate / 1000} kbps CBR).")
            inputSurface
        } catch (e: Exception) {
            onLog("✖ Gagal memulai Hardware Surface Bridge: ${e.message}")
            stop()
            null
        }
    }

    /**
     * Mode 2: Mulai encoder dengan `MediaProjection` untuk Screen Mirroring.
     */
    fun start(mediaProjection: MediaProjection) {
        if (isRunning) return
        try {
            configureAndStartCodec()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mediaProjection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        onLog("MediaProjection dihentikan oleh sistem.")
                        stop()
                    }
                }, null)
            }

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "UMOX-ClusterDisplay",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                null
            )

            onLog("✔ Hardware Screen Encoder aktif (${width}x${height} @ ${fps}fps, Baseline CBR).")
        } catch (e: Exception) {
            onLog("✖ Gagal memulai MediaEncoder: ${e.message}")
            stop()
        }
    }

    private fun configureAndStartCodec() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // Keyframe setiap 1 detik

            // KRUSIAL: Paksa GraphicBufferSource mengulang frame terakhir setiap 33.3ms (30 FPS)
            // ketika layar/peta sedang diam, sehingga stream tidak pernah membeku 5-10 detik
            // dan requestKeyFrame() selalu direspons instan (<33ms)!
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
            }

            // Prioritas realtime (0) & Low Latency
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }

            // H.264 Baseline Profile Level 3.1 (wajib untuk decoder Linux Desay SV)
            setInteger(
                MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
            )
            setInteger(
                MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.AVCLevel31
            )
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            )
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        mediaCodec = codec

        isRunning = true
        encoderThread = Thread({ drainEncoderLoop() }, "UMOX-HwEncoder").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /**
     * Meminta encoder menghasilkan IDR KeyFrame secepatnya (ketika cluster mengirim Msg 11).
     */
    fun requestKeyFrame() {
        if (!isRunning) return
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            mediaCodec?.setParameters(params)
        } catch (_: Exception) {}
    }

    private fun drainEncoderLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        var scratchBuf = ByteArray(65536)

        while (isRunning) {
            val codec = mediaCodec ?: break
            try {
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        // Ambil csd-0 (SPS) dan csd-1 (PPS) dari format output jika tersedia
                        newFormat.getByteBuffer("csd-0")?.let { csd0 ->
                            val bytes = ByteArray(csd0.remaining())
                            csd0.get(bytes)
                            naluSanitizer.processAnnexB(bytes, 0, bytes.size)
                        }
                        newFormat.getByteBuffer("csd-1")?.let { csd1 ->
                            val bytes = ByteArray(csd1.remaining())
                            csd1.get(bytes)
                            naluSanitizer.processAnnexB(bytes, 0, bytes.size)
                        }
                    }
                    outIndex >= 0 -> {
                        val encodedBuffer = codec.getOutputBuffer(outIndex)
                        if (encodedBuffer != null && bufferInfo.size > 0) {
                            encodedBuffer.position(bufferInfo.offset)
                            encodedBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            if (scratchBuf.size < bufferInfo.size) {
                                scratchBuf = ByteArray(bufferInfo.size + 8192)
                            }
                            encodedBuffer.get(scratchBuf, 0, bufferInfo.size)

                            // Sanitasi NALU (4-byte start code, strip SEI/AUD, gabung SPS+PPS+IDR)
                            naluSanitizer.processAnnexB(scratchBuf, 0, bufferInfo.size)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    onLog("⚠ Encoder loop warning: ${e.message}")
                }
                break
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            encoderThread?.join(400)
        } catch (_: Exception) {}
        encoderThread = null

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
    }
}
