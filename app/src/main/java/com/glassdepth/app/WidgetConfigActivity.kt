package com.glassdepth.app

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * Opens when a widget is added (and from "reconfigure"). The user tunes the glass and,
 * with X/Y, tells us where the widget sits on the home screen so the frosted crop lines up
 * with the wallpaper.
 */
class WidgetConfigActivity : AppCompatActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var preview: ImageView
    private lateinit var seekBlur: SeekBar
    private lateinit var seekTint: SeekBar
    private lateinit var seekCorner: SeekBar
    private lateinit var seekX: SeekBar
    private lateinit var seekY: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContentView(R.layout.activity_widget_config)
        preview = findViewById(R.id.cfg_preview)
        seekBlur = findViewById(R.id.seek_blur)
        seekTint = findViewById(R.id.seek_tint)
        seekCorner = findViewById(R.id.seek_corner)
        seekX = findViewById(R.id.seek_x)
        seekY = findViewById(R.id.seek_y)

        val s = Prefs.widgetStyle(this, widgetId)
        seekBlur.progress = s.blur
        seekTint.progress = s.tint
        seekCorner.progress = s.corner
        seekX.progress = s.posX
        seekY.progress = s.posY

        val refresh = { _: Int -> renderPreview() }
        seekBlur.onProgress(refresh)
        seekTint.onProgress(refresh)
        seekCorner.onProgress(refresh)
        seekX.onProgress(refresh)
        seekY.onProgress(refresh)

        findViewById<MaterialButton>(R.id.btn_save).setOnClickListener { save() }
        renderPreview()
    }

    private fun currentStyle() = GlassStyle(
        blur = seekBlur.progress,
        tint = seekTint.progress,
        corner = seekCorner.progress,
        posX = seekX.progress,
        posY = seekY.progress
    )

    private fun renderPreview() {
        val size = GlassRenderer.sizeFor(this, widgetId)
        preview.setImageBitmap(GlassRenderer.glassCard(this, size, currentStyle()))
    }

    private fun save() {
        Prefs.saveWidgetStyle(this, widgetId, currentStyle())

        // Ask the provider to redraw this widget (onUpdate is not sent when a configure screen exists)
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)
        if (info != null) {
            val update = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .setComponent(info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId))
            sendBroadcast(update)
        }

        setResult(
            RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        )
        finish()
    }
}
