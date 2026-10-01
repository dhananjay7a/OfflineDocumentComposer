package com.example.offlinedocumentcomposer.data.model

data class ImageAdjustments(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 1f,
    val sharpness: Float = 0f,
    val exposure: Float = 0f,
    val temperature: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f
) {
    companion object {
        val DEFAULT = ImageAdjustments()
        val ORIGINAL = ImageAdjustments(brightness = 0f, contrast = 0f, saturation = 1f)
        val DOCUMENT_BW = ImageAdjustments(brightness = 15f, contrast = 40f, saturation = 0f)
        val MAGIC_COLOR = ImageAdjustments(brightness = 12f, contrast = 30f, saturation = 1.15f)
        val GRAYSCALE = ImageAdjustments(brightness = 0f, contrast = 0f, saturation = 0f)
    }

    fun copy(): ImageAdjustments = ImageAdjustments(
        brightness = brightness,
        contrast = contrast,
        saturation = saturation,
        sharpness = sharpness,
        exposure = exposure,
        temperature = temperature,
        highlights = highlights,
        shadows = shadows
    )

    fun reset() = DEFAULT
}
