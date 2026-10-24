package com.umox.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import com.umox.app.ble.BleDeviceItem
import com.umox.app.ble.BleSniffer
import com.umox.app.ble.GattExplorer
import com.umox.app.probe.PortScanner
import com.umox.app.config.AppConfig
import com.umox.app.stream.ScreenCastService
import com.umox.app.wifi.WifiP2pProbe
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvConsoleLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var tvStatusBadge: TextView
    private lateinit var tvMetricBle: TextView
    private lateinit var tvMetricIp: TextView
    private lateinit var tvMetricPort: TextView
    private lateinit var tvStreamBadge: TextView

    // Live Telemetry Views
    private lateinit var tvLiveBattery: TextView
    private lateinit var tvLiveRange: TextView
    private lateinit var tvLiveOdo: TextView
    private lateinit var tvLiveSpeed: TextView
    private lateinit var tvLiveTemp: TextView
    private lateinit var tvLiveLock: TextView
    private lateinit var btnConnectBleGatt: MaterialButton

    private lateinit var btnScanBle: MaterialButton
    private lateinit var btnProbeWifi: MaterialButton
    private lateinit var btnScanPorts: MaterialButton
    private lateinit var btnClearLog: MaterialButton
    private lateinit var btnCopyLog: MaterialButton
    private lateinit var btnTestPattern: MaterialButton
    private lateinit var btnStartStream: MaterialButton
    private lateinit var btnOpenGoogleMaps: MaterialButton
    private lateinit var etTargetIp: EditText

    private lateinit var bleSniffer: BleSniffer
    private lateinit var gattExplorer: GattExplorer
    private lateinit var wifiP2pProbe: WifiP2pProbe
    private lateinit var portScanner: PortScanner
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private var isBleScanning = false
    private val discoveredBleDevices = mutableMapOf<String, BleDeviceItem>()

    // Launcher untuk izin tangkap layar (MediaProjection)
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val ip = etTargetIp.text.toString().trim().ifEmpty { AppConfig.defaultClusterIp }
            log("Izin rekam layar disetujui. Meluncurkan ScreenCastService ke $ip:9528 (Video) / 9527 (Ctrl)...")

            val serviceIntent = Intent(this, ScreenCastService::class.java).apply {
                action = ScreenCastService.ACTION_START_SCREEN
                putExtra(ScreenCastService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCastService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenCastService.EXTRA_TARGET_IP, ip)
                putExtra(ScreenCastService.EXTRA_VIDEO_PORT, 9528)
                putExtra(ScreenCastService.EXTRA_CTRL_PORT, 9527)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            btnStartStream.text = "Hentikan Stream Layar"
            btnStartStream.setBackgroundColor(ContextCompat.getColor(this, R.color.neon_red))
            tvStreamBadge.text = "STREAMING (960x360)"
            tvStreamBadge.setTextColor(ContextCompat.getColor(this, R.color.neon_green))
        } else {
            log("Izin rekam layar dibatalkan.")
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            log("✔ Izin Bluetooth & Wi-Fi aktif.")
            startAutoRadar()
        } else {
            log("⚠ Beberapa izin ditolak oleh sistem.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initModules()
        checkPermissions()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        wifiP2pProbe.checkConnectionState()
    }

    private fun initViews() {
        tvConsoleLog = findViewById(R.id.tvConsoleLog)
        scrollLog = findViewById(R.id.scrollLog)
        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        tvMetricBle = findViewById(R.id.tvMetricBle)
        tvMetricIp = findViewById(R.id.tvMetricIp)
        tvMetricPort = findViewById(R.id.tvMetricPort)
        tvStreamBadge = findViewById(R.id.tvStreamBadge)

        // Telemetry views
        tvLiveBattery = findViewById(R.id.tvLiveBattery)
        tvLiveRange = findViewById(R.id.tvLiveRange)
        tvLiveOdo = findViewById(R.id.tvLiveOdo)
        tvLiveSpeed = findViewById(R.id.tvLiveSpeed)
        tvLiveTemp = findViewById(R.id.tvLiveTemp)
        tvLiveLock = findViewById(R.id.tvLiveLock)
        btnConnectBleGatt = findViewById(R.id.btnConnectBleGatt)

        btnScanBle = findViewById(R.id.btnScanBle)
        btnProbeWifi = findViewById(R.id.btnProbeWifi)
        btnScanPorts = findViewById(R.id.btnScanPorts)
        btnClearLog = findViewById(R.id.btnClearLog)
        btnCopyLog = findViewById(R.id.btnCopyLog)
        btnTestPattern = findViewById(R.id.btnTestPattern)
        btnStartStream = findViewById(R.id.btnStartStream)
        btnOpenGoogleMaps = findViewById(R.id.btnOpenGoogleMaps)
        etTargetIp = findViewById(R.id.etTargetIp)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private fun initModules() {
        ScreenCastService.onLogCallback = { msg ->
            runOnUiThread { log(msg) }
        }

        gattExplorer = GattExplorer(
            context = this,
            onLog = { msg -> runOnUiThread { log(msg) } },
            onTelemetry = { telem ->
                runOnUiThread {
                    telem.batteryPercent?.let {
                        tvLiveBattery.text = "$it %"
                        ScreenCastService.currentHudRenderer?.state?.batteryPercent = it
                    }
                    telem.rangeKm?.let {
                        tvLiveRange.text = "$it km"
                        ScreenCastService.currentHudRenderer?.state?.rangeKm = it
                    }
                    telem.odometerKm?.let { tvLiveOdo.text = "${it.toInt()} km" }
                    telem.speedKmh?.let {
                        tvLiveSpeed.text = "$it km/h"
                        ScreenCastService.currentHudRenderer?.state?.speedKmh = it
                    }
                    telem.temperatureStatus?.let { tvLiveTemp.text = it }
                    telem.isLocked?.let { tvLiveLock.text = if (it) "Terkunci" else "Terbuka" }
                }
            },
            onWifiInfoFound = { ssid, pass, ip ->
                runOnUiThread {
                    etTargetIp.setText(ip)
                    tvMetricIp.text = ip
                    log("✔ Kredensial Wi-Fi Motor: SSID=$ssid, Password=$pass, IP=$ip")
                }
            }
        )

        bleSniffer = BleSniffer(
            context = this,
            onDeviceFound = { item ->
                runOnUiThread {
                    if (!discoveredBleDevices.containsKey(item.address)) {
                        discoveredBleDevices[item.address] = item
                        if (item.isCandidate) {
                            tvMetricBle.text = item.name.ifEmpty { item.address }
                            log("★ [KANDIDAT OTOMOTIF] ${item.name} (${item.address}) RSSI: ${item.rssi} dBm")
                            // Auto-connect ke BLE motor untuk langsung membaca data
                            gattExplorer.connectAndExplore(item.device)
                        } else {
                            log("• ${item.name} (${item.address}) [${item.rssi} dBm]")
                        }

                        if (item.serviceUuids.isNotEmpty()) {
                            log("   UUIDs: ${item.serviceUuids.joinToString(", ")}")
                        }
                        if (item.manufacturerDataHex.isNotEmpty()) {
                            log("   MfgData: ${item.manufacturerDataHex}")
                        }
                    }
                }
            },
            onLog = { msg -> runOnUiThread { log(msg) } }
        )

        wifiP2pProbe = WifiP2pProbe(
            context = this,
            onPeersDiscovered = { peers ->
                runOnUiThread {
                    tvStatusBadge.text = "● P2P: ${peers.size} PEERS"
                }
            },
            onConnectionInfo = { info, group ->
                runOnUiThread {
                    info.groupOwnerAddress?.hostAddress?.let { ip ->
                        etTargetIp.setText(ip)
                        tvMetricIp.text = ip
                        log("✔ IP Speedometer terdeteksi: $ip")
                    }
                    tvStatusBadge.text = "● P2P CONNECTED"
                }
            },
            onLog = { msg -> runOnUiThread { log(msg) } }
        )

        portScanner = PortScanner(
            onLog = { msg -> runOnUiThread { log(msg) } }
        )
    }

    private fun setupListeners() {
        btnConnectBleGatt.setOnClickListener {
            val candidate = discoveredBleDevices.values.firstOrNull { it.isCandidate }
                ?: discoveredBleDevices.values.firstOrNull()
            if (candidate != null) {
                log("Menghubungkan ke ${candidate.name} (${candidate.address}) via GATT...")
                gattExplorer.connectAndExplore(candidate.device)
            } else {
                log("Memulai BLE Radar untuk mencari motor...")
                bleSniffer.startScan()
                isBleScanning = true
                btnScanBle.text = "Hentikan Scan BLE"
                tvStatusBadge.text = "● SCANNING"
            }
        }
        btnConnectBleGatt.setOnLongClickListener {
            log("Mengirim request polling status ke motor...")
            gattExplorer.sendPollRequest()
            true
        }
        btnScanBle.setOnClickListener {
            if (!isBleScanning) {
                discoveredBleDevices.clear()
                bleSniffer.startScan()
                isBleScanning = true
                btnScanBle.text = "Hentikan Scan BLE"
                tvStatusBadge.text = "● SCANNING"
            } else {
                bleSniffer.stopScan()
                isBleScanning = false
                btnScanBle.text = "Mulai Pemindaian BLE Radar"
                tvStatusBadge.text = "● IDLE"
            }
        }

        btnProbeWifi.setOnClickListener {
            log("Memeriksa status & sambungan Wi-Fi Direct Motor (DIRECT-OMO)...")
            wifiP2pProbe.registerAndListen()
            if (wifiP2pProbe.isGroupConnected) {
                tvStatusBadge.text = "● P2P CONNECTED"
            } else {
                tvStatusBadge.text = "● P2P SCANNING"
            }
        }

        btnProbeWifi.setOnLongClickListener {
            log("Memutuskan sambungan Wi-Fi Direct (Reset Group)...")
            wifiP2pProbe.disconnectGroup()
            tvStatusBadge.text = "● P2P DISCONNECTED"
            true
        }

        btnScanPorts.setOnClickListener {
            val ip = etTargetIp.text.toString().trim()
            if (ip.isEmpty()) {
                Toast.makeText(this, "Masukkan Host IP terlebih dahulu!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            tvStatusBadge.text = "● SCANNING PORTS"
            lifecycleScope.launch {
                val openPorts = portScanner.scanHost(ip)
                if (openPorts.isNotEmpty()) {
                    val first = openPorts.first()
                    tvMetricPort.text = "${first.port} (${first.description.take(10)})"
                }
                tvStatusBadge.text = "● SCAN COMPLETED"
            }
        }

        btnTestPattern.setOnClickListener {
            if (!ScreenCastService.isStreamingActive) {
                val ip = etTargetIp.text.toString().trim().ifEmpty { AppConfig.defaultClusterIp }
                log("🚀 Meluncurkan Pure Android Auto HUD (960x360) ke $ip (Video: 9528, Ctrl: 9527)...")

                val serviceIntent = Intent(this, ScreenCastService::class.java).apply {
                    action = ScreenCastService.ACTION_START_PATTERN
                    putExtra(ScreenCastService.EXTRA_TARGET_IP, ip)
                    putExtra(ScreenCastService.EXTRA_VIDEO_PORT, 9528)
                    putExtra(ScreenCastService.EXTRA_CTRL_PORT, 9527)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                btnTestPattern.text = "⏹ Hentikan Android Auto HUD"
                btnTestPattern.setBackgroundColor(ContextCompat.getColor(this, R.color.neon_red))
                tvStreamBadge.text = "AUTO HUD (960x360)"
                tvStreamBadge.setTextColor(ContextCompat.getColor(this, R.color.neon_cyan))
            } else {
                val stopIntent = Intent(this, ScreenCastService::class.java).apply {
                    action = ScreenCastService.ACTION_STOP
                }
                startService(stopIntent)

                btnTestPattern.text = "🚀 Launch Pure Android Auto HUD (960x360)"
                btnTestPattern.setBackgroundColor(ContextCompat.getColor(this, R.color.neon_cyan))
                btnStartStream.text = "Mulai Stream Layar (Maps)"
                btnStartStream.setBackgroundColor(ContextCompat.getColor(this, R.color.neon_green))
                tvStreamBadge.text = "STANDBY"
                tvStreamBadge.setTextColor(ContextCompat.getColor(this, R.color.neon_amber))
            }
        }

        btnStartStream.setOnClickListener {
            if (!ScreenCastService.isStreamingActive) {
                // Minta izin rekam layar
                screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            } else {
                // Hentikan streaming
                val stopIntent = Intent(this, ScreenCastService::class.java).apply {
                    action = ScreenCastService.ACTION_STOP
                }
                startService(stopIntent)

                btnStartStream.text = "Mulai Stream Layar (Maps)"
                btnStartStream.setBackgroundColor(ContextCompat.getColor(this, R.color.neon_green))
                btnTestPattern.text = "⚡ Test Cast Gambar HUD (Tanpa Rekam Layar)"
                btnTestPattern.setBackgroundColor(ContextCompat.getColor(this, R.color.neon_cyan))
                tvStreamBadge.text = "STANDBY"
                tvStreamBadge.setTextColor(ContextCompat.getColor(this, R.color.neon_amber))
            }
        }

        btnOpenGoogleMaps.setOnClickListener {
            try {
                val gmmIntentUri = Uri.parse("google.navigation:q=Jakarta")
                val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
                    setPackage("com.google.android.apps.maps")
                }
                if (mapIntent.resolveActivity(packageManager) != null) {
                    startActivity(mapIntent)
                } else {
                    val genericMap = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))
                    startActivity(genericMap)
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Gagal membuka Maps: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        btnCopyLog.setOnClickListener {
            copyLogToClipboard()
        }

        btnClearLog.setOnClickListener {
            tvConsoleLog.text = ""
            log("Log dibersihkan.")
        }

        tvConsoleLog.setOnLongClickListener {
            copyLogToClipboard()
            true
        }
    }

    private fun copyLogToClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("UMOX Sniffer Log", tvConsoleLog.text.toString())
        cm.setPrimaryClip(clip)
        Toast.makeText(this, "Log berhasil disalin ke Clipboard!", Toast.LENGTH_SHORT).show()
    }

    private fun checkPermissions() {
        val perms = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            perms.add(Manifest.permission.BLUETOOTH)
            perms.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            startAutoRadar()
        }
    }

    private fun startAutoRadar() {
        if (!isBleScanning) {
            log("🚀 Memulai Pemindaian BLE Radar Otomatis...")
            bleSniffer.startScan()
            isBleScanning = true
            btnScanBle.text = "Hentikan Scan BLE"
            tvStatusBadge.text = "● RADAR ACTIVE"
        }
        wifiP2pProbe.registerAndListen()

        try {
            val locManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            locManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                500L,
                1f,
                object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (location.hasSpeed()) {
                            val kmh = (location.speed * 3.6f).toInt()
                            tvLiveSpeed.text = "$kmh km/h"
                            ScreenCastService.currentHudRenderer?.state?.speedKmh = kmh
                        }
                    }
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
            )
        } catch (_: Exception) {}
    }

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val formatted = "[$time] $message\n"
        android.util.Log.i("UMOX", message)
        tvConsoleLog.append(formatted)
        scrollLog.post {
            scrollLog.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleSniffer.stopScan()
        wifiP2pProbe.unregister()
        gattExplorer.disconnect()
        ScreenCastService.onLogCallback = null
    }
}
