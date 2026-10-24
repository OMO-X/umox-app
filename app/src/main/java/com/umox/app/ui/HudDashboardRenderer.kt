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
    DESTINATION
}

data class HudState(
    var speedKmh: Int = 0,
    var maxSpeed: Int = 120,
    var gearMode: String = "D",
    var batteryPercent: Int = 85,
    var rangeKm: Int = 68,
    var isNightMode: Boolean = true,
    var navInstruction: String = "Lurus 500m",
    var navRoadName: String = "Jl. Jenderal Sudirman",
    var navDistance: String = "500 m",
    var navManeuver: NavManeuver = NavManeuver.STRAIGHT,
    var navEta: String = "14:30",
    var navRemainingKm: String = "4.2 km",
    var wifiStatus: String = "Wi-Fi: 192.168.2.1",
    var clusterMode: String = "NIGHT"
)

class HudDashboardRenderer(
    private val width: Int = 960,
    private val height: Int = 360
) {
    val state = HudState()

    // Pre-allocated Paint objects for 60fps zero-allocation rendering
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isSubpixelText = true }

    // Pre-allocated RectF objects
    private val fullScreenRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
    private val leftCardRect = RectF(20f, 20f, 310f, 340f)
    private val centerCardRect = RectF(330f, 15f, 630f, 345f)
    private val rightCardRect = RectF(650f, 20f, 940f, 340f)
    private val speedArcRect = RectF(380f, 40f, 580f, 240f)
    private val batteryBarRect = RectF(680f, 175f, 910f, 195f)

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEE, dd MMM", Locale.getDefault())

    fun render(canvas: Canvas) {
        val now = Date()
        val timeStr = timeFormat.format(now)
        val dateStr = dateFormat.format(now)

        val isNight = state.isNightMode

        // 1. Background
        if (isNight) {
            canvas.drawColor(Color.rgb(8, 12, 22))
        } else {
            canvas.drawColor(Color.rgb(240, 244, 250))
        }

        // 2. Render Cards
        renderNavCard(canvas, isNight)
        renderSpeedometerCard(canvas, isNight)
        renderTelemetryCard(canvas, isNight, timeStr, dateStr)

        // 3. Top Status Bar / Accent Strip
        renderStatusBar(canvas, isNight)
    }

    private fun renderNavCard(canvas: Canvas, isNight: Boolean) {
        // Card Background
        fillPaint.color = if (isNight) Color.rgb(15, 23, 42) else Color.WHITE
        canvas.drawRoundRect(leftCardRect, 18f, 18f, fillPaint)

        strokePaint.color = if (isNight) Color.rgb(30, 41, 59) else Color.rgb(226, 232, 240)
        strokePaint.strokeWidth = 2f
        canvas.drawRoundRect(leftCardRect, 18f, 18f, strokePaint)

        // Navigation Header Badge
        fillPaint.color = Color.rgb(16, 185, 129) // Emerald Green
        canvas.drawRoundRect(40f, 35f, 140f, 62f, 8f, 8f, fillPaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = 14f
        textPaint.isFakeBoldText = true
        canvas.drawText("NAVIGASI", 52f, 54f, textPaint)

        // Maneuver Arrow
        drawManeuverArrow(canvas, 90f, 130f, state.navManeuver, isNight)

        // Distance to Turn
        textPaint.color = if (isNight) Color.WHITE else Color.rgb(15, 23, 42)
        textPaint.textSize = 38f
        textPaint.isFakeBoldText = true
        canvas.drawText(state.navDistance, 145f, 125f, textPaint)

        textPaint.textSize = 15f
        textPaint.color = if (isNight) Color.rgb(148, 163, 184) else Color.rgb(100, 116, 139)
        textPaint.isFakeBoldText = false
        canvas.drawText(state.navInstruction, 145f, 148f, textPaint)

        // Road Name
        textPaint.color = if (isNight) Color.rgb(0, 229, 255) else Color.rgb(2, 132, 199)
        textPaint.textSize = 20f
        textPaint.isFakeBoldText = true
        val truncatedRoad = if (state.navRoadName.length > 22) state.navRoadName.take(20) + "..." else state.navRoadName
        canvas.drawText(truncatedRoad, 40f, 215f, textPaint)

        // Divider
        strokePaint.color = if (isNight) Color.rgb(30, 41, 59) else Color.rgb(226, 232, 240)
        canvas.drawLine(40f, 235f, 290f, 235f, strokePaint)

        // Trip ETA & Remaining Distance
        textPaint.color = if (isNight) Color.WHITE else Color.rgb(15, 23, 42)
        textPaint.textSize = 18f
        textPaint.isFakeBoldText = true
        canvas.drawText("ETA ${state.navEta}", 40f, 275f, textPaint)

        textPaint.color = if (isNight) Color.rgb(148, 163, 184) else Color.rgb(100, 116, 139)
        textPaint.textSize = 16f
        textPaint.isFakeBoldText = false
        canvas.drawText("Sisa: ${state.navRemainingKm}", 40f, 305f, textPaint)
    }

    private fun renderSpeedometerCard(canvas: Canvas, isNight: Boolean) {
        // Outer speed background arc (135° to 405° = 270° sweep)
        strokePaint.strokeWidth = 14f
        strokePaint.color = if (isNight) Color.rgb(30, 41, 59) else Color.rgb(226, 232, 240)
        strokePaint.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(speedArcRect, 135f, 270f, false, strokePaint)

        // Active speed arc (Neon Cyan / Green)
        val speedClamped = state.speedKmh.coerceIn(0, state.maxSpeed)
        val sweepAngle = (speedClamped.toFloat() / state.maxSpeed.toFloat()) * 270f
        if (sweepAngle > 0) {
            strokePaint.color = when {
                speedClamped > 80 -> Color.rgb(239, 68, 68) // Red warning
                speedClamped > 50 -> Color.rgb(245, 158, 11) // Amber
                else -> Color.rgb(0, 229, 255) // Cyan
            }
            canvas.drawArc(speedArcRect, 135f, sweepAngle, false, strokePaint)
        }

        // Giant Digital Speed
        val cx = centerCardRect.centerX()
        val cy = centerCardRect.centerY() - 10f

        textPaint.color = if (isNight) Color.WHITE else Color.rgb(15, 23, 42)
        textPaint.textSize = 82f
        textPaint.isFakeBoldText = true
        val speedStr = "${state.speedKmh}"
        val speedWidth = textPaint.measureText(speedStr)
        canvas.drawText(speedStr, cx - speedWidth / 2f, cy + 28f, textPaint)

        // KM/H Unit
        textPaint.color = if (isNight) Color.rgb(0, 229, 255) else Color.rgb(2, 132, 199)
        textPaint.textSize = 16f
        textPaint.isFakeBoldText = true
        val kmhStr = "KM/H"
        canvas.drawText(kmhStr, cx - textPaint.measureText(kmhStr) / 2f, cy + 58f, textPaint)

        // Gear Modes [ P ] [ R ] [ N ] [ D ] [ S ]
        val gears = listOf("P", "ECO", "D", "S")
        val gearStartY = cy + 110f
        val startX = cx - 110f
        val stepX = 55f

        for (i in gears.indices) {
            val g = gears[i]
            val gx = startX + (i * stepX)
            val isCurrent = g == state.gearMode

            if (isCurrent) {
                fillPaint.color = Color.rgb(0, 229, 255)
                canvas.drawRoundRect(gx - 18f, gearStartY - 18f, gx + 18f, gearStartY + 14f, 6f, 6f, fillPaint)
                textPaint.color = Color.BLACK
            } else {
                textPaint.color = if (isNight) Color.rgb(100, 116, 139) else Color.rgb(148, 163, 184)
            }
            textPaint.textSize = 16f
            textPaint.isFakeBoldText = true
            val tw = textPaint.measureText(g)
            canvas.drawText(g, gx - tw / 2f, gearStartY + 3f, textPaint)
        }
    }

    private fun renderTelemetryCard(canvas: Canvas, isNight: Boolean, timeStr: String, dateStr: String) {
        // Card Background
        fillPaint.color = if (isNight) Color.rgb(15, 23, 42) else Color.WHITE
        canvas.drawRoundRect(rightCardRect, 18f, 18f, fillPaint)

        strokePaint.color = if (isNight) Color.rgb(30, 41, 59) else Color.rgb(226, 232, 240)
        strokePaint.strokeWidth = 2f
        canvas.drawRoundRect(rightCardRect, 18f, 18f, strokePaint)

        // Digital Clock
        textPaint.color = if (isNight) Color.WHITE else Color.rgb(15, 23, 42)
        textPaint.textSize = 38f
        textPaint.isFakeBoldText = true
        canvas.drawText(timeStr, 680f, 75f, textPaint)

        textPaint.color = if (isNight) Color.rgb(148, 163, 184) else Color.rgb(100, 116, 139)
        textPaint.textSize = 15f
        textPaint.isFakeBoldText = false
        canvas.drawText(dateStr, 680f, 102f, textPaint)

        // Divider
        strokePaint.color = if (isNight) Color.rgb(30, 41, 59) else Color.rgb(226, 232, 240)
        canvas.drawLine(680f, 120f, 910f, 120f, strokePaint)

        // Battery Header
        textPaint.color = if (isNight) Color.WHITE else Color.rgb(15, 23, 42)
        textPaint.textSize = 18f
        textPaint.isFakeBoldText = true
        canvas.drawText("BATERAI", 680f, 155f, textPaint)

        val batColor = when {
            state.batteryPercent > 40 -> Color.rgb(16, 185, 129) // Green
            state.batteryPercent > 20 -> Color.rgb(245, 158, 11) // Amber
            else -> Color.rgb(239, 68, 68) // Red
        }
        textPaint.color = batColor
        val batStr = "${state.batteryPercent}%"
        val batWidth = textPaint.measureText(batStr)
        canvas.drawText(batStr, 910f - batWidth, 155f, textPaint)

        // Battery Bar Outline & Fill
        fillPaint.color = if (isNight) Color.rgb(30, 41, 59) else Color.rgb(226, 232, 240)
        canvas.drawRoundRect(batteryBarRect, 6f, 6f, fillPaint)

        val fillWidth = batteryBarRect.width() * (state.batteryPercent.coerceIn(0, 100) / 100f)
        if (fillWidth > 0) {
            fillPaint.color = batColor
            canvas.drawRoundRect(
                batteryBarRect.left, batteryBarRect.top,
                batteryBarRect.left + fillWidth, batteryBarRect.bottom,
                6f, 6f, fillPaint
            )
        }

        // Remaining Range
        textPaint.color = if (isNight) Color.WHITE else Color.rgb(15, 23, 42)
        textPaint.textSize = 18f
        textPaint.isFakeBoldText = true
        canvas.drawText("Jarak: ${state.rangeKm} km", 680f, 240f, textPaint)

        // Speedometer Link Status
        textPaint.color = Color.rgb(16, 185, 129)
        textPaint.textSize = 14f
        textPaint.isFakeBoldText = true
        canvas.drawText("● DESAY SV 9528 OK", 680f, 280f, textPaint)

        textPaint.color = if (isNight) Color.rgb(148, 163, 184) else Color.rgb(100, 116, 139)
        textPaint.textSize = 13f
        textPaint.isFakeBoldText = false
        canvas.drawText(state.wifiStatus, 680f, 305f, textPaint)
    }

    private fun renderStatusBar(canvas: Canvas, isNight: Boolean) {
        // Top glowing accent line
        fillPaint.color = Color.rgb(0, 229, 255)
        canvas.drawRect(0f, 0f, width.toFloat(), 3f, fillPaint)

        // Brand Title
        textPaint.color = if (isNight) Color.rgb(100, 116, 139) else Color.rgb(148, 163, 184)
        textPaint.textSize = 12f
        textPaint.isFakeBoldText = true
        canvas.drawText("UMOX ANDROID AUTO HUD", width / 2f - 75f, 15f, textPaint)
    }

    private fun drawManeuverArrow(canvas: Canvas, cx: Float, cy: Float, maneuver: NavManeuver, isNight: Boolean) {
        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(16, 185, 129)
            style = Paint.Style.STROKE
            strokeWidth = 8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val path = Path()
        when (maneuver) {
            NavManeuver.STRAIGHT -> {
                path.moveTo(cx, cy + 25f)
                path.lineTo(cx, cy - 25f)
                path.moveTo(cx - 15f, cy - 10f)
                path.lineTo(cx, cy - 25f)
                path.lineTo(cx + 15f, cy - 10f)
            }
            NavManeuver.TURN_LEFT -> {
                path.moveTo(cx + 15f, cy + 25f)
                path.lineTo(cx + 15f, cy)
                path.arcTo(RectF(cx - 15f, cy - 15f, cx + 15f, cy + 15f), 0f, -90f, false)
                path.lineTo(cx - 20f, cy - 15f)
                path.moveTo(cx - 10f, cy - 30f)
                path.lineTo(cx - 25f, cy - 15f)
                path.lineTo(cx - 10f, cy)
            }
            NavManeuver.TURN_RIGHT -> {
                path.moveTo(cx - 15f, cy + 25f)
                path.lineTo(cx - 15f, cy)
                path.arcTo(RectF(cx - 15f, cy - 15f, cx + 15f, cy + 15f), 180f, 90f, false)
                path.lineTo(cx + 20f, cy - 15f)
                path.moveTo(cx + 10f, cy - 30f)
                path.lineTo(cx + 25f, cy - 15f)
                path.lineTo(cx + 10f, cy)
            }
            else -> {
                path.moveTo(cx, cy + 25f)
                path.lineTo(cx, cy - 25f)
                path.moveTo(cx - 15f, cy - 10f)
                path.lineTo(cx, cy - 25f)
                path.lineTo(cx + 15f, cy - 10f)
            }
        }
        canvas.drawPath(path, arrowPaint)
    }
}
