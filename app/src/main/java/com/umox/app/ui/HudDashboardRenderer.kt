package com.umox.app.ui

import android.graphics.*
import java.text.SimpleDateFormat
import java.util.*

enum class NavManeuver {
    STRAIGHT,
    TURN_LEFT,
    TURN_RIGHT,
    SLIGHT_LEFT,
    SLIGHT_RIGHT,
    U_TURN,
    ROUNDABOUT,
    DESTINATION
}

enum class HudTheme {
    COOLWALK_DASHBOARD, // Android Auto Coolwalk Style: Nav + Music + Notif (Bebas tabrakan OSD motor)
    SATELLITE_TOURING,   // Satellite Map Touring Layout
    CYBERPUNK_TACTICAL,  // Neon Cyberpunk HUD
    MINIMAL_OLED         // Ultra Clean OLED Stealth
}

data class HudState(
    var theme: HudTheme = HudTheme.COOLWALK_DASHBOARD,
    var isNightMode: Boolean = true,
    var clusterMode: String = "NIGHT",

    // Navigation Data
    var navManeuver: NavManeuver = NavManeuver.TURN_RIGHT,
    var navDistance: String = "350 m",
    var navRoadName: String = "Jl. Margonda Raya",
    var navInstruction: String = "Kemudian Belok Kanan ke Jl. Juanda",
    var navEta: String = "14:25",
    var navRemainingKm: String = "4.8 km",
    var routeProgress: Float = 0.65f, // 0.0 .. 1.0

    // Music & Media Data (Spotify / YouTube Music)
    var isMusicPlaying: Boolean = true,
    var songTitle: String = "Blinding Lights",
    var songArtist: String = "The Weeknd",
    var musicSource: String = "SPOTIFY",

    // Incoming Notifications (WhatsApp, Telepon, SMS)
    var notificationActive: Boolean = false,
    var notificationSender: String = "Budi Santoso",
    var notificationApp: String = "WhatsApp",
    var notificationText: String = "Posisi di mana bro? Gw udah sampai lokasi.",

    // Weather & Status
    var weatherText: String = "☀️ 29°C Cerah",
    var wifiStatus: String = "DESAY SV 9528 OK",

    // Telemetry Passthrough (dari BLE / GPS jika dibutuhkan)
    var speedKmh: Int = 0,
    var batteryPercent: Int = 92,
    var rangeKm: Int = 78,
    var gearMode: String = "D",

    // GPS & Real Visual Map Properties (Technique 2: Headless 960x360 Map)
    var latitude: Double = -6.2088,
    var longitude: Double = 106.8456,
    var bearing: Float = 45f,
    var liveMapBitmap: Bitmap? = null,
    var isSatelliteMapEnabled: Boolean = true
)

class HudDashboardRenderer(
    private val width: Int = 960,
    private val height: Int = 360
) {
    val state = HudState()

    // Pre-allocated Paint objects untuk zero-allocation rendering @ 30/60 FPS
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true }

    // Pre-allocated RectF objects
    private val navCardRect = RectF(35f, 45f, 570f, 325f)
    private val musicCardRect = RectF(585f, 45f, 755f, 225f)
    private val notifCardRect = RectF(160f, 20f, 720f, 95f)

    // Animated Equalizer state
    private val eqBars = intArrayOf(10, 16, 22, 14, 20, 12, 18, 8)
    private val random = Random()

    fun render(canvas: Canvas) {
        val isNight = state.isNightMode

        // Update Equalizer bar animation
        if (state.isMusicPlaying) {
            for (i in eqBars.indices) {
                eqBars[i] = random.nextInt(20) + 4
            }
        }

        when (state.theme) {
            HudTheme.COOLWALK_DASHBOARD -> renderCoolwalkDashboard(canvas, isNight)
            HudTheme.SATELLITE_TOURING -> renderSatelliteTouring(canvas, isNight)
            HudTheme.CYBERPUNK_TACTICAL -> renderCyberpunkTactical(canvas, isNight)
            HudTheme.MINIMAL_OLED -> renderMinimalOled(canvas, isNight)
        }

        // Render Notifikasi WhatsApp jika sedang aktif
        if (state.notificationActive) {
            renderNotificationBanner(canvas, isNight)
        }
    }

    // =========================================================================
    // TEMA 1: ANDROID AUTO COOLWALK DASHBOARD (SPLIT: NAV + MUSIC + NOTIF)
    // Sempurna & aman dari tabrakan OSD speedometer motor (speed, gear, power bar)
    // =========================================================================
    private fun renderCoolwalkDashboard(canvas: Canvas, isNight: Boolean) {
        // 1. Background Kanvas & Visual Map
        if (state.liveMapBitmap != null) {
            canvas.drawBitmap(state.liveMapBitmap!!, 0f, 0f, null)
            canvas.drawColor(Color.argb(85, 8, 12, 22)) // Dark contrast tint
            drawVehicleNavigationMarker(canvas, 300f, 210f, state.bearing)
        } else {
            canvas.drawColor(if (isNight) Color.rgb(7, 10, 18) else Color.rgb(241, 245, 249))

            // Grid peta halus di background navigasi
            strokePaint.color = if (isNight) Color.argb(40, 30, 41, 59) else Color.argb(60, 203, 213, 225)
            strokePaint.strokeWidth = 1.5f
            for (x in 40..560 step 50) {
                canvas.drawLine(x.toFloat(), 15f, x.toFloat(), 340f, strokePaint)
            }
            for (y in 25..345 step 50) {
                canvas.drawLine(35f, y.toFloat(), 570f, y.toFloat(), strokePaint)
            }

            // Jalur Rute Navigasi Virtual (Glow effect)
            strokePaint.color = Color.argb(70, 59, 130, 246)
            strokePaint.strokeWidth = 18f
            strokePaint.strokeCap = Paint.Cap.ROUND
            strokePaint.strokeJoin = Paint.Join.ROUND
            val routePath = Path().apply {
                moveTo(120f, 290f)
                lineTo(220f, 230f)
                lineTo(300f, 160f)
                lineTo(450f, 110f)
            }
            canvas.drawPath(routePath, strokePaint)

            strokePaint.color = Color.rgb(59, 130, 246)
            strokePaint.strokeWidth = 8f
            canvas.drawPath(routePath, strokePaint)
        }

        // 2. Main Navigation Card (x: 35..570, y: 45..325)
        fillPaint.color = if (isNight) Color.rgb(15, 23, 42) else Color.WHITE
        canvas.drawRoundRect(navCardRect, 18f, 18f, fillPaint)

        strokePaint.color = if (isNight) Color.rgb(30, 41, 59) else Color.rgb(226, 232, 240)
        strokePaint.strokeWidth = 1.8f
        canvas.drawRoundRect(navCardRect, 18f, 18f, strokePaint)

        // Maneuver Arrow Badge (Emerald Green / Neon Cyan)
        fillPaint.color = Color.rgb(16, 185, 129)
        canvas.drawRoundRect(RectF(55f, 65f, 135f, 145f), 14f, 14f, fillPaint)
        drawManeuverArrow(canvas, 95f, 105f, state.navManeuver, Color.WHITE)

        // Jarak Belokan Besar
        textPaint.color = if (isNight) Color.WHITE else Color.rgb(15, 23, 42)
        textPaint.textSize = 38f
        textPaint.isFakeBoldText = true
        canvas.drawText(state.navDistance, 155f, 102f, textPaint)

        // Nama Jalan Tujuan
        textPaint.color = Color.rgb(0, 229, 255)
        textPaint.textSize = 22f
        textPaint.isFakeBoldText = true
        val truncatedRoad = if (state.navRoadName.length > 22) state.navRoadName.take(20) + "..." else state.navRoadName
        canvas.drawText(truncatedRoad, 155f, 136f, textPaint)

        // Instruksi Lanjutan
        textPaint.color = if (isNight) Color.rgb(148, 163, 184) else Color.rgb(100, 116, 139)
        textPaint.textSize = 15f
        textPaint.isFakeBoldText = false
        canvas.drawText(state.navInstruction, 55f, 185f, textPaint)

        // Route Progress Bar
        val progBarTotal = 495f
        fillPaint.color = if (isNight) Color.rgb(30, 41, 59) else Color.rgb(226, 232, 240)
        canvas.drawRoundRect(RectF(55f, 210f, 55f + progBarTotal, 218f), 4f, 4f, fillPaint)

        fillPaint.color = Color.rgb(16, 185, 129)
        val activeProg = progBarTotal * state.routeProgress.coerceIn(0f, 1f)
        canvas.drawRoundRect(RectF(55f, 210f, 55f + activeProg, 218f), 4f, 4f, fillPaint)

        // Trip ETA & Remaining Distance
        textPaint.color = if (isNight) Color.WHITE else Color.rgb(15, 23, 42)
        textPaint.textSize = 21f
        textPaint.isFakeBoldText = true
        canvas.drawText("ETA ${state.navEta}", 55f, 258f, textPaint)

        textPaint.color = if (isNight) Color.rgb(148, 163, 184) else Color.rgb(100, 116, 139)
        textPaint.textSize = 16f
        textPaint.isFakeBoldText = false
        canvas.drawText("Sisa ${state.navRemainingKm} • Estimasi Lancar", 55f, 288f, textPaint)

        // 3. Right Music Card (x: 585..755, y: 45..225) -> Aman dari Speedometer OSD di x>780
        fillPaint.color = if (isNight) Color.rgb(15, 23, 42) else Color.WHITE
        canvas.drawRoundRect(musicCardRect, 16f, 16f, fillPaint)

        strokePaint.color = if (isNight) Color.rgb(30, 41, 59) else Color.rgb(226, 232, 240)
        strokePaint.strokeWidth = 1.8f
        canvas.drawRoundRect(musicCardRect, 16f, 16f, strokePaint)

        // Music Provider Badge
        textPaint.color = Color.rgb(29, 185, 84) // Spotify Green
        textPaint.textSize = 12f
        textPaint.isFakeBoldText = true
        canvas.drawText("● ${state.musicSource}", 605f, 72f, textPaint)

        // Song Title
        textPaint.color = if (isNight) Color.WHITE else Color.rgb(15, 23, 42)
        textPaint.textSize = 15f
        textPaint.isFakeBoldText = true
        val truncatedSong = if (state.songTitle.length > 13) state.songTitle.take(11) + "..." else state.songTitle
        canvas.drawText(truncatedSong, 605f, 105f, textPaint)

        // Artist Name
        textPaint.color = if (isNight) Color.rgb(148, 163, 184) else Color.rgb(100, 116, 139)
        textPaint.textSize = 13f
        textPaint.isFakeBoldText = false
        val truncatedArtist = if (state.songArtist.length > 14) state.songArtist.take(12) + "..." else state.songArtist
        canvas.drawText(truncatedArtist, 605f, 128f, textPaint)

        // Animated Equalizer Frequency Bars
        val eqStartX = 605f
        val eqBaseY = 180f
        fillPaint.color = Color.rgb(0, 229, 255)
        for (i in eqBars.indices) {
            val barH = if (state.isMusicPlaying) eqBars[i].toFloat() else 4f
            canvas.drawRect(eqStartX + (i * 9), eqBaseY - barH, eqStartX + (i * 9) + 5, eqBaseY, fillPaint)
        }

        // Media Controls Indicator
        textPaint.color = if (isNight) Color.rgb(203, 213, 225) else Color.rgb(71, 85, 105)
        textPaint.textSize = 13f
        textPaint.isFakeBoldText = true
        canvas.drawText(if (state.isMusicPlaying) "⏸" else "▶", 695f, 175f, textPaint)

        // 4. Status & Weather Strip (Top Center: x: 230..470, y: 10..36)
        val statusRect = RectF(230f, 10f, 470f, 36f)
        fillPaint.color = if (isNight) Color.argb(220, 15, 23, 42) else Color.argb(230, 255, 255, 255)
        canvas.drawRoundRect(statusRect, 13f, 13f, fillPaint)

        strokePaint.color = if (isNight) Color.rgb(30, 41, 59) else Color.rgb(203, 213, 225)
        strokePaint.strokeWidth = 1f
        canvas.drawRoundRect(statusRect, 13f, 13f, strokePaint)

        textPaint.color = if (isNight) Color.rgb(56, 189, 248) else Color.rgb(2, 132, 199)
        textPaint.textSize = 11.5f
        textPaint.isFakeBoldText = true
        canvas.drawText("${state.weatherText} • ${state.wifiStatus}", 242f, 27f, textPaint)

        // Proteksi OSD cluster motor (0 km/h di kanan bawah & 0.0 kW di bawah)
        renderOsdSafetyVignette(canvas)
    }

    // =========================================================================
    // TEMA 2: SATELLITE TOURING (FULL SATELLITE BACKGROUND DENGAN GLASS CARD)
    // =========================================================================
    private fun renderSatelliteTouring(canvas: Canvas, isNight: Boolean) {
        if (state.liveMapBitmap != null) {
            canvas.drawBitmap(state.liveMapBitmap!!, 0f, 0f, null)
            canvas.drawColor(Color.argb(35, 5, 8, 15)) // Subtle contrast tint
            drawVehicleNavigationMarker(canvas, 300f, 210f, state.bearing)
        } else {
            // Dark Earth / Satellite terrain base
            canvas.drawColor(Color.rgb(28, 36, 25))

            // Road network simulation
            strokePaint.color = Color.rgb(71, 85, 105)
            strokePaint.strokeWidth = 14f
            strokePaint.strokeCap = Paint.Cap.ROUND
            val roadNetwork = Path().apply {
                moveTo(80f, 360f)
                lineTo(260f, 210f)
                lineTo(440f, 180f)
                lineTo(680f, 80f)
            }
            canvas.drawPath(roadNetwork, strokePaint)

            // Active Route Highlight
            strokePaint.color = Color.rgb(56, 189, 248)
            strokePaint.strokeWidth = 10f
            val activePath = Path().apply {
                moveTo(80f, 360f)
                lineTo(260f, 210f)
                lineTo(440f, 180f)
            }
            canvas.drawPath(activePath, strokePaint)
        }

        // Floating Frosted Glass Card Navigasi (x: 40..530, y: 50..180)
        fillPaint.color = Color.argb(235, 15, 23, 42)
        canvas.drawRoundRect(RectF(40f, 50f, 530f, 180f), 18f, 18f, fillPaint)
        strokePaint.color = Color.argb(100, 56, 189, 248)
        strokePaint.strokeWidth = 1.5f
        canvas.drawRoundRect(RectF(40f, 50f, 530f, 180f), 18f, 18f, strokePaint)

        // Maneuver badge
        fillPaint.color = Color.rgb(2, 132, 199)
        canvas.drawRoundRect(RectF(55f, 65f, 115f, 125f), 12f, 12f, fillPaint)
        drawManeuverArrow(canvas, 85f, 95f, state.navManeuver, Color.WHITE)

        textPaint.color = Color.WHITE
        textPaint.textSize = 34f
        textPaint.isFakeBoldText = true
        canvas.drawText(state.navDistance, 135f, 98f, textPaint)

        textPaint.color = Color.rgb(56, 189, 248)
        textPaint.textSize = 20f
        canvas.drawText(state.navRoadName, 135f, 132f, textPaint)

        textPaint.color = Color.rgb(148, 163, 184)
        textPaint.textSize = 14f
        textPaint.isFakeBoldText = false
        canvas.drawText(state.navInstruction, 55f, 162f, textPaint)

        // Bottom Trip Status Card (x: 40..380, y: 220..310)
        fillPaint.color = Color.argb(235, 15, 23, 42)
        canvas.drawRoundRect(RectF(40f, 215f, 380f, 310f), 16f, 16f, fillPaint)
        strokePaint.color = Color.argb(120, 30, 41, 59)
        canvas.drawRoundRect(RectF(40f, 215f, 380f, 310f), 16f, 16f, strokePaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = 24f
        textPaint.isFakeBoldText = true
        canvas.drawText("ETA ${state.navEta}", 60f, 255f, textPaint)

        textPaint.color = Color.rgb(148, 163, 184)
        textPaint.textSize = 15f
        textPaint.isFakeBoldText = false
        canvas.drawText("Sisa: ${state.navRemainingKm} • Rute Satelit", 60f, 288f, textPaint)

        // Proteksi OSD cluster motor
        renderOsdSafetyVignette(canvas)
    }

    // =========================================================================
    // TEMA 3: CYBERPUNK TACTICAL COCKPIT
    // =========================================================================
    private fun renderCyberpunkTactical(canvas: Canvas, isNight: Boolean) {
        canvas.drawColor(Color.rgb(5, 7, 13))

        // Futuristic Grid lines
        strokePaint.color = Color.argb(35, 0, 229, 255)
        strokePaint.strokeWidth = 1f
        canvas.drawLine(30f, 180f, 760f, 180f, strokePaint)
        canvas.drawLine(350f, 20f, 350f, 330f, strokePaint)

        // Tactical Left Nav Box
        fillPaint.color = Color.rgb(9, 14, 26)
        val leftBox = RectF(40f, 45f, 335f, 310f)
        canvas.drawRoundRect(leftBox, 12f, 12f, fillPaint)
        strokePaint.color = Color.rgb(0, 229, 255)
        strokePaint.strokeWidth = 1.5f
        canvas.drawRoundRect(leftBox, 12f, 12f, strokePaint)

        textPaint.color = Color.rgb(0, 229, 255)
        textPaint.textSize = 12f
        textPaint.isFakeBoldText = true
        canvas.drawText("[TACTICAL NAVIGATION]", 55f, 75f, textPaint)

        drawManeuverArrow(canvas, 90f, 125f, state.navManeuver, Color.rgb(0, 229, 255))
        textPaint.color = Color.WHITE
        textPaint.textSize = 34f
        canvas.drawText(state.navDistance, 140f, 130f, textPaint)

        textPaint.color = Color.rgb(245, 158, 11)
        textPaint.textSize = 18f
        canvas.drawText(state.navRoadName, 55f, 195f, textPaint)

        textPaint.color = Color.rgb(148, 163, 184)
        textPaint.textSize = 14f
        textPaint.isFakeBoldText = false
        canvas.drawText("ETA: ${state.navEta} | ${state.navRemainingKm}", 55f, 240f, textPaint)

        // Telemetry & Music Center Panel
        val rightBox = RectF(365f, 45f, 745f, 310f)
        fillPaint.color = Color.rgb(9, 14, 26)
        canvas.drawRoundRect(rightBox, 12f, 12f, fillPaint)
        strokePaint.color = Color.rgb(30, 41, 59)
        canvas.drawRoundRect(rightBox, 12f, 12f, strokePaint)

        textPaint.color = Color.rgb(16, 185, 129)
        textPaint.textSize = 12f
        textPaint.isFakeBoldText = true
        canvas.drawText("[MEDIA & TELEMETRY]", 385f, 75f, textPaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = 18f
        canvas.drawText("NOW PLAYING: ${state.songTitle}", 385f, 115f, textPaint)

        textPaint.color = Color.rgb(0, 229, 255)
        textPaint.textSize = 14f
        textPaint.isFakeBoldText = false
        canvas.drawText("ARTIST: ${state.songArtist}", 385f, 140f, textPaint)

        // Audio Frequency Visualizer
        fillPaint.color = Color.rgb(0, 229, 255)
        for (i in eqBars.indices) {
            val barH = eqBars[i] * 2.5f
            canvas.drawRect(385f + (i * 18), 240f - barH, 385f + (i * 18) + 10, 240f, fillPaint)
        }
    }

    // =========================================================================
    // TEMA 4: MINIMAL OLED STEALTH
    // =========================================================================
    private fun renderMinimalOled(canvas: Canvas, isNight: Boolean) {
        canvas.drawColor(Color.BLACK)

        drawManeuverArrow(canvas, 100f, 140f, state.navManeuver, Color.rgb(0, 229, 255))
        textPaint.color = Color.WHITE
        textPaint.textSize = 50f
        textPaint.isFakeBoldText = true
        canvas.drawText(state.navDistance, 170f, 155f, textPaint)

        textPaint.color = Color.rgb(148, 163, 184)
        textPaint.textSize = 22f
        canvas.drawText(state.navRoadName, 100f, 215f, textPaint)

        textPaint.color = Color.rgb(16, 185, 129)
        textPaint.textSize = 18f
        canvas.drawText("ETA ${state.navEta} • Sisa ${state.navRemainingKm}", 100f, 255f, textPaint)
    }

    // =========================================================================
    // NOTIFIKASI WHATSAPP / CALL POPUP BANNER (TOP CENTER)
    // =========================================================================
    private fun renderNotificationBanner(canvas: Canvas, isNight: Boolean) {
        // Banner card at top center
        fillPaint.color = Color.rgb(15, 23, 42)
        canvas.drawRoundRect(notifCardRect, 16f, 16f, fillPaint)

        strokePaint.color = Color.rgb(37, 211, 102) // WhatsApp Green
        strokePaint.strokeWidth = 2f
        canvas.drawRoundRect(notifCardRect, 16f, 16f, strokePaint)

        // WhatsApp Icon Badge
        fillPaint.color = Color.rgb(37, 211, 102)
        canvas.drawCircle(195f, 57f, 18f, fillPaint)
        textPaint.color = Color.WHITE
        textPaint.textSize = 18f
        textPaint.isFakeBoldText = true
        canvas.drawText("💬", 185f, 63f, textPaint)

        // Sender Name
        textPaint.color = Color.WHITE
        textPaint.textSize = 16f
        textPaint.isFakeBoldText = true
        canvas.drawText("${state.notificationSender} (${state.notificationApp})", 230f, 48f, textPaint)

        // Notification Text Snippet
        textPaint.color = Color.rgb(148, 163, 184)
        textPaint.textSize = 13.5f
        textPaint.isFakeBoldText = false
        val truncatedNotif = if (state.notificationText.length > 55) state.notificationText.take(52) + "..." else state.notificationText
        canvas.drawText(truncatedNotif, 230f, 73f, textPaint)
    }

    private fun drawManeuverArrow(canvas: Canvas, cx: Float, cy: Float, maneuver: NavManeuver, color: Int) {
        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 7f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val path = Path()
        when (maneuver) {
            NavManeuver.TURN_RIGHT -> {
                path.moveTo(cx - 15f, cy + 20f)
                path.lineTo(cx - 15f, cy)
                path.arcTo(RectF(cx - 15f, cy - 15f, cx + 15f, cy + 15f), 180f, 90f, false)
                path.lineTo(cx + 15f, cy - 15f)
                path.moveTo(cx + 5f, cy - 25f)
                path.lineTo(cx + 18f, cy - 15f)
                path.lineTo(cx + 5f, cy - 5f)
            }
            NavManeuver.TURN_LEFT -> {
                path.moveTo(cx + 15f, cy + 20f)
                path.lineTo(cx + 15f, cy)
                path.arcTo(RectF(cx - 15f, cy - 15f, cx + 15f, cy + 15f), 0f, -90f, false)
                path.lineTo(cx - 15f, cy - 15f)
                path.moveTo(cx - 5f, cy - 25f)
                path.lineTo(cx - 18f, cy - 15f)
                path.lineTo(cx - 5f, cy - 5f)
            }
            NavManeuver.U_TURN -> {
                path.moveTo(cx + 15f, cy + 20f)
                path.lineTo(cx + 15f, cy - 5f)
                path.arcTo(RectF(cx - 15f, cy - 20f, cx + 15f, cy + 10f), 0f, -180f, false)
                path.lineTo(cx - 15f, cy + 15f)
                path.moveTo(cx - 25f, cy + 5f)
                path.lineTo(cx - 15f, cy + 18f)
                path.lineTo(cx - 5f, cy + 5f)
            }
            NavManeuver.ROUNDABOUT -> {
                path.addCircle(cx, cy, 14f, Path.Direction.CW)
                path.moveTo(cx + 14f, cy)
                path.lineTo(cx + 22f, cy - 8f)
            }
            else -> { // STRAIGHT
                path.moveTo(cx, cy + 22f)
                path.lineTo(cx, cy - 22f)
                path.moveTo(cx - 14f, cy - 8f)
                path.lineTo(cx, cy - 22f)
                path.lineTo(cx + 14f, cy - 8f)
            }
        }
        canvas.drawPath(path, arrowPaint)
    }

    private fun drawVehicleNavigationMarker(canvas: Canvas, cx: Float, cy: Float, bearing: Float) {
        canvas.save()
        canvas.rotate(bearing, cx, cy)

        // Outer glow
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 0, 229, 255)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, 22f, glowPaint)

        // Vehicle Chevron / Arrow
        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 229, 255)
            style = Paint.Style.FILL
        }
        val path = Path().apply {
            moveTo(cx, cy - 18f) // Tip
            lineTo(cx + 12f, cy + 14f) // Right bottom
            lineTo(cx, cy + 6f) // Indent
            lineTo(cx - 12f, cy + 14f) // Left bottom
            close()
        }
        canvas.drawPath(path, arrowPaint)

        // White core highlight
        val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val corePath = Path().apply {
            moveTo(cx, cy - 14f)
            lineTo(cx + 6f, cy + 10f)
            lineTo(cx, cy + 5f)
            lineTo(cx - 6f, cy + 10f)
            close()
        }
        canvas.drawPath(corePath, corePaint)

        canvas.restore()
    }

    private fun renderOsdSafetyVignette(canvas: Canvas) {
        // 1. Right Bottom Speedometer Zone (x: 740..960, y: 190..360) untuk speed 0 km/h
        val speedMask = Paint().apply {
            shader = RadialGradient(
                870f, 300f, 140f,
                Color.argb(250, 5, 8, 15),
                Color.argb(0, 5, 8, 15),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(720f, 170f, 960f, 360f, speedMask)

        // 2. Bottom Power Bar Zone (y: 325..360) untuk daya 0.0 kW
        val powerMask = Paint().apply {
            shader = LinearGradient(
                480f, 315f, 480f, 360f,
                Color.argb(0, 5, 8, 15),
                Color.argb(250, 5, 8, 15),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 315f, 960f, 360f, powerMask)
    }
}
