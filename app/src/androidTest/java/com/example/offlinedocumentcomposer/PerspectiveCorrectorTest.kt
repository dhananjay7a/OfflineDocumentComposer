package com.example.offlinedocumentcomposer

import android.graphics.Bitmap
import android.graphics.PointF
import org.junit.Assert.*
import org.junit.Test
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import com.example.offlinedocumentcomposer.opencv.PerspectiveCorrector

@RunWith(AndroidJUnit4::class)
class PerspectiveCorrectorTest {

    private val corrector = PerspectiveCorrector()

    @Test
    fun perspectiveCorrectionReturnsValidBitmap() {
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }

        val corners = listOf(
            PointF(50f, 50f),
            PointF(350f, 50f),
            PointF(350f, 350f),
            PointF(50f, 350f)
        )

        val result = corrector.correct(bitmap, corners)
        assertTrue(result.width > 100 && result.height > 100)
        assertEquals(android.graphics.Color.WHITE, result.getPixel(result.width / 2, result.height / 2))
        result.recycle(); bitmap.recycle()
    }

    @Test
    fun perspectiveCorrectionHandlesSkewedCorners() {
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }

        val corners = listOf(
            PointF(100f, 80f),
            PointF(320f, 60f),
            PointF(340f, 340f),
            PointF(60f, 320f)
        )

        val result = corrector.correct(bitmap, corners)
        assertTrue(result.width > 100 && result.height > 100)
        assertEquals(android.graphics.Color.WHITE, result.getPixel(result.width / 2, result.height / 2))
        result.recycle(); bitmap.recycle()
    }
}
