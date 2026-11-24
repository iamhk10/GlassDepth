package com.glassdepth.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Keeps the processed wallpaper inside the app's private storage:
 *  - bg.jpg : the photo, already cropped to the screen's aspect ratio
 *  - fg.png : the transparent subject cutout (optional)
 * The live wallpaper and the widgets both read from here, so they always match.
 */
object WallpaperStore {

    private fun bgFile(c: Context) = File(c.filesDir, "bg.jpg")
    private fun fgFile(c: Context) = File(c.filesDir, "fg.png")

    fun hasWallpaper(c: Context): Boolean = bgFile(c).exists()
    fun hasCutout(c: Context): Boolean = fgFile(c).exists()

    /** Changes whenever a new photo is saved, so readers know when to reload. */
    fun stamp(c: Context): Long = bgFile(c).lastModified() + fgFile(c).lastModified()

    fun loadBackground(c: Context, sample: Int = 1): Bitmap? = decode(bgFile(c), sample)
    fun loadForeground(c: Context, sample: Int = 1): Bitmap? = decode(fgFile(c), sample)

    private fun decode(f: File, sample: Int): Bitmap? {
        if (!f.exists()) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(f.absolutePath, opts)
    }

    fun save(c: Context, bg: Bitmap, fg: Bitmap?) {
        FileOutputStream(bgFile(c)).use { bg.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        if (fg != null) {
            FileOutputStream(fgFile(c)).use { fg.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } else {
            fgFile(c).delete()
        }
    }

    /**
     * Reads the picked photo, shrinks it (long side max 1600 px) and centre-crops it
     * to the screen's aspect ratio. Call from a background thread.
     */
    fun decodeAndFit(c: Context, uri: Uri): Bitmap {
        val dm = c.resources.displayMetrics
        val aspect = dm.widthPixels.toFloat() / dm.heightPixels.toFloat()

        val source = ImageDecoder.createSource(c.contentResolver, uri)
        val raw = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val scale = minOf(1f, 1600f / maxOf(w, h).toFloat())
            decoder.setTargetSize(
                (w * scale).toInt().coerceAtLeast(1),
                (h * scale).toInt().coerceAtLeast(1)
            )
        }

        val rw = raw.width
        val rh = raw.height
        var cw = rw
        var ch = (rw / aspect).toInt()
        if (ch > rh) {
            ch = rh
            cw = (rh * aspect).toInt()
        }
        cw = cw.coerceIn(1, rw)
        ch = ch.coerceIn(1, rh)
        val x = (rw - cw) / 2
        val y = (rh - ch) / 2
        return Bitmap.createBitmap(raw, x, y, cw, ch)
    }
}
