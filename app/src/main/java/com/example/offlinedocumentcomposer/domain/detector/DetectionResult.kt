package com.example.offlinedocumentcomposer.domain.detector

import android.graphics.PointF

data class DetectionResult(
    val success: Boolean,
    val corners: List<PointF>,
    val confidence: Float,
    val message: String = "",
    val status: DetectionStatus = if (success) DetectionStatus.DETECTED else DetectionStatus.NEEDS_ADJUSTMENT
) {
    companion object {
        const val CONFIDENCE_THRESHOLD = 0.50f
        val EMPTY = DetectionResult(false, emptyList(), 0f, "No detection")
    }
}
