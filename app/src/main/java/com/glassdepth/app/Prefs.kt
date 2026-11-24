package com.glassdepth.app

import android.appwidget.AppWidgetManager
import android.content.Context
import android.widget.SeekBar

/** Look of one glass widget. All values are plain ints so they are easy to store. */
data class GlassStyle(
    val blur: Int = 60,     // 0..100
    val tint: Int = 18,     // 0..60 (% white frost)
    val corner: Int = 28,   // dp
    val posX: Int = 50,     // 0..100, widget centre as % of screen width
    val posY: Int = 30      // 0..100, widget centre as % of screen height
)

object Prefs {
    private const val FILE = "glassdepth"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- widgets ----

    private fun defaultPosY(c: Context, id: Int): Int {
        val name = AppWidgetManager.getInstance(c).getAppWidgetInfo(id)?.provider?.className ?: ""
        return when {
            name.endsWith("ClockWidget") -> 22
            name.endsWith("DateWidget") -> 38
            name.endsWith("BatteryWidget") -> 52
            else -> 30
        }
    }

    fun widgetStyle(c: Context, id: Int): GlassStyle {
        val p = sp(c)
        val d = GlassStyle(posY = defaultPosY(c, id))
        return GlassStyle(
            blur = p.getInt("w${id}_blur", d.blur),
            tint = p.getInt("w${id}_tint", d.tint),
            corner = p.getInt("w${id}_corner", d.corner),
            posX = p.getInt("w${id}_x", d.posX),
            posY = p.getInt("w${id}_y", d.posY)
        )
    }

    fun saveWidgetStyle(c: Context, id: Int, s: GlassStyle) {
        sp(c).edit()
            .putInt("w${id}_blur", s.blur)
            .putInt("w${id}_tint", s.tint)
            .putInt("w${id}_corner", s.corner)
            .putInt("w${id}_x", s.posX)
            .putInt("w${id}_y", s.posY)
            .apply()
    }

    fun clearWidget(c: Context, id: Int) {
        sp(c).edit()
            .remove("w${id}_blur")
            .remove("w${id}_tint")
            .remove("w${id}_corner")
            .remove("w${id}_x")
            .remove("w${id}_y")
            .apply()
    }

    // ---- live wallpaper ----

    fun clockOn(c: Context): Boolean = sp(c).getBoolean("wp_clock_on", true)
    fun setClockOn(c: Context, v: Boolean) = sp(c).edit().putBoolean("wp_clock_on", v).apply()

    fun clockY(c: Context): Int = sp(c).getInt("wp_clock_y", 30)
    fun setClockY(c: Context, v: Int) = sp(c).edit().putInt("wp_clock_y", v).apply()

    fun parallax(c: Context): Int = sp(c).getInt("wp_parallax", 60)
    fun setParallax(c: Context, v: Int) = sp(c).edit().putInt("wp_parallax", v).apply()
}

/** Small helper so seek bar code stays readable. */
fun SeekBar.onProgress(block: (Int) -> Unit) {
    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            block(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    })
}
