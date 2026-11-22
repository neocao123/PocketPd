package com.pocketpd.app.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.pocketpd.app.R

/**
 * Miniature stereo VU strip, top-left of the patch canvas — the left bookend
 * of the OSD row (EDIT/RUN badge sits top-right, same 13sp pixel type).
 * Ten segments per channel, each segment split into an upper (LEFT) and
 * lower (RIGHT) half; segments 1–6 signal cyan ($D), 7–8 warn yellow ($E),
 * 9–10 error red ($A). Ground + hard black outline keep the OSD language.
 *
 * Levels arrive from the session's dac~ taps ([env~] chains the app injects
 * at load) or from the patch itself via receivers `pocketpd-vu-l` /
 * `pocketpd-vu-r` / mono `pocketpd-vu` (0..1), with a short decay.
 */
class VuStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val d = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private val segments = 10
    private var levelL = 0f
    private var levelR = 0f

    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * d
        color = 0xFF04040C.toInt()
    }
    private val ground = Paint().apply { color = 0xFF04040C.toInt() }
    private val lit = Paint()
    private val dimFill = Paint().apply { color = 0xFF04040C.toInt() }
    private val dimEdge = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = d
        color = 0xFF2A2A5C.toInt()
    }
    private val labelFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFCFCFC.toInt()
        typeface = runCatching {
            androidx.core.content.res.ResourcesCompat.getFont(context, R.font.pd98_latin)
        }.getOrNull() ?: android.graphics.Typeface.MONOSPACE
    }
    private val labelOutline = Paint(labelFill).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * d
        color = 0xFF04040C.toInt()
    }

    private val decay = object : Runnable {
        override fun run() {
            if (levelL > 0.015f || levelR > 0.015f) {
                levelL *= 0.85f
                levelR *= 0.85f
                invalidate()
                postDelayed(this, 60)
            } else {
                levelL = 0f
                levelR = 0f
                invalidate()
            }
        }
    }

    /** l / r: 0..1 linear levels; peaks win over the decay. */
    fun setLevels(l: Float, r: Float) {
        levelL = l.coerceIn(0f, 1f).coerceAtLeast(levelL * 0.7f)
        levelR = r.coerceIn(0f, 1f).coerceAtLeast(levelR * 0.7f)
        invalidate()
        removeCallbacks(decay)
        postDelayed(decay, 150)
    }

    override fun onDraw(canvas: Canvas) {
        val inset = 2f * d
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), ground)
        val fl = inset + frame.strokeWidth / 2f
        canvas.drawRect(fl, fl, width - fl, height - fl, frame)

        // "VU" at the OSD badge's type size (13sp), outlined like the badge
        val px = 13f * sp
        labelFill.textSize = px
        labelOutline.textSize = px
        val fm = labelFill.fontMetrics
        val baseY = (height - (fm.ascent + fm.descent)) / 2f
        val tx = 4f * d
        canvas.drawText("VU", tx, baseY, labelOutline)
        canvas.drawText("VU", tx, baseY, labelFill)

        // segments: 10 columns × 2 half-rows (top LEFT, bottom RIGHT)
        val innerL = inset + frame.strokeWidth
        val innerT = innerL
        val innerB = height - innerL
        val innerR = width - innerL
        val segX0 = tx + labelFill.measureText("VU") + 4f * d
        val gap = d
        val segW = (innerR - segX0 - gap * (segments - 1)) / segments
        val rowH = (innerB - innerT - gap) / 2f
        val litL = Math.round(levelL * segments)
        val litR = Math.round(levelR * segments)
        for (i in 0 until segments) {
            val color = when {
                i < 6 -> 0xFF54FCFC.toInt()
                i < 8 -> 0xFFFCFC54.toInt()
                else -> 0xFFFC5454.toInt()
            }
            val x0 = segX0 + i * (segW + gap)
            val x1 = x0 + segW
            val rows = listOf(
                (innerT to innerT + rowH) to litL,
                (innerT + rowH + gap to innerB) to litR,
            )
            for ((yPair, litCount) in rows) {
                val (y0, y1) = yPair
                if (i < litCount) {
                    lit.color = color
                    canvas.drawRect(x0, y0, x1, y1, lit)
                } else {
                    canvas.drawRect(x0, y0, x1, y1, dimFill)
                    val e = dimEdge.strokeWidth / 2
                    canvas.drawRect(x0 + e, y0 + e, x1 - e, y1 - e, dimEdge)
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(decay)
        super.onDetachedFromWindow()
    }
}
