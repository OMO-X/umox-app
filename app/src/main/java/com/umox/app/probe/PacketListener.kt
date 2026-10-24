package com.umox.app.probe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

class PacketListener(
    private val onLog: (String) -> Unit
) {
    private var isListening = false
    private var socket: DatagramSocket? = null

    suspend fun startListening(port: Int = 1900) = withContext(Dispatchers.IO) {
        if (isListening) return@withContext
        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
            isListening = true
            onLog("Mendengarkan paket broadcast UDP pada port $port...")

            val buffer = ByteArray(4096)
            while (isListening) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket?.receive(packet)
                val senderIp = packet.address.hostAddress
                val dataStr = String(packet.data, 0, packet.length)
                onLog("PAKET DITERIMA dari $senderIp:${packet.port} -> ${dataStr.take(150)}")
            }
        } catch (e: Exception) {
            if (isListening) {
                onLog("Listener UDP error: ${e.message}")
            }
        } finally {
            stopListening()
        }
    }

    fun stopListening() {
        isListening = false
        socket?.close()
        socket = null
        onLog("Listener UDP dihentikan.")
    }
}
