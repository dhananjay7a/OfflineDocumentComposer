package com.example.offlinedocumentcomposer.domain.resizer

import android.graphics.Bitmap
import android.net.Uri
import kotlin.math.roundToInt

/**
 * Dimension unit options for resizing an image.
 */
enum class ResizeUnit(val label: String, val shortSuffix: String) {
    PIXELS("Pixels", "px"),
    PERCENTAGE("Percentage", "%"),
    MILLIMETERS("Millimeters", "mm"),
    CENTIMETERS("Centimeters", "cm"),
    INCHES("Inches", "in")
}

/**
 * Output compression format options.
 */
enum class OutputFormat(val extension: String, val mimeType: String, val label: String) {
    JPEG("jpg", "image/jpeg", "JPG"),
    PNG("png", "image/png", "PNG"),
    WEBP("webp", "image/webp", "WebP")
}

/**
 * Category grouping for quick presets.
 */
enum class PresetCategory(val title: String) {
    ALL("All Presets"),
    EXAM_GOV("Gov & Exam Forms"),
    SOCIAL("Social Media"),
    WEB_DISPLAY("Web & Display")
}

/**
 * A curated dimension and size preset.
 */
data class ImagePreset(
    val id: String,
    val title: String,
    val category: PresetCategory,
    val width: Float,
    val height: Float,
    val unit: ResizeUnit = ResizeUnit.PIXELS,
    val dpi: Int = 300,
    val targetMaxKb: Int? = null,
    val description: String
)

object StandardImagePresets {
    val PRESETS = listOf(
        // Government & Exam Form Presets (India standard)
        ImagePreset(
            id = "govt_exam_photo",
            title = "Exam Photo (200×230)",
            category = PresetCategory.EXAM_GOV,
            width = 200f,
            height = 230f,
            unit = ResizeUnit.PIXELS,
            targetMaxKb = 50,
            description = "Standard 200 × 230 px photo (<50 KB) for SSC, UPSC, NTA, IBPS forms"
        ),
        ImagePreset(
            id = "govt_exam_sig",
            title = "Exam Signature (140×60)",
            category = PresetCategory.EXAM_GOV,
            width = 140f,
            height = 60f,
            unit = ResizeUnit.PIXELS,
            targetMaxKb = 20,
            description = "Standard 140 × 60 px signature (<20 KB) for online exams"
        ),
        ImagePreset(
            id = "passport_35x45",
            title = "Passport (35×45 mm)",
            category = PresetCategory.EXAM_GOV,
            width = 35f,
            height = 45f,
            unit = ResizeUnit.MILLIMETERS,
            dpi = 300,
            targetMaxKb = 100,
            description = "Indian Passport / Visa standard 35 × 45 mm at 300 DPI"
        ),
        ImagePreset(
            id = "pan_photo_25x35",
            title = "PAN Card Photo (25×35 mm)",
            category = PresetCategory.EXAM_GOV,
            width = 25f,
            height = 35f,
            unit = ResizeUnit.MILLIMETERS,
            dpi = 300,
            targetMaxKb = 50,
            description = "NSDL / UTI PAN card photo 25 × 35 mm"
        ),

        // Social Media Presets
        ImagePreset(
            id = "insta_square",
            title = "Instagram Post (1:1)",
            category = PresetCategory.SOCIAL,
            width = 1080f,
            height = 1080f,
            unit = ResizeUnit.PIXELS,
            description = "1080 × 1080 px square feed photo"
        ),
        ImagePreset(
            id = "insta_story",
            title = "Story / Reel (9:16)",
            category = PresetCategory.SOCIAL,
            width = 1080f,
            height = 1920f,
            unit = ResizeUnit.PIXELS,
            description = "1080 × 1920 px full screen vertical format"
        ),
        ImagePreset(
            id = "whatsapp_dp",
            title = "WhatsApp DP (500×500)",
            category = PresetCategory.SOCIAL,
            width = 500f,
            height = 500f,
            unit = ResizeUnit.PIXELS,
            description = "500 × 500 px profile picture"
        ),
        ImagePreset(
            id = "youtube_thumb",
            title = "YouTube Thumbnail (16:9)",
            category = PresetCategory.SOCIAL,
            width = 1280f,
            height = 720f,
            unit = ResizeUnit.PIXELS,
            description = "1280 × 720 px HD video thumbnail"
        ),

        // Web & Display Presets
        ImagePreset(
            id = "web_fhd",
            title = "Full HD (1920×1080)",
            category = PresetCategory.WEB_DISPLAY,
            width = 1920f,
            height = 1080f,
            unit = ResizeUnit.PIXELS,
            description = "1080p widescreen desktop and web display"
        ),
        ImagePreset(
            id = "web_hd",
            title = "HD (1280×720)",
            category = PresetCategory.WEB_DISPLAY,
            width = 1280f,
            height = 720f,
            unit = ResizeUnit.PIXELS,
            description = "720p standard compact display"
        ),
        ImagePreset(
            id = "web_banner",
            title = "Web Banner (1200×630)",
            category = PresetCategory.WEB_DISPLAY,
            width = 1200f,
            height = 630f,
            unit = ResizeUnit.PIXELS,
            description = "1200 × 630 px OpenGraph link preview card"
        )
    )
}

/**
 * Image Resizer configuration state.
 */
data class ResizeConfiguration(
    val unit: ResizeUnit = ResizeUnit.PIXELS,
    val width: Float = 0f,
    val height: Float = 0f,
    val scalePercent: Float = 100f,
    val lockAspectRatio: Boolean = true,
    val dpi: Int = 300,
    val targetMaxKb: Int? = null,
    val format: OutputFormat = OutputFormat.JPEG,
    val quality: Int = 90
) {
    /**
     * Resolves the target pixel width and height based on the configuration and original dimensions.
     */
    fun resolvePixelDimensions(origW: Int, origH: Int): Pair<Int, Int> {
        if (origW <= 0 || origH <= 0) return Pair(1, 1)

        return when (unit) {
            ResizeUnit.PIXELS -> {
                val targetW = if (width > 0) width.roundToInt() else origW
                val targetH = if (height > 0) height.roundToInt() else origH
                Pair(targetW.coerceAtLeast(1), targetH.coerceAtLeast(1))
            }
            ResizeUnit.PERCENTAGE -> {
                val factor = (scalePercent / 100f).coerceIn(0.01f, 10f)
                val targetW = (origW * factor).roundToInt().coerceAtLeast(1)
                val targetH = (origH * factor).roundToInt().coerceAtLeast(1)
                Pair(targetW, targetH)
            }
            ResizeUnit.MILLIMETERS -> {
                val mmToInch = 1f / 25.4f
                val targetW = ((if (width > 0) width else origW * 25.4f / dpi) * mmToInch * dpi).roundToInt()
                val targetH = ((if (height > 0) height else origH * 25.4f / dpi) * mmToInch * dpi).roundToInt()
                Pair(targetW.coerceAtLeast(1), targetH.coerceAtLeast(1))
            }
            ResizeUnit.CENTIMETERS -> {
                val cmToInch = 1f / 2.54f
                val targetW = ((if (width > 0) width else origW * 2.54f / dpi) * cmToInch * dpi).roundToInt()
                val targetH = ((if (height > 0) height else origH * 2.54f / dpi) * cmToInch * dpi).roundToInt()
                Pair(targetW.coerceAtLeast(1), targetH.coerceAtLeast(1))
            }
            ResizeUnit.INCHES -> {
                val targetW = ((if (width > 0) width else origW.toFloat() / dpi) * dpi).roundToInt()
                val targetH = ((if (height > 0) height else origH.toFloat() / dpi) * dpi).roundToInt()
                Pair(targetW.coerceAtLeast(1), targetH.coerceAtLeast(1))
            }
        }
    }
}

/**
 * Metadata about the loaded source image.
 */
data class ImageSourceMeta(
    val uri: Uri,
    val originalWidth: Int,
    val originalHeight: Int,
    val fileSizeBytes: Long = 0L,
    val fileName: String = "image.jpg"
) {
    val aspectRatio: Float
        get() = if (originalHeight > 0) originalWidth.toFloat() / originalHeight else 1f

    val formattedFileSize: String
        get() = when {
            fileSizeBytes >= 1024 * 1024 -> String.format("%.2f MB", fileSizeBytes.toDouble() / (1024 * 1024))
            fileSizeBytes >= 1024 -> String.format("%.1f KB", fileSizeBytes.toDouble() / 1024)
            fileSizeBytes > 0 -> "$fileSizeBytes B"
            else -> "Unknown"
        }
}
