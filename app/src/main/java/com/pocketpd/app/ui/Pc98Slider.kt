package com.pocketpd.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * PC-98 horizontal slider (design = the canvas hsl drawing): field-dark
 * track with an edge stroke, signal-cyan fill from the left, white thumb
 * bar at the fill edge. Progress 0..100; drags report continuously.
 */
class Pc98Slider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var progress = 100
        private set

    /** Reports every drag step and the final value on release. */
    var onProgressChange: ((Int) -> Unit)? = null

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFA8A8C0.toInt() // pd98 edge
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF04040C.toInt() // pd98 field
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF54FCFC.toInt() // pd98 signal
    }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFCFCFC.toInt() // pd98 ink
    }

    fun setProgress(v: Int, report: Boolean = false) {
        progress = v.coerceIn(0, 100)
        invalidate()
        if (report) onProgressChange?.invoke(progress)
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val inset = 2f * d
        val l = inset; val t = inset
        val r = width - inset; val b = height - inset
        canvas.drawRect(l, t, r, b, track)
        canvas.drawRect(l, t, r, b, stroke)
        val frac = progress / 100f
        val fw = (r - l - 2f * d) * frac
        if (fw > 0f) {
            canvas.drawRect(l + d, t + 2f * d, l + d + fw, b - 2f * d, fill)
        }
        val tx = l + d + fw
        canvas.drawRect(tx - 2f * d, t + d, tx + 2f * d, b - d, thumb)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                updateAt(e.x, report = true)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                updateAt(e.x, report = true)
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun updateAt(x: Float, report: Boolean) {
        val d = resources.displayMetrics.density
        val usable = (width - 2f * d).coerceAtLeast(1f)
        setProgress(((x - d) / usable * 100).toInt(), report)
    }
}
