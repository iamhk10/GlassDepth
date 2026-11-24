package com.glassdepth.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp

/**
 * Layers, back to front:
 *   1. photo           (moves least)
 *   2. glass clock     (moves a bit more, shows a magnified blurred copy of the photo inside)
 *   3. subject cutout  (moves most, so the clock really sits behind it)
 *
 * Battery notes: nothing runs while the wallpaper is hidden, drawing is capped at ~30 fps,
 * and a frame is only drawn when the tilt changed or the minute changed.
 */
class DepthWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = DepthEngine()

    inner class DepthEngine : Engine(), SensorEventListener {

        // If the depth motion feels inverted on your phone, flip these to -1f.
        private val signX = 1f
        private val signY = 1f

        private val handler = Handler(Looper.getMainLooper())
        private var gyro: Sensor? = null
        private var visible = false

        private var w = 0
        private var h = 0

        private var bg: Bitmap? = null
        private var fg: Bitmap? = null
        private var blurred: Bitmap? = null
        private var loadedStamp = -1L

        private var clockOn = true
        private var clockY = 30
        private var parallax = 60

        // tilt state, all in -1..1
        private var angX = 0f
        private var angY = 0f
        private var lastNs = 0L
        private var targetX = 0f
        private var targetY = 0f
        private var curX = 0f
        private var curY = 0f
        private var lastDrawX = 999f
        private var lastDrawY = 999f
        private var lastMinute = -1L
        private var needsRedraw = true

        private var timeText = ""
        private var dateText = ""

        private val bgBase = RectF()
        private val lensRect = RectF()
        private val glassRect = RectF()
        private val glassPath = Path()
        private var glassRadius = 0f

        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val tintPaint = Paint().apply { color = Color.argb(46, 255, 255, 255) }
        private val sheenPaint = Paint()
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 255, 255)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }

        private val frame = object : Runnable {
            override fun run() {
                if (!visible) return
                stepAndDraw()
                handler.postDelayed(this, FRAME_MS)
            }
        }

        // ---------- lifecycle ----------

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            w = width
            h = height
            layout()
            needsRedraw = true
            if (visible) draw()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            handler.removeCallbacks(frame)
            if (visible) {
                reloadIfNeeded()
                refreshTexts(System.currentTimeMillis())
                startSensor()
                handler.post(frame)
            } else {
                stopSensor()
                lastNs = 0L
                angX = 0f
                angY = 0f
                targetX = 0f
                targetY = 0f
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(frame)
            stopSensor()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            visible = false
            handler.removeCallbacks(frame)
            stopSensor()
            super.onDestroy()
        }

        // ---------- data ----------

        private fun reloadIfNeeded() {
            val ctx = applicationContext
            clockOn = Prefs.clockOn(ctx)
            clockY = Prefs.clockY(ctx)
            parallax = Prefs.parallax(ctx)

            val stamp = WallpaperStore.stamp(ctx)
            if (stamp != loadedStamp || bg == null) {
                loadedStamp = stamp
                val b = WallpaperStore.loadBackground(ctx) ?: GlassRenderer.fallbackGradient(540, 1200)
                bg = b
                fg = WallpaperStore.loadForeground(ctx)
                blurred = GlassRenderer.blur(b, 70)
            }
            layout()
            needsRedraw = true
        }

        private fun refreshTexts(now: Long) {
            val is24 = android.text.format.DateFormat.is24HourFormat(this@DepthWallpaperService)
            val date = Date(now)
            timeText = SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.getDefault()).format(date)
            dateText = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(date)
            lastMinute = now / 60000L
        }

        // ---------- geometry ----------

        private fun layout() {
            if (w == 0 || h == 0) return
            val wf = w.toFloat()
            val hf = h.toFloat()

            // photo is drawn a bit larger than the screen so it can slide without showing edges
            bgBase.set(-0.05f * wf, -0.05f * hf, 1.05f * wf, 1.05f * hf)

            val gw = wf * 0.80f
            val gh = wf * 0.46f
            val cy = (hf * clockY / 100f).coerceIn(gh / 2f, hf - gh / 2f)
            glassRect.set((wf - gw) / 2f, cy - gh / 2f, (wf + gw) / 2f, cy + gh / 2f)
            glassRadius = gw * 0.10f
            glassPath.reset()
            glassPath.addRoundRect(glassRect, glassRadius, glassRadius, Path.Direction.CW)

            // lens: the blurred photo inside the glass is slightly magnified around its centre
            val gx = glassRect.centerX()
            val gy = glassRect.centerY()
            val lens = 1.06f
            lensRect.set(
                gx + (bgBase.left - gx) * lens,
                gy + (bgBase.top - gy) * lens,
                gx + (bgBase.right - gx) * lens,
                gy + (bgBase.bottom - gy) * lens
            )

            sheenPaint.shader = LinearGradient(
                glassRect.left, glassRect.top, glassRect.right, glassRect.bottom,
                intArrayOf(
                    Color.argb(90, 255, 255, 255),
                    Color.argb(0, 255, 255, 255),
                    Color.argb(26, 255, 255, 255)
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            rimPaint.strokeWidth = maxOf(2f, wf * 0.003f)
            rimPaint.shader = LinearGradient(
                glassRect.left, glassRect.top, glassRect.right, glassRect.bottom,
                intArrayOf(
                    Color.argb(190, 255, 255, 255),
                    Color.argb(26, 255, 255, 255),
                    Color.argb(110, 255, 255, 255)
                ),
                null,
                Shader.TileMode.CLAMP
            )
            timePaint.textSize = gh * 0.48f
            timePaint.setShadowLayer(gh * 0.04f, 0f, gh * 0.012f, Color.argb(70, 0, 0, 0))
            datePaint.textSize = gh * 0.11f
        }

        // ---------- sensor ----------

        private fun startSensor() {
            val sm = this@DepthWallpaperService.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            gyro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }

        private fun stopSensor() {
            val sm = this@DepthWallpaperService.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            sm.unregisterListener(this)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null) return
            val now = event.timestamp
            if (lastNs != 0L) {
                val dt = (now - lastNs) / 1_000_000_000f
                if (dt in 0f..0.2f) {
                    // Integrate angular speed, but let it fade back to centre (~1 s). This hides
                    // gyro drift and makes the image settle by itself when the phone is still.
                    val decay = exp(-dt / 0.9f)
                    angX = (angX + event.values[1] * dt) * decay
                    angY = (angY + event.values[0] * dt) * decay
                    targetX = (signX * angX / 0.35f).coerceIn(-1f, 1f)
                    targetY = (signY * angY / 0.35f).coerceIn(-1f, 1f)
                }
            }
            lastNs = now
        }

        // ---------- drawing ----------

        private fun stepAndDraw() {
            curX += (targetX - curX) * 0.25f
            curY += (targetY - curY) * 0.25f

            val minute = System.currentTimeMillis() / 60000L
            if (minute != lastMinute) {
                refreshTexts(System.currentTimeMillis())
                needsRedraw = true
            }
            val moved = abs(curX - lastDrawX) > 0.002f || abs(curY - lastDrawY) > 0.002f
            if (moved || needsRedraw) {
                draw()
                lastDrawX = curX
                lastDrawY = curY
                needsRedraw = false
            }
        }

        private fun draw() {
            if (w == 0 || h == 0) return
            val holder = surfaceHolder
            if (!holder.surface.isValid) return
            var locked: Canvas? = null
            try {
                locked = holder.lockHardwareCanvas()
            } catch (e: Exception) {
                // surface went away between the check and the lock; skip this frame
            }
            val canvas = locked ?: return
            try {
                paintFrame(canvas)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }

        private fun paintFrame(canvas: Canvas) {
            val photo = bg ?: return
            val shift = w * 0.056f * (parallax / 100f)
            val bgX = curX * shift * 0.25f
            val bgY = curY * shift * 0.25f
            val clockX = curX * shift * 0.60f
            val clockYOff = curY * shift * 0.60f
            val fgX = curX * shift
            val fgY = curY * shift

            canvas.drawColor(Color.BLACK)

            // 1) photo
            canvas.save()
            canvas.translate(bgX, bgY)
            canvas.drawBitmap(photo, null, bgBase, bitmapPaint)
            canvas.restore()

            // 2) glass clock
            if (clockOn) drawGlass(canvas, bgX, bgY, clockX, clockYOff)

            // 3) subject in front
            fg?.let {
                canvas.save()
                canvas.translate(fgX, fgY)
                canvas.drawBitmap(it, null, bgBase, bitmapPaint)
                canvas.restore()
            }
        }

        private fun drawGlass(canvas: Canvas, bgX: Float, bgY: Float, cx: Float, cy: Float) {
            val blur = blurred
            // glass body: clip to the rounded rect, then draw the photo copy the way the photo moves
            canvas.save()
            canvas.translate(cx, cy)
            canvas.clipPath(glassPath)
            if (blur != null) {
                canvas.translate(bgX - cx, bgY - cy)
                canvas.drawBitmap(blur, null, lensRect, bitmapPaint)
                canvas.translate(cx - bgX, cy - bgY)
            }
            canvas.drawRect(glassRect, tintPaint)
            canvas.drawRect(glassRect, sheenPaint)
            canvas.restore()

            // rim and text move with the glass
            canvas.save()
            canvas.translate(cx, cy)
            val inset = rimPaint.strokeWidth / 2f
            canvas.drawRoundRect(
                glassRect.left + inset, glassRect.top + inset,
                glassRect.right - inset, glassRect.bottom - inset,
                glassRadius - inset, glassRadius - inset, rimPaint
            )
            val fm = timePaint.fontMetrics
            val timeBaseline = glassRect.centerY() - (fm.ascent + fm.descent) / 2f - glassRect.height() * 0.08f
            canvas.drawText(timeText, glassRect.centerX(), timeBaseline, timePaint)
            canvas.drawText(dateText, glassRect.centerX(), glassRect.bottom - glassRect.height() * 0.16f, datePaint)
            canvas.restore()
        }
    }

    companion object {
        private const val FRAME_MS = 33L // about 30 fps
    }
}
