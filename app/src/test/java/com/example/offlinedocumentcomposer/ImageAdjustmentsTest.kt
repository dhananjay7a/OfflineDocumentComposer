package com.example.offlinedocumentcomposer

import com.example.offlinedocumentcomposer.data.model.ImageAdjustments
import org.junit.Assert.*
import org.junit.Test

class ImageAdjustmentsTest {

    @Test
    fun `test default adjustments`() {
        val adjustments = ImageAdjustments()
        assertEquals(0f, adjustments.brightness, 0.01f)
        assertEquals(0f, adjustments.contrast, 0.01f)
        assertEquals(1f, adjustments.saturation, 0.01f)
    }

    @Test
    fun `test copy preserves values`() {
        val original = ImageAdjustments(brightness = 10f, contrast = 20f, saturation = 0.5f)
        val copy = original.copy()
        assertEquals(original.brightness, copy.brightness, 0.01f)
        assertEquals(original.contrast, copy.contrast, 0.01f)
        assertEquals(original.saturation, copy.saturation, 0.01f)
    }

    @Test
    fun `test reset returns defaults`() {
        val adjustments = ImageAdjustments(brightness = 50f, contrast = -30f, saturation = 0f)
        val reset = adjustments.reset()
        assertEquals(0f, reset.brightness, 0.01f)
        assertEquals(0f, reset.contrast, 0.01f)
        assertEquals(1f, reset.saturation, 0.01f)
    }
}
