package com.umox.app.service

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.umox.app.stream.ScreenCastService
import com.umox.app.ui.NavManeuver

/**
 * Service pemantau notifikasi cerdas UMOX (Smartwatch / HeadUnit Style).
 * Berjalan di background tanpa perlu tangkap layar (zero screen sharing overhead).
 * Membaca secara real-time:
 * 1. Google Maps Navigation: Jarak belokan, nama jalan, estimasi waktu (ETA), dan arah belokan.
 * 2. Media Player: Judul lagu & Artis (Spotify, YouTube Music, Apple Music).
 * 3. Notifikasi Pesan: WhatsApp / Panggilan masuk dengan pop-up banner transparan selama 6 detik.
 */
class UmoxNotificationListener : NotificationListenerService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var notifClearRunnable: Runnable? = null

    companion object {
        private const val TAG = "UmoxNotifListener"
        var isListenerConnected = false
            private set
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isListenerConnected = true
        Log.i(TAG, "✔ UmoxNotificationListener terhubung ke sistem notifikasi Android.")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isListenerConnected = false
        Log.i(TAG, "⚠ UmoxNotificationListener terputus.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val sbn = sbn ?: return
        val pkg = sbn.packageName ?: return
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""

        val renderer = ScreenCastService.currentHudRenderer ?: return

        // =====================================================================
        // 1. GOOGLE MAPS NAVIGATION TURN-BY-TURN INTERCEPTION
        // Membaca arahan navigasi langsung dari status bar tanpa membebani GPU/HP
        // =====================================================================
        if (pkg == "com.google.android.apps.maps") {
            if (title.isNotEmpty() || text.isNotEmpty()) {
                mainHandler.post {
                    renderer.state.apply {
                        // Pola Google Maps:
                        // Biasanya: Title = "In 250 m" atau "250 m", Text = "Turn right onto Jl. Margonda Raya"
                        // Atau sebaliknya tergantung bahasa HP
                        if (title.contains("m", ignoreCase = true) || title.contains("km", ignoreCase = true)) {
                            navDistance = title
                            if (text.isNotEmpty()) {
                                navRoadName = cleanRoadName(text)
                                navInstruction = text
                            }
                        } else if (text.contains("m", ignoreCase = true) || text.contains("km", ignoreCase = true)) {
                            navDistance = text
                            if (title.isNotEmpty()) {
                                navRoadName = cleanRoadName(title)
                                navInstruction = title
                            }
                        } else {
                            if (title.isNotEmpty()) navRoadName = cleanRoadName(title)
                            if (text.isNotEmpty()) navInstruction = text
                        }

                        if (subText.isNotEmpty()) {
                            navEta = subText
                        }

                        // Deteksi arah belokan dari teks instruksi
                        val combined = "$title $text".lowercase()
                        navManeuver = when {
                            combined.contains("u-turn") || combined.contains("putar balik") -> NavManeuver.U_TURN
                            combined.contains("right") || combined.contains("kanan") -> NavManeuver.TURN_RIGHT
                            combined.contains("left") || combined.contains("kiri") -> NavManeuver.TURN_LEFT
                            combined.contains("roundabout") || combined.contains("bundaran") -> NavManeuver.ROUNDABOUT
                            combined.contains("arrive") || combined.contains("sampai") || combined.contains("tujuan") -> NavManeuver.DESTINATION
                            else -> NavManeuver.STRAIGHT
                        }
                    }
                }
            }
        }

        // =====================================================================
        // 2. MEDIA PLAYER (SPOTIFY, YOUTUBE MUSIC, DLL)
        // Membaca judul lagu dan artis yang sedang diputar di latar belakang
        // =====================================================================
        if (pkg == "com.spotify.music" || pkg == "com.google.android.apps.youtube.music" || pkg.contains("music")) {
            if (title.isNotEmpty()) {
                mainHandler.post {
                    renderer.state.apply {
                        songTitle = title
                        songArtist = if (text.isNotEmpty()) text else "Audio Player"
                        musicSource = if (pkg.contains("spotify")) "SPOTIFY" else "YT MUSIC"
                        isMusicPlaying = true
                    }
                }
            }
        }

        // =====================================================================
        // 3. WHATSAPP & PANGGILAN (INCOMING NOTIFICATIONS)
        // Menampilkan Banner Pop-up transparan di cluster speedometer selama 6 detik
        // =====================================================================
        if (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") {
            if (title.isNotEmpty() && text.isNotEmpty()) {
                mainHandler.post {
                    renderer.state.apply {
                        notificationActive = true
                        notificationSender = title
                        notificationApp = "WhatsApp"
                        notificationText = text

                        // Reset timer pop-up 6 detik
                        notifClearRunnable?.let { mainHandler.removeCallbacks(it) }
                        notifClearRunnable = Runnable {
                            notificationActive = false
                        }
                        mainHandler.postDelayed(notifClearRunnable!!, 6000)
                    }
                }
            }
        }
    }

    private fun cleanRoadName(raw: String): String {
        return raw.replace(Regex("(?i)^(turn\\s+right\\s+onto|turn\\s+left\\s+onto|head\\s+toward|belok\\s+kanan\\s+ke|belok\\s+kiri\\s+ke|menuju\\s+ke)\\s*"), "")
            .trim()
    }
}
