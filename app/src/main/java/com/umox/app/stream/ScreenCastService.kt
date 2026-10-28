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
import androidx.core.app.NotificationCompat
import com.umox.app.MainActivity
import com.umox.app.aa.AaHeadlessReceiver
import com.umox.app.config.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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

        var onLogCallback: ((String) -> Unit)? = null
        var onStateChangedCallback: (() -> Unit)? = null
    }

    private var mediaProjection: MediaProjection? = null
    private var aaReceiver: AaHeadlessReceiver? = null
    private var mediaEncoder: MediaEncoder? = null
    private var assetPlayer: H264AssetPlayer? = null
    private var socketStreamer: SocketStreamer? = null
    private var controlChannelClient: ControlChannelClient? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var isVideoStreamRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        val targetIp = intent.getStringExtra(EXTRA_TARGET_IP) ?: AppConfig.defaultClusterIp
        val videoPort = intent.getIntExtra(EXTRA_VIDEO_PORT, AppConfig.defaultVideoPort)
        val ctrlPort = intent.getIntExtra(EXTRA_CTRL_PORT, AppConfig.defaultControlPort)

        when (action) {
            ACTION_START_ANDROID_AUTO -> {
                if (!isStreamingActive) {
                    val notif = buildNotification("Android Auto Wireless Bridge Aktif (960x360)")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                    } else {
                        startForeground(NOTIFICATION_ID, notif)
                    }
                    startAndroidAutoProjection(targetIp, videoPort, ctrlPort)
                }
            }
            ACTION_START_SCREEN -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

                if (resultData != null && !isStreamingActive) {
                    val notif = buildNotification("Screen Mirroring Aktif (960x360)")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                    } else {
                        startForeground(NOTIFICATION_ID, notif)
                    }
                    startScreenProjection(resultCode, resultData, targetIp, videoPort, ctrlPort)
                }
            }
            ACTION_START_PATTERN -> {
                if (!isStreamingActive) {
                    val notif = buildNotification("Test Video Baseline Aktif (960x360)")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                    } else {
                        startForeground(NOTIFICATION_ID, notif)
                    }
                    startPatternProjection(targetIp, videoPort, ctrlPort)
                }
            }
            ACTION_STOP -> {
                log("⏹ Menghentikan sesi streaming ke speedometer...")
                Thread {
                    try {
                        controlChannelClient?.sendHideSignal()
                        Thread.sleep(90)
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
        log("🚀 Memulai Android Auto Hardware Bridge (960x360) ke $targetIp...")
        isStreamingActive = true
        currentMode = StreamMode.ANDROID_AUTO
        isVideoStreamRunning = false
        onStateChangedCallback?.invoke()

        initSockets(targetIp, videoPort, ctrlPort)
    }

    private fun startPatternProjection(targetIp: String, videoPort: Int, ctrlPort: Int) {
        log("🎬 Memulai Test Video Baseline ke $targetIp (Video: $videoPort, Ctrl: $ctrlPort)...")
        isStreamingActive = true
        currentMode = StreamMode.TEST_PATTERN
        isVideoStreamRunning = false
        onStateChangedCallback?.invoke()

        initSockets(targetIp, videoPort, ctrlPort)
    }

    private fun startScreenProjection(
        resultCode: Int,
        data: Intent,
        targetIp: String,
        videoPort: Int,
        ctrlPort: Int
    ) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, data)

        if (mediaProjection == null) {
            log("✖ Gagal mendapatkan izin MediaProjection.")
            stopSelf()
            return
        }

        log("📱 Memulai Screen Mirroring (960x360 CBR) ke $targetIp:$videoPort...")
        isStreamingActive = true
        currentMode = StreamMode.SCREEN_MIRROR
        isVideoStreamRunning = false
        onStateChangedCallback?.invoke()

        initSockets(targetIp, videoPort, ctrlPort)
    }

    private fun initSockets(targetIp: String, videoPort: Int, ctrlPort: Int) {
        socketStreamer = SocketStreamer(targetIp, videoPort) { log(it) }.apply {
            onConnectionLost = {
                handleVideoReconnect(videoPort)
            }
        }

        controlChannelClient = ControlChannelClient(targetIp, ctrlPort) { log(it) }.apply {
            onShowRequested = {
                log("★ Cluster siap menampilkan video (SHOW diterima).")
                startVideoStream()
            }
            onHideRequested = {
                log("Cluster meminta HIDE, menjeda transmisi video.")
                pauseVideoStream()
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

        serviceScope.launch {
            controlChannelClient?.connect()

            // Tunggu negosiasi ECDH (Msg 49) di Port 9527 maksimal 1.6 detik
            delay(1600)
            if (isStreamingActive && !isVideoStreamRunning) {
                log("Memicu sinyal SHOW & membuka aliran video Port 9528...")
                controlChannelClient?.sendShowSignal()
                startVideoStream()
            }
        }
    }

    private fun startVideoStream() {
        if (!isStreamingActive || isVideoStreamRunning) {
            mediaEncoder?.requestKeyFrame()
            assetPlayer?.requestKeyFrame()
            return
        }
        isVideoStreamRunning = true

        serviceScope.launch {
            socketStreamer?.connect()

            when (currentMode) {
                StreamMode.ANDROID_AUTO -> {
                    // 1. Nyalakan Hardware H.264 Encoder (960x360 @ 30fps, 2.5 Mbps CBR) dengan GPU Surface Input
                    val encoder = MediaEncoder(
                        width = 960,
                        height = 360,
                        dpi = 160,
                        bitrate = 2_500_000,
                        fps = 30,
                        onPacketEncoded = { packet, _ ->
                            socketStreamer?.sendH264Packet(packet)
                        },
                        onLog = { log(it) }
                    )
                    val bridgeSurface = encoder.startSurfaceBridge()
                    mediaEncoder = encoder

                    // 2. Hubungkan Android Auto Receiver langsung ke GPU Surface milik MediaEncoder
                    val receiver = AaHeadlessReceiver(
                        context = applicationContext,
                        outputSurface = bridgeSurface,
                        onH264Frame = { bytes, offset, length ->
                            val nalu = ByteArray(length)
                            System.arraycopy(bytes, offset, nalu, 0, length)
                            socketStreamer?.sendH264Packet(nalu)
                        },
                        onStatusChanged = { status ->
                            log("[AA] $status")
                        }
                    ).apply {
                        start()
                    }
                    aaReceiver = receiver
                    activeAaReceiver = receiver
                    log("★ ANDROID AUTO HARDWARE BRIDGE AKTIF (960x360 @ 30 FPS Zero-Copy GPU)!")
                }
                StreamMode.SCREEN_MIRROR -> {
                    mediaEncoder = MediaEncoder(
                        width = 960,
                        height = 360,
                        dpi = 160,
                        bitrate = 2_500_000,
                        fps = 30,
                        onPacketEncoded = { packet, _ ->
                            socketStreamer?.sendH264Packet(packet)
                        },
                        onLog = { log(it) }
                    )
                    mediaProjection?.let { mp ->
                        mediaEncoder?.start(mp)
                        log("★ SCREEN MIRRORING AKTIF (960x360 @ 30 FPS CBR).")
                    }
                    mediaEncoder?.requestKeyFrame()
                }
                StreamMode.TEST_PATTERN -> {
                    assetPlayer = H264AssetPlayer(
                        context = applicationContext,
                        assetFilename = "test_baseline.h264",
                        fps = 30,
                        onPacketReady = { packet ->
                            socketStreamer?.sendH264Packet(packet)
                        },
                        onLog = { log(it) }
                    ).apply {
                        start()
                    }
                    log("★ TEST VIDEO BASELINE AKTIF (test_baseline.h264).")
                }
                StreamMode.IDLE -> {}
            }
        }
    }

    private fun pauseVideoStream() {
        isVideoStreamRunning = false
        aaReceiver?.stop()
        aaReceiver = null
        activeAaReceiver = null
        mediaEncoder?.stop()
        mediaEncoder = null
        assetPlayer?.stop()
        assetPlayer = null
        socketStreamer?.disconnect()
    }

    private fun handleVideoReconnect(videoPort: Int) {
        if (!isStreamingActive || !isVideoStreamRunning) return
        serviceScope.launch {
            log("Menyambungkan ulang soket video ($videoPort) dalam 1 detik...")
            delay(1000)
            if (isStreamingActive && isVideoStreamRunning) {
                socketStreamer?.connect()
                mediaEncoder?.requestKeyFrame()
                assetPlayer?.requestKeyFrame()
            }
        }
    }

    private fun stopProjection() {
        isStreamingActive = false
        currentMode = StreamMode.IDLE
        pauseVideoStream()
        onStateChangedCallback?.invoke()

        val sock = socketStreamer
        val ctrl = controlChannelClient
        val proj = mediaProjection

        socketStreamer = null
        controlChannelClient = null
        mediaProjection = null

        Thread {
            try {
                ctrl?.sendHideSignal()
                Thread.sleep(80)
            } catch (_: Exception) {}

            try {
                sock?.disconnect()
            } catch (_: Exception) {}

            try {
                ctrl?.disconnect()
            } catch (_: Exception) {}

            try {
                proj?.stop()
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
                description = "Layanan proyeksi Android Auto & Layar ke Speedometer Omoway"
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
            .setContentTitle("UMOX Auto Bridge")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProjection()
        serviceScope.cancel()
    }
}
