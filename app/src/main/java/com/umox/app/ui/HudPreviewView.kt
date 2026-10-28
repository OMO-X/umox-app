package com.umox.app.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View

/**
 * Custom View untuk menampilkan Live Preview dari antarmuka HUD Cockpit 960x360
 * langsung di layar smartphone sebelum atau selama dipancarkan ke speedometer motor.
 */
class HudPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var renderer: HudDashboardRenderer? = null
        set(value) {
            field = value
            invalidate()
        }

    init {
        // Refresh timer 30 FPS untuk animasi jam dan jarum speedometer
        post(object : Runnable {
            override fun run() {
                if (visibility == VISIBLE && isAttachedToWindow) {
                    invalidate()
                }
                postDelayed(this, 33L)
            }
        })
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // Rasio aspek HUD cluster Desay SV: 960 x 360 (8 : 3 / 2.667)
        val desiredHeight = (width / (960f / 360f)).toInt()
        setMeasuredDimension(width, desiredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = renderer ?: return

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val scaleX = viewW / 960f
        val scaleY = viewH / 360f

        val saveCount = canvas.save()
        canvas.scale(scaleX, scaleY)
        r.render(canvas)
        canvas.restoreToCount(saveCount)
    }
}
