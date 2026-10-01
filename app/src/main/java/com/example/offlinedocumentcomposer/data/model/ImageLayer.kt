package com.example.offlinedocumentcomposer.data.model

import android.graphics.Bitmap
import android.graphics.PointF

data class ImageLayer(
    val id: String = java.util.UUID.randomUUID().toString(),
    val originalBitmap: Bitmap? = null,
    var workingBitmap: Bitmap? = null,
    var x: Float = 0f,
    var y: Float = 0f,
    var width: Float = 0f,
    var height: Float = 0f,
    var rotation: Float = 0f,
    var brightness: Float = 0f,
    var contrast: Float = 0f,
    var saturation: Float = 1f,
    var sharpness: Float = 0f,
    var cropData: CropData? = null,
    var zIndex: Int = 0,
    var visible: Boolean = true,
    var name: String = "Image"
) {
    companion object {
        const val SATURATION_BW = 0f
        const val DEFAULT_BRIGHTNESS = 0f
        const val DEFAULT_CONTRAST = 0f
        const val DEFAULT_SATURATION = 1f
    }

    fun getAspectRatio(): Float = if (width > 0 && height > 0) width / height else 1f
    fun getCenterX(): Float = x + width / 2
    fun getCenterY(): Float = y + height / 2

    /**
     * Deep duplicate of this layer with a new unique ID and independent bitmap.
     * Used ONLY when explicitly cloning/duplicating an image layer (e.g. 2 copies feature).
     */
    fun duplicate(): ImageLayer = copy(
        id = java.util.UUID.randomUUID().toString(),
        originalBitmap = originalBitmap?.copy(Bitmap.Config.ARGB_8888, true),
        workingBitmap = workingBitmap?.copy(Bitmap.Config.ARGB_8888, true),
        cropData = cropData?.copy(),
        name = "$name (copy)"
    )
}
