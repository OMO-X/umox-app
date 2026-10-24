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
import androidx.core.app.ServiceCompat
import com.umox.app.MainActivity
import com.umox.app.config.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ScreenCastService : Service() {

    companion object {
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

        var isStreamingActive = false
            private set

        var isPatternMode = false
            private set

        var onLogCallback: ((String) -> Unit)? = null

        var currentHudRenderer: com.umox.app.ui.HudDashboardRenderer? = null
    }

    private var mediaProjection: MediaProjection? = null
    private var mediaEncoder: MediaEncoder? = null
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
            ACTION_START_SCREEN -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

                if (resultData != null && !isStreamingActive) {
                    val notif = buildNotification("Streaming Layar HP Aktif")
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
                    val notif = buildNotification("Test Pattern Speedometer Aktif")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                    } else {
                        startForeground(NOTIFICATION_ID, notif)
                    }
                    startPatternProjection(targetIp, videoPort, ctrlPort)
                }
            }
            ACTION_STOP -> {
                stopProjection()
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun startPatternProjection(targetIp: String, videoPort: Int, ctrlPort: Int) {
        log("Memulai Test Pattern HUD (960x360) ke $targetIp (Video: $videoPort, Ctrl: $ctrlPort)...")
        isStreamingActive = true
        isPatternMode = true
        isVideoStreamRunning = false

        socketStreamer = SocketStreamer(targetIp, videoPort) { log(it) }.apply {
            onConnectionLost = {
                handleVideoReconnect(videoPort)
            }
        }

        controlChannelClient = ControlChannelClient(targetIp, ctrlPort) { log(it) }.apply {
            onShowRequested = {
                log("★ Saluran kontrol siap / Speedometer meminta SHOW.")
                startVideoStream()
            }
            onHideRequested = {
                log("Speedometer meminta HIDE, menjeda transmisi video.")
                pauseVideoStream()
            }
            onKeyFrameRequested = {
                mediaEncoder?.requestKeyFrame()
            }
        }

        serviceScope.launch {
            // 1. Hubungkan saluran kontrol port 9527 terlebih dahulu
            controlChannelClient?.connect()

            // 2. Beri jeda hingga 2 detik agar negosiasi keamanan ECDH (Msg 49) selesai
            kotlinx.coroutines.delay(2000)
            if (isStreamingActive && !isVideoStreamRunning) {
                log("Batas waktu handshake terlewati, memicu sinyal SHOW manual...")
                controlChannelClient?.sendShowSignal()
                startVideoStream()
            }
        }
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
            log("Gagal mendapatkan MediaProjection.")
            stopSelf()
            return
        }

        log("Memulai streaming layar HP ke speedometer $targetIp:$videoPort...")
        isStreamingActive = true
        isPatternMode = false
        isVideoStreamRunning = false

        socketStreamer = SocketStreamer(targetIp, videoPort) { log(it) }.apply {
            onConnectionLost = {
                handleVideoReconnect(videoPort)
            }
        }

        controlChannelClient = ControlChannelClient(targetIp, ctrlPort) { log(it) }.apply {
            onShowRequested = {
                log("★ Saluran kontrol siap / Speedometer meminta SHOW.")
                startVideoStream()
            }
            onHideRequested = {
                log("Speedometer meminta HIDE, menjeda transmisi video.")
                pauseVideoStream()
            }
            onKeyFrameRequested = {
                mediaEncoder?.requestKeyFrame()
            }
            onClusterModeChanged = { modeStr ->
                val isNight = modeStr.contains("night", ignoreCase = true)
                currentHudRenderer?.state?.isNightMode = isNight
                currentHudRenderer?.state?.clusterMode = if (isNight) "NIGHT" else "DAY"
                log("🌓 Menyesuaikan Tema HUD Cockpit ke mode: ${if (isNight) "MALAM" else "SIANG"}")
            }
        }

        serviceScope.launch {
            // 1. Hubungkan saluran kontrol port 9527 terlebih dahulu
            controlChannelClient?.connect()

            // 2. Beri jeda hingga 2 detik agar negosiasi keamanan ECDH (Msg 49) selesai
            kotlinx.coroutines.delay(2000)
            if (isStreamingActive && !isVideoStreamRunning) {
                log("Batas waktu handshake terlewati, memicu sinyal SHOW manual...")
                controlChannelClient?.sendShowSignal()
                startVideoStream()
            }
        }
    }

    private fun startVideoStream() {
        if (!isStreamingActive || isVideoStreamRunning) {
            mediaEncoder?.requestKeyFrame()
            return
        }
        isVideoStreamRunning = true

        serviceScope.launch {
            // Buka soket video Port 9528 SETELAH handshake port 9527 divalidasi oleh motor
            socketStreamer?.connect()

            mediaEncoder = MediaEncoder(
                width = 960,
                height = 360,
                dpi = 160,
                bitrate = 2_000_000,
                fps = 30,
                onPacketEncoded = { packet, _ ->
                    socketStreamer?.sendH264Packet(packet)
                },
                onLog = { log(it) }
            )

            if (isPatternMode) {
                mediaEncoder?.startPatternTest()
                currentHudRenderer = mediaEncoder?.hudRenderer
                log("★ PURE ANDROID AUTO HUD AKTIF! Layar speedometer menampilkan Cockpit 960x360.")
            } else {
                mediaProjection?.let { mp ->
                    mediaEncoder?.start(mp)
                    log("★ STREAMING LAYAR HP AKTIF (960x360). Silakan buka Google Maps di HP.")
                }
            }

            // Segera picu Sync Frame (I-Frame) pertama
            mediaEncoder?.requestKeyFrame()
        }
    }

    private fun pauseVideoStream() {
        isVideoStreamRunning = false
        currentHudRenderer = null
        mediaEncoder?.stop()
        mediaEncoder = null
        socketStreamer?.disconnect()
    }

    private fun handleVideoReconnect(videoPort: Int) {
        if (!isStreamingActive || !isVideoStreamRunning) return
        serviceScope.launch {
            log("Mencoba menyambungkan kembali soket video ($videoPort) dalam 1 detik...")
            kotlinx.coroutines.delay(1000)
            if (isStreamingActive && isVideoStreamRunning) {
                socketStreamer?.connect()
                mediaEncoder?.requestKeyFrame()
            }
        }
    }

    private fun stopProjection() {
        isStreamingActive = false
        isPatternMode = false
        isVideoStreamRunning = false

        mediaEncoder?.stop()
        mediaEncoder = null

        socketStreamer?.disconnect()
        socketStreamer = null

        controlChannelClient?.disconnect()
        controlChannelClient = null

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null

        log("Streaming video & kontrol dihentikan.")
    }

    private fun log(message: String) {
        onLogCallback?.invoke(message)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "UMOX Screen Cast",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Layanan proyeksi layar ke speedometer"
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
            .setContentTitle("UMOX Desay SV Stream")
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
