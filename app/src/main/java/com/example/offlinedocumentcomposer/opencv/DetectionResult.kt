package com.example.offlinedocumentcomposer.opencv

import android.graphics.Bitmap
import android.graphics.PointF

data class DetectionResult(
    val success: Boolean,
    val corners: List<PointF>,
    val confidence: Float,
    val message: String = ""
) {
    companion object {
        const val CONFIDENCE_THRESHOLD = 0.50f
        val EMPTY = DetectionResult(false, emptyList(), 0f, "No detection")
    }
}
