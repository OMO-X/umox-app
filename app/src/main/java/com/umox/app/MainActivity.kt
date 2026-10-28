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
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationManagerCompat
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

    private lateinit var hudPreview: com.umox.app.ui.HudPreviewView
    private lateinit var btnStartAndroidAuto: MaterialButton
    private lateinit var btnOpenAaSettings: MaterialButton
    private lateinit var btnStartHud: MaterialButton
    private lateinit var btnStartStream: MaterialButton
    private lateinit var btnTestPattern: MaterialButton
    private lateinit var btnStopAllStream: MaterialButton
    private lateinit var btnOpenGoogleMaps: MaterialButton

    private lateinit var btnScanBle: MaterialButton
    private lateinit var btnProbeWifi: MaterialButton
    private lateinit var btnScanPorts: MaterialButton
    private lateinit var btnClearLog: MaterialButton
    private lateinit var btnCopyLog: MaterialButton
    private lateinit var etTargetIp: EditText

    private lateinit var bleSniffer: BleSniffer
    private lateinit var gattExplorer: GattExplorer
    private lateinit var wifiP2pProbe: WifiP2pProbe
    private lateinit var portScanner: PortScanner
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var mapTileManager: com.umox.app.map.MapTileManager

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

            updateStreamUiState(com.umox.app.stream.StreamMode.SCREEN_MIRROR)
            log("★ Mirroring layar HP aktif. Silakan buka Google Maps atau Waze.")
        } else {
            log("Izin rekam layar dibatalkan.")
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val grantedCount = permissions.values.count { it }
        log("✔ Izin sistem diperbarui ($grantedCount/${permissions.size} aktif).")
        startAutoRadar()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        AppConfig.loadFromPreferences(this)
        initViews()
        initModules()

        if (AppConfig.targetBleMac.isNotBlank()) {
            log("★ Memuat target BLE tersimpan: ${AppConfig.targetBleMac}")
        }
        if (AppConfig.targetP2pMac.isNotBlank()) {
            log("★ Memuat target Wi-Fi Direct tersimpan: ${AppConfig.targetP2pMac}")
        }

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
        hudPreview = findViewById(R.id.hudPreview)
        btnStartAndroidAuto = findViewById(R.id.btnStartAndroidAuto)
        btnOpenAaSettings = findViewById(R.id.btnOpenAaSettings)
        btnStartHud = findViewById(R.id.btnStartHud)
        btnStartStream = findViewById(R.id.btnStartStream)
        btnTestPattern = findViewById(R.id.btnTestPattern)
        btnStopAllStream = findViewById(R.id.btnStopAllStream)
        btnOpenGoogleMaps = findViewById(R.id.btnOpenGoogleMaps)
        etTargetIp = findViewById(R.id.etTargetIp)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private fun initModules() {
        ScreenCastService.onLogCallback = { msg ->
            runOnUiThread { log(msg) }
        }

        if (ScreenCastService.currentHudRenderer == null) {
            ScreenCastService.currentHudRenderer = com.umox.app.ui.HudDashboardRenderer(960, 360)
        }
        hudPreview.renderer = ScreenCastService.currentHudRenderer

        hudPreview.setOnClickListener {
            val renderer = ScreenCastService.currentHudRenderer ?: return@setOnClickListener
            val themes = com.umox.app.ui.HudTheme.values()
            val nextIdx = (themes.indexOf(renderer.state.theme) + 1) % themes.size
            renderer.state.theme = themes[nextIdx]
            log("🎨 Tema Tampilan HUD diganti ke: ${renderer.state.theme.name}")
            hudPreview.invalidate()
        }

        hudPreview.setOnLongClickListener {
            showThemeSelectorDialog()
            true
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
                    if (AppConfig.targetP2pMac.isNotBlank()) {
                        log("★ Menghubungkan Wi-Fi Direct dinamis ke $ssid (${AppConfig.targetP2pMac})...")
                        wifiP2pProbe.connectToMotor(AppConfig.targetP2pMac)
                    }
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
                            AppConfig.saveBleMac(this@MainActivity, item.address)
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
                    group?.owner?.deviceAddress?.let { p2pMac ->
                        AppConfig.saveP2pMac(this@MainActivity, p2pMac)
                    }
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

        mapTileManager = com.umox.app.map.MapTileManager(
            context = this,
            onMapBitmapUpdated = { bitmap ->
                ScreenCastService.currentHudRenderer?.state?.liveMapBitmap = bitmap
                hudPreview.postInvalidate()
            }
        )
    }

    private fun setupListeners() {
        btnConnectBleGatt.setOnClickListener {
            // Prioritaskan motor yang sudah dipasangkan di HP (Bonded) agar tidak salah konek ke motor lain
            val connectedBonded = checkBondedMotorcycleDevices()
            if (!connectedBonded) {
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
        }
        btnConnectBleGatt.setOnLongClickListener {
            showMotorcycleSelectorDialog()
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

        btnStartAndroidAuto.setOnClickListener {
            if (!ScreenCastService.isStreamingActive) {
                startAndroidAutoStream()
            } else {
                stopActiveStream()
            }
        }

        btnOpenAaSettings.setOnClickListener {
            openAndroidAutoSettings()
        }

        btnStartHud.setOnClickListener {
            if (!ScreenCastService.isStreamingActive) {
                val ip = etTargetIp.text.toString().trim().ifEmpty { AppConfig.defaultClusterIp }
                log("🚀 Meluncurkan HUD Cockpit Digital (960x360) ke $ip:9528 (Video) / 9527 (Ctrl)...")

                val serviceIntent = Intent(this, ScreenCastService::class.java).apply {
                    action = ScreenCastService.ACTION_START_HUD
                    putExtra(ScreenCastService.EXTRA_TARGET_IP, ip)
                    putExtra(ScreenCastService.EXTRA_VIDEO_PORT, 9528)
                    putExtra(ScreenCastService.EXTRA_CTRL_PORT, 9527)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                updateStreamUiState(com.umox.app.stream.StreamMode.HUD_COCKPIT)
            } else {
                stopActiveStream()
            }
        }

        btnStartStream.setOnClickListener {
            if (!ScreenCastService.isStreamingActive) {
                log("📱 Menyiapkan Screen Mirroring (Cast Layar HP) ke Speedometer...")
                screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            } else {
                stopActiveStream()
            }
        }

        btnTestPattern.setOnClickListener {
            if (!ScreenCastService.isStreamingActive) {
                val ip = etTargetIp.text.toString().trim().ifEmpty { AppConfig.defaultClusterIp }
                log("🧪 Memancarkan Test Pattern Baseline (test_baseline.h264) ke $ip...")

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

                updateStreamUiState(com.umox.app.stream.StreamMode.TEST_PATTERN)
            } else {
                stopActiveStream()
            }
        }

        btnStopAllStream.setOnClickListener {
            stopActiveStream()
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

    private fun updateStreamUiState(mode: com.umox.app.stream.StreamMode) {
        when (mode) {
            com.umox.app.stream.StreamMode.ANDROID_AUTO -> {
                btnStartAndroidAuto.text = "⏹ HENTIKAN ANDROID AUTO"
                btnStartAndroidAuto.setBackgroundColor(ContextCompat.getColor(this, R.color.neon_red))
                tvStreamBadge.text = "AA NATIVE 60FPS"
                tvStreamBadge.setTextColor(ContextCompat.getColor(this, R.color.neon_cyan))
            }
            com.umox.app.stream.StreamMode.HUD_COCKPIT -> {
                btnStartHud.text = "⏹ Hentikan HUD"
                btnStartHud.setBackgroundColor(ContextCompat.getColor(this, R.color.neon_red))
                tvStreamBadge.text = "HUD COCKPIT"
                tvStreamBadge.setTextColor(ContextCompat.getColor(this, R.color.neon_cyan))
            }
            com.umox.app.stream.StreamMode.SCREEN_MIRROR -> {
                btnStartStream.text = "⏹ Hentikan Cast"
                btnStartStream.setBackgroundColor(ContextCompat.getColor(this, R.color.neon_red))
                tvStreamBadge.text = "CAST LAYAR"
                tvStreamBadge.setTextColor(ContextCompat.getColor(this, R.color.neon_green))
            }
            com.umox.app.stream.StreamMode.TEST_PATTERN -> {
                btnTestPattern.text = "⏹ Hentikan Test"
                btnTestPattern.setBackgroundColor(ContextCompat.getColor(this, R.color.neon_red))
                tvStreamBadge.text = "TEST BASELINE"
                tvStreamBadge.setTextColor(ContextCompat.getColor(this, R.color.neon_green))
            }
            com.umox.app.stream.StreamMode.IDLE -> {
                btnStartAndroidAuto.text = "🚀 MULAI ANDROID AUTO OMOWAY (60 FPS)"
                btnStartAndroidAuto.setBackgroundColor(ContextCompat.getColor(this, R.color.neon_cyan))
                btnStartHud.text = "📟 Mode Custom HUD"
                btnStartHud.setBackgroundColor(ContextCompat.getColor(this, R.color.surface_card_elevated))
                btnStartStream.text = "📱 Cast Layar HP"
                btnStartStream.setBackgroundColor(ContextCompat.getColor(this, R.color.surface_card_elevated))
                btnTestPattern.text = "🧪 Test Baseline OK"
                btnTestPattern.setBackgroundColor(ContextCompat.getColor(this, R.color.surface_card_elevated))
                tvStreamBadge.text = "STANDBY"
                tvStreamBadge.setTextColor(ContextCompat.getColor(this, R.color.neon_amber))
            }
        }
    }

    private fun startAndroidAutoStream() {
        val ip = etTargetIp.text.toString().trim().ifEmpty { AppConfig.defaultClusterIp }
        log("🚀 Meluncurkan Android Auto Native (60 FPS) ke speedometer $ip...")

        val serviceIntent = Intent(this, ScreenCastService::class.java).apply {
            action = ScreenCastService.ACTION_START_ANDROID_AUTO
            putExtra(ScreenCastService.EXTRA_TARGET_IP, ip)
            putExtra(ScreenCastService.EXTRA_VIDEO_PORT, 9528)
            putExtra(ScreenCastService.EXTRA_CTRL_PORT, 9527)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        updateStreamUiState(com.umox.app.stream.StreamMode.ANDROID_AUTO)
    }

    private fun openAndroidAutoSettings() {
        try {
            val intent = Intent().apply {
                setClassName("com.google.android.projection.gearhead", "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            showAaSetupGuideDialog()
        } catch (e: Exception) {
            log("Membuka pengaturan aplikasi Android Auto: ${e.message}")
            try {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:com.google.android.projection.gearhead")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                showAaSetupGuideDialog()
            } catch (_: Exception) {
                Toast.makeText(this, "Aplikasi Android Auto tidak ditemukan di HP", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAaSetupGuideDialog() {
        AlertDialog.Builder(this)
            .setTitle("Petunjuk Head Unit Server")
            .setMessage(
                "Cara menjalankan server Android Auto di HP:\n\n" +
                "1. Di halaman Pengaturan Android Auto, scroll ke paling bawah.\n" +
                "2. Ketuk teks 'Versi' sebanyak 10 kali sampai Developer Mode aktif.\n" +
                "3. Ketuk menu Titik 3 di pojok kanan atas.\n" +
                "4. Pilih 'Start head unit server'.\n\n" +
                "Setelah itu kembali ke UMOX dan tekan 'MULAI ANDROID AUTO'."
            )
            .setPositiveButton("SIAP, SAYA PAHAM", null)
            .show()
    }

    private fun stopActiveStream() {
        val stopIntent = Intent(this, ScreenCastService::class.java).apply {
            action = ScreenCastService.ACTION_STOP
        }
        try {
            startService(stopIntent)
        } catch (_: Exception) {}
        updateStreamUiState(com.umox.app.stream.StreamMode.IDLE)
        log("⏹ Sesi streaming dihentikan. Layar speedometer kembali ke dashboard normal.")
    }

    @SuppressLint("MissingPermission")
    private fun checkBondedMotorcycleDevices(): Boolean {
        try {
            val btAdapter = BluetoothAdapter.getDefaultAdapter() ?: return false
            val bonded = btAdapter.bondedDevices ?: return false
            val omoBonded = bonded.filter { dev ->
                val name = dev.name ?: ""
                name.contains("OMOWAY", ignoreCase = true) ||
                name.contains("OMO", ignoreCase = true) ||
                name.contains("DESAY", ignoreCase = true) ||
                AppConfig.matchesCandidateName(name) ||
                AppConfig.matchesCandidateOui(dev.address)
            }

            if (omoBonded.isNotEmpty()) {
                val motor = omoBonded.first()
                log("★ TERDETEKSI MOTOR OMOWAY TERPASANG DI HP: ${motor.name} (${motor.address})")
                AppConfig.saveBleMac(this, motor.address)
                tvMetricBle.text = motor.name.ifEmpty { motor.address }
                gattExplorer.connectAndExplore(motor)
                return true
            }
        } catch (e: Exception) {
            log("Pemeriksaan perangkat terpasang: ${e.message}")
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun showMotorcycleSelectorDialog() {
        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        val bonded = btAdapter?.bondedDevices?.toList() ?: emptyList()
        val allDevices = (discoveredBleDevices.values.map { it.device } + bonded).distinctBy { it.address }

        if (allDevices.isEmpty()) {
            Toast.makeText(this, "Tidak ada perangkat Bluetooth terdeteksi di sekitar.", Toast.LENGTH_SHORT).show()
            bleSniffer.startScan()
            return
        }

        val deviceNames = allDevices.map { dev ->
            val isBonded = bonded.any { it.address == dev.address }
            "${dev.name ?: "Unknown"} [${dev.address}] ${if (isBonded) "★ (Terpasang)" else ""}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Pilih Motor Anda (Mencegah Salah Konek)")
            .setItems(deviceNames) { _, which ->
                val selected = allDevices[which]
                log("✔ Motor dipilih oleh pengguna: ${selected.name ?: selected.address} (${selected.address})")
                AppConfig.saveBleMac(this, selected.address)
                tvMetricBle.text = selected.name ?: selected.address
                gattExplorer.connectAndExplore(selected)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showThemeSelectorDialog() {
        val renderer = ScreenCastService.currentHudRenderer ?: return
        val themeNames = arrayOf(
            "1. Android Auto Coolwalk (Nav + Spotify + WA)",
            "2. Satelit Touring (Full Satellite View)",
            "3. Cyberpunk Neon Cockpit",
            "4. Minimalist Clean OLED"
        )
        val themes = com.umox.app.ui.HudTheme.values()

        AlertDialog.Builder(this)
            .setTitle("🎨 Pilih Tema Cockpit Speedometer")
            .setItems(themeNames) { _, which ->
                renderer.state.theme = themes[which]
                log("🎨 Tema Tampilan HUD diubah ke: ${themeNames[which]}")
                hudPreview.invalidate()
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun checkNotificationPermission() {
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        if (!enabled) {
            log("ℹ Izin Akses Notifikasi belum aktif. Navigasi & musik butuh izin ini untuk tampil di cluster.")
        } else {
            log("✔ Izin Notification Listener aktif (Google Maps & Musik siap dikirim ke cluster).")
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
        checkBondedMotorcycleDevices()
        checkNotificationPermission()

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
            
            // Muat lokasi terakhir yang diketahui saat startup
            val lastLoc = try {
                locManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } catch (_: SecurityException) { null }

            lastLoc?.let { loc ->
                ScreenCastService.currentHudRenderer?.state?.apply {
                    latitude = loc.latitude
                    longitude = loc.longitude
                }
                mapTileManager.updateLocation(loc.latitude, loc.longitude, isSatellite = true)
            }

            locManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                500L,
                1f,
                object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        val renderer = ScreenCastService.currentHudRenderer
                        if (location.hasSpeed()) {
                            val kmh = (location.speed * 3.6f).toInt()
                            tvLiveSpeed.text = "$kmh km/h"
                            renderer?.state?.speedKmh = kmh
                        }
                        if (location.hasBearing()) {
                            renderer?.state?.bearing = location.bearing
                        }
                        renderer?.state?.latitude = location.latitude
                        renderer?.state?.longitude = location.longitude

                        val isSat = renderer?.state?.theme == com.umox.app.ui.HudTheme.SATELLITE_TOURING
                        mapTileManager.updateLocation(location.latitude, location.longitude, isSatellite = isSat)
                        hudPreview.postInvalidate()
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
