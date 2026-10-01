package com.example.offlinedocumentcomposer

import android.graphics.Bitmap
import android.graphics.PointF
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*
import com.example.offlinedocumentcomposer.opencv.DocumentDetector

class DocumentDetectorTest {

    private lateinit var detector: DocumentDetector

    @Before
    fun setup() {
        detector = DocumentDetector()
    }

    @Test
    fun `test detection returns empty for blank image`() {
        val blankBitmap = mock(Bitmap::class.java)
        `when`(blankBitmap.width).thenReturn(100)
        `when`(blankBitmap.height).thenReturn(100)
        val result = detector.detect(blankBitmap)
        assertFalse(result.success)
        assertEquals(0f, result.confidence, 0.01f)
    }

    @Test
    fun `test detection returns valid result for rectangle`() {
        val bitmap = mock(Bitmap::class.java)
        `when`(bitmap.width).thenReturn(400)
        `when`(bitmap.height).thenReturn(400)
        val result = detector.detectOrDefault(bitmap)
        assertNotNull(result.corners)
        assertEquals(4, result.corners.size)
    }
}
