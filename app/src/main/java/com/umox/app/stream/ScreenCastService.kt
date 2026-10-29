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

        @Volatile
        private var currentInstance: ScreenCastService? = null

        var onLogCallback: ((String) -> Unit)? = null
        var onStateChangedCallback: (() -> Unit)? = null
    }

    private var wakeLock: PowerManager.WakeLock? = null
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

        // 1. Nyalakan OpenGL ES 2.0 Cropper + Hardware H.264 Encoder (960x360 @ 60 FPS, 4 Mbps, IDR tiap 6 frame) immediately!
        val encoder = MediaEncoder(
            width = 960,
            height = 360,
            dpi = 160,
            bitrate = 4_000_000,
            fps = 60,
            onFrameEncoded = { frame ->
                onNewEncodedFrame(frame)
            },
            onLog = { log(it) }
        )
        val bridgeSurface = encoder.startSurfaceBridge()
        mediaEncoder = encoder

        // 2. Nyalakan penerima proyeksi Android Auto (127.0.0.1:5277) langsung ke OpenGL Surface
        val receiver = AaHeadlessReceiver(
            context = applicationContext,
            outputSurface = bridgeSurface,
            onH264Frame = { _, _, _ -> },
            onStatusChanged = { status ->
                log("[AA] $status")
            }
        ).apply {
            start()
        }
        aaReceiver = receiver
        activeAaReceiver = receiver
        log("★ Pipeline Android Auto 60 FPS siap! Menunggu handshake 127.0.0.1:5277 & motor $targetIp...")
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
            bitrate = 4_000_000,
            fps = 60,
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
                    log("★ Cluster siap menampilkan video (SHOW diterima).")
                    socketStreamer?.isSinkReady = true
                    mediaEncoder?.requestKeyFrame()
                    assetPlayer?.requestKeyFrame()
                }
                onHideRequested = {
                    log("Cluster meminta HIDE.")
                }
                onKeyFrameRequested = {
                    mediaEncoder?.requestKeyFrame()
                    assetPlayer?.requestKeyFrame()
                }
                onClusterModeChanged = { modeStr ->
                    val isNight = modeStr.contains("night", ignoreCase = true)
                    aaReceiver?.sendNightMode(isNight)
                    log("🌓 Sensor cahaya motor mengubah tema ke: ${if (isNight) "MALAM" else "SIANG"}")
                }
            }
        }

        if (clusterMaintainJob?.isActive != true) {
            clusterMaintainJob = serviceScope.launch(Dispatchers.IO) {
                while (isActive && isStreamingActive) {
                    val ctrl = controlChannelClient
                    val sock = socketStreamer

                    if (ctrl != null && !ctrl.isConnected) {
                        val ok = ctrl.connect()
                        isClusterCtrlConnected = ok
                        if (ok) {
                            delay(250)
                            ctrl.sendShowSignal()
                        }
                    } else {
                        isClusterCtrlConnected = ctrl?.isConnected == true
                    }

                    if (sock != null && !sock.isConnected) {
                        val ok = sock.connect()
                        isClusterVideoConnected = ok
                        if (ok) {
                            ctrl?.sendShowSignal()
                            mediaEncoder?.requestKeyFrame()
                            assetPlayer?.requestKeyFrame()
                        }
                    } else {
                        isClusterVideoConnected = sock?.isConnected == true
                    }

                    delay(1800)
                }
            }
        } else {
            // Memicu requestKeyFrame apabila soket sudah terhubung saat ganti mode
            controlChannelClient?.sendShowSignal()
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
                acquire(4 * 60 * 60 * 1000L)
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
