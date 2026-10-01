package com.example.offlinedocumentcomposer.data.model

import android.graphics.PointF
import android.graphics.RectF

data class CropData(
    val originalRect: RectF = RectF(),
    val detectedCorners: List<PointF> = emptyList(),
    val manualCorners: List<PointF>? = null,
    val perspectiveTransformApplied: Boolean = false
) {
    fun getEffectiveCorners(): List<PointF> = manualCorners ?: detectedCorners
    fun copy(): CropData = CropData(
        originalRect = RectF(originalRect),
        detectedCorners = ArrayList(detectedCorners),
        manualCorners = manualCorners?.let { ArrayList(it) },
        perspectiveTransformApplied = perspectiveTransformApplied
    )
}
