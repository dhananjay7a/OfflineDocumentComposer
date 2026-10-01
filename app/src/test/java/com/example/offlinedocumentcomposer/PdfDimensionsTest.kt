package com.example.offlinedocumentcomposer

import com.example.offlinedocumentcomposer.data.model.PageModel
import com.example.offlinedocumentcomposer.data.model.PageSize
import org.junit.Assert.*
import org.junit.Test

class PdfDimensionsTest {

    @Test
    fun `test A4 portrait dimensions`() {
        val page = PageModel(pageSize = PageSize.A4_PORTRAIT)
        assertEquals(210f, page.getWidthMm(), 0.1f)
        assertEquals(297f, page.getHeightMm(), 0.1f)
        assertFalse(page.isLandscape())
    }

    @Test
    fun `test A4 landscape dimensions`() {
        val page = PageModel(pageSize = PageSize.A4_LANDSCAPE)
        assertEquals(297f, page.getWidthMm(), 0.1f)
        assertEquals(210f, page.getHeightMm(), 0.1f)
        assertTrue(page.isLandscape())
    }

    @Test
    fun `test page width pixel calculation`() {
        val page = PageModel(pageSize = PageSize.A4_PORTRAIT)
        val widthPx = page.getPageWidthPx()
        assertEquals(210f * 3.779527559055118f, widthPx, 0.1f)
    }

    @Test
    fun `test default margin`() {
        val page = PageModel()
        assertEquals(10.5f, page.margin, 0.01f)
    }
}
