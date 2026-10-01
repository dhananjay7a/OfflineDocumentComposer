package com.example.offlinedocumentcomposer.opencv

import android.graphics.Bitmap
import android.graphics.PointF
import com.example.offlinedocumentcomposer.domain.detector.DocumentDetector as DomainDetector

class DocumentDetector {

    private val delegate = DomainDetector()

    fun detect(bitmap: Bitmap): DetectionResult {
        val result = delegate.detect(bitmap)
        return DetectionResult(
            success = result.success,
            corners = result.corners,
            confidence = result.confidence,
            message = result.message
        )
    }

    fun getDefaultCorners(width: Int, height: Int): List<PointF> {
        return delegate.getDefaultCorners(width, height)
    }

    fun detectOrDefault(bitmap: Bitmap): DetectionResult {
        val result = delegate.detectOrDefault(bitmap)
        return DetectionResult(
            success = result.success,
            corners = result.corners,
            confidence = result.confidence,
            message = result.message
        )
    }
}
