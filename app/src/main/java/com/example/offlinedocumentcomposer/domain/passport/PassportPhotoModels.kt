package com.example.offlinedocumentcomposer.domain.passport

import android.graphics.Bitmap
import java.util.UUID

/**
 * Standard dimensions for passport, visa, and ID photos in millimeters.
 */
enum class PassportPhotoStandard(
    val title: String,
    val widthMm: Float,
    val heightMm: Float,
    val description: String
) {
    INDIAN_PASSPORT(
        title = "Indian Passport / Visa",
        widthMm = 35f,
        heightMm = 45f,
        description = "Standard 35 × 45 mm (3.5 × 4.5 cm) for Indian Passport, OCI, and Visa"
    ),
    PAN_CARD(
        title = "PAN Card",
        widthMm = 25f,
        heightMm = 35f,
        description = "Standard 25 × 35 mm (2.5 × 3.5 cm) for Indian NSDL/UTI PAN forms"
    ),
    STAMP_SIZE(
        title = "Stamp Size",
        widthMm = 20f,
        heightMm = 25f,
        description = "Standard 20 × 25 mm for official forms and identity cards"
    ),
    US_VISA(
        title = "US Visa / Passport (2×2 in)",
        widthMm = 51f,
        heightMm = 51f,
        description = "Square 2 × 2 inches (51 × 51 mm) for US Visa, DV Lottery, Green Card"
    ),
    CUSTOM(
        title = "Custom Size",
        widthMm = 35f,
        heightMm = 45f,
        description = "Custom width and height in millimeters"
    );

    val aspectRatio: Float
        get() = widthMm / heightMm
}

/**
 * Common photo paper and document sheet sizes in millimeters.
 */
enum class PassportSheetSize(
    val title: String,
    val widthMm: Float,
    val heightMm: Float,
    val category: String
) {
    PHOTO_4X6(
        title = "4 × 6 inch (10 × 15 cm)",
        widthMm = 101.6f,
        heightMm = 152.4f,
        category = "Studio Photo Paper"
    ),
    PHOTO_5X7(
        title = "5 × 7 inch (13 × 18 cm)",
        widthMm = 127.0f,
        heightMm = 177.8f,
        category = "Studio Photo Paper"
    ),
    A4(
        title = "A4 (210 × 297 mm)",
        widthMm = 210.0f,
        heightMm = 297.0f,
        category = "Standard Paper"
    ),
    A3(
        title = "A3 (297 × 420 mm)",
        widthMm = 297.0f,
        heightMm = 420.0f,
        category = "Large Paper"
    )
}

enum class SheetOrientation {
    PORTRAIT,
    LANDSCAPE
}

/**
 * An individual passport photo added to the composer.
 */
data class PassportPhotoItem(
    val id: String = UUID.randomUUID().toString(),
    val originalBitmap: Bitmap,
    val croppedBitmap: Bitmap = originalBitmap,
    val copies: Int = 4,
    val standard: PassportPhotoStandard = PassportPhotoStandard.INDIAN_PASSPORT,
    val customWidthMm: Float = 35f,
    val customHeightMm: Float = 45f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val rotationDegrees: Int = 0
) {
    val effectiveWidthMm: Float
        get() = if (standard == PassportPhotoStandard.CUSTOM) customWidthMm else standard.widthMm

    val effectiveHeightMm: Float
        get() = if (standard == PassportPhotoStandard.CUSTOM) customHeightMm else standard.heightMm

    val effectiveAspectRatio: Float
        get() = effectiveWidthMm / effectiveHeightMm.coerceAtLeast(1f)
}

/**
 * Configuration options for rendering a passport photo sheet.
 */
data class PassportSheetConfig(
    val sheetSize: PassportSheetSize = PassportSheetSize.A4,
    val orientation: SheetOrientation = SheetOrientation.PORTRAIT,
    val marginMm: Float = 2f,
    val gapMm: Float = 1f,
    val customColumns: Int? = null, // e.g. 6 for 6 photos in one row
    val showCuttingBorder: Boolean = true,
    val cuttingBorderColorHex: Long = 0xFF000000, // Black color
    val cuttingBorderWidthPx: Float = 5f,          // 5px width
    val centerGrid: Boolean = false,               // false = strictly respect marginMm
    val showCornerMarks: Boolean = true
) {
    val effectiveSheetWidthMm: Float
        get() = if (orientation == SheetOrientation.PORTRAIT) sheetSize.widthMm else sheetSize.heightMm

    val effectiveSheetHeightMm: Float
        get() = if (orientation == SheetOrientation.PORTRAIT) sheetSize.heightMm else sheetSize.widthMm

    val cuttingBorderWidthPt: Float
        get() = (cuttingBorderWidthPx * 72f / 300f).coerceAtLeast(0.5f)
}
