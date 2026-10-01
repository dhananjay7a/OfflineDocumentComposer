package com.example.offlinedocumentcomposer.domain.layout

import com.example.offlinedocumentcomposer.data.model.ImageLayer
import com.example.offlinedocumentcomposer.data.model.PageModel

class SideBySideLayout {

    companion object {
        // True physical ID Card dimensions in px at standard screen/canvas scale (3.7795 px/mm)
        // 85.60 mm x 53.98 mm (ISO/IEC 7810 ID-1 standard for Aadhaar, PAN, DL, Voter ID)
        const val MM_TO_PX = 3.779527559055118f
        const val TRUE_CARD_WIDTH_PX = 85.60f * MM_TO_PX  // ~323.53 px
        const val TRUE_CARD_HEIGHT_PX = 53.98f * MM_TO_PX // ~204.02 px

        // Exact 5% margins per user instruction:
        // Left & right margin = 210mm * 0.05 = 10.50 mm (1.05 cm)
        // Top margin = 297mm * 0.05 = 14.85 mm (1.485 cm)
        const val MARGIN_5_PERCENT_MM = 10.50f
        const val TOP_MARGIN_MM = 14.85f // 5% of A4 height (297 mm)
        const val TOP_MARGIN_PX = TOP_MARGIN_MM * MM_TO_PX // ~56.13 px

        // Prominent enlarged ID Card for prominent A4 printing (per user instruction):
        // 92.00 mm x 58.01 mm (preserving the exact 1.5858 ID-1 aspect ratio).
        // Two cards: 92.0 * 2 + 5.0 (gap) = 189.0 mm
        // 210.0 mm - 189.0 mm = 21.0 mm total margin (10.5 mm left and 10.5 mm right = exact 5%!)
        const val ENLARGED_CARD_WIDTH_PX = 92.00f * MM_TO_PX  // ~347.72 px
        const val ENLARGED_CARD_HEIGHT_PX = 58.01f * MM_TO_PX // ~219.25 px
        const val CARD_SPACING_MM = 5.0f
        const val CARD_SPACING_PX = CARD_SPACING_MM * MM_TO_PX
    }

    /**
     * DeshKit layout: Place Front and Back side-by-side at enlarged size (92.0 x 58.0 mm)
     * near the top of the A4 page with exact 5% margins (10.5mm left/right, 14.85mm top).
     */
    fun arrangeIdCardSideBySideTop(images: List<ImageLayer>, page: PageModel) {
        if (images.isEmpty()) return
        val count = images.size

        val cardW = ENLARGED_CARD_WIDTH_PX
        val cardH = ENLARGED_CARD_HEIGHT_PX

        val totalBlockWidth = cardW * count + CARD_SPACING_PX * (count - 1)
        val startX = (page.getPageWidthPx() - totalBlockWidth) / 2f
        val startY = TOP_MARGIN_PX // 5% (14.85mm) from top

        var currX = startX
        for (image in images) {
            image.x = currX
            image.y = startY
            image.width = cardW
            image.height = cardH
            currX += cardW + CARD_SPACING_PX
        }
    }

    /**
     * Center Front & Back side-by-side on the page.
     */
    fun arrangeIdCardSideBySideCenter(images: List<ImageLayer>, page: PageModel) {
        if (images.isEmpty()) return
        val count = images.size
        val cardW = ENLARGED_CARD_WIDTH_PX
        val cardH = ENLARGED_CARD_HEIGHT_PX

        val totalBlockWidth = cardW * count + CARD_SPACING_PX * (count - 1)
        val startX = (page.getPageWidthPx() - totalBlockWidth) / 2f
        val startY = (page.getPageHeightPx() - cardH) / 2f

        var currX = startX
        for (image in images) {
            image.x = currX
            image.y = startY
            image.width = cardW
            image.height = cardH
            currX += cardW + CARD_SPACING_PX
        }
    }

    /**
     * Stack Front and Back vertically at enlarged ID card size with top margin = 5%.
     */
    fun arrangeIdCardsStacked(images: List<ImageLayer>, page: PageModel) {
        if (images.isEmpty()) return
        val spacingPx = 8.0f * MM_TO_PX
        val cardW = ENLARGED_CARD_WIDTH_PX
        val cardH = ENLARGED_CARD_HEIGHT_PX

        val startX = (page.getPageWidthPx() - cardW) / 2f
        val startY = TOP_MARGIN_PX // 5% (14.85mm) from top

        var currY = startY
        for (image in images) {
            image.x = startX
            image.y = currY
            image.width = cardW
            image.height = cardH
            currY += cardH + spacingPx
        }
    }

    /**
     * Legacy multi-image side-by-side arrangement.
     */
    fun arrangeSideBySide(images: List<ImageLayer>, page: PageModel) {
        if (images.isEmpty()) return
        val spacing = CARD_SPACING_PX
        val slot = ((page.getUsableWidthPx() - spacing * (images.size - 1)) / images.size).coerceAtLeast(1f)
        val maxHeight = page.getUsableHeightPx().coerceAtLeast(1f)
        images.forEachIndexed { index, image ->
            val ratio = image.getAspectRatio().coerceAtLeast(.01f)
            image.width = minOf(slot, maxHeight * ratio)
            image.height = image.width / ratio
            image.x = page.getLeftMarginPx() + index * (slot + spacing) + (slot - image.width) / 2
            image.y = page.getTopMarginPx()
        }
    }

    fun arrangeVertical(images: List<ImageLayer>, page: PageModel) {
        arrangeIdCardsStacked(images, page)
    }

    fun alignCenter(images: List<ImageLayer>, page: PageModel) {
        for (image in images) {
            image.x = (page.getPageWidthPx() - image.width) / 2f
            image.y = (page.getPageHeightPx() - image.height) / 2f
        }
    }
}
