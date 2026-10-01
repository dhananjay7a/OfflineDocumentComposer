package com.example.offlinedocumentcomposer.domain.image

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import com.example.offlinedocumentcomposer.opencv.OpenCvUtils
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

class PerspectiveCorrector {

    companion object {
        private const val TAG = "PerspectiveCorrector"
    }

    /**
     * Corrects perspective distortion using OpenCV native warpPerspective with bicubic interpolation.
     * Falls back to closed-form bilinear homography if OpenCV native is unavailable.
     * @param bitmap Original source bitmap.
     * @param corners 4 points ordered: Top-Left, Top-Right, Bottom-Right, Bottom-Left.
     */
    fun correct(bitmap: Bitmap, corners: List<PointF>): Bitmap {
        require(com.example.offlinedocumentcomposer.domain.detector.CropGeometry.valid(corners, bitmap.width, bitmap.height)) { "Invalid crop corners" }

        // 1. Try OpenCV native perspective warp first (fast, hardware accelerated, bicubic)
        val cvWarped = OpenCvUtils.warpPerspective(bitmap, corners)
        if (cvWarped != null) {
            Log.d(TAG, "OpenCV native perspective warp succeeded: ${cvWarped.width}x${cvWarped.height}")
            return cvWarped
        }

        Log.w(TAG, "OpenCV warp failed or unavailable, running fallback bilinear homography")
        return fallbackHomography(bitmap, corners)
    }

    private fun fallbackHomography(bitmap: Bitmap, corners: List<PointF>): Bitmap {
        return try {
            val p0 = corners[0] // TL
            val p1 = corners[1] // TR
            val p2 = corners[2] // BR
            val p3 = corners[3] // BL

            val topW = hypot(p1.x - p0.x, p1.y - p0.y)
            val botW = hypot(p2.x - p3.x, p2.y - p3.y)
            val leftH = hypot(p3.x - p0.x, p3.y - p0.y)
            val rightH = hypot(p2.x - p1.x, p2.y - p1.y)

            val width = max(topW, botW) + 1f
            val height = max(leftH, rightH) + 1f
            val outputScale = minOf(1f, 3508f / max(width, height))
            val targetWidth = (width * outputScale).toInt().coerceAtLeast(1)
            val targetHeight = (height * outputScale).toInt().coerceAtLeast(1)

            val srcW = bitmap.width
            val srcH = bitmap.height

            val srcPixels = IntArray(srcW * srcH)
            bitmap.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)

            val dstPixels = IntArray(targetWidth * targetHeight)

            val dx1 = p1.x - p2.x
            val dx2 = p3.x - p2.x
            val dx3 = p0.x - p1.x + p2.x - p3.x
            val dy1 = p1.y - p2.y
            val dy2 = p3.y - p2.y
            val dy3 = p0.y - p1.y + p2.y - p3.y

            val a11: Float
            val a12: Float
            val a13: Float
            val a21: Float
            val a22: Float
            val a23: Float
            val a31: Float = p0.x
            val a32: Float = p0.y

            if (abs(dx3) < 0.001f && abs(dy3) < 0.001f) {
                a11 = p1.x - p0.x
                a21 = p2.x - p1.x
                a12 = p1.y - p0.y
                a22 = p2.y - p1.y
                a13 = 0f
                a23 = 0f
            } else {
                val det = dx1 * dy2 - dx2 * dy1
                if (abs(det) < 1e-6f) {
                    a13 = 0f
                    a23 = 0f
                } else {
                    a13 = (dx3 * dy2 - dx2 * dy3) / det
                    a23 = (dx1 * dy3 - dx3 * dy1) / det
                }
                a11 = p1.x - p0.x + a13 * p1.x
                a21 = p3.x - p0.x + a23 * p3.x
                a12 = p1.y - p0.y + a13 * p1.y
                a22 = p3.y - p0.y + a23 * p3.y
            }

            val maxTargetX = (targetWidth - 1).coerceAtLeast(1).toFloat()
            val maxTargetY = (targetHeight - 1).coerceAtLeast(1).toFloat()

            for (y in 0 until targetHeight) {
                val t = y / maxTargetY
                val rowOffset = y * targetWidth
                for (x in 0 until targetWidth) {
                    val s = x / maxTargetX
                    val denom = a13 * s + a23 * t + 1f
                    if (abs(denom) > 1e-7f) {
                        val srcX = (a11 * s + a21 * t + a31) / denom
                        val srcY = (a12 * s + a22 * t + a32) / denom

                        val x0 = srcX.toInt().coerceIn(0, srcW - 1)
                        val y0 = srcY.toInt().coerceIn(0, srcH - 1)
                        val x1 = (x0 + 1).coerceIn(0, srcW - 1)
                        val y1 = (y0 + 1).coerceIn(0, srcH - 1)

                        val fx = (srcX - x0).coerceIn(0f, 1f)
                        val fy = (srcY - y0).coerceIn(0f, 1f)
                        val ifx = 1f - fx
                        val ify = 1f - fy

                        val c00 = srcPixels[y0 * srcW + x0]
                        val c10 = srcPixels[y0 * srcW + x1]
                        val c01 = srcPixels[y1 * srcW + x0]
                        val c11 = srcPixels[y1 * srcW + x1]

                        val a = (c00 ushr 24)
                        val r0 = ((c00 shr 16 and 0xFF) * ifx + (c10 shr 16 and 0xFF) * fx)
                        val r1 = ((c01 shr 16 and 0xFF) * ifx + (c11 shr 16 and 0xFF) * fx)
                        val r = (r0 * ify + r1 * fy).toInt().coerceIn(0, 255)

                        val g0 = ((c00 shr 8 and 0xFF) * ifx + (c10 shr 8 and 0xFF) * fx)
                        val g1 = ((c01 shr 8 and 0xFF) * ifx + (c11 shr 8 and 0xFF) * fx)
                        val g = (g0 * ify + g1 * fy).toInt().coerceIn(0, 255)

                        val b0 = ((c00 and 0xFF) * ifx + (c10 and 0xFF) * fx)
                        val b1 = ((c01 and 0xFF) * ifx + (c11 and 0xFF) * fx)
                        val b = (b0 * ify + b1 * fy).toInt().coerceIn(0, 255)

                        dstPixels[rowOffset + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    } else {
                        dstPixels[rowOffset + x] = 0xFFFFFFFF.toInt()
                    }
                }
            }

            val result = try {
                Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            } catch (e: Exception) {
                null
            } ?: error("Unable to allocate crop")
            try {
                result.setPixels(dstPixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
            } catch (e: Exception) { result.recycle(); throw e }
            result
        } catch (e: Exception) {
            e.printStackTrace()
            throw IllegalStateException("Perspective crop failed", e)
        }
    }
}
