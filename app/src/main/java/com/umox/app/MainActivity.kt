package com.umox.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.umox.app.aa.AaHeadlessReceiver
import com.umox.app.ble.BleDeviceItem
import com.umox.app.ble.BleSniffer
import com.umox.app.ble.GattExplorer
import com.umox.app.config.AppConfig
import com.umox.app.databinding.ActivityMainBinding
import com.umox.app.stream.OmowayViewportConfig
import com.umox.app.stream.ScreenCastService
import com.umox.app.stream.SocketStreamer
import com.umox.app.stream.StreamMode
import com.umox.app.wifi.WifiP2pProbe
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bleSniffer: BleSniffer
    private lateinit var gattExplorer: GattExplorer
    private lateinit var wifiP2pProbe: WifiP2pProbe

    private var extractedSsid: String = "DIRECT-OMO-*"
    private var extractedPass: String = "12345678"
    private val discoveredBleDevices = mutableMapOf<String, BleDeviceItem>()
    private var hasAutoConnectedBle = false
    private var activePreviewSurface: Surface? = null
    private var pendingAutoStartAaAfterSettings = false

    @Volatile
    private var isAaServerListening: Boolean = false

    @Volatile
    private var isMotorNetworkReady: Boolean = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val probeExecutor = Executors.newSingleThreadExecutor()
    private var isTickerRunning = false

    private val statusTicker = object : Runnable {
        override fun run() {
            if (!isTickerRunning) return
            probeAaServerAsync()
            refreshDashboardState()
            uiHandler.postDelayed(this, 800L)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            appendLog("✔ Semua izin sistem (BLE, Lokasi, Wi-Fi Direct) aktif.")
            startAutoDiscovery()
        } else {
            appendLog("⚠ Beberapa izin belum diberikan, mencoba memindai dengan izin yang ada...")
            startAutoDiscovery()
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startScreenCastService(result.resultCode, result.data!!)
        } else {
            appendLog("✖ Izin perekaman layar dibatalkan.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        AppConfig.loadFromPreferences(this)
        initManagers()
        setupLivePreviewTexture()
        setupClickListeners()
        checkAndRequestPermissions()

        ScreenCastService.onLogCallback = { msg ->
            runOnUiThread { appendLog(msg) }
        }
        ScreenCastService.onStateChangedCallback = {
            runOnUiThread { refreshDashboardState() }
        }

        refreshDashboardState()
    }

    override fun onResume() {
        super.onResume()
        isTickerRunning = true
        uiHandler.post(statusTicker)
    }

    override fun onPause() {
        super.onPause()
        isTickerRunning = false
        uiHandler.removeCallbacks(statusTicker)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupLivePreviewTexture() {
        binding.textureLivePreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                OmowayViewportConfig.attachPreview(surfaceTexture, width, height)
            }

            override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                OmowayViewportConfig.attachPreview(surfaceTexture, width, height)
            }

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                return OmowayViewportConfig.detachPreview(surfaceTexture)
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}
        }

        binding.textureLivePreview.setOnTouchListener { view, event ->
            val receiver = ScreenCastService.activeAaReceiver
            if (receiver != null && view.width > 0 && view.height > 0) {
                val normX = (event.x / view.width.toFloat()).coerceIn(0f, 1f)
                val normY = (event.y / view.height.toFloat()).coerceIn(0f, 1f)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        receiver.sendTouch(normX, normY, 0)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        receiver.sendTouch(normX, normY, 2)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        receiver.sendTouch(normX, normY, 1)
                    }
                }
                true
            } else {
                false
            }
        }
    }

    private fun initManagers() {
        wifiP2pProbe = WifiP2pProbe(
            context = this,
            onPeersDiscovered = { peers ->
                runOnUiThread {
                    if (peers.isNotEmpty()) {
                        refreshDashboardState()
                    }
                }
            },
            onConnectionInfo = { info, group ->
                isMotorNetworkReady = info.groupFormed
                runOnUiThread {
                    val ownerIp = info.groupOwnerAddress?.hostAddress ?: "192.168.2.1"
                    binding.etClusterIp.setText(ownerIp)
                    group?.networkName?.let { netName ->
                        extractedSsid = netName
                        binding.tvExtractedSsid.text = "SSID: $netName"
                    }
                    refreshDashboardState()
                    if (info.groupFormed) {
                        appendLog("★ Wi-Fi Direct Motor Terhubung! Gateway: $ownerIp")
                    }
                }
            },
            onLog = { msg -> runOnUiThread { appendLog("[P2P] $msg") } }
        )

        gattExplorer = GattExplorer(
            context = this,
            onLog = { msg -> runOnUiThread { appendLog("[GATT] $msg") } },
            onTelemetry = { tele ->
                runOnUiThread {
                    tele.speedKmh?.let { binding.tvTeleSpeed.text = "$it" }
                    tele.batteryPercent?.let { binding.tvTeleBat.text = "$it%" }
                    tele.rangeKm?.let { binding.tvTeleRange.text = "$it km" }
                    tele.odometerKm?.let {
                        binding.tvTeleOdo.text = String.format(Locale.US, "%.1f", it)
                    }
                    tele.isLocked?.let { locked ->
                        binding.tvTeleLockState.text = if (!locked) "STATUS: MENYALA 🔓" else "STATUS: TERKUNCI 🔒"
                        binding.tvTeleLockState.setTextColor(
                            Color.parseColor(if (!locked) "#00E676" else "#FFB300")
                        )
                    }
                }
            },
            onWifiInfoFound = { ssid, pass, ip ->
                runOnUiThread {
                    if (ssid.isNotBlank()) {
                        extractedSsid = ssid
                        binding.tvExtractedSsid.text = "SSID: $ssid"
                    }
                    if (pass.isNotBlank()) {
                        extractedPass = pass
                        binding.tvExtractedPass.text = "PASS: $pass"
                    }
                    if (ip.isNotBlank()) {
                        binding.etClusterIp.setText(ip)
                    }
                    wifiP2pProbe.connectToMotor()
                    refreshDashboardState()
                }
            }
        )

        bleSniffer = BleSniffer(
            context = this,
            onDeviceFound = { item ->
                discoveredBleDevices[item.address] = item
                if (item.isCandidate && !hasAutoConnectedBle) {
                    hasAutoConnectedBle = true
                    AppConfig.saveBleMac(this, item.address)
                    runOnUiThread {
                        appendLog("★ Kandidat Motor Ditemukan: ${item.name} (${item.address}) [${item.rssi} dBm]")
                        bleSniffer.stopScan()
                        gattExplorer.connectAndExplore(item.device)
                    }
                }
            },
            onLog = { msg -> runOnUiThread { appendLog("[BLE] $msg") } }
        )
    }

    private fun startAutoDiscovery() {
        try {
            wifiP2pProbe.registerAndListen()
            bleSniffer.startScan()
        } catch (e: Exception) {
            appendLog("⚠ Auto-discovery info: ${e.message}")
        }
    }

    private fun setupClickListeners() {
        // Langkah 1: Buka Pengaturan Server Unit Kepala Android Auto
        binding.btnOpenAaSettings.setOnClickListener {
            pendingAutoStartAaAfterSettings = true
            openAndroidAutoHeadUnitServer()
        }

        // Langkah 2: Auto-Link Motor (Scan BLE + Connect Wi-Fi Direct)
        binding.btnAutoLinkMotor.setOnClickListener {
            appendLog("⚡ Auto-Link: Memindai BLE & menghubungkan Wi-Fi Direct motor...")
            hasAutoConnectedBle = false
            bleSniffer.startScan()
            wifiP2pProbe.registerAndListen()
            wifiP2pProbe.connectToMotor()
        }

        // Primary CTA: Mulai / Restart Android Auto 60 FPS
        binding.btnStartAndroidAuto.setOnClickListener {
            startAndroidAutoService()
        }

        // Stop Streaming
        binding.btnStopCast.setOnClickListener {
            stopCastService()
        }

        // Rotary Knob Controls (seperti knob stang motor & WayXAuto)
        binding.btnRotaryPrev.setOnClickListener {
            val receiver = ScreenCastService.activeAaReceiver
            if (receiver != null) {
                receiver.sendRotary(-1)
            } else {
                Toast.makeText(this, "Mulai Android Auto terlebih dahulu", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnRotarySelect.setOnClickListener {
            val receiver = ScreenCastService.activeAaReceiver
            if (receiver != null) {
                receiver.sendKey(AaHeadlessReceiver.KEYCODE_DPAD_CENTER)
            } else {
                Toast.makeText(this, "Mulai Android Auto terlebih dahulu", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnRotaryNext.setOnClickListener {
            val receiver = ScreenCastService.activeAaReceiver
            if (receiver != null) {
                receiver.sendRotary(1)
            } else {
                Toast.makeText(this, "Mulai Android Auto terlebih dahulu", Toast.LENGTH_SHORT).show()
            }
        }

        // Remote Bar untuk Android Auto
        binding.btnRemoteMaps.setOnClickListener {
            val receiver = ScreenCastService.activeAaReceiver
            if (receiver != null) {
                receiver.sendKey(AaHeadlessReceiver.KEYCODE_NAVIGATION)
                appendLog("🗺️ Mengirim perintah buka Google Maps ke Android Auto.")
            } else {
                Toast.makeText(this, "Mulai Android Auto terlebih dahulu", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnRemoteMusic.setOnClickListener {
            val receiver = ScreenCastService.activeAaReceiver
            if (receiver != null) {
                receiver.sendKey(AaHeadlessReceiver.KEYCODE_MUSIC)
                appendLog("🎵 Mengirim perintah buka Musik ke Android Auto.")
            } else {
                Toast.makeText(this, "Mulai Android Auto terlebih dahulu", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnRemotePlayPause.setOnClickListener {
            val receiver = ScreenCastService.activeAaReceiver
            if (receiver != null) {
                receiver.sendKey(AaHeadlessReceiver.KEYCODE_MEDIA_PLAY_PAUSE)
                appendLog("⏯ Mengirim perintah Play/Pause ke Android Auto.")
            } else {
                Toast.makeText(this, "Mulai Android Auto terlebih dahulu", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnRemoteNightMode.setOnClickListener {
            val receiver = ScreenCastService.activeAaReceiver
            if (receiver != null) {
                val nextNight = !receiver.isNightModeActive
                receiver.sendNightMode(nextNight)
                appendLog("🌓 Tema Android Auto diubah ke: ${if (nextNight) "MALAM" else "SIANG"}")
            } else {
                Toast.makeText(this, "Mulai Android Auto terlebih dahulu", Toast.LENGTH_SHORT).show()
            }
        }

        // Mode Alternatif: Screen Mirroring & Test Video Baseline
        binding.btnStartCast.setOnClickListener {
            requestScreenCapture()
        }

        binding.btnStartPattern.setOnClickListener {
            startPatternService()
        }

        // Tombol Utilitas Jaringan Manual
        binding.btnScanBle.setOnClickListener {
            hasAutoConnectedBle = false
            bleSniffer.startScan()
        }

        binding.btnConnectWifi.setOnClickListener {
            gattExplorer.sendWifiQueryRequest()
            wifiP2pProbe.connectToMotor()
        }

        binding.btnForceP2pConnect.setOnClickListener {
            wifiP2pProbe.discoverPeers()
            wifiP2pProbe.connectToMotor()
        }

        binding.btnUnbindNet.setOnClickListener {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.bindProcessToNetwork(null)
            } catch (_: Exception) {}
            wifiP2pProbe.disconnectGroup()
            isMotorNetworkReady = false
            refreshDashboardState()
            appendLog("✔ Binding jaringan direset.")
        }

        // Kontrol Polling & Kueri BLE
        binding.btnUnlockBike.setOnClickListener {
            gattExplorer.sendPollRequest()
            appendLog("⚡ Meminta sinkronisasi telemetri BLE motor...")
        }

        binding.btnLockBike.setOnClickListener {
            gattExplorer.sendWifiQueryRequest()
            appendLog("📡 Meminta kredensial Wi-Fi Direct dari BLE motor...")
        }

        // Log Console Actions
        binding.btnCopyLog.setOnClickListener {
            val logText = binding.tvConsoleLog.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("UMOX Log", logText))
            Toast.makeText(this, "Log berhasil disalin", Toast.LENGTH_SHORT).show()
        }

        binding.btnClearLog.setOnClickListener {
            binding.tvConsoleLog.text = ""
            appendLog("Log dibersihkan.")
        }
    }

    /**
     * Membuka halaman Android Auto Developer / Head Unit Server secara langsung.
     */
    private fun openAndroidAutoHeadUnitServer() {
        val candidateIntents = listOf(
            Intent().apply {
                component = ComponentName(
                    "com.google.android.projection.gearhead",
                    "com.google.android.projection.gearhead.companion.devsettings.DeveloperSettingsActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent("com.google.android.projection.gearhead.SETTINGS").apply {
                setPackage("com.google.android.projection.gearhead")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            packageManager.getLaunchIntentForPackage("com.google.android.projection.gearhead")
        )

        for (intent in candidateIntents.filterNotNull()) {
            try {
                startActivity(intent)
                appendLog("Membuka pengaturan Android Auto (Aktifkan 'Mulai server unit kepala' di titik tiga kanan atas).")
                return
            } catch (_: Exception) {}
        }

        Toast.makeText(
            this,
            "Buka Pengaturan HP -> Android Auto -> Titik Tiga -> Mulai server unit kepala",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Mengecek apakah port 5277 sedang aktif TANPA melakukan `Socket.connect()`
     * (persis `v.i(Context)` di WayXAuto: `Socket.connect` lalu `close` akan membunuh sesi tunggal
     * `DeveloperHeadUnitNetworkService` milik Google Android Auto!).
     */
    private fun probeAaServerAsync() {
        probeExecutor.execute {
            val active = if (ScreenCastService.isStreamingActive &&
                ScreenCastService.currentMode == StreamMode.ANDROID_AUTO &&
                ScreenCastService.activeAaReceiver?.isVideoStreaming == true
            ) {
                true
            } else {
                try {
                    ServerSocket().use { ss ->
                        ss.reuseAddress = false
                        ss.bind(
                            InetSocketAddress(
                                InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)),
                                AaHeadlessReceiver.AA_PORT_LOCAL
                            ),
                            1
                        )
                    }
                    false
                } catch (e: BindException) {
                    val msg = e.message.orEmpty()
                    msg.contains("EADDRINUSE", ignoreCase = true) || msg.contains("in use", ignoreCase = true)
                } catch (_: Exception) {
                    false
                }
            }
            val p2pActive = wifiP2pProbe.isGroupConnected
            if (isAaServerListening != active || isMotorNetworkReady != p2pActive) {
                isAaServerListening = active
                isMotorNetworkReady = p2pActive
                uiHandler.post {
                    refreshDashboardState()
                    if (active && pendingAutoStartAaAfterSettings && !ScreenCastService.isStreamingActive) {
                        pendingAutoStartAaAfterSettings = false
                        appendLog("★ Server Unit Kepala 5277 terdeteksi aktif! Memulai Android Auto otomatis...")
                        startAndroidAutoService()
                    }
                }
            }
        }
    }

    private fun refreshDashboardState() {
        val isStreaming = ScreenCastService.isStreamingActive
        val mode = ScreenCastService.currentMode
        val clusterConnected = ScreenCastService.isClusterVideoConnected
        val netFps = SocketStreamer.currentFps
        val netKbps = SocketStreamer.currentKbps
        val encFps = ScreenCastService.localEncodedFps
        val encKbps = ScreenCastService.localEncodedKbps
        val totalFrames = ScreenCastService.totalEncodedFrames
        val totalIdr = ScreenCastService.totalKeyFrames

        val displayFps = if (clusterConnected && netFps > 0) netFps else encFps
        val displayKbps = if (clusterConnected && netKbps > 0) netKbps else encKbps

        // 1. Update Step 1 (Server AA 5277)
        val aaReady = isAaServerListening || (isStreaming && mode == StreamMode.ANDROID_AUTO && totalFrames > 0)
        binding.dotStepAa.backgroundTintList = ColorStateList.valueOf(
            Color.parseColor(if (aaReady) "#00E676" else "#FFB300")
        )
        binding.tvStepAaStatus.text = if (aaReady) {
            "✔ Server Unit Kepala Aktif (127.0.0.1:5277)"
        } else {
            "Tekan 'Buka AA' -> Titik 3 -> Mulai server unit kepala"
        }
        binding.tvStepAaStatus.setTextColor(
            Color.parseColor(if (aaReady) "#00E676" else "#8B949E")
        )

        // 2. Update Step 2 (Motor BLE + Wi-Fi 192.168.2.1)
        val motorReady = isMotorNetworkReady || wifiP2pProbe.isGroupConnected || clusterConnected
        binding.dotStepMotor.backgroundTintList = ColorStateList.valueOf(
            Color.parseColor(if (motorReady) "#00E676" else "#00E5FF")
        )
        binding.tvStepMotorStatus.text = if (clusterConnected) {
            "✔ Terhubung ke Speedometer Omoway (192.168.2.1:9528)"
        } else if (motorReady) {
            "✔ Jaringan Motor Siap ($extractedSsid • 192.168.2.1)"
        } else {
            "Target: $extractedSsid • Auto-reconnect latar belakang aktif"
        }

        // 3. Update Live Preview Placeholder & HUD Overlay
        if (isStreaming && totalFrames > 0) {
            binding.layoutPreviewPlaceholder.visibility = View.GONE
            binding.tvPreviewHudOverlay.text =
                "LIVE $displayFps FPS • $displayKbps kbps • IDR #$totalIdr"
            binding.tvPreviewHudOverlay.setTextColor(Color.parseColor("#00E676"))
        } else if (isStreaming) {
            binding.layoutPreviewPlaceholder.visibility = View.VISIBLE
            binding.tvPreviewHudOverlay.text = "MENYIAPKAN PIPELINE 60 FPS..."
            binding.tvPreviewHudOverlay.setTextColor(Color.parseColor("#FFB300"))
        } else {
            binding.layoutPreviewPlaceholder.visibility = View.VISIBLE
            binding.tvPreviewHudOverlay.text = "960×360 • 60Hz GOP-6"
            binding.tvPreviewHudOverlay.setTextColor(Color.parseColor("#00E5FF"))
        }

        // 4. Update Step 3 & Top Badges
        if (isStreaming) {
            binding.dotStepStream.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E676"))
            val modeLabel = when (mode) {
                StreamMode.ANDROID_AUTO -> "ANDROID AUTO"
                StreamMode.SCREEN_MIRROR -> "MIRROR LAYAR"
                StreamMode.TEST_PATTERN -> "TEST VIDEO"
                StreamMode.IDLE -> "IDLE"
            }
            binding.tvConnectionBadge.text = "● LIVE: $modeLabel"
            binding.tvConnectionBadge.setTextColor(Color.parseColor("#00E676"))
            binding.tvStreamStatsBadge.text = "960×360 • $displayFps FPS • $displayKbps kbps"
            binding.tvStreamStatsBadge.setTextColor(Color.parseColor("#00E5FF"))

            binding.tvStepStreamStatus.text = if (clusterConnected) {
                "★ Transmisi Cluster Aktif ($netFps FPS • $netKbps kbps ke Port 9528)"
            } else {
                "★ Encoder Lokal Aktif ($encFps FPS • $encKbps kbps) • Menunggu Cluster 192.168.2.1..."
            }
            binding.tvStepStreamStatus.setTextColor(Color.parseColor("#00E676"))

            binding.btnStopCast.visibility = View.VISIBLE
            binding.btnStartAndroidAuto.text = if (mode == StreamMode.ANDROID_AUTO) {
                "🔄  RESTART ANDROID AUTO (60 FPS)"
            } else {
                "🚀  BERALIH KE ANDROID AUTO (60 FPS)"
            }
        } else {
            binding.dotStepStream.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4B5563"))
            binding.tvConnectionBadge.text = if (motorReady) "● P2P SIAP" else "● SIAGA"
            binding.tvConnectionBadge.setTextColor(
                Color.parseColor(if (motorReady) "#00E676" else "#00E5FF")
            )
            binding.tvStreamStatsBadge.text = "960×360 • 60 FPS ZERO-DELAY"
            binding.tvStreamStatsBadge.setTextColor(Color.parseColor("#8B949E"))
            binding.tvStepStreamStatus.text = "Siaga • Tekan Mulai untuk menjalankan pipeline 60 FPS"
            binding.tvStepStreamStatus.setTextColor(Color.parseColor("#8B949E"))

            binding.btnStopCast.visibility = View.GONE
            binding.btnStartAndroidAuto.text = "🚀  MULAI ANDROID AUTO (60 FPS)"
        }
    }

    private fun startAndroidAutoService() {
        val targetIp = binding.etClusterIp.text.toString().ifBlank { AppConfig.defaultClusterIp }
        val ctrlPort = binding.etControlPort.text.toString().toIntOrNull() ?: AppConfig.defaultControlPort
        val videoPort = binding.etVideoPort.text.toString().toIntOrNull() ?: AppConfig.defaultVideoPort

        val serviceIntent = Intent(this, ScreenCastService::class.java).apply {
            action = ScreenCastService.ACTION_START_ANDROID_AUTO
            putExtra(ScreenCastService.EXTRA_TARGET_IP, targetIp)
            putExtra(ScreenCastService.EXTRA_CTRL_PORT, ctrlPort)
            putExtra(ScreenCastService.EXTRA_VIDEO_PORT, videoPort)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun startPatternService() {
        val targetIp = binding.etClusterIp.text.toString().ifBlank { AppConfig.defaultClusterIp }
        val ctrlPort = binding.etControlPort.text.toString().toIntOrNull() ?: AppConfig.defaultControlPort
        val videoPort = binding.etVideoPort.text.toString().toIntOrNull() ?: AppConfig.defaultVideoPort

        val serviceIntent = Intent(this, ScreenCastService::class.java).apply {
            action = ScreenCastService.ACTION_START_PATTERN
            putExtra(ScreenCastService.EXTRA_TARGET_IP, targetIp)
            putExtra(ScreenCastService.EXTRA_CTRL_PORT, ctrlPort)
            putExtra(ScreenCastService.EXTRA_VIDEO_PORT, videoPort)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun requestScreenCapture() {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
    }

    private fun startScreenCastService(resultCode: Int, data: Intent) {
        val targetIp = binding.etClusterIp.text.toString().ifBlank { AppConfig.defaultClusterIp }
        val ctrlPort = binding.etControlPort.text.toString().toIntOrNull() ?: AppConfig.defaultControlPort
        val videoPort = binding.etVideoPort.text.toString().toIntOrNull() ?: AppConfig.defaultVideoPort

        val serviceIntent = Intent(this, ScreenCastService::class.java).apply {
            action = ScreenCastService.ACTION_START_SCREEN
            putExtra(ScreenCastService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCastService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenCastService.EXTRA_TARGET_IP, targetIp)
            putExtra(ScreenCastService.EXTRA_CTRL_PORT, ctrlPort)
            putExtra(ScreenCastService.EXTRA_VIDEO_PORT, videoPort)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopCastService() {
        val stopIntent = Intent(this, ScreenCastService::class.java).apply {
            action = ScreenCastService.ACTION_STOP
        }
        startService(stopIntent)
    }

    private fun checkAndRequestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            startAutoDiscovery()
        }
    }

    private fun appendLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$time] $message\n"
        binding.tvConsoleLog.append(line)
        binding.scrollConsole.post {
            val delta = binding.tvConsoleLog.bottom - (binding.scrollConsole.height + binding.scrollConsole.scrollY)
            if (delta > 0) {
                binding.scrollConsole.scrollBy(0, delta)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isTickerRunning = false
        uiHandler.removeCallbacks(statusTicker)
        probeExecutor.shutdownNow()
        activePreviewSurface?.release()
        activePreviewSurface = null
        bleSniffer.stopScan()
        gattExplorer.disconnect()
        wifiP2pProbe.unregister()
    }
}
