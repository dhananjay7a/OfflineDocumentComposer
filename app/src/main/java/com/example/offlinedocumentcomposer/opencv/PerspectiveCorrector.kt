package com.example.offlinedocumentcomposer.opencv

import android.graphics.Bitmap
import android.graphics.PointF
import com.example.offlinedocumentcomposer.domain.image.PerspectiveCorrector as DomainCorrector

class PerspectiveCorrector {

    private val delegate = DomainCorrector()

    fun correct(bitmap: Bitmap, corners: List<PointF>): Bitmap {
        return delegate.correct(bitmap, corners)
    }

    fun correctPerspective(bitmap: Bitmap, corners: List<PointF>): Bitmap {
        return delegate.correct(bitmap, corners)
    }
}
