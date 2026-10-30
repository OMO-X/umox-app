package com.umox.app.aa

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Abstraksi Transport untuk Sesi Proyeksi Android Auto.
 * Mendukung aliran data over TCP (Wi-Fi / Hotspot / Direct) dan USB AOA 2.0 (Kabel Type-C).
 */
interface AaProjectionTransport {
    val name: String
    val inputStream: InputStream
    val outputStream: OutputStream
    val isConnected: Boolean
    fun close()
}

class AaSocketTransport(private val socket: Socket) : AaProjectionTransport {
    override val name: String
        get() = "TCP (${socket.remoteSocketAddress ?: "unknown"})"

    override val inputStream: InputStream
        get() = socket.getInputStream()

    override val outputStream: OutputStream
        get() = socket.getOutputStream()

    override val isConnected: Boolean
        get() = socket.isConnected && !socket.isClosed

    override fun close() {
        try {
            socket.close()
        } catch (_: Exception) {}
    }
}
