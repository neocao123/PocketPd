package com.pocketpd.app.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import com.pocketpd.app.R

/**
 * VHS OSD mode badge, top-right of the patch canvas (design 02/03):
 * pause/play glyph + EDIT / RUN at the design's 26px (= 13sp), white with a
 * 6px (= 3dp) hard black outline and red/cyan chroma ghosts. It IS the mode
 * switch: tapping it flips EDIT/RUN (the activity wires the click); no button
 * frame is drawn around it, like a camcorder overlay. Purely decorative
 * otherwise — never intercepts drags meant for the canvas edges.
 */
class VhsOsdView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    enum class Mode { EDIT, RUN, LOCK }

    var mode: Mode = Mode.EDIT
        set(value) {
            field = value
            text = when (value) {
                Mode.EDIT -> "EDIT"
                Mode.RUN -> "RUN"
                Mode.LOCK -> "LOCK"
            }
            contentDescription = when (value) {
                Mode.EDIT -> "EDIT — tap for RUN, hold for LOCK"
                Mode.RUN -> "RUN — tap for EDIT, hold for LOCK"
                Mode.LOCK -> "LOCK — hold for EDIT"
            }
            val c = when (value) {
                Mode.EDIT -> 0xFFFC54FC.toInt() // $B magenta = edit
                Mode.RUN -> 0xFF54FC54.toInt()  // $C green = run
                Mode.LOCK -> 0xFFFCFC54.toInt() // $E yellow = locked
            }
            fill.color = c
            glyphFill.color = c
            invalidate()
        }

    private val d = resources.displayMetrics.density

    /** Label ink follows the mode color (design §2: magenta = edit,
     *  green = run) so the badge and the toolbar state read at a glance;
     *  outline and ghosts stay as designed. */
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFC54FC.toInt()
        typeface = runCatching {
            androidx.core.content.res.ResourcesCompat.getFont(context, R.font.pd98_latin)
        }.getOrNull() ?: android.graphics.Typeface.MONOSPACE
    }
    private val outline = Paint(fill).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * d
        color = 0xFF04040C.toInt()
    }
    private val ghostRed = Paint(fill).apply { color = 0x70FC5454.toInt() }
    private val ghostCyan = Paint(fill).apply { color = 0x7054FCFC.toInt() }
    private val glyphFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFCFCFC.toInt() }
    private val glyphOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
        color = 0xFF04040C.toInt()
    }
    private val glyph = Path()

    override fun onDraw(canvas: Canvas) {
        // the view's textSize (13sp) drives every paint — the label is drawn
        // manually for the outline + ghost passes, not by super.onDraw
        val px = textSize
        for (p in arrayOf(fill, outline, ghostRed, ghostCyan)) p.textSize = px
        val baseY = (height - (fill.fontMetrics.ascent + fill.fontMetrics.descent)) / 2f
        val tx = paddingLeft.toFloat()
        val label = text.toString()
        // chroma ghosts first, then the black outline pass, then white fill
        canvas.drawText(label, tx - 1.2f * d, baseY, ghostRed)
        canvas.drawText(label, tx + 1.2f * d, baseY, ghostCyan)
        canvas.drawText(label, tx, baseY, outline)
        canvas.drawText(label, tx, baseY, fill)
        drawModeGlyph(canvas, 5f * d, height / 2f, px)
    }

    /** pause bars (EDIT), play triangle (RUN) or padlock (LOCK), sized off
     *  the label; the triangle overshoots the bars' box so both carry the
     *  same visual mass. */
    private fun drawModeGlyph(canvas: Canvas, cx: Float, cy: Float, px: Float) {
        val h = px * 0.32f
        if (mode == Mode.EDIT) {
            val w = px * 0.14f
            canvas.drawRect(cx - w * 2, cy - h, cx - w * 0.2f, cy + h, glyphFill)
            canvas.drawRect(cx + w * 0.2f, cy - h, cx + w * 2, cy + h, glyphFill)
        } else if (mode == Mode.RUN) {
            val h2 = px * 0.42f
            glyph.reset()
            glyph.moveTo(cx - px * 0.24f, cy - h2)
            glyph.lineTo(cx + px * 0.52f, cy)
            glyph.lineTo(cx - px * 0.24f, cy + h2)
            glyph.close()
            canvas.drawPath(glyph, glyphFill)
        } else {
            // LOCK: upward triangle — same mass and box as the RUN triangle
            val h2 = px * 0.42f
            glyph.reset()
            glyph.moveTo(cx, cy - h2)
            glyph.lineTo(cx - px * 0.38f, cy + h2)
            glyph.lineTo(cx + px * 0.38f, cy + h2)
            glyph.close()
            canvas.drawPath(glyph, glyphFill)
        }
    }
}
