package com.glassdepth.app

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions

/** On-device subject cutout using ML Kit. Nothing leaves the phone. */
object SubjectCutout {

    /**
     * Calls [onDone] on the main thread with a transparent bitmap of the same size as [source]
     * that contains only the subject, or null if nothing was found or the model is not ready.
     */
    fun extract(source: Bitmap, onDone: (Bitmap?) -> Unit) {
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundBitmap()
            .build()
        val segmenter = SubjectSegmentation.getClient(options)
        try {
            segmenter.process(InputImage.fromBitmap(source, 0))
                .addOnSuccessListener { result ->
                    val fg = result.foregroundBitmap
                    segmenter.close()
                    onDone(fg)
                }
                .addOnFailureListener {
                    segmenter.close()
                    onDone(null)
                }
        } catch (e: Exception) {
            segmenter.close()
            onDone(null)
        }
    }
}
