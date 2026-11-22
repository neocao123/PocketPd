package com.pocketpd.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * design 05 §4 CRT scanlines. Every mockup ends with one full-screen
 * `url(#scan)` rect painted over EVERYTHING, so the app does the same via
 * [overlay] on a non-interactive top view — chrome, BIOS field, canvas and
 * content all get the texture, and it intercepts no touches. The tile is
 * generated in code (the PNG route lost its alpha through AAPT2) at the
 * device's density: the mockup's 4px period / 2px black is drawn on a 2x
 * grid, i.e. 1dp black + 1dp clear.
 */
class ScanlineDrawable private constructor(private val baseColor: Int, tile: Bitmap) :
    Drawable() {

    private val lines = Paint().apply {
        shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    override fun draw(canvas: Canvas) {
        if (baseColor != 0) canvas.drawColor(baseColor)
        canvas.drawRect(bounds, lines)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        private var cachedTile: Bitmap? = null

        /** Black @25% for 1dp, clear for 1dp (mockup: 2 of every 4px at 2x;
         *  14% vanishes at 1dp pitch on 8-bit panels, so the dose is raised
         *  until the texture actually reads on device). */
        fun tile(density: Float): Bitmap {
            cachedTile?.let { return it }
            val dark = Math.round(density).coerceAtLeast(1)
            return Bitmap.createBitmap(1, dark * 2, Bitmap.Config.ARGB_8888)
                .apply { for (y in 0 until dark) setPixel(0, y, 0x40000000) }
                .also { cachedTile = it }
        }

        /** Full-screen CRT layer: no ground, just the stripes. */
        fun overlay(context: Context): ScanlineDrawable =
            ScanlineDrawable(0, tile(context.resources.displayMetrics.density))

        /** $1 title bar ground + the 2dp bright-blue underline (stripes come
         *  from the full-screen overlay). Deliberately matte — the user
         *  wants no glow on the titlebar. */
        fun titleBar(context: Context): Drawable =
            object : Drawable() {
                private val ground = Paint().apply { color = 0xFF0000A8.toInt() }
                private val underline = Paint().apply { color = 0xFF5454FC.toInt() }

                override fun draw(canvas: Canvas) {
                    canvas.drawRect(bounds, ground)
                    val h = Math.round(2f * context.resources.displayMetrics.density)
                        .coerceAtLeast(1)
                    canvas.drawRect(
                        bounds.left.toFloat(), (bounds.bottom - h).toFloat(),
                        bounds.right.toFloat(), bounds.bottom.toFloat(), underline,
                    )
                }

                override fun setAlpha(alpha: Int) {}
                override fun setColorFilter(colorFilter: ColorFilter?) {}
                @Deprecated("Deprecated in Java")
                override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
            }
    }
}
