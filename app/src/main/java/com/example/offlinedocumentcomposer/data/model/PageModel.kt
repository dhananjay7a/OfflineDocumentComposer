package com.example.offlinedocumentcomposer.data.model

import android.graphics.PointF

data class PageModel(
    val id: String = java.util.UUID.randomUUID().toString(),
    val pageSize: PageSize = PageSize.A4_PORTRAIT,
    val margin: Float = 10.50f, // 5% of 210 mm A4 width
    val imageLayers: MutableList<ImageLayer> = mutableListOf(),
    val backgroundColor: Int = android.graphics.Color.WHITE
) {
    fun getPageWidthPx(): Float = getWidthMm() * 3.779527559055118f
    fun getPageHeightPx(): Float = getHeightMm() * 3.779527559055118f
    fun getWidthMm(): Float = when (pageSize) {
        PageSize.A4_PORTRAIT -> 210f
        PageSize.A4_LANDSCAPE -> 297f
        PageSize.LETTER_PORTRAIT -> 215.9f
        PageSize.LETTER_LANDSCAPE -> 279.4f
        PageSize.CUSTOM -> 210f
    }
    fun getHeightMm(): Float = when (pageSize) {
        PageSize.A4_PORTRAIT -> 297f
        PageSize.A4_LANDSCAPE -> 210f
        PageSize.LETTER_PORTRAIT -> 279.4f
        PageSize.LETTER_LANDSCAPE -> 215.9f
        PageSize.CUSTOM -> 297f
    }
    fun isLandscape(): Boolean = pageSize == PageSize.A4_LANDSCAPE || pageSize == PageSize.LETTER_LANDSCAPE
    fun getUsableWidthPx(): Float = getPageWidthPx() - 2 * margin * 3.779527559055118f
    fun getUsableHeightPx(): Float = getPageHeightPx() - 2 * margin * 3.779527559055118f
    fun getTopMarginPx(): Float = margin * 3.779527559055118f
    fun getLeftMarginPx(): Float = margin * 3.779527559055118f

    fun copyPage(): PageModel = PageModel(
        pageSize = pageSize,
        margin = margin,
        imageLayers = imageLayers.map { it.duplicate() }.toMutableList(),
        backgroundColor = backgroundColor
    )
}

enum class PageSize {
    A4_PORTRAIT, A4_LANDSCAPE,
    LETTER_PORTRAIT, LETTER_LANDSCAPE,
    CUSTOM
}
