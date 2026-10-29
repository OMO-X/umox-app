package com.umox.app.stream

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

/**
 * Klien TCP Real-Time Zero-Delay untuk Port 9528 (Saluran Video H.264 Desay SV).
 *
 * Mengadopsi arsitektur `t.java`, `p.java`, dan `bd.java` (Bounded Live Queue) dari WayXAuto:
 * 1. **Wi-Fi Direct Route Binding**: Mengikat soket langsung ke interface Wi-Fi Direct (`192.168.2.x`)
 *    agar koneksi ke `192.168.2.1:9528` tidak pernah dibelokkan ke 4G/5G.
 * 2. **16 KB Kernel Send Buffer (`SO_SNDBUF = 16384`)**: Mencegah penumpukan paket (bufferbloat) di OS kernel.
 * 3. **Bounded Live Queue (`bd.java`)**: Antrean maksimum **6 frame** atau umur maksimum **100 ms**.
 *    Apabila antrean melampaui batas tersebut, frame lama langsung dibuang (flush) dan encoder
 *    diminta memancarkan IDR KeyFrame baru seketika.
 * 4. **Desay SV 18-Byte Video Framing (`p.java`)**:
 *    `[Ch=2 (4B LE)][Len (4B LE)][Codec=1 (1B)][Type=1|2 (1B)][PtsUs (8B LE)][if IDR: spsLen(2B)+sps+ppsLen(2B)+pps][Slice]`.
 */
class SocketStreamer(
    private val context: Context? = null,
    private val targetIp: String = "192.168.2.1",
    private val targetPort: Int = 9528,
    private val onLog: (String) -> Unit
) {
    companion object {
        private const val MAX_QUEUE_FRAMES = 6
        private const val MAX_QUEUE_AGE_NS = 100_000_000L // 100 ms

        @Volatile
        var currentFps: Int = 0
            private set

        @Volatile
        var currentKbps: Int = 0
            private set

        @Volatile
        var totalFramesSentGlobal: Long = 0L
            private set
    }

    private class QueuedPacket(
        val wireBytes: ByteArray,
        val enqueuedAtNs: Long
    )

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    /**
     * Gate apakah cluster siap menerima frame video (aktif segera setelah koneksi / ECDH).
     */
    @Volatile
    var isSinkReady: Boolean = true

    private val queueLock = Object()
    private val liveQueue = ArrayDeque<QueuedPacket>()
    private var senderThread: Thread? = null

    private var cachedSps = ByteArray(0)
    private var cachedPps = ByteArray(0)

    private var frameCountWindow = 0
    private var byteCountWindow = 0L
    private var lastWindowTime = System.currentTimeMillis()

    var onConnectionLost: (() -> Unit)? = null
    var onRequestKeyFrame: (() -> Unit)? = null

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            disconnect()
            onLog("Menghubungkan soket video TCP ke $targetIp:$targetPort (Wi-Fi Direct Bound, 16KB Buf)...")
            val s = WifiDirectNetworkBinder.createBoundSocket(
                context = context,
                targetIp = targetIp,
                targetPort = targetPort,
                connectTimeoutMs = 2500,
                sendBufferBytes = 16384
            )

            socket = s
            outputStream = s.getOutputStream()
            isConnected = true
            frameCountWindow = 0
            byteCountWindow = 0L
            lastWindowTime = System.currentTimeMillis()

            synchronized(queueLock) {
                liveQueue.clear()
            }

            senderThread = Thread({ runSenderLoop() }, "UMOX-VideoSender").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

            onLog("✔ Soket Video (Port $targetPort) terhubung [16KB Low-Latency + BoundedQueue 100ms].")
            onRequestKeyFrame?.invoke()
            true
        } catch (e: Exception) {
            onLog("✖ Gagal terhubung ke soket video $targetPort: ${e.message}")
            isConnected = false
            false
        }
    }

    /**
     * Mengemas `EncodedVideoFrame` dengan header Desay SV (`p.java`) dan memasukkannya ke Bounded Live Queue (`bd.java`).
     */
    fun enqueueVideoFrame(frame: EncodedVideoFrame) {
        if (!isConnected || !isSinkReady) return
        if (frame.sps.isNotEmpty()) cachedSps = frame.sps
        if (frame.pps.isNotEmpty()) cachedPps = frame.pps

        val spsToUse = if (frame.sps.isNotEmpty()) frame.sps else cachedSps
        val ppsToUse = if (frame.pps.isNotEmpty()) frame.pps else cachedPps

        val isKey = frame.isKeyFrame && spsToUse.isNotEmpty() && ppsToUse.isNotEmpty()
        val extraCfgLen = if (isKey) (2 + spsToUse.size + 2 + ppsToUse.size) else 0
        val sliceLen = frame.annexBSlice.size
        if (sliceLen == 0) return

        val packet = ByteBuffer.allocate(18 + extraCfgLen + sliceLen)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                putInt(2) // Channel = 2 (Video)
                putInt(10 + extraCfgLen + sliceLen) // Inner payload length
                put(1.toByte()) // Codec version = 1
                put((if (frame.isKeyFrame) 1 else 2).toByte()) // 1 = IDR KeyFrame, 2 = P-Frame
                putLong(frame.presentationTimeUs)
                if (isKey) {
                    putShort(spsToUse.size.toShort())
                    put(spsToUse)
                    putShort(ppsToUse.size.toShort())
                    put(ppsToUse)
                }
                put(frame.annexBSlice)
            }
            .array()

        val nowNs = SystemClock.elapsedRealtimeNanos()
        var droppedStale = false

        synchronized(queueLock) {
            liveQueue.addLast(QueuedPacket(packet, nowNs))
            val oldest = liveQueue.peekFirst()
            if (liveQueue.size > MAX_QUEUE_FRAMES ||
                (oldest != null && (nowNs - oldest.enqueuedAtNs) > MAX_QUEUE_AGE_NS)
            ) {
                liveQueue.clear()
                droppedStale = true
            } else {
                queueLock.notifyAll()
            }
        }

        if (droppedStale) {
            // Antrean sempat padat >100ms, minta IDR KeyFrame baru seketika agar 0 delay!
            onRequestKeyFrame?.invoke()
        }
    }

    /**
     * Kompatibilitas langsung untuk pengirim raw Annex-B (seperti `H264AssetPlayer`).
     */
    fun sendH264Packet(h264Data: ByteArray) {
        if (!isConnected || h264Data.isEmpty()) return
        val sps = MediaEncoder.extractNaluByType(7, h264Data)
        val pps = MediaEncoder.extractNaluByType(8, h264Data)
        if (sps.isNotEmpty()) cachedSps = sps
        if (pps.isNotEmpty()) cachedPps = pps

        val isKey = MediaEncoder.containsNaluType(5, h264Data)
        val normalized = MediaEncoder.normalizeAnnexBNalus(h264Data)
        enqueueVideoFrame(
            EncodedVideoFrame(
                annexBSlice = normalized,
                isKeyFrame = isKey,
                presentationTimeUs = SystemClock.elapsedRealtimeNanos() / 1000L,
                sps = cachedSps,
                pps = cachedPps
            )
        )
    }

    private fun runSenderLoop() {
        while (isConnected) {
            val item: QueuedPacket = synchronized(queueLock) {
                while (isConnected && liveQueue.isEmpty()) {
                    try {
                        queueLock.wait(250L)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
                if (!isConnected) return
                liveQueue.pollFirst()
            } ?: continue

            // Cek kembali umur paket sebelum tulis ke soket (`bd.java` line 43)
            if ((SystemClock.elapsedRealtimeNanos() - item.enqueuedAtNs) > MAX_QUEUE_AGE_NS) {
                synchronized(queueLock) {
                    liveQueue.clear()
                }
                onRequestKeyFrame?.invoke()
                continue
            }

            try {
                val out = outputStream ?: break
                out.write(item.wireBytes)
                out.flush()

                totalFramesSentGlobal++
                frameCountWindow++
                byteCountWindow += item.wireBytes.size

                val now = System.currentTimeMillis()
                val elapsed = now - lastWindowTime
                if (elapsed >= 1000L) {
                    currentFps = ((frameCountWindow * 1000L) / elapsed).toInt()
                    currentKbps = ((byteCountWindow * 8L) / elapsed).toInt()
                    frameCountWindow = 0
                    byteCountWindow = 0L
                    lastWindowTime = now
                }
            } catch (e: Exception) {
                handleSocketError("Transmisi video terputus: ${e.message}")
                break
            }
        }
    }

    private fun handleSocketError(reason: String) {
        if (!isConnected) return
        isConnected = false
        currentFps = 0
        currentKbps = 0
        synchronized(queueLock) {
            liveQueue.clear()
            queueLock.notifyAll()
        }
        onLog("⚠ Soket video ($targetPort) terputus: $reason")
        onConnectionLost?.invoke()
    }

    fun disconnect() {
        isConnected = false
        currentFps = 0
        currentKbps = 0
        synchronized(queueLock) {
            liveQueue.clear()
            queueLock.notifyAll()
        }
        try {
            outputStream?.close()
        } catch (_: Exception) {}
        try {
            socket?.close()
        } catch (_: Exception) {}
        outputStream = null
        socket = null

        val t = senderThread
        senderThread = null
        if (t != null && t !== Thread.currentThread()) {
            t.interrupt()
            try {
                t.join(300)
            } catch (_: Exception) {}
        }
    }
}
