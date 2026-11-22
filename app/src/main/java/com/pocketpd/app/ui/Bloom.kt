package com.pocketpd.app.ui

import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.view.View
import com.pocketpd.app.R

/**
 * CRT 荧光辉光 — the standard full-screen recipe, per the established
 * Android approach (RenderEffect / RenderNode, see the official
 * "Blurring the Lines" androiddev article and the RealtimeBlurView line
 * of libraries): draw the frame ONCE, let the PLATFORM blur the whole
 * frame on the GPU, blend the blurred copy back on top.
 *
 * Per frame, inside the root's dispatchDraw:
 *
 *   1. children render once into a RenderNode (the frame copy);
 *   2. the node draws to the canvas — sharp content, native resolution;
 *   3. a second node wrapping the first carries RenderEffect blur; it is
 *      drawn through a saveLayer whose paint SCREEN-blends (result ≥
 *      max(dst, src) — the pass can only add light) at ~50% alpha;
 *   4. the scanline overlay draws last (glow under the CRT grille).
 *
 * Everything derives from the SAME single render of the CURRENT frame, so
 * the glow always follows motion exactly — no capture latency, no ghost
 * copies, and the platform gaussian is uniform across the screen (no
 * faceting, no per-control logic, no idle cost: nothing runs when the
 * screen isn't redrawing).
 *
 * API 31+ takes the RenderEffect path. Older devices fall back to a
 * single-layer down/up-sample chain (the "blur at reduced resolution and
 * scale back" method) — smooth, but softer than the GPU gaussian.
 * A settings toggle (Settings.bloomEnabled → Bloom.enabled) turns the
 * whole pass into a plain child draw.
 */
object Bloom {
    /**
     * Glow strength 0..1 (mirrors Settings.bloomStrength); 0 disables the
     * pass entirely. Read every frame, so volatile.
     */
    @Volatile
    var strength: Float = 1f
}

/** Bitmap/RenderNode bookkeeping + compositing shared by both roots. */
class BloomPass {

    private val glowPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix().apply { setScale(1.8f, 1.8f, 1.8f, 1f) }
        )
    }

    // pre-31 fallback: quarter (1/4) frame copy, mid (1/16) glow layer
    private var quarter: android.graphics.Bitmap? = null
    private var mid: android.graphics.Bitmap? = null
    private val work = Canvas()
    private val bounds = android.graphics.RectF()

    fun render(canvas: Canvas, w: Int, h: Int, children: (Canvas) -> Unit) {
        // content must NEVER depend on the toggle: with the glow off (or on
        // a software canvas) this is a plain child draw — returning without
        // drawing here left black screens that nothing ever invalidated
        if (Bloom.strength <= 0f || !canvas.isHardwareAccelerated) {
            children(canvas)
            return
        }
        if (w < 8 || h < 8) {
            children(canvas)
            return
        }
        when {
            Build.VERSION.SDK_INT >= 31 -> BloomGlow31.draw(
                canvas, w, h, children, glowPaint, glowAlpha()
            )
            Build.VERSION.SDK_INT >= 28 -> chainPath(canvas, w, h, children)
            else -> children(canvas)
        }
    }

    /** API 31+ platform blur lives here so pre-31 verifiers never see it. */
    private object BloomGlow31 {
        private var src: RenderNode? = null
        private var blur: RenderNode? = null

        fun draw(
            canvas: Canvas, w: Int, h: Int,
            children: (Canvas) -> Unit, glowPaint: Paint, alpha: Int,
        ) {
            val a = src ?: RenderNode("bloom-frame").also { src = it }
            val b = blur ?: RenderNode("bloom-blur").also { blur = it }
            a.setPosition(0, 0, w, h)
            val sc = a.beginRecording(w, h)
            children(sc)
            a.endRecording()
            b.setPosition(0, 0, w, h)
            val bc = b.beginRecording(w, h)
            bc.drawRenderNode(a)
            b.endRecording()
            b.setRenderEffect(
                RenderEffect.createBlurEffect(
                    BLUR_RADIUS_PX, BLUR_RADIUS_PX, Shader.TileMode.CLAMP
                )
            )
            // sharp frame, then the platform-blurred copy screen-blended on top
            canvas.drawRenderNode(a)
            glowPaint.alpha = alpha
            canvas.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), glowPaint)
            canvas.drawRenderNode(b)
            canvas.restore()
        }
    }

    /** Pre-31: single-layer down/up-sample blur (smooth, softer edge). */
    private fun chainPath(canvas: Canvas, w: Int, h: Int, children: (Canvas) -> Unit) {
        val qw = (w / 4).coerceAtLeast(8)
        val qh = (h / 4).coerceAtLeast(8)
        val mw = (qw / 4).coerceAtLeast(4)
        val mh = (qh / 4).coerceAtLeast(4)
        val q = ensure(quarter, qw, qh).also { quarter = it }
        val m = ensure(mid, mw, mh).also { mid = it }
        val sc = work
        sc.setBitmap(q)
        q.eraseColor(android.graphics.Color.TRANSPARENT)
        sc.setMatrix(android.graphics.Matrix())
        sc.scale(1f / 4, 1f / 4)
        children(sc)
        sc.setBitmap(null)
        // quarter -> mid: the bilinear resample is the blur
        m.eraseColor(android.graphics.Color.TRANSPARENT)
        work.setBitmap(m)
        work.setMatrix(android.graphics.Matrix())
        work.scale(mw.toFloat() / qw, mh.toFloat() / qh)
        work.drawBitmap(q, 0f, 0f, glowPaint.also { it.alpha = 255 })
        work.setBitmap(null)
        children(canvas)
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
        glowPaint.alpha = glowAlpha()
        canvas.drawBitmap(m, null, bounds, glowPaint)
    }

    private fun glowAlpha(): Int =
        (GLOW_ALPHA * Bloom.strength).toInt().coerceIn(1, 255)

    private fun ensure(
        b: android.graphics.Bitmap?, w: Int, h: Int,
    ): android.graphics.Bitmap {
        if (b != null && b.width == w && b.height == h) return b
        return android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    }

    companion object {
        /** Glow strength at 100%; scaled by Bloom.strength at composite. */
        private const val GLOW_ALPHA = 130 // ≈51% screen-blend strength
        private const val BLUR_RADIUS_PX = 22f
    }
}

/** CoordinatorLayout root whose children glow (browser, editor). */
class BloomCoordinatorLayout @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null,
) : androidx.coordinatorlayout.widget.CoordinatorLayout(context, attrs) {

    private val pass = BloomPass()
    private var scan: View? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scan = findViewById(R.id.scan_overlay)
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        // any descendant redraw changes the frame the glow derives from —
        // hardware rendering re-records only the child's display list, so
        // the glow layer would otherwise stay stale (slider live-update bug).
        // onDescendantInvalidated is the hardware-path hook;
        // invalidateChildInParent never fires there.
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val scan = this.scan
        pass.render(canvas, width, height) { sc ->
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (c !== scan) drawChild(sc, c, drawingTime)
            }
        }
        // the grille goes on last: glow under scanlines, drawn exactly once
        scan?.draw(canvas)
    }
}

/** FrameLayout root whose children glow (settings / BIOS page). */
class BloomFrameLayout @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null,
) : android.widget.FrameLayout(context, attrs) {

    private val pass = BloomPass()
    private var scan: View? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scan = findViewById(R.id.scan_overlay)
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        invalidate() // keep the derived glow layer in sync (see Coordinator root)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val scan = this.scan
        pass.render(canvas, width, height) { sc ->
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (c !== scan) drawChild(sc, c, drawingTime)
            }
        }
        scan?.draw(canvas)
    }
}
