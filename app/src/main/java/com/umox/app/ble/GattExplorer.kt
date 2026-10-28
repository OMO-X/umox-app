package com.umox.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.umox.app.config.AppConfig
import org.json.JSONObject
import java.util.UUID

class GattExplorer(
    private val context: Context,
    private val onLog: (String) -> Unit,
    private val onTelemetry: (MotorcycleTelemetry) -> Unit = {},
    private val onWifiInfoFound: (ssid: String, pass: String, ip: String) -> Unit = { _, _, _ -> }
) {
    private var currentGatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    val currentTelemetry = MotorcycleTelemetry()

    companion object {
        val SERVICE_VDLM: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val CHAR_WRITE: UUID = UUID.fromString("0000fffd-0000-1000-8000-00805f9b34fb")
        val CHAR_NOTIFY: UUID = UUID.fromString("0000fffe-0000-1000-8000-00805f9b34fb")
        val CCCD_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        val FRAME_ACK = byteArrayOf(0xAA.toByte(), 0x56.toByte(), 0x00)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onLog("✔ Terhubung ke BLE $deviceAddress (Status: $status)! Meminta MTU & Services...")
                // Request MTU 247
                try {
                    gatt.requestMtu(247)
                } catch (e: Exception) {
                    onLog("Gagal request MTU: ${e.message}")
                }
                // Discover services dengan delay singkat agar GATT stabil
                mainHandler.postDelayed({
                    onLog("Menjalankan discoverServices pada $deviceAddress...")
                    gatt.discoverServices()
                }, 500)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onLog("⚠ Koneksi BLE ke $deviceAddress terputus (Status: $status).")
                stopPeriodicPolling()
                writeChar = null
                notifyChar = null
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            onLog("✔ MTU BLE aktif: $mtu bytes (Status: $status).")
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onLog("=== LAYANAN GATT DITEMUKAN (${gatt.services.size} Services) ===")
                for (s in gatt.services) {
                    onLog("• SERVICE: ${s.uuid}")
                    for (ch in s.characteristics) {
                        onLog("   └─ CHAR: ${ch.uuid}")
                    }
                }

                val service = gatt.getService(SERVICE_VDLM)
                if (service != null) {
                    onLog("★ LAYANAN DESAY SV (0xFFF0) DITEMUKAN!")
                    writeChar = service.getCharacteristic(CHAR_WRITE)
                    notifyChar = service.getCharacteristic(CHAR_NOTIFY)

                    if (notifyChar != null) {
                        onLog("Mengaktifkan notifikasi pada 0xFFFE...")
                        gatt.setCharacteristicNotification(notifyChar, true)

                        val descriptor = notifyChar?.getDescriptor(CCCD_DESCRIPTOR)
                        if (descriptor != null) {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                            onLog("✔ Langganan telemetri (CCCD) berhasil diaktifkan.")
                        }
                    }
                } else {
                    // Cari karakteristik notify apa saja
                    for (s in gatt.services) {
                        for (ch in s.characteristics) {
                            if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                                onLog("Mencoba subscribe ke notify char: ${ch.uuid}")
                                gatt.setCharacteristicNotification(ch, true)
                                val desc = ch.getDescriptor(CCCD_DESCRIPTOR)
                                if (desc != null) {
                                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    gatt.writeDescriptor(desc)
                                }
                            }
                        }
                    }
                }
            } else {
                onLog("Gagal menemukan service GATT, status: $status")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            onLog("✔ CCCD Berhasil Diaktifkan (Status: $status)! Mengambil info Wi-Fi motor...")
            // 1. Minta kredensial Wi-Fi Direct motor secara otomatis
            mainHandler.postDelayed({
                sendWifiQueryRequest()
            }, 300)

            // 2. Mulai polling status telemetri berkala setelah 2 detik
            mainHandler.postDelayed({
                startPeriodicPolling()
            }, 2000)
        }

        private val multiFrameBuffer = java.io.ByteArrayOutputStream()

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val bytes = characteristic.value ?: return
            val hex = bytes.joinToString("") { "%02X".format(it) }

            onLog("📥 DATA MASUK [${characteristic.uuid}]: (${bytes.size} B) $hex")

            // Kirim link-layer ACK (AA5600) balik ke motor jika ini 0xFFFE
            sendAck()

            // Handle link framing AA55
            if (bytes.size >= 4 && bytes[0] == 0xAA.toByte() && bytes[1] == 0x55.toByte()) {
                val frameCtrl = bytes[2].toInt() and 0xFF
                val isMultiFrame = (frameCtrl and 0x40) != 0
                val seq = frameCtrl and 0x3F

                if (isMultiFrame) {
                    if (seq == 0) {
                        multiFrameBuffer.reset()
                    }
                    multiFrameBuffer.write(bytes, 4, bytes.size - 4)
                } else {
                    multiFrameBuffer.reset()
                    multiFrameBuffer.write(bytes, 4, bytes.size - 4)
                }
            } else {
                multiFrameBuffer.write(bytes)
            }

            // Periksa jika ada teks JSON Wi-Fi (dari extSearchWifiInfo) baik di frame langsung atau multi-frame
            val directAscii = String(bytes, Charsets.UTF_8)
            val bufferedAscii = multiFrameBuffer.toString("UTF-8")
            if (bufferedAscii.contains("ap_config") || directAscii.contains("ap_config")) {
                val candidateStr = if (bufferedAscii.contains("ap_config")) bufferedAscii else directAscii
                parseWifiJson(candidateStr)
            }

            // Parse telemetri motor
            parseTelemetryBytes(bytes, hex)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            onLog("📤 DATA TERKIRIM [${characteristic.uuid}] Status: $status")
        }
    }

    private var pollingRunnable: Runnable? = null

    fun startPeriodicPolling() {
        stopPeriodicPolling()
        pollingRunnable = object : Runnable {
            override fun run() {
                sendPollRequest()
                mainHandler.postDelayed(this, 5000)
            }
        }
        mainHandler.postDelayed(pollingRunnable!!, 400)
    }

    fun stopPeriodicPolling() {
        pollingRunnable?.let { mainHandler.removeCallbacks(it) }
        pollingRunnable = null
    }

    @SuppressLint("MissingPermission")
    fun sendAck() {
        val wc = writeChar ?: return
        val gatt = currentGatt ?: return
        wc.value = FRAME_ACK
        gatt.writeCharacteristic(wc)
    }

    @SuppressLint("MissingPermission")
    fun sendWifiQueryRequest() {
        val wc = writeChar
        val gatt = currentGatt
        if (wc != null && gatt != null) {
            val wifiQueryFrame = AppConfig.wifiQueryPayload
            onLog("📤 Mengirim Kueri Wi-Fi Direct (extSearchWifiInfo - 1569) ke Motor via 0xFFFD...")
            wc.value = wifiQueryFrame
            gatt.writeCharacteristic(wc)
        } else {
            onLog("Saluran kontrol BLE 0xFFFD belum siap untuk kueri Wi-Fi.")
        }
    }

    @SuppressLint("MissingPermission")
    fun sendPollRequest() {
        val wc = writeChar
        val gatt = currentGatt
        if (wc != null && gatt != null) {
            // Frame Polling Status (AA55 + frame length + request payload)
            val pollFrame = AppConfig.defaultStatusPollPayload
            onLog("📤 Mengirim Polling Status ke Motor via 0xFFFD...")
            wc.value = pollFrame
            gatt.writeCharacteristic(wc)
        } else {
            onLog("Saluran kontrol BLE 0xFFFD belum siap.")
        }
    }

    private fun parseWifiJson(text: String) {
        try {
            val startIdx = text.indexOf('{')
            val endIdx = text.lastIndexOf('}')
            if (startIdx in 0 until endIdx) {
                val jsonStr = text.substring(startIdx, endIdx + 1)
                val json = JSONObject(jsonStr)
                if (json.has("ap_config")) {
                    val ap = json.getJSONObject("ap_config")
                    val ssid = ap.optString("ssid")
                    val pass = ap.optString("password")
                    val ip = ap.optString("ip_address", "192.168.2.1")

                    var p2pMac = ""
                    if (json.has("p2p_config")) {
                        p2pMac = json.getJSONObject("p2p_config").optString("device_address")
                    }

                    currentTelemetry.wifiSsid = ssid
                    currentTelemetry.wifiPassword = pass
                    currentTelemetry.wifiIp = ip

                    onLog("★ KREDENSIAL WI-FI MOTOR DIDAPAT DARI BLE:")
                    onLog("   SSID: $ssid | Password: $pass | IP: $ip ${if (p2pMac.isNotBlank()) "| P2P MAC: $p2pMac" else ""}")
                    if (p2pMac.isNotBlank()) {
                        AppConfig.saveP2pMac(context, p2pMac)
                    }
                    onWifiInfoFound(ssid, pass, ip)
                }
            }
        } catch (e: Exception) {
            onLog("Info Wi-Fi non-JSON atau parsial: ${e.message}")
        }
    }

    private fun parseTelemetryBytes(bytes: ByteArray, hex: String) {
        currentTelemetry.rawHex = hex

        // Scan Function IDs umum dalam pesan STATUS_REPLY
        for (i in 0 until bytes.size - 4) {
            // Function ID 1026 (Baterai SoC): varint 0x82, 0x08
            if (bytes[i] == 0x82.toByte() && bytes[i + 1] == 0x08.toByte()) {
                val soc = bytes[i + 3].toInt() and 0xFF
                if (soc in 0..100) {
                    currentTelemetry.batteryPercent = soc
                    onLog("⚡ Telemetri Baterai: $soc %")
                }
            }
            // Function ID 1028 (Range km): varint 0x84, 0x08
            if (bytes[i] == 0x84.toByte() && bytes[i + 1] == 0x08.toByte()) {
                val range = bytes[i + 3].toInt() and 0xFF
                if (range in 0..300) {
                    currentTelemetry.rangeKm = range
                    onLog("⚡ Telemetri Sisa Jarak: $range km")
                }
            }
            // Function ID 1040 (Speed km/h): varint 0x90, 0x08
            if (bytes[i] == 0x90.toByte() && bytes[i + 1] == 0x08.toByte()) {
                val speed = bytes[i + 3].toInt() and 0xFF
                currentTelemetry.speedKmh = speed
                onLog("⚡ Telemetri Kecepatan: $speed km/h")
            }
        }

        onTelemetry(currentTelemetry)
    }

    @SuppressLint("MissingPermission")
    fun connectAndExplore(device: BluetoothDevice) {
        disconnect()
        onLog("Menghubungkan ke ${device.name ?: device.address} via BLE LE Transport...")
        currentGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopPeriodicPolling()
        currentGatt?.disconnect()
        currentGatt?.close()
        currentGatt = null
        writeChar = null
        notifyChar = null
    }
}
