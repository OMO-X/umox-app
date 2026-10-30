package com.umox.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
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
import android.net.Uri
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.SeekBar
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
import com.umox.app.discovery.UmoxPeerDiscovery
import com.umox.app.stream.OmowayViewportConfig
import com.umox.app.stream.ScreenCastService
import com.umox.app.stream.SocketStreamer
import com.umox.app.stream.StreamMode
import com.umox.app.usb.UsbAoaManager
import com.umox.app.usb.UsbAoaTransport
import com.umox.app.wifi.WifiP2pProbe
import com.umox.app.wpp.WppBluetoothServer
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bleSniffer: BleSniffer
    private lateinit var gattExplorer: GattExplorer
    private lateinit var wifiP2pProbe: WifiP2pProbe
    private lateinit var peerDiscovery: UmoxPeerDiscovery
    private lateinit var usbAoaManager: UsbAoaManager
    private lateinit var wppBluetoothServer: WppBluetoothServer

    @Volatile
    private var pendingUsbTransport: UsbAoaTransport? = null

    private var extractedSsid: String = "DIRECT-OMO-*"
    private var extractedPass: String = "12345678"
    private val discoveredBleDevices = mutableMapOf<String, BleDeviceItem>()
    private val discoveredP2pPeers = mutableMapOf<String, android.net.wifi.p2p.WifiP2pDevice>()
    private val discoveredServers = java.util.concurrent.ConcurrentHashMap<String, com.umox.app.discovery.DiscoveredServer>()
    private var hasAutoConnectedBle = false
    private var activePreviewSurface: Surface? = null
    private var pendingAutoStartAaAfterSettings = false

    @Volatile
    private var isAaServerListening: Boolean = false

    @Volatile
    private var isMotorNetworkReady: Boolean = false

    /**
     * Pasangan `[packageName, label]` aplikasi kompetitor yang terinstal di HP
     * (persis `tv.a` di WayXAuto-0.2.5: mendeteksi aplikasi OMOWAY resmi / WayXAuto yang bisa menahan sesi proyeksi motor).
     */
    private var competingAppPair: Pair<String, String>? = null

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
        // Jika HP saat ini sedang memancarkan Mobile Hotspot, otomatis set ke Mode SERVER
        if (isHotspotHost() && AppConfig.appMode == AppConfig.AppMode.STANDALONE) {
            AppConfig.saveAppMode(this, AppConfig.AppMode.SERVER)
        }
        initManagers()
        setupLivePreviewTexture()
        setupViewportAndBoostControls()
        setupClickListeners()
        updateModeUi()
        checkAndRequestPermissions()

        ScreenCastService.onLogCallback = { msg ->
            runOnUiThread { appendLog(msg) }
        }
        var lastStateRefreshMs = 0L
        ScreenCastService.onStateChangedCallback = {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastStateRefreshMs >= 350L) {
                lastStateRefreshMs = now
                runOnUiThread { refreshDashboardState() }
            }
        }

        refreshDashboardState()
        handleCustomAction(intent)
    }

    override fun onResume() {
        super.onResume()
        detectCompetingApp()
        isTickerRunning = true
        uiHandler.post(statusTicker)
        if (binding.textureLivePreview.isAvailable) {
            binding.textureLivePreview.surfaceTexture?.let { st ->
                OmowayViewportConfig.attachPreview(
                    st,
                    binding.textureLivePreview.width,
                    binding.textureLivePreview.height
                )
            }
        }
    }

    private fun detectCompetingApp() {
        val candidates = listOf(
            "com.omoway.app.android" to "OMOWAY",
            "com.omoway.app" to "OMOWAY",
            "com.sdmv.wayxauto" to "WayXAuto",
            "com.sdmv.wayxcast" to "WayXCast",
            "com.codebros.omopoc" to "OMOWAY POC"
        )
        competingAppPair = candidates.firstOrNull { (pkg, _) ->
            try {
                packageManager.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    override fun onPause() {
        super.onPause()
        isTickerRunning = false
        uiHandler.removeCallbacks(statusTicker)
        // Lepas sementara target render preview saat layar mati / masuk background
        // agar eglSwapBuffers pada TextureView tidak pernah menahan encoder 60 FPS!
        OmowayViewportConfig.pausePreview(binding.textureLivePreview.surfaceTexture)
    }

    /**
     * Menangkap input dari Remot Stang Bluetooth (HID Keyboard / Gamepad / Media Button / SilverFox)
     * dan meneruskannya langsung ke kanal Rotary & Key Android Auto (`AaHeadlessReceiver`).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val receiver = ScreenCastService.activeAaReceiver
        if (receiver != null && ScreenCastService.isStreamingActive &&
            ScreenCastService.currentMode == StreamMode.ANDROID_AUTO
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_PAGE_UP,
                    KeyEvent.KEYCODE_BUTTON_L1 -> {
                        receiver.sendRotary(-1)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_PAGE_DOWN,
                    KeyEvent.KEYCODE_BUTTON_R1 -> {
                        receiver.sendRotary(1)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        receiver.sendKey(AaHeadlessReceiver.KEYCODE_DPAD_LEFT)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        receiver.sendKey(AaHeadlessReceiver.KEYCODE_DPAD_RIGHT)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_SPACE,
                    KeyEvent.KEYCODE_BUTTON_A -> {
                        receiver.sendKey(AaHeadlessReceiver.KEYCODE_DPAD_CENTER)
                        return true
                    }
                    KeyEvent.KEYCODE_BUTTON_B,
                    KeyEvent.KEYCODE_ESCAPE -> {
                        receiver.sendKey(AaHeadlessReceiver.KEYCODE_BACK)
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_MEDIA_PAUSE,
                    KeyEvent.KEYCODE_HEADSETHOOK -> {
                        receiver.sendKey(AaHeadlessReceiver.KEYCODE_MEDIA_PLAY_PAUSE)
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        receiver.sendKey(AaHeadlessReceiver.KEYCODE_MEDIA_NEXT)
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        receiver.sendKey(AaHeadlessReceiver.KEYCODE_MEDIA_PREVIOUS)
                        return true
                    }
                    KeyEvent.KEYCODE_VOLUME_UP -> {
                        receiver.sendKey(AaHeadlessReceiver.KEYCODE_VOLUME_UP)
                        return true
                    }
                    KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        receiver.sendKey(AaHeadlessReceiver.KEYCODE_VOLUME_DOWN)
                        return true
                    }
                    KeyEvent.KEYCODE_VOLUME_MUTE -> {
                        receiver.sendKey(AaHeadlessReceiver.KEYCODE_VOLUME_MUTE)
                        return true
                    }
                    KeyEvent.KEYCODE_M,
                    KeyEvent.KEYCODE_BUTTON_X -> {
                        receiver.sendKey(AaHeadlessReceiver.KEYCODE_NAVIGATION)
                        return true
                    }
                    KeyEvent.KEYCODE_N,
                    KeyEvent.KEYCODE_BUTTON_Y -> {
                        receiver.sendKey(AaHeadlessReceiver.KEYCODE_MUSIC)
                        return true
                    }
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_PAGE_UP,
                    KeyEvent.KEYCODE_BUTTON_L1,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_PAGE_DOWN,
                    KeyEvent.KEYCODE_BUTTON_R1,
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_SPACE,
                    KeyEvent.KEYCODE_BUTTON_A,
                    KeyEvent.KEYCODE_BUTTON_B,
                    KeyEvent.KEYCODE_ESCAPE,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_MEDIA_PAUSE,
                    KeyEvent.KEYCODE_HEADSETHOOK,
                    KeyEvent.KEYCODE_MEDIA_NEXT,
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                    KeyEvent.KEYCODE_VOLUME_UP,
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    KeyEvent.KEYCODE_VOLUME_MUTE,
                    KeyEvent.KEYCODE_M,
                    KeyEvent.KEYCODE_BUTTON_X,
                    KeyEvent.KEYCODE_N,
                    KeyEvent.KEYCODE_BUTTON_Y -> return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
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
                val outW = OmowayViewportConfig.outputWidth.toFloat()
                val outH = OmowayViewportConfig.outputHeight.toFloat()
                val leftFrac = OmowayViewportConfig.contentLeft / outW
                val topFrac = (if (OmowayViewportConfig.useClusterSafeMargins) OmowayViewportConfig.marginTop else 0) / outH
                val widthFrac = (OmowayViewportConfig.contentWidth / outW).coerceAtLeast(0.2f)
                val heightFrac = (OmowayViewportConfig.contentHeight / outH).coerceAtLeast(0.2f)

                val rawX = event.x / view.width.toFloat()
                val rawY = event.y / view.height.toFloat()
                val normX = ((rawX - leftFrac) / widthFrac).coerceIn(0f, 1f)
                val normY = ((rawY - topFrac) / heightFrac).coerceIn(0f, 1f)
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

    private var isUpdatingSlidersProgrammatically = false

    private fun setupViewportAndBoostControls() {
        syncViewportAndBoostUiFromConfig()

        // 1. Toggle Margin Aman HUD Aktif / Nonaktif
        binding.btnToggleSafeMargins.setOnClickListener {
            OmowayViewportConfig.useClusterSafeMargins = !OmowayViewportConfig.useClusterSafeMargins
            AppConfig.saveViewportAndBoostSettings(this)
            syncViewportAndBoostUiFromConfig()
            ScreenCastService.requestImmediateKeyFrame()
            appendLog(
                if (OmowayViewportConfig.useClusterSafeMargins) {
                    "📐 Margin Aman HUD diaktifkan (${OmowayViewportConfig.contentWidth}×${OmowayViewportConfig.contentHeight})."
                } else {
                    "📐 Margin HUD dinonaktifkan (Layar Penuh 960×360)."
                }
            )
        }

        // Preset Margin: Standar HUD Omoway (Kanan 120, Atas 45, Bawah 45, Kiri 0)
        binding.btnMarginPresetDefault.setOnClickListener {
            OmowayViewportConfig.useClusterSafeMargins = true
            OmowayViewportConfig.marginRight = 120
            OmowayViewportConfig.marginBottom = 45
            OmowayViewportConfig.marginTop = 45
            OmowayViewportConfig.marginLeft = 0
            AppConfig.saveViewportAndBoostSettings(this)
            syncViewportAndBoostUiFromConfig()
            ScreenCastService.requestImmediateKeyFrame()
            appendLog("📐 Preset Margin Standar HUD Omoway diterapkan (Kanan 120, Atas 45, Bawah 45).")
        }

        // Preset Margin: Ekstra Lega (Kanan 145, Atas 52, Bawah 55, Kiri 18)
        binding.btnMarginPresetExtra.setOnClickListener {
            OmowayViewportConfig.useClusterSafeMargins = true
            OmowayViewportConfig.marginRight = 145
            OmowayViewportConfig.marginBottom = 55
            OmowayViewportConfig.marginTop = 52
            OmowayViewportConfig.marginLeft = 18
            AppConfig.saveViewportAndBoostSettings(this)
            syncViewportAndBoostUiFromConfig()
            ScreenCastService.requestImmediateKeyFrame()
            appendLog("📐 Preset Margin Ekstra Lega diterapkan (Kanan 145, Atas 52, Bawah 55, Kiri 18).")
        }

        // Preset Margin: Layar Penuh 960x360 (Nonaktif)
        binding.btnMarginPresetFull.setOnClickListener {
            OmowayViewportConfig.useClusterSafeMargins = false
            AppConfig.saveViewportAndBoostSettings(this)
            syncViewportAndBoostUiFromConfig()
            ScreenCastService.requestImmediateKeyFrame()
            appendLog("📐 Mode Layar Penuh 960×360 diterapkan (Margin Nonaktif).")
        }

        val marginSeekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingSlidersProgrammatically) return
                OmowayViewportConfig.useClusterSafeMargins = true
                when (seekBar?.id) {
                    binding.seekMarginRight.id -> OmowayViewportConfig.marginRight = progress
                    binding.seekMarginBottom.id -> OmowayViewportConfig.marginBottom = progress
                    binding.seekMarginTop.id -> OmowayViewportConfig.marginTop = progress
                    binding.seekMarginLeft.id -> OmowayViewportConfig.marginLeft = progress
                }
                syncViewportAndBoostUiFromConfig(skipSliders = true)
                ScreenCastService.requestImmediateKeyFrame()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                AppConfig.saveViewportAndBoostSettings(this@MainActivity)
                ScreenCastService.requestImmediateKeyFrame()
            }
        }

        binding.seekMarginRight.setOnSeekBarChangeListener(marginSeekListener)
        binding.seekMarginBottom.setOnSeekBarChangeListener(marginSeekListener)
        binding.seekMarginTop.setOnSeekBarChangeListener(marginSeekListener)
        binding.seekMarginLeft.setOnSeekBarChangeListener(marginSeekListener)

        // 2. Toggle Boost Anti-Redup (Lawan Overlay Gelap Omoway)
        binding.btnToggleColorBoost.setOnClickListener {
            OmowayViewportConfig.colorBoostEnabled = !OmowayViewportConfig.colorBoostEnabled
            AppConfig.saveViewportAndBoostSettings(this)
            syncViewportAndBoostUiFromConfig()
            ScreenCastService.requestImmediateKeyFrame()
            appendLog(
                if (OmowayViewportConfig.colorBoostEnabled) {
                    "☀️ Boost Anti-Redup GPU diaktifkan (melawan overlay gelap Omoway)."
                } else {
                    "○ Boost Anti-Redup GPU dinonaktifkan (warna standar)."
                }
            )
        }

        // Preset Boost: Siang Terang (Rekomendasi)
        binding.btnBoostPresetDay.setOnClickListener {
            OmowayViewportConfig.colorBoostEnabled = true
            OmowayViewportConfig.boostGamma = 0.68f
            OmowayViewportConfig.boostBrightness = 0.14f
            OmowayViewportConfig.boostContrast = 1.32f
            OmowayViewportConfig.boostSaturation = 1.35f
            AppConfig.saveViewportAndBoostSettings(this)
            syncViewportAndBoostUiFromConfig()
            ScreenCastService.activeAaReceiver?.sendNightMode(false)
            ScreenCastService.requestImmediateKeyFrame()
            appendLog("☀️ Preset Siang Terang diterapkan (Gamma 0.68, Brightness +14%, Contrast 132%, Saturation 135% + Peta Siang).")
        }

        // Preset Boost: Matahari Terik Maksimal
        binding.btnBoostPresetMax.setOnClickListener {
            OmowayViewportConfig.colorBoostEnabled = true
            OmowayViewportConfig.boostGamma = 0.55f
            OmowayViewportConfig.boostBrightness = 0.24f
            OmowayViewportConfig.boostContrast = 1.48f
            OmowayViewportConfig.boostSaturation = 1.55f
            AppConfig.saveViewportAndBoostSettings(this)
            syncViewportAndBoostUiFromConfig()
            ScreenCastService.activeAaReceiver?.sendNightMode(false)
            ScreenCastService.requestImmediateKeyFrame()
            appendLog("🔥 Preset Matahari Terik Maksimal diterapkan (Gamma 0.55, Brightness +24%, Contrast 148%, Saturation 155% + Peta Siang).")
        }

        // Preset Boost: Normal (0%)
        binding.btnBoostPresetOff.setOnClickListener {
            OmowayViewportConfig.colorBoostEnabled = false
            AppConfig.saveViewportAndBoostSettings(this)
            syncViewportAndBoostUiFromConfig()
            ScreenCastService.requestImmediateKeyFrame()
            appendLog("○ Preset Warna Standar (0% Boost) diterapkan.")
        }

        val boostSeekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingSlidersProgrammatically) return
                OmowayViewportConfig.colorBoostEnabled = true
                when (seekBar?.id) {
                    binding.seekBoostGamma.id -> {
                        OmowayViewportConfig.boostGamma = (1.0f - (progress / 100.0f)).coerceIn(0.45f, 1.0f)
                    }
                    binding.seekBoostBrightness.id -> {
                        OmowayViewportConfig.boostBrightness = (progress / 100.0f).coerceIn(0.0f, 0.45f)
                    }
                    binding.seekBoostContrast.id -> {
                        OmowayViewportConfig.boostContrast = (1.0f + (progress / 100.0f)).coerceIn(1.0f, 1.90f)
                    }
                    binding.seekBoostSaturation.id -> {
                        OmowayViewportConfig.boostSaturation = (1.0f + (progress / 100.0f)).coerceIn(1.0f, 1.90f)
                    }
                }
                syncViewportAndBoostUiFromConfig(skipSliders = true)
                ScreenCastService.requestImmediateKeyFrame()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                AppConfig.saveViewportAndBoostSettings(this@MainActivity)
                ScreenCastService.requestImmediateKeyFrame()
            }
        }

        binding.seekBoostGamma.setOnSeekBarChangeListener(boostSeekListener)
        binding.seekBoostBrightness.setOnSeekBarChangeListener(boostSeekListener)
        binding.seekBoostContrast.setOnSeekBarChangeListener(boostSeekListener)
        binding.seekBoostSaturation.setOnSeekBarChangeListener(boostSeekListener)
    }

    private fun syncViewportAndBoostUiFromConfig(skipSliders: Boolean = false) {
        val marginOn = OmowayViewportConfig.useClusterSafeMargins
        val mr = OmowayViewportConfig.marginRight
        val mb = OmowayViewportConfig.marginBottom
        val mt = OmowayViewportConfig.marginTop
        val ml = OmowayViewportConfig.marginLeft
        val cw = OmowayViewportConfig.contentWidth
        val ch = OmowayViewportConfig.contentHeight

        binding.btnToggleSafeMargins.text = if (marginOn) "✔ AKTIF" else "○ NONAKTIF"
        binding.btnToggleSafeMargins.setTextColor(
            Color.parseColor(if (marginOn) "#00E676" else "#8B949E")
        )
        binding.tvMarginSummary.text = if (marginOn) {
            "Aktif: Area ${cw}×${ch} (Kanan $mr, Bawah $mb, Atas $mt, Kiri $ml)"
        } else {
            "Nonaktif: Layar Penuh 960×360 (Tanpa Margin HUD)"
        }

        binding.tvMarginRightVal.text = "$mr px"
        binding.tvMarginBottomVal.text = "$mb px"
        binding.tvMarginTopVal.text = "$mt px"
        binding.tvMarginLeftVal.text = "$ml px"

        val boostOn = OmowayViewportConfig.colorBoostEnabled
        val gammaLiftPct = ((1.0f - OmowayViewportConfig.boostGamma) * 100f).toInt().coerceIn(0, 55)
        val brightPct = (OmowayViewportConfig.boostBrightness * 100f).toInt().coerceIn(0, 45)
        val contrastPct = (OmowayViewportConfig.boostContrast * 100f).toInt().coerceIn(100, 190)
        val satPct = (OmowayViewportConfig.boostSaturation * 100f).toInt().coerceIn(100, 190)

        binding.btnToggleColorBoost.text = if (boostOn) "☀️ AKTIF" else "○ STANDAR"
        binding.btnToggleColorBoost.setTextColor(
            Color.parseColor(if (boostOn) "#00E676" else "#8B949E")
        )
        binding.tvBoostSummary.text = if (boostOn) {
            "Aktif: Bayangan +$gammaLiftPct% · Terang +$brightPct% · Kontras $contrastPct% · Warna $satPct%"
        } else {
            "Nonaktif: Warna & Kecerahan Standar (Tanpa Boost)"
        }

        binding.tvBoostGammaVal.text = String.format(Locale.US, "+%d%% (γ %.2f)", gammaLiftPct, OmowayViewportConfig.boostGamma)
        binding.tvBoostBrightnessVal.text = "+$brightPct%"
        binding.tvBoostContrastVal.text = "$contrastPct%"
        binding.tvBoostSaturationVal.text = "$satPct%"

        if (!skipSliders) {
            isUpdatingSlidersProgrammatically = true
            binding.seekMarginRight.progress = mr.coerceIn(0, 240)
            binding.seekMarginBottom.progress = mb.coerceIn(0, 120)
            binding.seekMarginTop.progress = mt.coerceIn(0, 120)
            binding.seekMarginLeft.progress = ml.coerceIn(0, 160)

            binding.seekBoostGamma.progress = gammaLiftPct
            binding.seekBoostBrightness.progress = brightPct
            binding.seekBoostContrast.progress = (contrastPct - 100).coerceIn(0, 90)
            binding.seekBoostSaturation.progress = (satPct - 100).coerceIn(0, 90)
            isUpdatingSlidersProgrammatically = false
        }
    }

    private fun initManagers() {
        wifiP2pProbe = WifiP2pProbe(
            context = this,
            onPeersDiscovered = { peers ->
                runOnUiThread {
                    discoveredP2pPeers.clear()
                    peers.forEach { discoveredP2pPeers[it.deviceAddress] = it }
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
                runOnUiThread {
                    // HANYA sambungkan otomatis jika MAC motor ini persis sama dengan motor yang disimpan pengguna!
                    // Jika belum pernah memilih motor (AppConfig.targetBleMac kosong), JANGAN sambungkan sembarangan!
                    if (AppConfig.targetBleMac.isNotBlank()) {
                        if (item.address.equals(AppConfig.targetBleMac, ignoreCase = true) && !hasAutoConnectedBle) {
                            hasAutoConnectedBle = true
                            appendLog("★ Menyambungkan otomatis ke motor tersimpan: ${item.name} (${item.address})")
                            bleSniffer.stopScan()
                            gattExplorer.connectAndExplore(item.device)
                        }
                    }
                }
            },
            onLog = { msg -> runOnUiThread { appendLog("[BLE] $msg") } }
        )

        peerDiscovery = UmoxPeerDiscovery(
            onLog = { msg -> runOnUiThread { appendLog("[RADAR] $msg") } }
        )

        usbAoaManager = UsbAoaManager(
            context = this,
            onStatusChanged = { msg ->
                runOnUiThread { appendLog(msg) }
            },
            onTransportReady = { transport ->
                runOnUiThread { onUsbTransportConnected(transport) }
            },
            onTransportClosed = {
                runOnUiThread { appendLog("🔌 Sesi USB AOA ditutup.") }
            }
        )
        usbAoaManager.startListening()

        wppBluetoothServer = WppBluetoothServer(
            context = this,
            getHotspotSsid = {
                if (isHotspotHost()) {
                    AppConfig.hotspotSsid
                } else {
                    val clientSsid = getActiveWifiSsid()
                    // Cegah loop refleksi jika HP Penerima terhubung ke Hotspot HP Utama yang dipilih
                    val primaryName = AppConfig.primaryPhoneBtName.trim()
                    if (primaryName.isNotBlank() && clientSsid.contains(primaryName, ignoreCase = true)) {
                        runOnUiThread {
                            appendLog("⚠ Peringatan: HP Penerima terhubung ke Hotspot HP Utama ($clientSsid). Di Android Auto Wireless, HP Penerima yang harus menjadi Host! Mengalihkan ke SSID: ${AppConfig.hotspotSsid}")
                        }
                        AppConfig.hotspotSsid
                    } else {
                        clientSsid
                    }
                }
            },
            getHotspotPassword = { AppConfig.hotspotPass },
            getHotspotBssid = {
                if (isHotspotHost()) "" else getActiveWifiBssid()
            },
            getTargetIp = {
                val ip = getWifiIpAddress()
                if (ip != null) ip else if (isHotspotHost()) "192.168.43.1" else "127.0.0.1"
            },
            onPhoneConnected = { phoneName ->
                runOnUiThread {
                    appendLog("★ Ponsel $phoneName terhubung via Bluetooth RFCOMM! Mempersiapkan Android Auto...")
                    if (!ScreenCastService.isStreamingActive) {
                        startAndroidAutoService()
                    }
                }
            },
            onStatusChanged = { msg ->
                runOnUiThread { appendLog(msg) }
            },
            onPhoneHandshakeSuccess = { phoneName ->
                runOnUiThread {
                    appendLog("★ Sesi Nirkabel WPP berhasil dinegosiasikan dengan $phoneName! Memastikan Android Auto aktif...")
                    if (!ScreenCastService.isStreamingActive) {
                        startAndroidAutoService()
                    }
                }
            }
        )
        wppBluetoothServer.start()
    }

    private fun onUsbTransportConnected(transport: UsbAoaTransport) {
        val activeReceiver = ScreenCastService.activeAaReceiver
        if (activeReceiver != null && ScreenCastService.isStreamingActive) {
            appendLog("★ Menghubungkan aliran data USB AOA ke Pipeline Android Auto yang sedang aktif...")
            activeReceiver.attachTransport(transport)
        } else {
            appendLog("★ Memulai Pipeline Android Auto otomatis via USB AOA 2.0...")
            pendingUsbTransport = transport
            startAndroidAutoService()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCustomAction(intent)
    }

    private fun handleCustomAction(intent: Intent?) {
        if (intent == null) return
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED || intent.action == "com.umox.app.ACTION_SCAN_USB") {
            appendLog("🔌 Intent USB Device Attached / Scan diterima!")
            if (!ScreenCastService.isStreamingActive) {
                appendLog("🔌 Mempersiapkan Receiver Android Auto...")
                startAndroidAutoService()
            }
            usbAoaManager.scanAndConnect()
        } else if (intent.action == "com.umox.app.ACTION_WAKE_PHONE") {
            val mac = intent.getStringExtra("mac")?.ifBlank { null } ?: AppConfig.primaryPhoneBtMac
            if (mac.isNotBlank()) {
                if (!ScreenCastService.isStreamingActive) {
                    appendLog("🔌 Mempersiapkan Receiver Android Auto...")
                    startAndroidAutoService()
                }
                appendLog("⚡ Memicu Wake Phone via Intent ke $mac...")
                wppBluetoothServer.wakePhone(mac)
            }
        }
    }

    private fun isHotspotHost(): Boolean {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp) continue
                val name = iface.name.lowercase()
                val isTether = name.contains("swlan") || name.contains("ap") || name.contains("tether") || name.contains("rndis") || name.contains("bridge")
                if (!isTether) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return true
                    }
                }
            }
        } catch (_: Exception) {}
        return false
    }

    private fun startAutoDiscovery() {
        if (AppConfig.appMode == AppConfig.AppMode.SERVER || isHotspotHost()) {
            appendLog("📡 Hotspot Host terdeteksi (Mode Server): Wi-Fi Direct dinonaktifkan agar Hotspot tetap aktif.")
            return
        }
        try {
            wifiP2pProbe.registerAndListen()
            bleSniffer.startScan()
        } catch (e: Exception) {
            appendLog("⚠ Auto-discovery info: ${e.message}")
        }
    }

    private fun getWifiGatewayIp(): String? {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val dhcp = wm?.dhcpInfo
            val gateway = dhcp?.gateway ?: 0
            if (gateway != 0) {
                return String.format(
                    Locale.US,
                    "%d.%d.%d.%d",
                    gateway and 0xFF,
                    gateway shr 8 and 0xFF,
                    gateway shr 16 and 0xFF,
                    gateway shr 24 and 0xFF
                )
            }
        } catch (_: Exception) {}

        // Fallback: baca dari tabel ARP kernel untuk menemukan IP gateway Hotspot secara dinamis
        try {
            val arpLines = java.io.File("/proc/net/arp").readLines()
            for (line in arpLines.drop(1)) {
                val tokens = line.split("\\s+".toRegex())
                if (tokens.size >= 4 && tokens[0].isNotBlank()) {
                    val ip = tokens[0]
                    if (ip != "0.0.0.0" && !ip.startsWith("127.")) {
                        return ip
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun getWifiIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            var tetherIp: String? = null
            var wifiIp: String? = null
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp) continue
                val name = iface.name.lowercase()
                val isTether = name.contains("swlan") || name.contains("ap") || name.contains("tether") || name.contains("rndis") || name.contains("wlan1") || name.contains("bridge")
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (isTether) {
                            tetherIp = host
                            break
                        } else if (wifiIp == null) {
                            wifiIp = host
                        }
                    }
                }
                if (tetherIp != null) return tetherIp
            }
            if (wifiIp != null) return wifiIp

            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ip = wm?.connectionInfo?.ipAddress ?: 0
            if (ip != 0) {
                return String.format(
                    Locale.US,
                    "%d.%d.%d.%d",
                    ip and 0xFF,
                    ip shr 8 and 0xFF,
                    ip shr 16 and 0xFF,
                    ip shr 24 and 0xFF
                )
            }
        } catch (_: Exception) {}
        return null
    }

    private fun getActiveWifiSsid(): String {
        if (isHotspotHost()) {
            return AppConfig.hotspotSsid
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val rawSsid = wm?.connectionInfo?.ssid?.replace("\"", "")?.trim()
            if (!rawSsid.isNullOrBlank() && rawSsid != "<unknown ssid>" && rawSsid != "0x") {
                return rawSsid
            }
        } catch (_: Exception) {}
        return AppConfig.hotspotSsid
    }

    private fun getActiveWifiBssid(): String {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val bssid = wm?.connectionInfo?.bssid?.trim() ?: ""
            if (bssid.isNotBlank() && bssid != "00:00:00:00:00:00") {
                return bssid
            }
        } catch (_: Exception) {}
        return ""
    }

    private fun updateModeUi() {
        when (AppConfig.appMode) {
            AppConfig.AppMode.STANDALONE -> {
                peerDiscovery.stopAll()
                if (!isHotspotHost()) {
                    startAutoDiscovery()
                }
                binding.btnAppMode.text = "MODE: STANDALONE ▼"
                binding.btnAppMode.setTextColor(Color.parseColor("#FFB74D"))
            }
            AppConfig.AppMode.SERVER -> {
                peerDiscovery.stopListening()
                try {
                    wifiP2pProbe.unregister()
                    bleSniffer.stopScan()
                } catch (_: Exception) {}
                val myIp = getWifiIpAddress() ?: "127.0.0.1"
                val devName = Build.MODEL ?: "HP Server"
                peerDiscovery.startBroadcasting(devName, myIp, AaHeadlessReceiver.AA_PORT_LOCAL) {
                    isAaServerListening
                }
                binding.btnAppMode.text = "MODE: SERVER ($myIp) ▼"
                binding.btnAppMode.setTextColor(Color.parseColor("#4CAF50"))
            }
            AppConfig.AppMode.CLIENT -> {
                peerDiscovery.stopBroadcasting()
                try {
                    startAutoDiscovery()
                } catch (_: Exception) {}
                if (!peerDiscovery.isListening) {
                    peerDiscovery.startListening { serverName, serverIp, serverPort, isAaActive ->
                        discoveredServers[serverIp] = com.umox.app.discovery.DiscoveredServer(serverName, serverIp, serverPort, isAaActive)

                        if (AppConfig.serverHostIp.isBlank() || AppConfig.serverHostIp == "127.0.0.1") {
                            AppConfig.saveAppMode(this, AppConfig.AppMode.CLIENT, serverIp, serverName)
                            appendLog("📡 RADAR: Terhubung otomatis ke $serverName ($serverIp:$serverPort)")
                            Toast.makeText(this, "✔ Terhubung otomatis ke $serverName!", Toast.LENGTH_SHORT).show()
                            val displayLabel = "$serverName ($serverIp)"
                            binding.btnAppMode.text = "MODE: CLIENT ($displayLabel) ▼"
                            isAaServerListening = isAaActive
                            refreshDashboardState()
                        } else if (AppConfig.serverHostIp == serverIp) {
                            if (isAaServerListening != isAaActive) {
                                isAaServerListening = isAaActive
                                refreshDashboardState()
                            }
                        }
                    }
                }
                val currentHost = AppConfig.serverHostIp
                val hostLabel = if (AppConfig.serverHostName.isNotBlank()) "${AppConfig.serverHostName} ($currentHost)" else currentHost.ifBlank { "Auto Radar" }
                binding.btnAppMode.text = "MODE: CLIENT ($hostLabel) ▼"
                binding.btnAppMode.setTextColor(Color.parseColor("#00E5FF"))
            }
        }
        refreshDashboardState()
    }

    private fun showModeSelectionDialog() {
        val modes = arrayOf(
            "📱 Standalone (1 HP Langsung ke Motor)",
            "🚀 Server (HP Utama - Host Android Auto & Hotspot)",
            "📡 Client (HP Layar / Donor - Penerima Proyeksi)"
        )
        val currentIndex = when (AppConfig.appMode) {
            AppConfig.AppMode.STANDALONE -> 0
            AppConfig.AppMode.SERVER -> 1
            AppConfig.AppMode.CLIENT -> 2
        }

        AlertDialog.Builder(this)
            .setTitle("Pilih Mode Operasional UMOX")
            .setSingleChoiceItems(modes, currentIndex) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> {
                        AppConfig.saveAppMode(this, AppConfig.AppMode.STANDALONE, "127.0.0.1", "Lokal")
                        appendLog("Mode diubah ke: STANDALONE (127.0.0.1)")
                        updateModeUi()
                    }
                    1 -> {
                        AppConfig.saveAppMode(this, AppConfig.AppMode.SERVER)
                        appendLog("Mode diubah ke: SERVER (Host Android Auto & Radar)")
                        updateModeUi()
                    }
                    2 -> {
                        showServerSelectionDialog()
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showServerSelectionDialog() {
        val servers = discoveredServers.values.toList()
        val items = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        val currentHost = AppConfig.serverHostIp

        // 1. Tampilkan server-server yang terdeteksi via Radar UDP
        if (servers.isNotEmpty()) {
            items.add("--- SERVER TERDETEKSI DI JARINGAN ---")
            actions.add {}
            for (s in servers) {
                val isSelected = s.ip == currentHost
                val badge = if (isSelected) " [✔ Aktif]" else ""
                val aaBadge = if (s.isAaActive) " • AA Siap ✓" else " • Menunggu AA"
                items.add("📡 ${s.name} (${s.ip}:${s.port})$aaBadge$badge")
                actions.add {
                    AppConfig.saveAppMode(this, AppConfig.AppMode.CLIENT, s.ip, s.name)
                    appendLog("✔ Server dipilih: ${s.name} (${s.ip}:${s.port})")
                    Toast.makeText(this, "✔ Terhubung ke ${s.name}!", Toast.LENGTH_SHORT).show()
                    isAaServerListening = s.isAaActive
                    updateModeUi()
                }
            }
        }

        // 2. Gateway Hotspot Otomatis jika terdeteksi
        val detectedGateway = getWifiGatewayIp()
        if (!detectedGateway.isNullOrBlank() && servers.none { it.ip == detectedGateway }) {
            items.add("🌐 Host Gateway Hotspot: $detectedGateway:5277")
            actions.add {
                AppConfig.saveAppMode(this, AppConfig.AppMode.CLIENT, detectedGateway, "Hotspot Host")
                appendLog("✔ Target Server diatur ke Gateway Hotspot: $detectedGateway:5277")
                Toast.makeText(this, "✔ Terhubung ke Gateway $detectedGateway!", Toast.LENGTH_SHORT).show()
                updateModeUi()
            }
        }

        // 3. Opsi Input Manual
        items.add("✏️ Masukkan Alamat IP Manual...")
        actions.add {
            showManualIpInputDialog()
        }

        AlertDialog.Builder(this)
            .setTitle("Pilih Server Android Auto (HP Utama)")
            .setItems(items.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                if (which in actions.indices) {
                    actions[which].invoke()
                }
            }
            .setPositiveButton("Pindai Ulang Radar") { _, _ ->
                discoveredServers.clear()
                peerDiscovery.stopListening()
                updateModeUi()
                showServerSelectionDialog()
            }
            .setNeutralButton("📶 Hotspot") { _, _ ->
                showHotspotConfigDialog()
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun showManualIpInputDialog() {
        val autoGateway = getWifiGatewayIp() ?: ""
        val currentIp = if (AppConfig.serverHostIp.isNotBlank() && AppConfig.serverHostIp != "127.0.0.1") {
            AppConfig.serverHostIp
        } else {
            autoGateway
        }
        val input = EditText(this).apply {
            setText(currentIp)
            hint = "Contoh: 192.168.43.1"
        }
        AlertDialog.Builder(this)
            .setTitle("Alamat IP Server Android Auto")
            .setMessage("Masukkan alamat IP HP Utama yang menjalankan Server Android Auto:")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotBlank()) {
                    AppConfig.saveAppMode(this, AppConfig.AppMode.CLIENT, ip, "Manual")
                    appendLog("Mode diubah ke: CLIENT (Target Server: $ip:5277)")
                    updateModeUi()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showHotspotConfigDialog() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
        }

        val tvInfo = android.widget.TextView(this).apply {
            text = "Kredensial Wi-Fi Hotspot HP Layar (Receiver):\nHP Utama akan menyambung ke jaringan ini secara nirkabel untuk Android Auto."
            setTextColor(Color.parseColor("#8B949E"))
            textSize = 12f
            setPadding(0, 0, 0, 20)
        }
        layout.addView(tvInfo)

        val etSsid = EditText(this).apply {
            hint = "Nama Hotspot (SSID)"
            setText(AppConfig.hotspotSsid)
        }
        layout.addView(etSsid)

        val etPass = EditText(this).apply {
            hint = "Sandi Hotspot (Minimal 8 karakter)"
            setText(AppConfig.hotspotPass)
        }
        layout.addView(etPass)

        AlertDialog.Builder(this)
            .setTitle("📶 Pengaturan Hotspot Android Auto")
            .setView(layout)
            .setPositiveButton("Simpan") { _, _ ->
                val newSsid = etSsid.text.toString().trim()
                val newPass = etPass.text.toString().trim()
                if (newSsid.isNotBlank() && newPass.length >= 8) {
                    AppConfig.saveHotspotCredentials(this, newSsid, newPass)
                    Toast.makeText(this, "✔ Kredensial Hotspot disimpan ($newSsid)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "⚠ Sandi minimal harus 8 karakter!", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("⚙ Buka Hotspot HP") { _, _ ->
                try {
                    val intent = Intent("android.settings.TETHER_SETTINGS")
                    startActivity(intent)
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                    } catch (_: Exception) {}
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showAaSettingsMenuDialog() {
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        options.add("📶 Konfigurasi Hotspot Android Auto (SSID / Sandi)")
        actions.add { showHotspotConfigDialog() }

        if (AppConfig.appMode == AppConfig.AppMode.CLIENT) {
            options.add("🔍 Cari & Pilih Server Proyeksi (Client)")
            actions.add { showServerSelectionDialog() }
        } else {
            options.add("⚙ Buka Pengaturan Android Auto (Developer HU Server)")
            actions.add {
                pendingAutoStartAaAfterSettings = true
                openAndroidAutoHeadUnitServer()
            }
        }

        options.add("🔄 Ganti Mode Operasi (Standalone / Server / Client)")
        actions.add { showModeSelectionDialog() }

        AlertDialog.Builder(this)
            .setTitle("⚙ Pengaturan Android Auto & Jaringan")
            .setItems(options.toTypedArray()) { _, which ->
                actions[which].invoke()
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun showPrimaryPhoneSelectionDialog() {
        val btAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            Toast.makeText(this, "Silakan aktifkan Bluetooth terlebih dahulu", Toast.LENGTH_SHORT).show()
            return
        }

        val bonded = btAdapter.bondedDevices.toList()
        if (bonded.isEmpty()) {
            Toast.makeText(
                this,
                "Belum ada HP yang dipasangkan (paired) via Bluetooth.\nSilakan pasangkan HP Utama dengan HP ini di Pengaturan Bluetooth HP.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val items = bonded.map { dev ->
            val name = dev.name ?: "Ponsel Tanpa Nama"
            val mac = dev.address
            val isCurrent = mac.equals(AppConfig.primaryPhoneBtMac, ignoreCase = true)
            val badge = if (isCurrent) " [DISIMPAN]" else ""
            "$name\n$mac$badge"
        }.toTypedArray()

        var selectedIndex = bonded.indexOfFirst {
            it.address.equals(AppConfig.primaryPhoneBtMac, ignoreCase = true)
        }.coerceAtLeast(0)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Pilih HP Utama (Bangunkan Android Auto)")
            .setSingleChoiceItems(items, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton("⚡ Bangunkan (Poke)") { _, _ ->
                val chosen = bonded[selectedIndex]
                val name = chosen.name ?: chosen.address
                AppConfig.savePrimaryPhoneBt(this, chosen.address, name)
                if (!ScreenCastService.isStreamingActive) {
                    appendLog("🔌 Mempersiapkan Receiver Android Auto...")
                    startAndroidAutoService()
                }
                appendLog("⚡ Mengirim sinyal bangun (HFP Poke) ke HP Utama: $name (${chosen.address})...")
                wppBluetoothServer.wakePhone(chosen.address) { success, msg ->
                    runOnUiThread {
                        if (success) {
                            Toast.makeText(this@MainActivity, "✔ Sinyal bangun terkirim ke $name!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "⚠ Gagal wake: $msg", Toast.LENGTH_LONG).show()
                        }
                        refreshDashboardState()
                    }
                }
            }
            .setNeutralButton("📶 Hotspot") { _, _ ->
                showHotspotConfigDialog()
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun showMotorSelectionDialog() {
        if (AppConfig.appMode == AppConfig.AppMode.SERVER) {
            Toast.makeText(
                this,
                "Mode Server aktif: Hubungkan motor dari HP Penerima / Layar!",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Jalankan pemindaian BLE dan Wi-Fi Direct
        hasAutoConnectedBle = false
        bleSniffer.startScan()
        wifiP2pProbe.registerAndListen()
        wifiP2pProbe.discoverPeers()

        val items = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        // 1. Tampilkan opsi motor tersimpan (jika ada)
        if (AppConfig.targetBleMac.isNotBlank() || AppConfig.targetP2pMac.isNotBlank()) {
            val savedName = AppConfig.targetBleName.ifBlank { AppConfig.targetP2pName }.ifBlank { "Motor Tersimpan" }
            val savedMac = AppConfig.targetBleMac.ifBlank { AppConfig.targetP2pMac }
            items.add("⭐ Hubungkan ke: $savedName ($savedMac)")
            actions.add {
                appendLog("⚡ Menghubungkan ke motor tersimpan: $savedName ($savedMac)...")
                if (AppConfig.targetBleMac.isNotBlank()) {
                    val dev = discoveredBleDevices[AppConfig.targetBleMac]?.device
                    if (dev != null) {
                        hasAutoConnectedBle = true
                        bleSniffer.stopScan()
                        gattExplorer.connectAndExplore(dev)
                    } else {
                        bleSniffer.startScan()
                        Toast.makeText(this, "Mencari sinyal $savedName di sekitar...", Toast.LENGTH_SHORT).show()
                    }
                }
                if (AppConfig.targetP2pMac.isNotBlank()) {
                    wifiP2pProbe.connectToMotor(AppConfig.targetP2pMac)
                }
            }
        }

        // 2. Daftar Perangkat BLE yang terdeteksi
        val bleList = discoveredBleDevices.values.sortedByDescending { it.rssi }
        if (bleList.isNotEmpty()) {
            items.add("--- DAFTAR MOTOR BLE TERDETEKSI ---")
            actions.add {}
            for (ble in bleList) {
                val isCandidate = ble.isCandidate
                val isCurrent = ble.address.equals(AppConfig.targetBleMac, ignoreCase = true)
                val badge = if (isCurrent) " [✔ Tersimpan]" else if (isCandidate) " [★ Cocok]" else ""
                items.add("📡 ${ble.name} (${ble.address}) ${ble.rssi} dBm$badge")
                actions.add {
                    AppConfig.saveBleMac(this, ble.address, ble.name)
                    hasAutoConnectedBle = true
                    appendLog("✔ Motor dipilih: ${ble.name} (${ble.address}). Tersimpan sebagai motor utama.")
                    Toast.makeText(this, "✔ Terhubung ke ${ble.name}!", Toast.LENGTH_SHORT).show()
                    bleSniffer.stopScan()
                    gattExplorer.connectAndExplore(ble.device)
                    refreshDashboardState()
                }
            }
        }

        // 3. Daftar Perangkat Wi-Fi Direct yang terdeteksi
        val p2pList = discoveredP2pPeers.values.toList()
        if (p2pList.isNotEmpty()) {
            items.add("--- DAFTAR WI-FI DIRECT TERDETEKSI ---")
            actions.add {}
            for (p2p in p2pList) {
                val isCurrent = p2p.deviceAddress.equals(AppConfig.targetP2pMac, ignoreCase = true)
                val badge = if (isCurrent) " [✔ Tersimpan]" else ""
                items.add("📶 ${p2p.deviceName} (${p2p.deviceAddress})$badge")
                actions.add {
                    AppConfig.saveP2pMac(this, p2p.deviceAddress, p2p.deviceName)
                    appendLog("✔ Wi-Fi Direct dipilih: ${p2p.deviceName} (${p2p.deviceAddress}).")
                    Toast.makeText(this, "✔ Menghubungkan ke ${p2p.deviceName}!", Toast.LENGTH_SHORT).show()
                    wifiP2pProbe.connectToMotor(p2p.deviceAddress)
                    refreshDashboardState()
                }
            }
        }

        if (bleList.isEmpty() && p2pList.isEmpty()) {
            items.add("⏳ Sedang memindai perangkat di sekitar... Ketuk 'Pindai Ulang' jika motor belum muncul.")
            actions.add {
                bleSniffer.startScan()
                wifiP2pProbe.discoverPeers()
            }
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("Pilih Motor / Speedometer")
            .setItems(items.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                if (which in actions.indices) {
                    actions[which].invoke()
                }
            }
            .setPositiveButton("Pindai Ulang") { _, _ ->
                discoveredBleDevices.clear()
                discoveredP2pPeers.clear()
                bleSniffer.startScan()
                wifiP2pProbe.discoverPeers()
                showMotorSelectionDialog()
            }
            .setNegativeButton("Tutup", null)

        if (AppConfig.targetBleMac.isNotBlank() || AppConfig.targetP2pMac.isNotBlank()) {
            builder.setNeutralButton("Lupakan Motor") { _, _ ->
                AppConfig.resetMotorPairing(this)
                hasAutoConnectedBle = false
                appendLog("✔ Pasangan motor direset. Silakan pilih motor baru.")
                Toast.makeText(this, "Pasangan motor dihapus", Toast.LENGTH_SHORT).show()
                refreshDashboardState()
            }
        }

        builder.show()
    }

    private fun setupClickListeners() {
        // Tombol Pemilihan Mode (STANDALONE, SERVER, CLIENT)
        binding.btnAppMode.setOnClickListener {
            showModeSelectionDialog()
        }

        // Info Kunci Publik Built-in (Google-Android-Reference RSA-2048 + X.509)
        binding.tvAaBadge.setOnClickListener {
            val msg = "🔑 Kunci Head Unit Publik (Google-Android-Reference RSA-2048 + X.509, berlaku s/d 2046) sudah tertanam 100% Built-in di UMOX. Tidak perlu unduh file dari GitHub!"
            appendLog(msg)
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }

        // Langkah 1: Buka Pengaturan Server Unit Kepala Android Auto / Pilih Server Client
        binding.btnTriggerWirelessAa.setOnClickListener {
            if (!ScreenCastService.isStreamingActive) {
                appendLog("🔌 Mempersiapkan Receiver Android Auto sebelum membangunkan ponsel...")
                startAndroidAutoService()
            }
            showPrimaryPhoneSelectionDialog()
        }

        binding.btnScanUsbAoa.setOnClickListener {
            if (!ScreenCastService.isStreamingActive) {
                appendLog("🔌 Mempersiapkan Receiver Android Auto...")
                startAndroidAutoService()
            }
            appendLog("🔌 Memindai kabel USB Type-C / OTG untuk aktivasi otomatis AOA 2.0...")
            usbAoaManager.scanAndConnect()
        }

        binding.btnOpenAaSettings.setOnClickListener {
            if (AppConfig.appMode == AppConfig.AppMode.CLIENT) {
                showServerSelectionDialog()
            } else {
                pendingAutoStartAaAfterSettings = true
                openAndroidAutoHeadUnitServer()
            }
        }

        // Langkah 2: Pilih & Hubungkan Motor (BLE & Wi-Fi Direct)
        binding.btnAutoLinkMotor.setOnClickListener {
            showMotorSelectionDialog()
        }

        // Tombol Resolusi Konflik OMOWAY / Reopen Navigasi Motor (WayXAuto 0.2.5)
        binding.btnResolveConflict.setOnClickListener {
            resolveCompetingAppConflict()
        }

        // Primary CTA: Mulai / Restart Android Auto 60 FPS
        binding.btnStartAndroidAuto.setOnClickListener {
            if (AppConfig.appMode == AppConfig.AppMode.SERVER) {
                Toast.makeText(
                    this,
                    "Mode Server aktif: Buka 'Titik Tiga -> Mulai server unit kepala' di AA. Proyeksi dijalankan dari HP Layar!",
                    Toast.LENGTH_LONG
                ).show()
                openAndroidAutoHeadUnitServer()
                return@setOnClickListener
            }
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
                binding.btnRemoteNightMode.text = if (nextNight) "🌙 Malam" else "☀️ Siang"
                ScreenCastService.requestImmediateKeyFrame()
                appendLog("🌓 Tema Peta Android Auto diubah ke: ${if (nextNight) "MALAM (Gelap)" else "SIANG (Terang)"}")
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
            showMotorSelectionDialog()
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
            } else if (AppConfig.appMode == AppConfig.AppMode.CLIENT) {
                AppConfig.serverHostIp.isNotBlank() && AppConfig.serverHostIp != "127.0.0.1"
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
            val p2pActive = if (AppConfig.appMode == AppConfig.AppMode.SERVER) false else wifiP2pProbe.isGroupConnected
            if (isAaServerListening != active || isMotorNetworkReady != p2pActive) {
                isAaServerListening = active
                isMotorNetworkReady = p2pActive
                uiHandler.post {
                    refreshDashboardState()
                    if (active && pendingAutoStartAaAfterSettings && !ScreenCastService.isStreamingActive) {
                        pendingAutoStartAaAfterSettings = false
                        if (AppConfig.appMode != AppConfig.AppMode.SERVER) {
                            appendLog("★ Server Unit Kepala 5277 terdeteksi aktif! Memulai Android Auto otomatis...")
                            startAndroidAutoService()
                        } else {
                            appendLog("★ Server Unit Kepala 5277 aktif! Radar memancarkan status SIAP ke HP Donor.")
                        }
                    }
                }
            }
        }
    }

    private fun resolveCompetingAppConflict() {
        if (!ScreenCastService.isHeadUnitBusy && ScreenCastService.needsBikeNavReopenHint) {
            ScreenCastService.dismissBikeNavReopenHint()
            return
        }

        val competing = competingAppPair
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            listOf(
                "com.omoway.app.android",
                "com.omoway.app",
                "com.sdmv.wayxauto",
                "com.sdmv.wayxcast",
                "com.codebros.omopoc"
            ).forEach { pkg ->
                try {
                    am?.killBackgroundProcesses(pkg)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        if (competing != null) {
            try {
                appendLog("🔧 Membuka Info Aplikasi ${competing.second} (${competing.first}) -> Ketuk 'Paksa Berhenti' (Force Stop).")
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", competing.first, null)
                    )
                )
                return
            } catch (_: Exception) {}
        }

        Toast.makeText(
            this,
            "Buka Pengaturan HP -> Aplikasi -> Paksa Berhenti (Force Stop) aplikasi OMOWAY",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun refreshDashboardState() {
        val isStreaming = ScreenCastService.isStreamingActive
        val mode = ScreenCastService.currentMode
        val clusterConnected = ScreenCastService.isClusterVideoConnected
        val bikeProjecting = ScreenCastService.isBikeProjecting
        val headUnitBusy = ScreenCastService.isHeadUnitBusy
        val needsReopenHint = ScreenCastService.needsBikeNavReopenHint
        val netFps = SocketStreamer.currentFps
        val netKbps = SocketStreamer.currentKbps
        val encFps = ScreenCastService.localEncodedFps
        val encKbps = ScreenCastService.localEncodedKbps
        val totalFrames = ScreenCastService.totalEncodedFrames
        val totalIdr = ScreenCastService.totalKeyFrames
        val totalSentFrames = SocketStreamer.totalFramesSentGlobal

        val displayFps = if (bikeProjecting && netFps > 0) netFps else encFps
        val displayKbps = if (bikeProjecting && netKbps > 0) netKbps else encKbps

        // 1. Update Step 1 (Server AA 5277 - Auto-Green begitu aktif!)
        val isServer = AppConfig.appMode == AppConfig.AppMode.SERVER
        val isClient = AppConfig.appMode == AppConfig.AppMode.CLIENT
        val isUsbAoaActive = ScreenCastService.activeAaReceiver?.currentTransport is UsbAoaTransport
        val isWirelessActive = ScreenCastService.activeAaReceiver?.currentTransport is com.umox.app.aa.AaSocketTransport

        binding.tvUsbAoaBadge.text = if (isUsbAoaActive) "🔌 USB AOA: PROYEKSI AKTIF" else "🔌 USB AOA: SIAGA"
        binding.tvUsbAoaBadge.setTextColor(Color.parseColor(if (isUsbAoaActive) "#00E676" else "#00E5FF"))

        val phoneLabel = if (AppConfig.primaryPhoneBtName.isNotBlank()) AppConfig.primaryPhoneBtName else "SIAGA"
        binding.tvWppBadge.text = if (isWirelessActive) "📡 WIRELESS: AKTIF" else "📡 BT WPP: $phoneLabel"
        binding.tvWppBadge.setTextColor(Color.parseColor(if (isWirelessActive) "#00E676" else "#00E5FF"))

        val aaReady = if (isServer) {
            true
        } else if (isUsbAoaActive) {
            true
        } else if (isClient) {
            (isStreaming && mode == StreamMode.ANDROID_AUTO) || isAaServerListening
        } else {
            isAaServerListening || (isStreaming && mode == StreamMode.ANDROID_AUTO && totalFrames > 0)
        }
        binding.dotStepAa.backgroundTintList = ColorStateList.valueOf(
            Color.parseColor(if (aaReady) "#00E676" else if (isClient) "#00E5FF" else "#FFB300")
        )
        val myIp = getWifiIpAddress() ?: "127.0.0.1"
        binding.tvStepAaStatus.text = if (isServer) {
            "🚀 MODE SERVER: Memancarkan radar ke HP Layar ($myIp:5277)"
        } else if (isUsbAoaActive) {
            "✔ Android Auto Aktif via Kabel USB AOA (60 FPS)"
        } else if (isClient) {
            val sLabel = if (AppConfig.serverHostName.isNotBlank()) AppConfig.serverHostName else AppConfig.serverHostIp
            if (isStreaming && mode == StreamMode.ANDROID_AUTO && ScreenCastService.activeAaReceiver?.isVideoStreaming == true) {
                "✔ Terhubung ke Server ($sLabel:5277) · Video Aktif"
            } else {
                "📡 Radar Client: Terhubung ke $sLabel:5277 (Tekan Mulai)"
            }
        } else if (aaReady) {
            val transportName = ScreenCastService.activeAaReceiver?.currentTransport?.name ?: "127.0.0.1:5277"
            "✔ Android Auto Terhubung & Siap ($transportName)"
        } else {
            "Otomatis Siap: Tancapkan USB Type-C atau ketuk 'Bangunkan BT'"
        }
        binding.tvStepAaStatus.setTextColor(
            Color.parseColor(if (aaReady) "#00E676" else "#8B949E")
        )
        binding.btnOpenAaSettings.text = "⚙"

        // 2. Update Step 2 (Motor BLE + Wi-Fi 192.168.2.1)
        val motorReady = isMotorNetworkReady || wifiP2pProbe.isGroupConnected || clusterConnected
        binding.dotStepMotor.backgroundTintList = ColorStateList.valueOf(
            Color.parseColor(if (motorReady) "#00E676" else "#00E5FF")
        )
        val pairedMotorName = AppConfig.targetBleName.ifBlank { AppConfig.targetP2pName }
        val pairedMotorMac = AppConfig.targetBleMac.ifBlank { AppConfig.targetP2pMac }
        binding.tvStepMotorStatus.text = if (bikeProjecting) {
            "✔ Motor Menerima Gambar (192.168.2.1:9528 · $totalSentFrames frame)"
        } else if (clusterConnected) {
            "✔ Terhubung ke TCP Motor (Menunggu konfirmasi layar Navigasi motor...)"
        } else if (motorReady) {
            "✔ Jaringan Motor Siap ($extractedSsid • 192.168.2.1)"
        } else if (pairedMotorMac.isNotBlank()) {
            val label = if (pairedMotorName.isNotBlank()) "$pairedMotorName ($pairedMotorMac)" else pairedMotorMac
            "Motor Dipilih: $label · Menghubungkan..."
        } else {
            "Belum ada motor dipilih · Ketuk 'PILIH MOTOR' untuk memindai"
        }
        binding.btnAutoLinkMotor.text = "PILIH MOTOR"

        // 3. Update Conflict Banner (OMOWAY App Conflict / Reopen Navigation Hint - WayXAuto 0.2.5)
        val competing = competingAppPair
        if (headUnitBusy) {
            binding.layoutConflictBanner.visibility = View.VISIBLE
            val appName = competing?.second ?: "OMOWAY"
            binding.tvConflictTitle.text = "⚠ Motor Masih Menampilkan Aplikasi $appName?"
            binding.tvConflictTitle.setTextColor(Color.parseColor("#FFB300"))
            binding.tvConflictBody.text =
                "Motor hanya dapat menampilkan 1 aplikasi HP dalam satu waktu, dan $appName mungkin masih menahan sesi proyeksi:\n" +
                    "1. Ketuk tombol di bawah lalu pilih Paksa Berhenti (Force Stop).\n" +
                    "2. Di layar motor Anda, tutup menu Navigasi lalu buka kembali."
            binding.btnResolveConflict.visibility = View.VISIBLE
            binding.btnResolveConflict.text = "Paksa Berhenti $appName (Force Stop)"
        } else if (needsReopenHint && bikeProjecting) {
            binding.layoutConflictBanner.visibility = View.VISIBLE
            binding.tvConflictTitle.text = "✔ Gambar Terkirim · Buka Ulang Navigasi di Motor"
            binding.tvConflictTitle.setTextColor(Color.parseColor("#00E676"))
            binding.tvConflictBody.text =
                "UMOX sedang mengirim video 60 FPS ke motor Anda. Jika layar motor masih menampilkan gambar aplikasi sebelumnya, tutup Navigasi di motor lalu buka kembali."
            binding.btnResolveConflict.visibility = View.VISIBLE
            binding.btnResolveConflict.text = "Selesai"
        } else if (competing != null && !bikeProjecting) {
            // Tampilkan pintasan cepat Force Stop apabila aplikasi kompetitor (OMOWAY/WayXAuto) terdeteksi di HP
            binding.layoutConflictBanner.visibility = View.VISIBLE
            binding.tvConflictTitle.text = "💡 Aplikasi ${competing.second} Terdeteksi di HP Ini"
            binding.tvConflictTitle.setTextColor(Color.parseColor("#00E5FF"))
            binding.tvConflictBody.text =
                "Jika motor masih menampilkan ${competing.second}, paksa berhenti aplikasi tersebut lalu tutup & buka kembali menu Navigasi di motor."
            binding.btnResolveConflict.visibility = View.VISIBLE
            binding.btnResolveConflict.text = "Buka Info ${competing.second} (Force Stop)"
        } else {
            binding.layoutConflictBanner.visibility = View.GONE
        }

        // 4. Update Live Preview Placeholder & HUD Overlay
        if (isStreaming && totalFrames > 0) {
            binding.layoutPreviewPlaceholder.visibility = View.GONE
            binding.tvPreviewHudOverlay.text = if (bikeProjecting) {
                "★ ON BIKE $displayFps FPS • $displayKbps kbps • IDR #$totalIdr"
            } else {
                "LIVE HP $displayFps FPS • $displayKbps kbps • IDR #$totalIdr"
            }
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

        // 5. Update Step 3 & Top Badges
        // Sesuai WayXAuto 0.2.5: "Android Auto Tampil di Motor" HANYA muncul setelah motor benar-benar menerima gambar (`bikeProjecting == true`)!
        if (isStreaming) {
            binding.dotStepStream.backgroundTintList = ColorStateList.valueOf(
                Color.parseColor(if (bikeProjecting) "#00E676" else "#00E5FF")
            )
            val modeLabel = when (mode) {
                StreamMode.ANDROID_AUTO -> "ANDROID AUTO"
                StreamMode.SCREEN_MIRROR -> "MIRROR LAYAR"
                StreamMode.TEST_PATTERN -> "TEST VIDEO"
                StreamMode.IDLE -> "IDLE"
            }
            binding.tvConnectionBadge.text = if (bikeProjecting) {
                "★ TAMPIL DI MOTOR: $modeLabel"
            } else {
                "● LIVE DI HP: $modeLabel"
            }
            binding.tvConnectionBadge.setTextColor(
                Color.parseColor(if (bikeProjecting) "#00E676" else "#00E5FF")
            )
            binding.tvStreamStatsBadge.text = "960×360 • $displayFps FPS • $displayKbps kbps"
            binding.tvStreamStatsBadge.setTextColor(Color.parseColor("#00E5FF"))

            binding.tvStepStreamStatus.text = if (bikeProjecting) {
                "★ $modeLabel Tampil di Layar Motor ($netFps FPS • $netKbps kbps · $totalSentFrames frame)"
            } else if (clusterConnected) {
                "⚡ Soket 9527/9528 Terhubung · Menunggu Motor Menerima Gambar..."
            } else {
                "★ Live di HP ($encFps FPS • $encKbps kbps) · Menunggu Wi-Fi Motor 192.168.2.1..."
            }
            binding.tvStepStreamStatus.setTextColor(
                Color.parseColor(if (bikeProjecting) "#00E676" else "#00E5FF")
            )

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
            binding.tvStepStreamStatus.text = if (isServer) {
                "Mode Server Aktif · Menunggu HP Donor terhubung & memproyeksikan video"
            } else {
                "Siaga • Tekan Mulai untuk menjalankan pipeline 60 FPS"
            }
            binding.tvStepStreamStatus.setTextColor(Color.parseColor("#8B949E"))

            binding.btnStopCast.visibility = View.GONE
            binding.btnStartAndroidAuto.text = if (isServer) {
                "📡  RADAR SERVER AKTIF (BUKA AA)"
            } else {
                "🚀  MULAI ANDROID AUTO (60 FPS)"
            }
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

        // Jika terdapat transport USB yang sedang menunggu, sambungkan ke receiver begitu siap
        pendingUsbTransport?.let { transport ->
            uiHandler.postDelayed(object : Runnable {
                var attempts = 0
                override fun run() {
                    val receiver = ScreenCastService.activeAaReceiver
                    if (receiver != null) {
                        appendLog("★ Menyambungkan transport USB ke receiver Android Auto...")
                        receiver.attachTransport(transport)
                        pendingUsbTransport = null
                    } else if (attempts++ < 15) {
                        uiHandler.postDelayed(this, 200)
                    }
                }
            }, 300)
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
        if (binding.tvConsoleLog.length() > 8000) {
            val text = binding.tvConsoleLog.text.toString()
            binding.tvConsoleLog.text = text.substring(text.length - 4000)
        }
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
        peerDiscovery.stopAll()
        bleSniffer.stopScan()
        gattExplorer.disconnect()
        wifiP2pProbe.unregister()
        try { usbAoaManager.stopListening() } catch (_: Exception) {}
        try { wppBluetoothServer.stop() } catch (_: Exception) {}
    }
}
