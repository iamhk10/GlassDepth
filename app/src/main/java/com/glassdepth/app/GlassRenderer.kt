package com.glassdepth.app

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Real size of a widget on screen (span*) and the size of the bitmap we draw for it (out*).
 * The bitmap is capped so RemoteViews stays within the system's memory limit.
 */
data class WidgetSize(
    val spanW: Int,
    val spanH: Int,
    val outW: Int,
    val outH: Int,
    val wDp: Int,
    val hDp: Int
)

object GlassRenderer {

    private const val MAX_WIDGET_BITMAP_PX = 720f

    fun sizeFor(ctx: Context, id: Int): WidgetSize {
        val mgr = AppWidgetManager.getInstance(ctx)
        val o = mgr.getAppWidgetOptions(id)
        val wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).coerceAtLeast(40)
        val hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110).coerceAtLeast(40)
        val d = ctx.resources.displayMetrics.density
        val spanW = (wDp * d).roundToInt()
        val spanH = (hDp * d).roundToInt()
        val k = min(1f, MAX_WIDGET_BITMAP_PX / max(spanW, spanH).toFloat())
        return WidgetSize(
            spanW, spanH,
            max(2, (spanW * k).roundToInt()),
            max(2, (spanH * k).roundToInt()),
            wDp, hDp
        )
    }

    /**
     * Frosted glass panel for a widget:
     * blurred crop of the wallpaper at the widget's spot + frost + sheen + rim light.
     */
    fun glassCard(ctx: Context, size: WidgetSize, gs: GlassStyle): Bitmap {
        val w = size.outW
        val h = size.outH
        val density = ctx.resources.displayMetrics.density
        val k = w.toFloat() / size.spanW.toFloat()
        val radius = gs.corner * density * k
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())

        // 1) content layer (square corners)
        val content = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cc = Canvas(content)
        cc.drawBitmap(backdrop(ctx, size, gs), null, rect, Paint(Paint.FILTER_BITMAP_FLAG))

        val frost = Paint().apply {
            color = Color.argb((gs.tint * 255 / 100).coerceIn(0, 255), 255, 255, 255)
        }
        cc.drawRect(rect, frost)

        val sheen = Paint().apply {
            shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(
                    Color.argb(90, 255, 255, 255),
                    Color.argb(0, 255, 255, 255),
                    Color.argb(26, 255, 255, 255)
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        cc.drawRect(rect, sheen)

        // 2) round the corners with an anti-aliased mask
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val oc = Canvas(out)
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        oc.drawRoundRect(rect, radius, radius, maskPaint)
        maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        oc.drawBitmap(content, 0f, 0f, maskPaint)
        content.recycle()

        // 3) rim light on the edge
        val stroke = max(1f, 1.2f * density * k)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(
                    Color.argb(190, 255, 255, 255),
                    Color.argb(26, 255, 255, 255),
                    Color.argb(110, 255, 255, 255)
                ),
                null,
                Shader.TileMode.CLAMP
            )
        }
        val inset = stroke / 2f
        oc.drawRoundRect(
            RectF(inset, inset, w - inset, h - inset),
            max(0f, radius - inset), max(0f, radius - inset), rim
        )
        return out
    }

    /** Blurred piece of the wallpaper that sits behind this widget. */
    private fun backdrop(ctx: Context, size: WidgetSize, gs: GlassStyle): Bitmap {
        val dm = ctx.resources.displayMetrics
        val src = WallpaperStore.loadBackground(ctx)
        val base: Bitmap = if (src != null) {
            val scaleX = src.width.toFloat() / dm.widthPixels
            val scaleY = src.height.toFloat() / dm.heightPixels
            val cx = dm.widthPixels * gs.posX / 100f
            val cy = dm.heightPixels * gs.posY / 100f
            val cw = (size.spanW * scaleX).toInt().coerceIn(1, src.width)
            val ch = (size.spanH * scaleY).toInt().coerceIn(1, src.height)
            val left = ((cx - size.spanW / 2f) * scaleX).toInt().coerceIn(0, src.width - cw)
            val top = ((cy - size.spanH / 2f) * scaleY).toInt().coerceIn(0, src.height - ch)
            Bitmap.createBitmap(src, left, top, cw, ch)
        } else {
            fallbackGradient(size.outW, size.outH)
        }
        return blur(base, gs.blur)
    }

    fun fallbackGradient(w: Int, h: Int): Bitmap {
        val bw = max(1, w / 4)
        val bh = max(1, h / 4)
        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val p = Paint().apply {
            shader = LinearGradient(
                0f, 0f, bw.toFloat(), bh.toFloat(),
                Color.parseColor("#3B2A6B"), Color.parseColor("#0E7C86"),
                Shader.TileMode.CLAMP
            )
        }
        Canvas(b).drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), p)
        return b
    }

    // ---------------- blur ----------------

    /** strength 0..100. Shrinks the bitmap, then box-blurs it. Cheap and good enough for glass. */
    fun blur(src: Bitmap, strength: Int): Bitmap {
        val s = strength.coerceIn(0, 100)
        if (s == 0) return src
        val targetW = max(16, 240 - (240 - 24) * s / 100)
        val scale = targetW.toFloat() / src.width.toFloat()
        val sw = max(1, targetW)
        val sh = max(1, (src.height * scale).roundToInt())
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        return boxBlur(small, 2, 2)
    }

    private fun boxBlur(bmp: Bitmap, radius: Int, passes: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        bmp.getPixels(a, 0, w, 0, 0, w, h)
        var from = a
        var to = b
        repeat(passes) {
            blurPass(from, to, w, h, radius, true)
            blurPass(to, from, w, h, radius, false)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(from, 0, w, 0, 0, w, h)
        return out
    }

    private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val stride = if (horizontal) 1 else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            val base = if (horizontal) line * w else line
            var sa = 0
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val c = src[base + i.coerceIn(0, len - 1) * stride]
                sa += c ushr 24
                sr += (c shr 16) and 0xff
                sg += (c shr 8) and 0xff
                sb += c and 0xff
            }
            for (i in 0 until len) {
                dst[base + i * stride] =
                    ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = src[base + (i + r + 1).coerceIn(0, len - 1) * stride]
                val rem = src[base + (i - r).coerceIn(0, len - 1) * stride]
                sa += (add ushr 24) - (rem ushr 24)
                sr += ((add shr 16) and 0xff) - ((rem shr 16) and 0xff)
                sg += ((add shr 8) and 0xff) - ((rem shr 8) and 0xff)
                sb += (add and 0xff) - (rem and 0xff)
            }
        }
    }
}
