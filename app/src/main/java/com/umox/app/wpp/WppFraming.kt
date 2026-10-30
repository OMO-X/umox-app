package com.umox.app.wpp

import java.io.DataInputStream
import java.io.OutputStream

/**
 * Wire framing untuk Android Auto Wireless Projection Protocol (WPP).
 * Setiap pesan terdiri dari 4-byte Big-Endian header diikuti oleh Protobuf payload:
 * 0..1 : Panjang Payload (uint16 BE)
 * 2..3 : Tipe Pesan (uint16 BE)
 */
object WppFraming {
    const val HEADER_SIZE = 4

    // Message Types (Sesuai spesifikasi Google Wireless Projection Protocol)
    const val TYPE_START_REQUEST = 1       // HU -> Phone: Target IP + Port 5288
    const val TYPE_INFO_REQUEST = 2        // Phone -> HU: Meminta Kredensial Wi-Fi
    const val TYPE_INFO_RESPONSE = 3       // HU -> Phone: SSID + Password Wi-Fi
    const val TYPE_VERSION_REQUEST = 4     // HU -> Phone: Buka sesi v4.2
    const val TYPE_VERSION_RESPONSE = 5    // Phone -> HU: Balasan versi & info ponsel
    const val TYPE_CONNECT_STATUS = 6      // Phone -> HU: Laporan status koneksi ke AP
    const val TYPE_START_RESPONSE = 7      // Phone -> HU: Konfirmasi siap proyeksi
    const val TYPE_PING_REQUEST = 8        // Keepalive Request
    const val TYPE_PING_RESPONSE = 9       // Keepalive Response

    fun encodeFrame(type: Int, payload: ByteArray): ByteArray {
        val size = payload.size
        val frame = ByteArray(HEADER_SIZE + size)
        frame[0] = ((size shr 8) and 0xFF).toByte()
        frame[1] = (size and 0xFF).toByte()
        frame[2] = ((type shr 8) and 0xFF).toByte()
        frame[3] = (type and 0xFF).toByte()
        System.arraycopy(payload, 0, frame, HEADER_SIZE, size)
        return frame
    }

    fun writeFrame(out: OutputStream, type: Int, payload: ByteArray) {
        val frame = encodeFrame(type, payload)
        out.write(frame)
        out.flush()
    }

    fun readFrame(input: DataInputStream): WppMessage {
        val header = ByteArray(HEADER_SIZE)
        input.readFully(header)
        val size = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
        val type = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        val payload = if (size > 0) {
            val p = ByteArray(size)
            input.readFully(p)
            p
        } else {
            ByteArray(0)
        }
        return WppMessage(type, payload)
    }
}

data class WppMessage(val type: Int, val payload: ByteArray)
