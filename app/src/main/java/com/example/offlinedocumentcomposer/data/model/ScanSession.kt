package com.example.offlinedocumentcomposer.data.model

import java.util.UUID

data class ScanPoint(val x: Float, val y: Float)
enum class ScanFilter { ORIGINAL, COLOR, GRAYSCALE, BLACK_WHITE }
data class ScanPage(
    val id: String = UUID.randomUUID().toString(),
    val source: String,
    val corners: List<ScanPoint>,
    val rotation: Int = 0,
    val filter: ScanFilter = ScanFilter.BLACK_WHITE,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val needsReview: Boolean = false,
    val revision: Int = 0
)
data class ScanSession(val pages: List<ScanPage> = emptyList())
