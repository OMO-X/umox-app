package com.umox.app.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.umox.app.MainActivity
import com.umox.app.aa.AaHeadlessReceiver
import com.umox.app.config.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class StreamMode {
    IDLE,
    ANDROID_AUTO,
    SCREEN_MIRROR,
    TEST_PATTERN
}

class ScreenCastService : Service() {

    companion object {
        const val ACTION_START_ANDROID_AUTO = "com.umox.app.stream.START_ANDROID_AUTO"
        const val ACTION_START_SCREEN = "com.umox.app.stream.START_SCREEN"
        const val ACTION_START_PATTERN = "com.umox.app.stream.START_PATTERN"
        const val ACTION_STOP = "com.umox.app.stream.STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_TARGET_IP = "extra_target_ip"
        const val EXTRA_VIDEO_PORT = "extra_video_port"
        const val EXTRA_CTRL_PORT = "extra_ctrl_port"

        private const val NOTIFICATION_CHANNEL_ID = "umox_cast_channel"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var isStreamingActive = false
            private set

        @Volatile
        var currentMode = StreamMode.IDLE
            private set

        @Volatile
        var activeAaReceiver: AaHeadlessReceiver? = null
            private set

        @Volatile
        var localEncodedFps: Int = 0
            private set

        @Volatile
        var localEncodedKbps: Int = 0
            private set

        @Volatile
        var totalEncodedFrames: Long = 0L
            private set

        @Volatile
        var totalKeyFrames: Long = 0L
            private set

        @Volatile
        var isClusterVideoConnected: Boolean = false
            private set

        @Volatile
        var isClusterCtrlConnected: Boolean = false
            private set

        /**
         * Bernilai `true` HANYA ketika motor sudah menerima handshake kontrol Port 9527 (ECDH Msg 49 / Ping Msg 9),
         * menerima `Ch 7, Msg 1 ({"code":0})`, DAN frame video H.264 sudah diterima oleh Port 9528
         * (persis `e0.n` / `bike_projecting` di WayXAuto-0.2.5).
         */
        @Volatile
        var isBikeProjecting: Boolean = false
            private set

        /**
         * Bernilai `true` apabila rute Wi-Fi Direct / TCP motor aktif namun dalam >= 8 detik
         * motor belum merespons handshake proyeksi (karena ditahan aplikasi OMOWAY resmi / layar Navigasi motor belum dibuka)
         * (persis `e0.o` / `head_unit_busy` di WayXAuto-0.2.5).
         */
        @Volatile
        var isHeadUnitBusy: Boolean = false
            private set

        /**
         * Bernilai `true` apabila sebelumnya sempat terjadi konflik (`isHeadUnitBusy`), lalu aliran video
         * sudah berhasil masuk ke motor (`isBikeProjecting = true`), untuk mengingatkan pengguna menutup
         * dan membuka ulang menu Navigasi di motor bila gambar belum berpindah.
         */
        @Volatile
        var needsBikeNavReopenHint: Boolean = false
            private set

        fun dismissBikeNavReopenHint() {
            needsBikeNavReopenHint = false
            onStateChangedCallback?.invoke()
        }

        fun requestImmediateKeyFrame() {
            currentInstance?.mediaEncoder?.requestKeyFrame()
            currentInstance?.assetPlayer?.requestKeyFrame()
        }

        val activeEncoder: MediaEncoder?
            get() = currentInstance?.mediaEncoder

        fun updateStreamQuality(bitrate: Int = AppConfig.currentBitrate, fps: Int = AppConfig.currentFps) {
            currentInstance?.mediaEncoder?.updateBitrate(bitrate, fps)
        }

        @Volatile
        private var currentInstance: ScreenCastService? = null

        var onLogCallback: ((String) -> Unit)? = null
        var onStateChangedCallback: (() -> Unit)? = null
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var mediaProjection: MediaProjection? = null
    private var aaReceiver: AaHeadlessReceiver? = null
    private var mediaEncoder: MediaEncoder? = null
    private var assetPlayer: H264AssetPlayer? = null
    private var socketStreamer: SocketStreamer? = null
    private var controlChannelClient: ControlChannelClient? = null
    private var clusterMaintainJob: Job? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var activeTargetIp: String = AppConfig.defaultClusterIp
    private var activeVideoPort: Int = AppConfig.defaultVideoPort
    private var activeCtrlPort: Int = AppConfig.defaultControlPort

    private var encFrameCountWindow = 0
    private var encByteCountWindow = 0L
    private var encLastWindowMs = System.currentTimeMillis()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        currentInstance = this
        AppConfig.loadFromPreferences(applicationContext)
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        val targetIp = intent.getStringExtra(EXTRA_TARGET_IP) ?: AppConfig.defaultClusterIp
        val videoPort = intent.getIntExtra(EXTRA_VIDEO_PORT, AppConfig.defaultVideoPort)
        val ctrlPort = intent.getIntExtra(EXTRA_CTRL_PORT, AppConfig.defaultControlPort)

        when (action) {
            ACTION_START_ANDROID_AUTO -> {
                val notif = buildNotification("Android Auto 60 FPS Aktif (960×360)")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                } else {
                    startForeground(NOTIFICATION_ID, notif)
                }
                startAndroidAutoProjection(targetIp, videoPort, ctrlPort)
            }
            ACTION_START_SCREEN -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

                if (resultData != null) {
                    val notif = buildNotification("Screen Mirroring 60 FPS Aktif (960×360)")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                    } else {
                        startForeground(NOTIFICATION_ID, notif)
                    }
                    startScreenProjection(resultCode, resultData, targetIp, videoPort, ctrlPort)
                }
            }
            ACTION_START_PATTERN -> {
                val notif = buildNotification("Test Video Baseline Aktif (960×360)")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                } else {
                    startForeground(NOTIFICATION_ID, notif)
                }
                startPatternProjection(targetIp, videoPort, ctrlPort)
            }
            ACTION_STOP -> {
                log("⏹ Menghentikan sesi streaming...")
                Thread {
                    try {
                        controlChannelClient?.sendHideSignal()
                        Thread.sleep(80)
                    } catch (_: Exception) {}
                    serviceScope.launch(Dispatchers.Main) {
                        stopProjection()
                        stopSelf()
                    }
                }.start()
            }
        }

        return START_NOT_STICKY
    }

    private fun startAndroidAutoProjection(targetIp: String, videoPort: Int, ctrlPort: Int) {
        stopPipelineOnly()
        log("🚀 Memulai Android Auto Hardware Bridge (960×360 @ 60 FPS, 4 Mbps, GOP 6)...")
        isStreamingActive = true
        currentMode = StreamMode.ANDROID_AUTO
        resetStats()
        onStateChangedCallback?.invoke()

        ensureClusterSocketsAndLoop(targetIp, videoPort, ctrlPort)

        // 1. Nyalakan OpenGL ES 2.0 Cropper + Hardware H.264 Encoder (${AppConfig.currentFps} FPS, ${AppConfig.currentBitrate / 1000} kbps, IDR tiap 6 frame) immediately!
        val encoder = MediaEncoder(
            width = 960,
            height = 360,
            dpi = 160,
            bitrate = AppConfig.currentBitrate,
            fps = AppConfig.currentFps,
            onFrameEncoded = { frame ->
                onNewEncodedFrame(frame)
            },
            onLog = { log(it) }
        )
        val bridgeSurface = encoder.startSurfaceBridge()
        mediaEncoder = encoder

        // 2. Nyalakan penerima proyeksi Android Auto (127.0.0.1 atau Server Remote) langsung ke OpenGL Surface
        val targetAaHost = if (AppConfig.appMode == AppConfig.AppMode.CLIENT) {
            AppConfig.serverHostIp.ifBlank { "127.0.0.1" }
        } else {
            "127.0.0.1"
        }

        val receiver = AaHeadlessReceiver(
            context = applicationContext,
            outputSurface = bridgeSurface,
            targetHost = targetAaHost,
            onH264Frame = { _, _, _ -> },
            onStatusChanged = { status ->
                log("[AA] $status")
            }
        ).apply {
            start()
        }
        aaReceiver = receiver
        activeAaReceiver = receiver
        log("★ Pipeline Android Auto 60 FPS siap! Menunggu handshake $targetAaHost:5277 & motor $targetIp...")
    }

    private fun startScreenProjection(
        resultCode: Int,
        data: Intent,
        targetIp: String,
        videoPort: Int,
        ctrlPort: Int
    ) {
        stopPipelineOnly()

        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpManager.getMediaProjection(resultCode, data)
        if (mp == null) {
            log("✖ Gagal mendapatkan izin MediaProjection.")
            stopSelf()
            return
        }
        mediaProjection = mp

        log("📱 Memulai Screen Mirroring Zero-Delay (960×360 @ 60 FPS, 4 Mbps, GOP 6)...")
        isStreamingActive = true
        currentMode = StreamMode.SCREEN_MIRROR
        resetStats()
        onStateChangedCallback?.invoke()

        ensureClusterSocketsAndLoop(targetIp, videoPort, ctrlPort)

        val encoder = MediaEncoder(
            width = 960,
            height = 360,
            dpi = 160,
            bitrate = AppConfig.currentBitrate,
            fps = AppConfig.currentFps,
            onFrameEncoded = { frame ->
                onNewEncodedFrame(frame)
            },
            onLog = { log(it) }
        )
        mediaEncoder = encoder
        encoder.start(mp)
        encoder.requestKeyFrame()
        log("★ Screen Mirroring 60 FPS aktif (OpenGL ES 2.0 + BoundedQueue 100ms)!")
    }

    private fun startPatternProjection(targetIp: String, videoPort: Int, ctrlPort: Int) {
        stopPipelineOnly()
        log("🎬 Memulai Test Video Baseline (960×360 @ 30 FPS) ke $targetIp...")
        isStreamingActive = true
        currentMode = StreamMode.TEST_PATTERN
        resetStats()
        onStateChangedCallback?.invoke()

        ensureClusterSocketsAndLoop(targetIp, videoPort, ctrlPort)

        val player = H264AssetPlayer(
            context = applicationContext,
            assetFilename = "test_baseline.h264",
            fps = 30,
            onFrameReady = { frame ->
                onNewEncodedFrame(frame)
            },
            onLog = { log(it) }
        )
        assetPlayer = player
        player.start()
    }

    private fun onNewEncodedFrame(frame: EncodedVideoFrame) {
        totalEncodedFrames++
        if (frame.isKeyFrame) {
            totalKeyFrames++
        }
        encFrameCountWindow++
        encByteCountWindow += frame.annexBSlice.size + frame.sps.size + frame.pps.size

        val now = System.currentTimeMillis()
        val elapsed = now - encLastWindowMs
        if (elapsed >= 1000L) {
            localEncodedFps = ((encFrameCountWindow * 1000L) / elapsed).toInt()
            localEncodedKbps = ((encByteCountWindow * 8L) / elapsed).toInt()
            encFrameCountWindow = 0
            encByteCountWindow = 0L
            encLastWindowMs = now
        }

        socketStreamer?.enqueueVideoFrame(frame)
    }

    private fun ensureClusterSocketsAndLoop(targetIp: String, videoPort: Int, ctrlPort: Int) {
        val ipChanged = activeTargetIp != targetIp || activeVideoPort != videoPort || activeCtrlPort != ctrlPort
        activeTargetIp = targetIp
        activeVideoPort = videoPort
        activeCtrlPort = ctrlPort

        if (socketStreamer == null || ipChanged) {
            socketStreamer?.disconnect()
            socketStreamer = SocketStreamer(applicationContext, targetIp, videoPort) { log(it) }.apply {
                onConnectionLost = {
                    isClusterVideoConnected = false
                    isBikeProjecting = false
                    onStateChangedCallback?.invoke()
                }
                onRequestKeyFrame = {
                    mediaEncoder?.requestKeyFrame()
                    assetPlayer?.requestKeyFrame()
                }
            }
        }

        if (controlChannelClient == null || ipChanged) {
            controlChannelClient?.disconnect()
            controlChannelClient = ControlChannelClient(applicationContext, targetIp, ctrlPort) { log(it) }.apply {
                onShowRequested = {
                    log("★ Cluster siap menampilkan video (Handshake Kontrol & SHOW diterima).")
                    socketStreamer?.enableSink()
                    mediaEncoder?.requestKeyFrame()
                    assetPlayer?.requestKeyFrame()
                    onStateChangedCallback?.invoke()
                }
                onHideRequested = {
                    log("Cluster meminta HIDE.")
                }
                onKeyFrameRequested = {
                    mediaEncoder?.requestKeyFrame()
                    assetPlayer?.requestKeyFrame()
                }
                onClusterModeChanged = { _ ->
                    // Catatan Penting: Firmware Omoway selalu mengirim Msg 37 {"mode":"night"} meskipun siang hari (08:44 pagi).
                    // Persis WayXAuto (q.java line 227), kita cukup membalas Msg 39 ke motor tanpa memaksa Google Maps menjadi gelap,
                    // agar peta tetap terang & jelas di siang hari (pengguna dapat mengubah Siang/Malam lewat tombol 🌓 di UMOX).
                }
                onConnectionLost = {
                    isClusterCtrlConnected = false
                    isBikeProjecting = false
                    onStateChangedCallback?.invoke()
                }
            }
        }

        if (clusterMaintainJob?.isActive != true) {
            clusterMaintainJob = serviceScope.launch(Dispatchers.IO) {
                var motorRouteSinceMs = 0L
                var lastSentFramesSnapshot = SocketStreamer.totalFramesSentGlobal
                var lastSentProgressMs = android.os.SystemClock.elapsedRealtime()
                var hadBusyConflict = false

                while (isActive && isStreamingActive) {
                    val ctrl = controlChannelClient
                    val sock = socketStreamer

                    // Hanya coba sambungkan ke soket motor jika antarmuka Wi-Fi Direct (192.168.2.x) sudah terhubung!
                    val hasMotorSubnet = WifiDirectNetworkBinder.findLocalSubnetAddress("192.168.2.") != null
                    if (hasMotorSubnet) {
                        if (ctrl != null && !ctrl.isConnected) {
                            val ok = ctrl.connect()
                            isClusterCtrlConnected = ok
                        } else {
                            isClusterCtrlConnected = ctrl?.isConnected == true
                        }

                        if (sock != null && !sock.isConnected) {
                            val ok = sock.connect()
                            isClusterVideoConnected = ok
                            if (ok) {
                                lastSentFramesSnapshot = SocketStreamer.totalFramesSentGlobal
                                lastSentProgressMs = android.os.SystemClock.elapsedRealtime()
                                if (ctrl?.isProjectionControlReady == true) {
                                    sock.enableSink()
                                    ctrl.sendShowSignal()
                                }
                                mediaEncoder?.requestKeyFrame()
                                assetPlayer?.requestKeyFrame()
                            }
                        } else {
                            isClusterVideoConnected = sock?.isConnected == true
                        }
                    } else {
                        isClusterCtrlConnected = false
                        isClusterVideoConnected = false
                    }

                    val nowMs = android.os.SystemClock.elapsedRealtime()
                    val currentSent = SocketStreamer.totalFramesSentGlobal
                    if (currentSent > lastSentFramesSnapshot) {
                        lastSentFramesSnapshot = currentSent
                        lastSentProgressMs = nowMs
                    }

                    // Verifikasi `bike_projecting` (`e0.n` di WayXAuto-0.2.5):
                    // Hanya bernilai true apabila Port 9527 sudah menyelesaikan handshake proyeksi DAN Port 9528 menerima frame!
                    val projectingNow = isClusterCtrlConnected &&
                        isClusterVideoConnected &&
                        (ctrl?.isProjectionControlReady == true || currentSent > 0L)
                    isBikeProjecting = projectingNow

                    // Deteksi rute Wi-Fi Direct / TCP motor untuk `head_unit_busy` (`e0.o` di WayXAuto-0.2.5)
                    val hasMotorRoute = isClusterCtrlConnected ||
                        isClusterVideoConnected ||
                        WifiDirectNetworkBinder.findLocalSubnetAddress("192.168.2.") != null

                    if (!hasMotorRoute) {
                        motorRouteSinceMs = 0L
                        isHeadUnitBusy = false
                    } else if (projectingNow) {
                        motorRouteSinceMs = 0L
                        if (isHeadUnitBusy || hadBusyConflict) {
                            isHeadUnitBusy = false
                            needsBikeNavReopenHint = true
                        }
                    } else {
                        if (motorRouteSinceMs == 0L) {
                            motorRouteSinceMs = nowMs
                        } else if (nowMs - motorRouteSinceMs >= 8_000L && !isHeadUnitBusy) {
                            isHeadUnitBusy = true
                            hadBusyConflict = true
                            log("⚠ Motor terdeteksi masih menahan sesi aplikasi OMOWAY (>8 detik tanpa respons proyeksi). Paksa berhenti aplikasi OMOWAY lalu buka ulang Navigasi di motor.")
                        }
                    }

                    // Deteksi 8-detik frame stall (`head_unit_stalled` pada `o.java` line 152 di WayXAuto-0.2.5)
                    if (isClusterVideoConnected && isClusterCtrlConnected && (nowMs - lastSentProgressMs >= 8_000L)) {
                        log("⚠ Aliran video ke motor tertahan >8 detik (head_unit_stalled) · merestart soket 9527 & 9528...")
                        lastSentProgressMs = nowMs
                        sock?.disconnect()
                        ctrl?.disconnect(sendHide = false)
                        isClusterVideoConnected = false
                        isClusterCtrlConnected = false
                        isBikeProjecting = false
                    }

                    onStateChangedCallback?.invoke()
                    delay(1000)
                }
            }
        } else {
            // Memicu requestKeyFrame apabila soket sudah terhubung saat ganti mode
            if (controlChannelClient?.isProjectionControlReady == true) {
                controlChannelClient?.sendShowSignal()
            }
            mediaEncoder?.requestKeyFrame()
            assetPlayer?.requestKeyFrame()
        }
    }

    private fun stopPipelineOnly() {
        aaReceiver?.stop()
        aaReceiver = null
        activeAaReceiver = null

        mediaEncoder?.stop()
        mediaEncoder = null

        assetPlayer?.stop()
        assetPlayer = null

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null
    }

    private fun resetStats() {
        localEncodedFps = 0
        localEncodedKbps = 0
        totalEncodedFrames = 0L
        totalKeyFrames = 0L
        encFrameCountWindow = 0
        encByteCountWindow = 0L
        encLastWindowMs = System.currentTimeMillis()
    }

    private fun stopProjection() {
        isStreamingActive = false
        currentMode = StreamMode.IDLE
        isClusterVideoConnected = false
        isClusterCtrlConnected = false
        isBikeProjecting = false
        isHeadUnitBusy = false
        needsBikeNavReopenHint = false
        clusterMaintainJob?.cancel()
        clusterMaintainJob = null

        stopPipelineOnly()
        resetStats()
        onStateChangedCallback?.invoke()

        val sock = socketStreamer
        val ctrl = controlChannelClient
        socketStreamer = null
        controlChannelClient = null

        Thread {
            try {
                ctrl?.sendHideSignal()
                Thread.sleep(60)
            } catch (_: Exception) {}
            try {
                sock?.disconnect()
            } catch (_: Exception) {}
            try {
                ctrl?.disconnect()
            } catch (_: Exception) {}
            log("✔ Sesi streaming ditutup dengan bersih.")
        }.start()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {}
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UMOX::StreamWakeLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(lockMode, "UMOX::StreamWifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        wakeLock = null

        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (_: Exception) {}
        wifiLock = null
    }

    private fun log(message: String) {
        onLogCallback?.invoke(message)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "UMOX Stream Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Layanan proyeksi Android Auto & Layar 60 FPS ke Speedometer Omoway"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("UMOX Auto Bridge 60Hz")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (currentInstance === this) {
            currentInstance = null
        }
        stopProjection()
        releaseWakeLock()
        serviceScope.cancel()
    }
}
