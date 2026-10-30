package com.umox.app.wpp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.umox.app.aa.AaProto
import java.io.DataInputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android Auto Wireless Projection Protocol (WPP) Server & Bluetooth Auto-Wake.
 *
 * Mengotomatiskan aktivasi Android Auto Wireless tanpa memerlukan Developer Settings:
 * 1. Menjalankan RFCOMM Server pada UUID resmi Android Auto Wireless (`4de17a00-52cb-11e6-bdf4-0800200c9a66`)
 *    dan WPP UUID alternatif (`4de9d444-2251-4144-9d07-aa21eddae504`).
 * 2. Menyediakan fungsi "Auto-Wake Poke" ke profil HFP ponsel (`0000111e-0000-1000-8000-00805f9b34fb`)
 *    untuk membangunkan layanan proyeksi Android Auto di latar belakang ponsel utama secara instan.
 * 3. Melakukan negosiasi handshake WPP Type 4 (Version), Type 1 (Start), dan Type 3 (Info/Kredensial Hotspot).
 * 4. Ponsel utama otomatis bergabung ke jaringan Wi-Fi dan menyambung ke port 5288 untuk proyeksi!
 */
class WppBluetoothServer(
    private val context: Context,
    private val getHotspotSsid: () -> String,
    private val getHotspotPassword: () -> String,
    private val getHotspotBssid: () -> String = { "" },
    private val getTargetIp: () -> String,
    private val onPhoneConnected: ((phoneName: String) -> Unit)? = null,
    private val onStatusChanged: (String) -> Unit,
    private val onPhoneHandshakeSuccess: (phoneName: String) -> Unit
) {
    companion object {
        private const val TAG = "WppBluetoothServer"

        // UUID Resmi Android Auto Wireless & WPP
        val AA_WIRELESS_UUID: UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")
        val AA_WPP_UUID: UUID = UUID.fromString("4de9d444-2251-4144-9d07-aa21eddae504")

        // UUID Audio Gateway Hands-Free (digunakan untuk auto-wake / poke ponsel)
        val HFP_AG_UUID: UUID = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb")
        val HSP_AG_UUID: UUID = UUID.fromString("00001112-0000-1000-8000-00805f9b34fb")

        private const val WPP_PORT = 5288
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private val isRunning = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()

    private val activeServerSockets = mutableListOf<BluetoothServerSocket>()
    @Volatile
    private var activeHandshakeSocket: BluetoothSocket? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning.getAndSet(true)) return

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            onStatusChanged("⚠ Bluetooth tidak aktif. Silakan aktifkan Bluetooth terlebih dahulu.")
            isRunning.set(false)
            return
        }

        executor.execute {
            startRfcommListeners(adapter)
        }
    }

    fun stop() {
        isRunning.set(false)
        synchronized(activeServerSockets) {
            for (ss in activeServerSockets) {
                try { ss.close() } catch (_: Exception) {}
            }
            activeServerSockets.clear()
        }
        try {
            activeHandshakeSocket?.close()
        } catch (_: Exception) {}
        activeHandshakeSocket = null
        onStatusChanged("Server Bluetooth WPP dihentikan.")
    }

    /**
     * Membangunkan layanan Android Auto pada ponsel utama via HFP Poke.
     */
    @SuppressLint("MissingPermission")
    fun wakePhone(deviceAddress: String, onComplete: ((Boolean, String) -> Unit)? = null) {
        val adapter = bluetoothAdapter ?: run {
            onComplete?.invoke(false, "Bluetooth adapter tidak tersedia")
            return
        }
        executor.execute {
            try {
                val device = adapter.getRemoteDevice(deviceAddress)
                val devName = device.name ?: deviceAddress
                onStatusChanged("⚡ Membangunkan Android Auto pada $devName...")

                var woke = false
                val wakeUuids = listOf(HFP_AG_UUID, HSP_AG_UUID)
                for (uuid in wakeUuids) {
                    var pokeSocket: BluetoothSocket? = null
                    try {
                        pokeSocket = device.createRfcommSocketToServiceRecord(uuid)
                        pokeSocket.connect()
                        woke = true
                        Log.i(TAG, "✔ Berhasil poke $devName via $uuid")
                        // Tahan koneksi selama 1 detik agar Android Auto di ponsel menerima trigger
                        Thread.sleep(1000)
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "Gagal poke $devName via $uuid: ${e.message}")
                    } finally {
                        try { pokeSocket?.close() } catch (_: Exception) {}
                    }
                }

                if (woke) {
                    onStatusChanged("★ Sinyal bangun terkirim ke $devName! Menunggu koneksi balik Android Auto...")
                    onComplete?.invoke(true, "Berhasil membangunkan $devName")
                } else {
                    onStatusChanged("⚠ Tidak dapat menyambung langsung ke profil HFP $devName. Pastikan ponsel sudah dipasangkan (paired).")
                    onComplete?.invoke(false, "Gagal koneksi HFP ke $devName")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Kesalahan saat wake phone: ${e.message}", e)
                onStatusChanged("⚠ Kesalahan wake phone: ${e.message}")
                onComplete?.invoke(false, e.message ?: "Error")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRfcommListeners(adapter: BluetoothAdapter) {
        val listeners = listOf(
            Pair("AndroidAuto", AA_WIRELESS_UUID),
            Pair("WirelessAuto", AA_WPP_UUID)
        )

        for ((name, uuid) in listeners) {
            try {
                val serverSocket = adapter.listenUsingRfcommWithServiceRecord(name, uuid)
                synchronized(activeServerSockets) {
                    activeServerSockets.add(serverSocket)
                }
                executor.execute {
                    listenLoop(serverSocket, name)
                }
                Log.i(TAG, "✔ Mendengarkan RFCOMM [$name] pada UUID $uuid")
            } catch (e: Exception) {
                Log.w(TAG, "Gagal membuka listener RFCOMM [$name]: ${e.message}")
            }
        }

        onStatusChanged("★ Server Bluetooth WPP SIAGA. Menunggu ponsel terhubung...")
    }

    @SuppressLint("MissingPermission")
    private fun listenLoop(serverSocket: BluetoothServerSocket, serviceName: String) {
        while (isRunning.get()) {
            val socket: BluetoothSocket
            try {
                socket = serverSocket.accept()
            } catch (e: Exception) {
                if (!isRunning.get()) break
                Log.d(TAG, "Accept berakhir pada [$serviceName]: ${e.message}")
                break
            }

            val remoteDevice = socket.remoteDevice
            val remoteName = remoteDevice?.name ?: "Ponsel"
            val remoteMac = remoteDevice?.address ?: "Unknown"
            onStatusChanged("✔ Terkoneksi Bluetooth RFCOMM dari $remoteName ($remoteMac) [$serviceName]!")
            Log.i(TAG, "✔ Terkoneksi Bluetooth RFCOMM dari $remoteName ($remoteMac) [$serviceName]!")
            try {
                onPhoneConnected?.invoke(remoteName)
            } catch (e: Exception) {
                Log.w(TAG, "Error invoking onPhoneConnected: ${e.message}")
            }

            activeHandshakeSocket = socket
            try {
                handleHandshakeSession(socket, remoteName)
            } catch (e: Exception) {
                Log.w(TAG, "Sesi handshake Bluetooth berakhir: ${e.message}")
            } finally {
                try { socket.close() } catch (_: Exception) {}
                if (activeHandshakeSocket == socket) {
                    activeHandshakeSocket = null
                }
            }
        }
    }

    private fun handleHandshakeSession(socket: BluetoothSocket, phoneName: String) {
        val input = DataInputStream(socket.inputStream)
        val output = socket.outputStream

        // Settle delay singkat
        try { Thread.sleep(300) } catch (_: Exception) {}

        val targetIp = getTargetIp().ifBlank { "0.0.0.0" }

        // 1. Kirim Type 4: WifiVersionRequest (v4.2 + CarInfo + WppInfo)
        onStatusChanged("Mengirim WPP Version Request v4.2 ke $phoneName...")
        val verReqPayload = AaProto.encode {
            writeVarint(1, 4) // major = 4
            writeVarint(2, 2) // minor = 2
            writeVarint(3, 2) // supported_wifi_channel_type = DUAL_BAND (2)
            writeMessage(5) { // car_info
                writeString(1, "UMOX")
                writeString(2, "Universal HU")
                writeString(3, "2026")
                writeString(4, "UMOX-001")
                writeString(5, "UMOX")
                writeString(6, "Wireless")
                writeString(7, "1")
                writeString(8, "0.1.0")
            }
            writeMessage(6) { // wpp_info
                writeString(1, targetIp)
                writeVarint(2, WPP_PORT)
            }
        }
        WppFraming.writeFrame(output, WppFraming.TYPE_VERSION_REQUEST, verReqPayload)

        // Loop membaca pesan dari ponsel
        while (isRunning.get() && socket.isConnected) {
            val msg = try {
                WppFraming.readFrame(input)
            } catch (e: Exception) {
                Log.d(TAG, "Koneksi WPP selesai atau terputus: ${e.message}")
                break
            }

            Log.i(TAG, "Diterima WPP Frame TYPE ${msg.type} (ukuran: ${msg.payload.size})")

            when (msg.type) {
                WppFraming.TYPE_VERSION_RESPONSE -> {
                    onStatusChanged("✔ $phoneName merespons versi protokol WPP. Mengirim permintaan proyeksi...")
                    // Kirim Type 1: WifiStartRequest
                    val startPayload = AaProto.encode {
                        writeString(1, targetIp)
                        writeVarint(2, WPP_PORT)
                        writeVarint(3, 0) // START_REQUEST_REASON_USER_REQUEST
                    }
                    WppFraming.writeFrame(output, WppFraming.TYPE_START_REQUEST, startPayload)
                }
                WppFraming.TYPE_INFO_REQUEST -> {
                    // Ponsel meminta kredensial Wi-Fi / Hotspot!
                    val ssid = getHotspotSsid()
                    val pass = getHotspotPassword()
                    val bssid = getHotspotBssid()
                    Log.i(TAG, "Ponsel meminta Info Wi-Fi: mengirim SSID='$ssid', BSSID='$bssid'")
                    onStatusChanged("Mengirim informasi Wi-Fi ($ssid) ke $phoneName...")

                    val infoPayload = AaProto.encode {
                        writeString(1, ssid)
                        writeString(2, pass)
                        writeString(3, bssid)
                        writeVarint(4, 8)  // WPA2_PERSONAL
                        writeVarint(5, 0)  // STATIC
                    }
                    WppFraming.writeFrame(output, WppFraming.TYPE_INFO_RESPONSE, infoPayload)
                    onStatusChanged("★ Info Wi-Fi ($ssid) terkirim ke $phoneName! Mempersiapkan sesi proyeksi...")
                }
                WppFraming.TYPE_START_RESPONSE -> {
                    Log.i(TAG, "Ponsel mengonfirmasi Start Request (Type 7).")
                }
                WppFraming.TYPE_CONNECT_STATUS -> {
                    val fields = AaProto.parseFields(msg.payload)
                    val status = (fields[1]?.firstOrNull() as? Long)?.toInt() ?: 0
                    Log.i(TAG, "Diterima CONNECT_STATUS dari $phoneName: status=$status (payload: ${msg.payload.size}b)")
                    if (status == 0) {
                        onStatusChanged("★ $phoneName BERHASIL terhubung ke Wi-Fi! Menghubungkan proyeksi ke port $WPP_PORT...")
                    } else {
                        onStatusChanged("✔ $phoneName status koneksi: $status. Memulai sambungan proyeksi...")
                    }
                    // Langsung picu kesuksesan handshake agar port 5288 siap menerima TCP stream
                    onPhoneHandshakeSuccess(phoneName)
                }
                WppFraming.TYPE_PING_REQUEST -> {
                    // Balas ping dengan payload yang sama
                    WppFraming.writeFrame(output, WppFraming.TYPE_PING_RESPONSE, msg.payload)
                }
            }
        }
    }
}
