package com.umox.app.stream

import android.content.Context
import kotlinx.coroutines.*
import java.io.InputStream

/**
 * Pemutar video H.264 Baseline langsung dari assets APK.
 * Memancarkan frame H.264 Baseline (Constrained Baseline, Level 3.1) @ 30 FPS
 * yang terbukti 100% kompatibel dan tampil sempurna di speedometer Desay SV Omoway.
 */
class H264AssetPlayer(
    private val context: Context,
    private val assetFilename: String = "test_baseline.h264",
    private val fps: Int = 30,
    private val onPacketReady: (ByteArray) -> Unit,
    private val onLog: (String) -> Unit
) {
    private var playJob: Job? = null
    @Volatile
    private var isPlaying = false
    @Volatile
    private var requestKeyFrameFlag = false

    private val frames = mutableListOf<ByteArray>()

    fun loadFrames(): Boolean {
        try {
            val input: InputStream = context.assets.open(assetFilename)
            val raw = input.readBytes()
            input.close()

            // 1. Temukan seluruh start code NALU (00 00 00 01 atau 00 00 01)
            val indices = mutableListOf<Pair<Int, Int>>() // Pair(startPos, scLen)
            var i = 0
            while (i < raw.size - 2) {
                if (raw[i] == 0.toByte() && raw[i + 1] == 0.toByte() && raw[i + 2] == 1.toByte()) {
                    val scLen = if (i > 0 && raw[i - 1] == 0.toByte()) 4 else 3
                    val startPos = if (scLen == 4) i - 1 else i
                    indices.add(Pair(startPos, scLen))
                    i += 3
                } else {
                    i++
                }
            }

            val nalus = mutableListOf<ByteArray>()
            for (j in 0 until indices.size) {
                val (startPos, scLen) = indices[j]
                val endPos = if (j + 1 < indices.size) indices[j + 1].first else raw.size
                val naluData = ByteArray(endPos - (startPos + scLen))
                System.arraycopy(raw, startPos + scLen, naluData, 0, naluData.size)
                // Normalize dengan 4-byte Annex-B start code (00 00 00 01)
                val fullNalu = ByteArray(4 + naluData.size)
                fullNalu[0] = 0; fullNalu[1] = 0; fullNalu[2] = 0; fullNalu[3] = 1
                System.arraycopy(naluData, 0, fullNalu, 4, naluData.size)
                nalus.add(fullNalu)
            }

            // 2. Kelompokkan SPS (7) + PPS (8) + IDR (5) menjadi satu paket KeyFrame utuh
            frames.clear()
            var sps = byteArrayOf()
            var pps = byteArrayOf()
            for (n in nalus) {
                val ntype = n[4].toInt() and 0x1F
                when (ntype) {
                    7 -> { // SPS
                        sps = n
                    }
                    8 -> { // PPS
                        pps = n
                    }
                    5 -> { // IDR Slice
                        val keyframe = mutableListOf<Byte>()
                        if (sps.isNotEmpty()) keyframe.addAll(sps.toList())
                        if (pps.isNotEmpty()) keyframe.addAll(pps.toList())
                        keyframe.addAll(n.toList())
                        frames.add(keyframe.toByteArray())
                    }
                    1 -> { // Non-IDR P-Frame
                        frames.add(n)
                    }
                }
            }

            onLog("✔ Berhasil memuat $assetFilename: ${frames.size} frame H.264 Baseline siap tayang!")
            return frames.isNotEmpty()
        } catch (e: Exception) {
            onLog("⚠ Gagal memuat asset $assetFilename: ${e.message}")
            return false
        }
    }

    fun start() {
        if (isPlaying) return
        if (frames.isEmpty()) {
            if (!loadFrames()) return
        }

        isPlaying = true
        val frameIntervalMs = 1000L / fps

        playJob = CoroutineScope(Dispatchers.IO).launch {
            onLog("★ MEMANCARKAN STREAM VIDEO HUD STANDALONE KE SPEEDOMETER (@ $fps FPS)...")
            var frameIdx = 0

            while (isActive && isPlaying) {
                val t0 = System.currentTimeMillis()

                if (requestKeyFrameFlag) {
                    requestKeyFrameFlag = false
                    frameIdx = 0 // Reset ke Frame 0 (SPS + PPS + IDR KeyFrame)
                    onLog("► Menyuntikkan IDR KeyFrame atas permintaan speedometer!")
                }

                val frame = frames[frameIdx]
                onPacketReady(frame)

                frameIdx = (frameIdx + 1) % frames.size

                val elapsed = System.currentTimeMillis() - t0
                val sleepTime = (frameIntervalMs - elapsed).coerceAtLeast(0L)
                delay(sleepTime)
            }
        }
    }

    fun requestKeyFrame() {
        requestKeyFrameFlag = true
    }

    fun stop() {
        isPlaying = false
        playJob?.cancel()
        playJob = null
    }
}
