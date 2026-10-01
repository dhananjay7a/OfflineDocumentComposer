package com.example.offlinedocumentcomposer.domain.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.example.offlinedocumentcomposer.data.model.ImageAdjustments

class ImageProcessor {

    /**
     * Applies full suite of adjustments (brightness, contrast, saturation)
     * using hardware-accelerated ColorMatrix.
     */
    fun applyAdjustments(bitmap: Bitmap, adjustments: ImageAdjustments): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val finalMatrix = ColorMatrix()

        // 1. Saturation
        val satMatrix = ColorMatrix().apply {
            setSaturation(adjustments.saturation.coerceIn(0f, 3f))
        }

        // 2. Contrast
        val contrastFactor = (adjustments.contrast + 100f) / 100f
        val contrastTranslate = (-0.5f * contrastFactor + 0.5f) * 255f
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrastFactor, 0f, 0f, 0f, contrastTranslate,
                0f, contrastFactor, 0f, 0f, contrastTranslate,
                0f, 0f, contrastFactor, 0f, contrastTranslate,
                0f, 0f, 0f, 1f, 0f
            )
        )

        // 3. Brightness
        val brightnessShift = adjustments.brightness * 1.5f
        val brightnessMatrix = ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, brightnessShift,
                0f, 1f, 0f, 0f, brightnessShift,
                0f, 0f, 1f, 0f, brightnessShift,
                0f, 0f, 0f, 1f, 0f
            )
        )

        // Combine: sat * contrast * brightness
        finalMatrix.postConcat(satMatrix)
        finalMatrix.postConcat(contrastMatrix)
        finalMatrix.postConcat(brightnessMatrix)

        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            colorFilter = ColorMatrixColorFilter(finalMatrix)
        }

        canvas.drawBitmap(bitmap, 0f, 0f, paint)

        // 4. Sharpness via OpenCV unsharp masking (enhances text clarity and removes blur)
        if (adjustments.sharpness > 0f && com.example.offlinedocumentcomposer.opencv.OpenCvUtils.initOpenCV()) {
            try {
                val srcMat = com.example.offlinedocumentcomposer.opencv.OpenCvUtils.bitmapToMat(output)
                val blurred = org.opencv.core.Mat()
                org.opencv.imgproc.Imgproc.GaussianBlur(srcMat, blurred, org.opencv.core.Size(0.0, 0.0), 3.0)
                val sharpened = org.opencv.core.Mat()
                val factor = (adjustments.sharpness / 50.0).coerceIn(0.0, 2.5)
                val alpha = 1.0 + factor
                val beta = -factor
                org.opencv.core.Core.addWeighted(srcMat, alpha, blurred, beta, 0.0, sharpened)
                val sharpBmp = com.example.offlinedocumentcomposer.opencv.OpenCvUtils.matToBitmap(sharpened)
                srcMat.release()
                blurred.release()
                sharpened.release()
                output.recycle()
                return sharpBmp
            } catch (e: Throwable) {
                android.util.Log.e("ImageProcessor", "Error applying sharpness filter", e)
            }
        }

        return output
    }

    fun adjustSharpness(bitmap: Bitmap, amount: Float): Bitmap {
        val adj = ImageAdjustments(sharpness = amount)
        return applyAdjustments(bitmap, adj)
    }

    fun adjustBrightness(bitmap: Bitmap, amount: Float): Bitmap {
        val adj = ImageAdjustments(brightness = amount * 100f)
        return applyAdjustments(bitmap, adj)
    }

    fun adjustContrast(bitmap: Bitmap, amount: Float): Bitmap {
        val adj = ImageAdjustments(contrast = amount)
        return applyAdjustments(bitmap, adj)
    }

    fun adjustSaturation(bitmap: Bitmap, amount: Float): Bitmap {
        val adj = ImageAdjustments(saturation = amount)
        return applyAdjustments(bitmap, adj)
    }

    fun toGrayscale(bitmap: Bitmap): Bitmap {
        return applyAdjustments(bitmap, ImageAdjustments.GRAYSCALE)
    }

    fun rotate(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees % 360f == 0f) return bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun resize(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    fun crop(bitmap: Bitmap, rect: Rect): Bitmap {
        val left = rect.left.coerceIn(0, bitmap.width - 1)
        val top = rect.top.coerceIn(0, bitmap.height - 1)
        val width = rect.width().coerceIn(1, bitmap.width - left)
        val height = rect.height().coerceIn(1, bitmap.height - top)
        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    fun applySharpen(bitmap: Bitmap, amount: Float): Bitmap {
        return bitmap.copy(Bitmap.Config.ARGB_8888, true)
    }
}
