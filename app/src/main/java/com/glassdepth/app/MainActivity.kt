package com.glassdepth.app

import android.app.WallpaperManager
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var preview: ImageView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnPick: MaterialButton

    private val picker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) process(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        btnPick = findViewById(R.id.btn_pick)

        btnPick.setOnClickListener {
            picker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        val switchClock = findViewById<MaterialSwitch>(R.id.switch_clock)
        val seekY = findViewById<SeekBar>(R.id.seek_clock_y)
        val seekParallax = findViewById<SeekBar>(R.id.seek_parallax)
        switchClock.isChecked = Prefs.clockOn(this)
        seekY.progress = Prefs.clockY(this)
        seekParallax.progress = Prefs.parallax(this)
        switchClock.setOnCheckedChangeListener { _, checked -> Prefs.setClockOn(this, checked) }
        seekY.onProgress { Prefs.setClockY(this, it) }
        seekParallax.onProgress { Prefs.setParallax(this, it) }

        findViewById<MaterialButton>(R.id.btn_apply).setOnClickListener { openWallpaperPicker() }
        findViewById<MaterialButton>(R.id.btn_clock).setOnClickListener { pin(ClockWidget::class.java) }
        findViewById<MaterialButton>(R.id.btn_date).setOnClickListener { pin(DateWidget::class.java) }
        findViewById<MaterialButton>(R.id.btn_battery).setOnClickListener { pin(BatteryWidget::class.java) }
    }

    override fun onResume() {
        super.onResume()
        refreshPreview()
    }

    private fun refreshPreview() {
        if (WallpaperStore.hasWallpaper(this)) {
            preview.setImageBitmap(WallpaperStore.loadBackground(this, 4))
            status.setText(
                if (WallpaperStore.hasCutout(this)) R.string.status_ready_depth
                else R.string.status_ready_flat
            )
        } else {
            preview.setImageDrawable(null)
            status.setText(R.string.status_idle)
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        btnPick.isEnabled = !busy
        if (busy) status.setText(R.string.status_processing)
    }

    /** decode -> cut out subject -> save -> refresh widgets */
    private fun process(uri: Uri) {
        setBusy(true)
        Thread {
            val bg = try {
                WallpaperStore.decodeAndFit(this, uri)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (bg == null) {
                    setBusy(false)
                    status.setText(R.string.status_failed)
                    return@runOnUiThread
                }
                SubjectCutout.extract(bg) { fg ->
                    Thread {
                        WallpaperStore.save(this, bg, fg)
                        runOnUiThread {
                            setBusy(false)
                            refreshPreview()
                            WidgetUpdater.updateAll(this)
                        }
                    }.start()
                }
            }
        }.start()
    }

    private fun openWallpaperPicker() {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, DepthWallpaperService::class.java)
        )
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(this, R.string.pin_unsupported, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun pin(cls: Class<*>) {
        val mgr = AppWidgetManager.getInstance(this)
        if (mgr.isRequestPinAppWidgetSupported) {
            mgr.requestPinAppWidget(ComponentName(this, cls), null, null)
        } else {
            Toast.makeText(this, R.string.pin_unsupported, Toast.LENGTH_LONG).show()
        }
    }
}
