package com.umox.app.discovery

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

data class DiscoveredServer(
    val name: String,
    val ip: String,
    val port: Int,
    val isAaActive: Boolean,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)

/**
 * Otomatisasi Penemuan Peer UMOX (Server HP Utama <-> Client HP Layar) via UDP Broadcast.
 * - Server: Memancarkan beacon "UMOX_BEACON_V1|Nama Perangkat|IP|Port|StatusAA" tiap 1.5 detik.
 * - Client: Mendengarkan beacon di port 9529 untuk mendeteksi IP Server secara dinamis tanpa input manual.
 */
class UmoxPeerDiscovery(
    private val onLog: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "UmoxPeerDiscovery"
        const val DISCOVERY_PORT = 9529
        const val BEACON_PREFIX = "UMOX_BEACON_V1"
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var broadcastJob: Job? = null
    private var listenJob: Job? = null
    private var broadcastSocket: DatagramSocket? = null
    private var listenSocket: DatagramSocket? = null

    @Volatile
    var isBroadcasting: Boolean = false
        private set

    @Volatile
    var isListening: Boolean = false
        private set

    /**
     * Jalankan pancaran beacon penemuan pada HP Utama (Mode Server).
     */
    fun startBroadcasting(
        deviceName: String,
        serverIp: String,
        port: Int = 5277,
        isAaActiveProvider: () -> Boolean = { true }
    ) {
        stopBroadcasting()
        isBroadcasting = true

        broadcastJob = scope.launch {
            try {
                val socket = DatagramSocket().apply {
                    broadcast = true
                }
                broadcastSocket = socket

                val targets = mutableListOf<InetAddress>()
                try { targets.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}
                try {
                    val lastDot = serverIp.lastIndexOf('.')
                    if (lastDot > 0) {
                        targets.add(InetAddress.getByName(serverIp.substring(0, lastDot) + ".255"))
                    }
                } catch (_: Exception) {}

                onLog("📡 Memancarkan radar Server UMOX ($deviceName • $serverIp:$port)...")

                while (isActive && isBroadcasting) {
                    if (socket.isClosed) break
                    try {
                        val aaFlag = if (isAaActiveProvider()) "1" else "0"
                        val payload = "$BEACON_PREFIX|$deviceName|$serverIp|$port|$aaFlag"
                        val bytes = payload.toByteArray(Charsets.UTF_8)
                        for (target in targets) {
                            try {
                                val packet = DatagramPacket(bytes, bytes.size, target, DISCOVERY_PORT)
                                socket.send(packet)
                            } catch (_: Exception) {}
                        }
                    } catch (e: Exception) {
                        if (socket.isClosed || !isActive || !isBroadcasting) break
                        Log.w(TAG, "Gagal kirim beacon: ${e.message}")
                    }
                    delay(1500L)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Kesalahan socket broadcast: ${e.message}")
            }
        }
    }

    /**
     * Jalankan pendengar beacon penemuan pada HP Penerima (Mode Client).
     */
    fun startListening(onServerDiscovered: (serverName: String, serverIp: String, serverPort: Int, isAaActive: Boolean) -> Unit) {
        stopListening()
        isListening = true

        listenJob = scope.launch {
            try {
                val socket = DatagramSocket(DISCOVERY_PORT).apply {
                    broadcast = true
                    reuseAddress = true
                    soTimeout = 3000
                }
                listenSocket = socket
                val buffer = ByteArray(1024)

                onLog("🔍 Memindai Server UMOX di jaringan Hotspot/Wi-Fi...")

                while (isActive && isListening) {
                    if (socket.isClosed) break
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val msg = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()

                        if (msg.startsWith(BEACON_PREFIX)) {
                            val parts = msg.split("|")
                            if (parts.size >= 4) {
                                val sName = parts[1]
                                val sIp = parts[2]
                                val sPort = parts[3].toIntOrNull() ?: 5277
                                val isAaActive = parts.getOrNull(4) == "1"

                                mainHandler.post {
                                    onServerDiscovered(sName, sIp, sPort, isAaActive)
                                }
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // Timeout biasa, lanjutkan loop
                    } catch (e: Exception) {
                        if (socket.isClosed || !isActive || !isListening) {
                            break
                        }
                        Log.w(TAG, "Kesalahan penerima beacon: ${e.message}")
                        delay(500L)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gagal membuka socket pendengar: ${e.message}")
            }
        }
    }

    fun stopBroadcasting() {
        isBroadcasting = false
        broadcastJob?.cancel()
        broadcastJob = null
        try {
            broadcastSocket?.close()
        } catch (_: Exception) {}
        broadcastSocket = null
    }

    fun stopListening() {
        isListening = false
        listenJob?.cancel()
        listenJob = null
        try {
            listenSocket?.close()
        } catch (_: Exception) {}
        listenSocket = null
    }

    fun stopAll() {
        stopBroadcasting()
        stopListening()
    }
}
