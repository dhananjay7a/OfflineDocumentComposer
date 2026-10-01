package com.example.offlinedocumentcomposer

import android.graphics.PointF
import com.example.offlinedocumentcomposer.data.model.ImageLayer
import com.example.offlinedocumentcomposer.data.model.PageModel
import com.example.offlinedocumentcomposer.domain.layout.SideBySideLayout
import org.junit.Assert.*
import org.junit.Test

class SideBySideLayoutTest {

    private val layout = SideBySideLayout()

    @Test
    fun `test side by side arranges two images horizontally`() {
        val page = PageModel()
        val image1 = ImageLayer(width = 200f, height = 300f)
        val image2 = ImageLayer(width = 200f, height = 300f)

        layout.arrangeSideBySide(listOf(image1, image2), page)

        assertNotEquals(image1.x, image2.x, 0.01f)
        assertEquals(image1.y, image2.y, 0.01f)
    }

    @Test
    fun `test side by side preserves aspect ratio`() {
        val page = PageModel()
        val image1 = ImageLayer(width = 100f, height = 200f)
        val image2 = ImageLayer(width = 100f, height = 200f)

        layout.arrangeSideBySide(listOf(image1, image2), page)

        assertEquals(0.5f, image1.getAspectRatio(), 0.01f)
        assertEquals(0.5f, image2.getAspectRatio(), 0.01f)
    }
}
