package com.umox.app.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log
import com.umox.app.aa.AaProjectionTransport
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Transport Proyeksi Android Auto via USB AOA 2.0 (Android Open Accessory).
 * Menghubungkan aliran data AAP langsung ke Bulk Endpoint IN/OUT USB tanpa socket TCP.
 */
class UsbAoaTransport(
    val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint
) : AaProjectionTransport {

    companion object {
        private const val TAG = "UsbAoaTransport"
        private const val USB_TIMEOUT_MS = 2500
    }

    private val isClosed = AtomicBoolean(false)

    override val name: String
        get() = "USB AOA 2.0 (${device.manufacturerName ?: "Android"} ${device.productName ?: "Auto"} [0x${Integer.toHexString(device.vendorId)}:0x${Integer.toHexString(device.productId)}])"

    override val isConnected: Boolean
        get() = !isClosed.get()

    override val inputStream: InputStream = object : InputStream() {
        private val internalBuffer = ByteArray(16384)
        private var bufferPos = 0
        private var bufferAvailable = 0

        override fun read(): Int {
            val single = ByteArray(1)
            while (!isClosed.get()) {
                val res = read(single, 0, 1)
                if (res > 0) return (single[0].toInt() and 0xFF)
                if (res < 0) return -1
            }
            return -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (isClosed.get()) return -1
            if (len == 0) return 0

            // 1. Ambil dari buffer internal jika masih ada
            if (bufferAvailable > 0) {
                val toCopy = minOf(len, bufferAvailable)
                System.arraycopy(internalBuffer, bufferPos, b, off, toCopy)
                bufferPos += toCopy
                bufferAvailable -= toCopy
                return toCopy
            }

            // 2. Baca chunk baru dari bulk endpoint USB secara non-terminating (abaikan timeout sesaat jika koneksi masih hidup)
            while (!isClosed.get()) {
                val readCount = connection.bulkTransfer(endpointIn, internalBuffer, internalBuffer.size, USB_TIMEOUT_MS)
                if (readCount > 0) {
                    val toCopy = minOf(len, readCount)
                    System.arraycopy(internalBuffer, 0, b, off, toCopy)
                    bufferPos = toCopy
                    bufferAvailable = readCount - toCopy
                    return toCopy
                } else if (readCount < 0) {
                    if (isClosed.get()) return -1
                    // Timeout pada bulkTransfer biasa terjadi saat tidak ada transmisi data frame
                    // Lanjutkan loop pembacaan selama transport belum ditutup
                    continue
                }
            }
            return -1
        }
    }

    override val outputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (isClosed.get()) throw IOException("USB AOA transport telah ditutup")
            var offset = off
            var remaining = len
            val maxChunk = 16384
            while (remaining > 0 && !isClosed.get()) {
                val chunk = minOf(remaining, maxChunk)
                val sub = if (offset == 0 && chunk == b.size) b else b.copyOfRange(offset, offset + chunk)
                val written = connection.bulkTransfer(endpointOut, sub, chunk, USB_TIMEOUT_MS)
                if (written < 0) {
                    if (isClosed.get()) throw IOException("USB connection interrupted")
                    throw IOException("USB bulkTransfer OUT gagal dengan kode $written")
                }
                offset += written
                remaining -= written
            }
        }
    }

    override fun close() {
        if (isClosed.getAndSet(true)) return
        Log.i(TAG, "Menutup kanal koneksi USB AOA: $name")
        try {
            connection.releaseInterface(usbInterface)
        } catch (e: Exception) {
            Log.w(TAG, "Gagal melepaskan USB interface: ${e.message}")
        }
        try {
            connection.close()
        } catch (e: Exception) {
            Log.w(TAG, "Gagal menutup koneksi USB device: ${e.message}")
        }
    }
}
