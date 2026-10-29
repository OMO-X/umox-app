package com.umox.app.stream

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Pemutar video H.264 Baseline langsung dari assets APK (`test_baseline.h264`).
 * Mengeluarkan `EncodedVideoFrame` terstruktur (`sps`, `pps`, `annexBSlice`, `isKeyFrame`, `presentationTimeUs`)
 * agar 100% sesuai dengan header video Desay SV (`p.java`), serta merender ke Live Preview di layar HP.
 */
class H264AssetPlayer(
    private val context: Context,
    private val assetFilename: String = "test_baseline.h264",
    private val fps: Int = 30,
    private val onFrameReady: (EncodedVideoFrame) -> Unit,
    private val onLog: (String) -> Unit
) {
    private data class ParsedSlice(
        val sliceNalu: ByteArray,
        val isKeyFrame: Boolean
    )

    private var playJob: Job? = null

    @Volatile
    private var isPlaying = false

    @Volatile
    private var requestKeyFrameFlag = true

    private var spsBytes = ByteArray(0)
    private var ppsBytes = ByteArray(0)
    private val slices = mutableListOf<ParsedSlice>()

    private val previewLock = Object()
    private var boundSurfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var previewDecoder: MediaCodec? = null

    private fun ensurePreviewDecoderLocked() {
        val targetSt = OmowayViewportConfig.previewSurfaceTexture
        if (targetSt == null) {
            if (previewDecoder != null) {
                releasePreviewDecoderLocked()
            }
            return
        }
        if (boundSurfaceTexture === targetSt && previewDecoder != null) {
            return
        }
        releasePreviewDecoderLocked()
        if (spsBytes.isEmpty() || ppsBytes.isEmpty()) return
        try {
            val surf = Surface(targetSt)
            if (!surf.isValid) {
                surf.release()
                return
            }
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 960, 360).apply {
                val csd0 = ByteBuffer.allocate(4 + spsBytes.size).apply {
                    put(byteArrayOf(0, 0, 0, 1))
                    put(spsBytes)
                    flip()
                }
                val csd1 = ByteBuffer.allocate(4 + ppsBytes.size).apply {
                    put(byteArrayOf(0, 0, 0, 1))
                    put(ppsBytes)
                    flip()
                }
                setByteBuffer("csd-0", csd0)
                setByteBuffer("csd-1", csd1)
            }
            val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            dec.configure(format, surf, null, 0)
            dec.start()
            boundSurfaceTexture = targetSt
            previewSurface = surf
            previewDecoder = dec
            requestKeyFrameFlag = true
        } catch (_: Exception) {
            releasePreviewDecoderLocked()
        }
    }

    private fun releasePreviewDecoderLocked() {
        val dec = previewDecoder
        previewDecoder = null
        if (dec != null) {
            try {
                dec.stop()
            } catch (_: Exception) {}
            try {
                dec.release()
            } catch (_: Exception) {}
        }
        val surf = previewSurface
        previewSurface = null
        boundSurfaceTexture = null
        if (surf != null) {
            try {
                surf.release()
            } catch (_: Exception) {}
        }
    }

    private fun feedPreviewDecoder(sliceAnnexB: ByteArray, ptsUs: Long) {
        synchronized(previewLock) {
            ensurePreviewDecoderLocked()
            val dec = previewDecoder ?: return
            try {
                val inIdx = dec.dequeueInputBuffer(0)
                if (inIdx >= 0) {
                    val buf = dec.getInputBuffer(inIdx)
                    buf?.clear()
                    buf?.put(sliceAnnexB)
                    dec.queueInputBuffer(inIdx, 0, sliceAnnexB.size, ptsUs, 0)
                }
                val info = MediaCodec.BufferInfo()
                var outIdx = dec.dequeueOutputBuffer(info, 0)
                while (outIdx >= 0) {
                    dec.releaseOutputBuffer(outIdx, true)
                    outIdx = dec.dequeueOutputBuffer(info, 0)
                }
            } catch (_: Exception) {
                releasePreviewDecoderLocked()
            }
        }
    }

    fun loadFrames(): Boolean {
        return try {
            val input: InputStream = context.assets.open(assetFilename)
            val raw = input.readBytes()
            input.close()

            spsBytes = MediaEncoder.extractNaluByType(7, raw)
            ppsBytes = MediaEncoder.extractNaluByType(8, raw)
            slices.clear()

            for (nalu in MediaEncoder.splitNalus(raw)) {
                if (nalu.isEmpty()) continue
                val ntype = nalu[0].toInt() and 0x1F
                if (ntype == 5 || ntype == 1) {
                    val fullNalu = ByteArray(4 + nalu.size)
                    fullNalu[3] = 1
                    System.arraycopy(nalu, 0, fullNalu, 4, nalu.size)
                    slices.add(ParsedSlice(fullNalu, isKeyFrame = (ntype == 5)))
                }
            }

            onLog("✔ Asset $assetFilename siap: ${slices.size} frame (SPS=${spsBytes.size}B, PPS=${ppsBytes.size}B).")
            slices.isNotEmpty()
        } catch (e: Exception) {
            onLog("⚠ Gagal memuat asset $assetFilename: ${e.message}")
            false
        }
    }

    fun start() {
        if (isPlaying) return
        if (slices.isEmpty() && !loadFrames()) return

        isPlaying = true
        val frameIntervalMs = 1000L / fps

        playJob = CoroutineScope(Dispatchers.IO).launch {
            onLog("★ Memancarkan Test Video Baseline ($assetFilename @ $fps FPS)...")
            var frameIdx = 0
            val startUs = SystemClock.elapsedRealtimeNanos() / 1000L
            var emittedCount = 0L

            while (isActive && isPlaying) {
                val t0 = SystemClock.elapsedRealtime()

                if (requestKeyFrameFlag) {
                    requestKeyFrameFlag = false
                    val firstIdr = slices.indexOfFirst { it.isKeyFrame }
                    if (firstIdr >= 0) frameIdx = firstIdr
                }

                val item = slices[frameIdx]
                val ptsUs = startUs + (emittedCount * 1_000_000L / fps)
                val frame = EncodedVideoFrame(
                    annexBSlice = item.sliceNalu,
                    isKeyFrame = item.isKeyFrame,
                    presentationTimeUs = ptsUs,
                    sps = spsBytes,
                    pps = ppsBytes
                )
                onFrameReady(frame)
                feedPreviewDecoder(item.sliceNalu, ptsUs)

                emittedCount++
                frameIdx = (frameIdx + 1) % slices.size

                val elapsed = SystemClock.elapsedRealtime() - t0
                val sleepTime = (frameIntervalMs - elapsed).coerceAtLeast(1L)
                delay(sleepTime)
            }
        }
    }

    fun requestKeyFrame() {
        requestKeyFrameFlag = true
    }

    fun stop() {
        isPlaying = false
        val job = playJob
        playJob = null
        job?.cancel()
        synchronized(previewLock) {
            releasePreviewDecoderLocked()
        }
    }
}

