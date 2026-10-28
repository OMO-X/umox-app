package com.umox.app.stream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import com.umox.app.config.AppConfig

class SocketStreamer(
    private val targetIp: String = AppConfig.defaultClusterIp,
    private val targetPort: Int = AppConfig.defaultVideoPort,
    private val onLog: (String) -> Unit
) {
    private var tcpSocket: Socket? = null
    private var outputStream: OutputStream? = null
    @Volatile
    var isConnected = false
        private set
    private var framesSent = 0L

    var onConnectionLost: (() -> Unit)? = null

    private var readJob: kotlinx.coroutines.Job? = null

    suspend fun connect() = withContext(Dispatchers.IO) {
        disconnect()
        framesSent = 0L

        try {
            onLog("Membuka koneksi TCP Video Stream ke $targetIp:$targetPort...")
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.sendBufferSize = 128 * 1024
            socket.connect(InetSocketAddress(targetIp, targetPort), 3000)
            tcpSocket = socket
            outputStream = socket.getOutputStream()
            isConnected = true
            onLog("✔ Terhubung ke Saluran Video Speedometer ($targetIp:$targetPort) via TCP!")

            readJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val input = socket.getInputStream()
                    val buf = ByteArray(1024)
                    while (isActive && isConnected) {
                        val n = input.read(buf)
                        if (n < 0) {
                            onLog("Saluran video ditutup oleh speedometer (EOF).")
                            break
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            isConnected = false
            onLog("Gagal terhubung ke soket video ($targetIp:$targetPort): ${e.message}")
        }
    }

    fun sendH264Packet(packet: ByteArray) {
        if (!isConnected) return

        try {
            val os = outputStream ?: return

            // Desay SV Video Framing: 8-byte Little-Endian Header
            // Byte 0-3: Subsystem Channel ID = 2 (Video Stream)
            // Byte 4-7: Payload Length = packet.size
            val header = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            header.putInt(2)
            header.putInt(packet.size)

            os.write(header.array())
            os.write(packet)
            os.flush()
            framesSent++

            if (framesSent == 1L) {
                onLog("► Frame video pertama (${packet.size} bytes) terkirim ke speedometer ($targetPort)!")
            } else if (framesSent % 30L == 0L) {
                val sec = framesSent / 30L
                onLog("Streaming video aktif: $framesSent frame (~$sec detik) terkirim ke speedometer ($targetPort).")
            }
        } catch (e: Exception) {
            val errMsg = e.message ?: "Broken pipe"
            onLog("⚠ Gangguan transmisi video ($errMsg). Mengatur ulang soket video...")
            disconnect()
            onConnectionLost?.invoke()
        }
    }

    fun disconnect() {
        isConnected = false
        readJob?.cancel()
        readJob = null
        try {
            outputStream?.close()
            tcpSocket?.close()
        } catch (_: Exception) {
            // Cleanup
        }
        outputStream = null
        tcpSocket = null
    }
}
