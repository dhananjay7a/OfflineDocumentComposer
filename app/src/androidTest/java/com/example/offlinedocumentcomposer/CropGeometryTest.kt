package com.example.offlinedocumentcomposer

import android.graphics.PointF
import com.example.offlinedocumentcomposer.domain.detector.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class CropGeometryTest {
    private val quad = listOf(PointF(10f,20f),PointF(200f,10f),PointF(180f,140f),PointF(25f,160f))
    @Test fun acceptsPerspectiveAndBothWindingDirections() {
        assertTrue(CropGeometry.valid(quad,250,200))
        assertTrue(CropGeometry.valid(quad.reversed(),250,200))
    }
    @Test fun rejectsCrossedDuplicateOutsideAndNonFiniteCorners() {
        assertFalse(CropGeometry.valid(listOf(quad[0],quad[2],quad[1],quad[3]),250,200))
        assertFalse(CropGeometry.valid(listOf(quad[0],quad[0],quad[2],quad[3]),250,200))
        assertFalse(CropGeometry.valid(quad.dropLast(1)+PointF(-1f,100f),250,200))
        assertFalse(CropGeometry.valid(quad.dropLast(1)+PointF(Float.NaN,100f),250,200))
    }
    @Test fun fullImageCornersAreInsidePixelBounds() {
        assertTrue(CropGeometry.valid(DocumentDetector().getFullImageCorners(300,200),300,200))
    }
}
