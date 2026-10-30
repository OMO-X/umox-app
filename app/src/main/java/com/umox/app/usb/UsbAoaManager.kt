package com.umox.app.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import java.util.concurrent.Executors

/**
 * Manajer Koneksi USB AOA 2.0 (Android Open Accessory) untuk Android Auto.
 *
 * Mengotomatiskan transisi HP Utama ke mode Android Auto Head Unit tanpa perlu
 * menyalakan Developer Settings secara manual di HP Utama:
 * 1. Mendeteksi HP yang terhubung via kabel USB Type-C / OTG.
 * 2. Mengirim instruksi Vendor Control Transfer AOA (51, 52, 53).
 * 3. HP Utama otomatis beralih ke Mode Aksesori (VID 0x18D1, PID 0x2D00/0x2D01).
 * 4. Membuka Bulk Endpoint IN/OUT dan memicu streaming proyeksi AAP secara otomatis.
 */
class UsbAoaManager(
    private val context: Context,
    private val onStatusChanged: (String) -> Unit,
    private val onTransportReady: (UsbAoaTransport) -> Unit,
    private val onTransportClosed: () -> Unit
) {
    companion object {
        private const val TAG = "UsbAoaManager"
        const val ACTION_USB_PERMISSION = "com.umox.app.USB_PERMISSION"

        private const val GOOGLE_VID = 0x18D1
        private val ACCESSORY_PIDS = setOf(0x2D00, 0x2D01, 0x2D04, 0x2D05)

        // AOA Protocol Requests
        private const val ACC_REQ_GET_PROTOCOL = 51
        private const val ACC_REQ_SEND_STRING = 52
        private const val ACC_REQ_START = 53

        // AOA Identity Strings
        private const val ACC_MANUFACTURER = "Android"
        private const val ACC_MODEL = "Android Auto"
        private const val ACC_DESCRIPTION = "Android Auto"
        private const val ACC_VERSION = "2.0.1"
        private const val ACC_URI = "https://developer.android.com/auto/index.html"
        private const val ACC_SERIAL = "HU-AAAAAA001"

        private val EXCLUDED_DEVICE_CLASSES = setOf(
            UsbConstants.USB_CLASS_AUDIO,
            UsbConstants.USB_CLASS_PRINTER,
            UsbConstants.USB_CLASS_MASS_STORAGE,
            UsbConstants.USB_CLASS_HUB
        )
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var activeTransport: UsbAoaTransport? = null

    @Volatile
    private var isReceiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val action = intent.action ?: return
            when (action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device != null) {
                        onStatusChanged("🔌 Perangkat USB terdeteksi: ${device.deviceName} (${getDeviceName(device)})")
                        executor.execute { handleDevice(device) }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device != null && activeTransport?.device?.deviceId == device.deviceId) {
                        onStatusChanged("🔌 Perangkat USB dilepaskan: ${getDeviceName(device)}")
                        closeActiveTransport()
                    }
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (granted && device != null) {
                        onStatusChanged("✔ Izin USB diberikan untuk ${getDeviceName(device)}")
                        executor.execute { handleDevice(device) }
                    } else {
                        onStatusChanged("⚠ Izin USB ditolak untuk ${device?.let { getDeviceName(it) } ?: "perangkat"}")
                    }
                }
            }
        }
    }

    fun startListening() {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        isReceiverRegistered = true
        scanAndConnect()
    }

    fun stopListening() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(usbReceiver)
            } catch (_: Exception) {}
            isReceiverRegistered = false
        }
        closeActiveTransport()
    }

    fun scanAndConnect() {
        executor.execute {
            val mgr = usbManager ?: run {
                onStatusChanged("⚠ Layanan USB Host tidak tersedia pada perangkat ini.")
                return@execute
            }
            val devices = mgr.deviceList
            if (devices.isEmpty()) {
                onStatusChanged("🔌 Siaga USB AOA: Menunggu kabel USB terhubung ke ponsel...")
                return@execute
            }

            for ((_, device) in devices) {
                if (isPotentialPhone(device)) {
                    handleDevice(device)
                    break
                }
            }
        }
    }

    private fun handleDevice(device: UsbDevice) {
        val mgr = usbManager ?: return
        if (device.deviceClass in EXCLUDED_DEVICE_CLASSES) {
            Log.d(TAG, "Device diabaikan karena bukan ponsel: class ${device.deviceClass}")
            return
        }

        // Cek apakah sudah dalam mode Aksesori Android Auto
        if (isInAccessoryMode(device)) {
            onStatusChanged("★ Perangkat sudah dalam Mode Aksesori Android Auto (VID:0x${Integer.toHexString(device.vendorId)})")
            connectAccessoryEndpoints(device)
            return
        }

        // Perangkat belum dalam mode aksesori -> lakukan AOA Switch
        if (!mgr.hasPermission(device)) {
            onStatusChanged("Meminta izin USB untuk ${getDeviceName(device)}...")
            requestUsbPermission(device)
            return
        }

        onStatusChanged("Mengirim sinyal aktivasi otomatis AOA 2.0 ke ${getDeviceName(device)}...")
        performAoaSwitch(device)
    }

    private fun performAoaSwitch(device: UsbDevice) {
        val mgr = usbManager ?: return
        val conn: UsbDeviceConnection?
        try {
            conn = mgr.openDevice(device)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal membuka USB device untuk AOA switch: ${e.message}")
            onStatusChanged("⚠ Gagal membuka koneksi USB (${e.message})")
            return
        }

        if (conn == null) {
            onStatusChanged("⚠ Gagal membuka handle USB (koneksi null)")
            return
        }

        try {
            val protocolBuffer = ByteArray(2)
            val protocolLen = conn.controlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_VENDOR,
                ACC_REQ_GET_PROTOCOL,
                0, 0,
                protocolBuffer, 2, 1000
            )

            if (protocolLen < 2) {
                Log.w(TAG, "Perangkat tidak merespons protokol AOA (len: $protocolLen)")
                onStatusChanged("ℹ Perangkat tidak merespons AOA (bukan ponsel Android atau mode MTP mati)")
                return
            }

            val protocol = (protocolBuffer[1].toInt() shl 8) or (protocolBuffer[0].toInt() and 0xFF)
            Log.i(TAG, "Versi AOA yang didukung: $protocol")
            if (protocol < 1) {
                onStatusChanged("⚠ Perangkat tidak mendukung AOA (versi: $protocol)")
                return
            }

            // Kirim 6 identitas Android Auto
            val strings = listOf(
                ACC_MANUFACTURER,
                ACC_MODEL,
                ACC_DESCRIPTION,
                ACC_VERSION,
                ACC_URI,
                ACC_SERIAL
            )

            for ((idx, str) in strings.withIndex()) {
                val bytes = str.toByteArray()
                val sent = conn.controlTransfer(
                    UsbConstants.USB_TYPE_VENDOR,
                    ACC_REQ_SEND_STRING,
                    0, idx,
                    bytes, bytes.size, 1000
                )
                if (sent < 0) {
                    Log.w(TAG, "Gagal kirim identitas AOA idx $idx: $str")
                    return
                }
            }

            // Kirim ACC_REQ_START (53)
            onStatusChanged("✔ Mengirim perintah Start AOA 2.0! Menunggu ponsel berganti mode...")
            val startRes = conn.controlTransfer(
                UsbConstants.USB_TYPE_VENDOR,
                ACC_REQ_START,
                0, 0,
                byteArrayOf(), 0, 1000
            )
            Log.i(TAG, "Perintah ACC_REQ_START terkirim (res=$startRes). Ponsel akan re-enumerasi.")
        } catch (e: Exception) {
            Log.e(TAG, "Kesalahan saat AOA switch: ${e.message}", e)
            onStatusChanged("⚠ Kesalahan AOA switch: ${e.message}")
        } finally {
            try {
                conn.close()
            } catch (_: Exception) {}
        }
    }

    private fun connectAccessoryEndpoints(device: UsbDevice) {
        val mgr = usbManager ?: return
        if (!mgr.hasPermission(device)) {
            requestUsbPermission(device)
            return
        }

        var conn: UsbDeviceConnection? = null
        for (attempt in 1..3) {
            try {
                conn = mgr.openDevice(device)
                if (conn != null) break
            } catch (_: Exception) {}
            try { Thread.sleep(500) } catch (_: Exception) {}
        }

        if (conn == null) {
            onStatusChanged("⚠ Tidak dapat membuka koneksi ke perangkat mode aksesori.")
            return
        }

        val ifaceCount = device.interfaceCount
        if (ifaceCount == 0) {
            conn.close()
            onStatusChanged("⚠ Tidak ada interface USB pada perangkat aksesori.")
            return
        }

        val iface = device.getInterface(0)
        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null

        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN && epIn == null) {
                    epIn = ep
                } else if (ep.direction == UsbConstants.USB_DIR_OUT && epOut == null) {
                    epOut = ep
                }
            }
        }

        if (epIn == null || epOut == null) {
            conn.close()
            onStatusChanged("⚠ Gagal menemukan Bulk Endpoint IN/OUT pada aksesori.")
            return
        }

        if (!conn.claimInterface(iface, true)) {
            conn.close()
            onStatusChanged("⚠ Gagal klaim interface USB aksesori.")
            return
        }

        closeActiveTransport()
        val transport = UsbAoaTransport(device, conn, iface, epIn, epOut)
        activeTransport = transport
        onStatusChanged("★ KONEKSI USB AOA AKTIF: ${transport.name}")
        onTransportReady(transport)
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
        usbManager?.requestPermission(device, pi)
    }

    private fun isInAccessoryMode(device: UsbDevice): Boolean {
        return device.vendorId == GOOGLE_VID && device.productId in ACCESSORY_PIDS
    }

    private fun isPotentialPhone(device: UsbDevice): Boolean {
        if (device.vendorId == 0x05AC) return false // Apple
        if (device.deviceClass in EXCLUDED_DEVICE_CLASSES) return false
        if (isInAccessoryMode(device)) return true
        // Vendor-specific interface, ADB, MTP, or RNDIS
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == 255 || iface.interfaceClass == 6 || iface.interfaceClass == 224) {
                return true
            }
        }
        return true
    }

    private fun getDeviceName(device: UsbDevice): String {
        val m = device.manufacturerName.orEmpty().trim()
        val p = device.productName.orEmpty().trim()
        val label = if (m.isNotBlank() || p.isNotBlank()) "$m $p".trim() else "Device"
        return "$label [0x${Integer.toHexString(device.vendorId)}:0x${Integer.toHexString(device.productId)}]"
    }

    private fun closeActiveTransport() {
        activeTransport?.let {
            it.close()
            activeTransport = null
            onTransportClosed()
        }
    }
}
