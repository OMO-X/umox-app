package com.umox.app.stream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Klien TCP berkecepatan tinggi untuk Port 9528 (Saluran Video H.264 Desay SV).
 *
 * Optimasi Performa:
 * - Zero-allocation header framing (8-byte LE: `[Channel=2 (4B)][Length (4B)]`).
 * - `TCP_NODELAY` + `IPTOS_LOWDELAY (0x10)` + `BufferedOutputStream(64KB)` sehingga
 *   header 8-byte dan payload NALU terkirim dalam 1 syscall TCP tunggal tanpa pecah.
 * - Telemetri FPS & Bitrate real-time untuk indikator UI.
 */
class SocketStreamer(
    private val targetIp: String = "192.168.2.1",
    private val targetPort: Int = 9528,
    private val onLog: (String) -> Unit
) {
    companion object {
        @Volatile
        var currentFps: Int = 0
            private set

        @Volatile
        var currentKbps: Int = 0
            private set
    }

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var isConnected = false
    private var heartbeatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val writeLock = Any()

    // Reusable 8-byte header buffer (menghindari alokasi ByteBuffer di setiap frame)
    private val headerBuf = ByteArray(8)
    private val heartbeatHeader = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

    private var frameCountWindow = 0
    private var byteCountWindow = 0L
    private var lastWindowTime = System.currentTimeMillis()
    private var totalFramesSent = 0L

    var onConnectionLost: (() -> Unit)? = null

    suspend fun connect() = withContext(Dispatchers.IO) {
        try {
            disconnect()
            onLog("Menghubungkan soket video TCP ke $targetIp:$targetPort...")
            val s = Socket()
            s.tcpNoDelay = true
            try {
                s.trafficClass = 0x10 // IPTOS_LOWDELAY
                s.sendBufferSize = 262144
            } catch (_: Exception) {}
            s.connect(InetSocketAddress(targetIp, targetPort), 3500)

            synchronized(writeLock) {
                socket = s
                outputStream = BufferedOutputStream(s.getOutputStream(), 65536)
                isConnected = true
                frameCountWindow = 0
                byteCountWindow = 0L
                lastWindowTime = System.currentTimeMillis()
            }

            onLog("✔ Soket Video (Port $targetPort) terhubung [TCP_NODELAY + LOWDELAY].")
            startHeartbeatLoop()
        } catch (e: Exception) {
            onLog("✖ Gagal terhubung ke soket video $targetPort: ${e.message}")
            isConnected = false
        }
    }

    /**
     * Detak jantung (Heartbeat) video: Channel 0, Length 0 setiap 800 ms.
     */
    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && isConnected) {
                delay(800)
                if (isConnected) {
                    try {
                        synchronized(writeLock) {
                            outputStream?.write(heartbeatHeader)
                            outputStream?.flush()
                        }
                    } catch (e: Exception) {
                        handleSocketError("Heartbeat gagal: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Mengirimkan paket H.264 Annex-B (sudah disanitasi) ke Channel 2.
     */
    fun sendH264Packet(h264Data: ByteArray) {
        if (!isConnected || h264Data.isEmpty()) return

        try {
            synchronized(writeLock) {
                val out = outputStream ?: return
                val len = h264Data.size

                // Channel ID = 2 (Little-Endian)
                headerBuf[0] = 2
                headerBuf[1] = 0
                headerBuf[2] = 0
                headerBuf[3] = 0

                // Payload Length (Little-Endian)
                headerBuf[4] = (len and 0xFF).toByte()
                headerBuf[5] = ((len ushr 8) and 0xFF).toByte()
                headerBuf[6] = ((len ushr 16) and 0xFF).toByte()
                headerBuf[7] = ((len ushr 24) and 0xFF).toByte()

                out.write(headerBuf, 0, 8)
                out.write(h264Data, 0, len)
                out.flush()

                totalFramesSent++
                frameCountWindow++
                byteCountWindow += (8 + len)

                val now = System.currentTimeMillis()
                val elapsed = now - lastWindowTime
                if (elapsed >= 1000L) {
                    currentFps = ((frameCountWindow * 1000L) / elapsed).toInt()
                    currentKbps = ((byteCountWindow * 8L) / elapsed).toInt()
                    frameCountWindow = 0
                    byteCountWindow = 0L
                    lastWindowTime = now
                }
            }
        } catch (e: Exception) {
            handleSocketError("Transmisi video terputus: ${e.message}")
        }
    }

    private fun handleSocketError(reason: String) {
        if (!isConnected) return
        isConnected = false
        currentFps = 0
        currentKbps = 0
        onLog("⚠ Soket video ($targetPort) error: $reason")
        onConnectionLost?.invoke()
    }

    fun disconnect() {
        isConnected = false
        currentFps = 0
        currentKbps = 0
        heartbeatJob?.cancel()
        heartbeatJob = null
        synchronized(writeLock) {
            try {
                outputStream?.close()
            } catch (_: Exception) {}
            try {
                socket?.close()
            } catch (_: Exception) {}
            outputStream = null
            socket = null
        }
    }
}
