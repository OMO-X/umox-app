package com.umox.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.umox.app.config.AppConfig

data class BleDeviceItem(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int,
    val serviceUuids: List<String>,
    val manufacturerDataHex: String,
    val isCandidate: Boolean,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)

class BleSniffer(
    private val context: Context,
    private val onDeviceFound: (BleDeviceItem) -> Unit,
    private val onLog: (String) -> Unit
) {
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var isScanning = false

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = result.scanRecord?.deviceName ?: device.name ?: "Unknown"
            val address = device.address

            val uuids = result.scanRecord?.serviceUuids?.map { it.toString() } ?: emptyList()

            // Hex encoding manufacturer data
            val mfgData = result.scanRecord?.manufacturerSpecificData
            val mfgBuilder = StringBuilder()
            if (mfgData != null) {
                for (i in 0 until mfgData.size()) {
                    val key = mfgData.keyAt(i)
                    val bytes = mfgData.valueAt(i)
                    mfgBuilder.append("ID 0x${Integer.toHexString(key)}: ")
                    mfgBuilder.append(bytes.joinToString("") { "%02X".format(it) })
                    mfgBuilder.append("; ")
                }
            }

            val hasVdlmUuid = uuids.any { it.contains("fff0", ignoreCase = true) }
            val isCandidate = hasVdlmUuid ||
                    AppConfig.matchesCandidateName(name) ||
                    AppConfig.matchesTargetBleMac(address) ||
                    AppConfig.matchesCandidateOui(address)

            val item = BleDeviceItem(
                device = device,
                name = name,
                address = address,
                rssi = result.rssi,
                serviceUuids = uuids,
                manufacturerDataHex = mfgBuilder.toString(),
                isCandidate = isCandidate
            )

            onDeviceFound(item)
        }

        override fun onScanFailed(errorCode: Int) {
            onLog("BLE Scan gagal dengan kode error: $errorCode")
            isScanning = false
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            onLog("Bluetooth belum aktif atau tidak didukung pada perangkat ini.")
            return
        }

        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            onLog("BluetoothLeScanner tidak tersedia.")
            return
        }

        if (isScanning) return

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        onLog("Memulai pemindaian BLE (Sniffing sinyal dashboard/Omoway)...")
        scanner.startScan(null, settings, scanCallback)
        isScanning = true
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!isScanning) return
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        isScanning = false
        onLog("Pemindaian BLE dihentikan.")
    }
}
